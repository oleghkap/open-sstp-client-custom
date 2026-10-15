package kittoku.osc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import kittoku.osc.R
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.PROFILE_KEY_HEADER
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getSetPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.checkPreferences
import kittoku.osc.preference.deserializeProfile
import kittoku.osc.preference.importProfile


internal const val AUTO_CONNECT_CHANNEL = "AUTOCONNECT"
internal const val AUTO_CONNECT_NOTIFICATION_ID = 5
internal const val EXTRA_FROM_UI = "kittoku.osc.FROM_UI"

// how long after the service is started from the UI the networks that are already up are ignored,
// so merely opening the app / flipping a setting does not reconnect a tunnel the user just closed
private const val INITIAL_EVENTS_GRACE = 2_500L

// the Wi-Fi SSID is not always readable the instant the network becomes available
private const val SSID_SETTLE_DELAY = 1_500L
private const val SSID_MAX_RETRIES = 3

// how long to wait, after one transport is lost, before deciding what to do about it - gives the
// OTHER transport (the one a "defer to" rule cares about) a chance to actually appear, so e.g.
// rule 4 can correctly tell whether Wi-Fi really did take over before it decides anything
private const val FALLBACK_DELAY = 3_000L

// a rule never fires the same action twice within this window: protects against a flapping
// network turning one rule into an endless connect/disconnect loop
private const val ACTION_COOLDOWN = 15_000L


// An explicit auto-connect "disconnect" rule ("Отключаться при отключении от ...") and the
// tunnel's own failure detection react to the exact same physical event (the network dying),
// essentially at the same moment, on different threads. Without this gate it's a coin flip
// whether the rule's explicit disconnect or the tunnel's own automatic reconnect "wins". The
// rule is supposed to win outright, so it marks a short window here; Controller.kill() checks
// it before ever scheduling a reconnect attempt.
internal object ReconnectGate {
    @Volatile
    private var suppressedUntil = 0L

    fun suppress(durationMs: Long = 8_000L) {
        suppressedUntil = SystemClock.elapsedRealtime() + durationMs
    }

    fun isSuppressed(): Boolean = SystemClock.elapsedRealtime() < suppressedUntil
}


internal fun isAutoConnectRequired(prefs: SharedPreferences): Boolean {
    if (getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_DISABLED, prefs)) return false

    return getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_MOBILE, prefs) ||
            getBooleanPrefValue(OscPrefKey.AUTO_DISCONNECT_ON_MOBILE_LOSS, prefs) ||
            getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_WIFI_INCLUDE, prefs) ||
            getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_WIFI_EXCLUDE, prefs) ||
            getBooleanPrefValue(OscPrefKey.AUTO_DISCONNECT_ON_WIFI_LOSS, prefs)
}

// Whether the CURRENT active network (read fresh from ConnectivityManager, independent of
// AutoConnectService's own bookkeeping - this must also work from the VPN service itself, where
// the watcher's internal state isn't available) is one the Wi-Fi connect rules (5/6) approve of.
// Used to gate the tunnel's own ordinary "the connection broke, try again" reconnect: if a
// Wi-Fi allow/deny list is configured, blind reconnection must respect it instead of just
// reconnecting to whatever network happens to be up.
internal fun isCurrentNetworkApproved(context: Context): Boolean {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    val useInclude = getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_WIFI_INCLUDE, prefs)
    val useExclude = getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_WIFI_EXCLUDE, prefs)
    if (!useInclude && !useExclude) return true // nothing configured to filter on: no restriction

    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    val network = connectivity.activeNetwork ?: return true
    val caps = connectivity.getNetworkCapabilities(network) ?: return true

    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true // 5/6 only filter Wi-Fi

    val ssid = readCurrentSsid(context) ?: return true // can't verify right now: don't block

    val included = getSetPrefValue(OscPrefKey.AUTO_CONNECT_WIFI_INCLUDE_SSIDS, prefs)
    val excluded = getSetPrefValue(OscPrefKey.AUTO_CONNECT_WIFI_EXCLUDE_SSIDS, prefs)

    return (useInclude && ssid in included) || (useExclude && ssid !in excluded)
}

// starts the lightweight network watcher while at least one auto-connect rule is on, stops it
// otherwise. The watcher is event-driven (it just waits for Android to report network changes),
// so it costs next to no battery.
internal fun syncAutoConnectService(context: Context, fromUi: Boolean) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val intent = Intent(context, AutoConnectService::class.java)

    if (isAutoConnectRequired(prefs)) {
        intent.putExtra(EXTRA_FROM_UI, fromUi)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {
            // the system may refuse to start a foreground service from the background
        }
    } else {
        context.stopService(intent)
    }
}

// connects the profile that was last active, the same way the on/off switch does
internal fun connectLastProfile(context: Context, prefs: SharedPreferences) {
    if (getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_DISABLED, prefs)) return
    if (getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)) return

    val name = getStringPrefValue(OscPrefKey.ACTIVE_PROFILE_NAME, prefs)
    if (name.isEmpty()) return

    val json = prefs.getString(PROFILE_KEY_HEADER + name, null) ?: return
    val profile = deserializeProfile(json) ?: return

    // the VPN consent dialog cannot be shown from the background, so this only works once the
    // person has approved the VPN at least once
    if (VpnService.prepare(context) != null) return

    importProfile(profile, prefs)

    if (checkPreferences(prefs) != null) return

    startVpnService(context, ACTION_VPN_CONNECT)
}


internal class AutoConnectService : Service() {
    private lateinit var prefs: SharedPreferences
    private lateinit var connectivity: ConnectivityManager

    private val handler = Handler(Looper.getMainLooper())

    private var wifiNetwork: Network? = null
    private var cellNetwork: Network? = null

    private var isRegistered = false
    private var ignoreEventsUntil = 0L

    private var lastConnectAt = -ACTION_COOLDOWN
    private var lastDisconnectAt = -ACTION_COOLDOWN

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { onWifiAvailable(network) }
        }

        override fun onLost(network: Network) {
            handler.post { onWifiLost(network) }
        }
    }

    private val cellCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { onCellAvailable(network) }
        }

        override fun onLost(network: Network) {
            handler.post { onCellLost(network) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        startForeground(AUTO_CONNECT_NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRegistered) {
            if (intent?.getBooleanExtra(EXTRA_FROM_UI, false) == true) {
                ignoreEventsUntil = SystemClock.elapsedRealtime() + INITIAL_EVENTS_GRACE
            }

            registerCallbacks()
            isRegistered = true
        }

        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)

        if (isRegistered) {
            try {
                connectivity.unregisterNetworkCallback(wifiCallback)
            } catch (_: Exception) { }

            try {
                connectivity.unregisterNetworkCallback(cellCallback)
            } catch (_: Exception) { }
        }

        super.onDestroy()
    }

    private fun registerCallbacks() {
        // the VPN network itself must never be mistaken for a Wi-Fi / mobile network
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val cellRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        try {
            connectivity.registerNetworkCallback(wifiRequest, wifiCallback)
            connectivity.registerNetworkCallback(cellRequest, cellCallback)
        } catch (_: Exception) {
            // nothing more can be done; the notification stays so the person can see it is on
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    AUTO_CONNECT_CHANNEL,
                    "Автоподключение",
                    NotificationManager.IMPORTANCE_MIN,
                )
            )
        }

        // the channel is IMPORTANCE_MIN, so this notification is already silent and collapsed
        return NotificationCompat.Builder(this, AUTO_CONNECT_CHANNEL)
            .setSmallIcon(R.drawable.ic_baseline_vpn_lock_24)
            .setContentTitle("Автоподключение включено")
            .setContentText("Слежу за изменениями сети")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun isSuppressed() = SystemClock.elapsedRealtime() < ignoreEventsUntil

    private fun isMasterOff() = getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_DISABLED, prefs)

    private fun isTunnelRunning() = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)

    private fun connectNow() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastConnectAt < ACTION_COOLDOWN) return
        if (isTunnelRunning()) return

        lastConnectAt = now
        connectLastProfile(this, prefs)
    }

    private fun disconnectNow() {
        if (!isTunnelRunning()) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastDisconnectAt < ACTION_COOLDOWN) return

        lastDisconnectAt = now
        // an explicit disconnect rule always wins over the tunnel's own "the network changed,
        // try to reconnect" reflex, even though both can be triggered by the same event
        ReconnectGate.suppress()
        startVpnService(this, ACTION_VPN_DISCONNECT)
    }

    // true if a Wi-Fi connect rule (5/6) is configured AND approves of the Wi-Fi network the
    // phone is on right now. Used so rule 4 ("disconnect on mobile loss") can defer to 5/6
    // instead of outranking them - it is only supposed to outrank rule 3.
    private fun isCurrentWifiApprovedByConnectRules(): Boolean {
        val useInclude = getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_WIFI_INCLUDE, prefs)
        val useExclude = getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_WIFI_EXCLUDE, prefs)
        if (!useInclude && !useExclude) return false // nothing configured to defer to

        val ssid = readCurrentSsid(this) ?: return false // can't verify yet: don't defer

        val included = getSetPrefValue(OscPrefKey.AUTO_CONNECT_WIFI_INCLUDE_SSIDS, prefs)
        val excluded = getSetPrefValue(OscPrefKey.AUTO_CONNECT_WIFI_EXCLUDE_SSIDS, prefs)

        return (useInclude && ssid in included) || (useExclude && ssid !in excluded)
    }

    // --- Wi-Fi ---

    private fun onWifiAvailable(network: Network) {
        wifiNetwork = network

        if (isSuppressed() || isMasterOff()) return

        handler.postDelayed({ evaluateWifiRules(network, 0) }, SSID_SETTLE_DELAY)
    }

    private fun evaluateWifiRules(network: Network, attempt: Int) {
        if (wifiNetwork != network || isMasterOff()) return

        val useInclude = getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_WIFI_INCLUDE, prefs)
        val useExclude = getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_WIFI_EXCLUDE, prefs)
        if (!useInclude && !useExclude) return

        val ssid = readCurrentSsid(this)
        if (ssid == null) {
            // not readable yet (or the location permission is missing): try a few more times
            if (attempt < SSID_MAX_RETRIES) {
                handler.postDelayed({ evaluateWifiRules(network, attempt + 1) }, SSID_SETTLE_DELAY)
            }
            return
        }

        val included = getSetPrefValue(OscPrefKey.AUTO_CONNECT_WIFI_INCLUDE_SSIDS, prefs)
        val excluded = getSetPrefValue(OscPrefKey.AUTO_CONNECT_WIFI_EXCLUDE_SSIDS, prefs)

        val shouldConnect = (useInclude && ssid in included) || (useExclude && ssid !in excluded)
        if (shouldConnect) {
            connectNow()
        } else {
            // the allow/deny list explicitly rejects this Wi-Fi network: must not stay connected
            // through it, whatever else is or isn't running
            disconnectNow()
        }
    }

    private fun onWifiLost(network: Network) {
        if (wifiNetwork != network) return
        wifiNetwork = null

        if (isMasterOff()) return

        if (getBooleanPrefValue(OscPrefKey.AUTO_DISCONNECT_ON_WIFI_LOSS, prefs)) {
            // rule 7 only outranks rules 5/6, not rule 3: wait a moment to see whether mobile data
            // actually takes over, and if rule 3 is on, let it keep the connection instead
            handler.postDelayed({ resolveWifiLossDisconnect() }, FALLBACK_DELAY)
        }

        if (getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_MOBILE, prefs)) {
            handler.postDelayed({
                if (wifiNetwork == null && cellNetwork != null) connectOnMobileIfEnabled()
            }, FALLBACK_DELAY)
        }
    }

    private fun resolveWifiLossDisconnect() {
        if (wifiNetwork != null) return // Wi-Fi came back in the meantime
        if (isMasterOff()) return
        if (!getBooleanPrefValue(OscPrefKey.AUTO_DISCONNECT_ON_WIFI_LOSS, prefs)) return

        val deferToMobileRule = cellNetwork != null && getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_MOBILE, prefs)
        if (deferToMobileRule) return

        disconnectNow()
    }

    // --- Mobile data ---

    private fun connectOnMobileIfEnabled() {
        if (isMasterOff()) return

        if (getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_MOBILE, prefs)) {
            connectNow()
        }
    }

    private fun onCellAvailable(network: Network) {
        cellNetwork = network

        if (isSuppressed()) return

        // mobile data is often up in the background while on Wi-Fi: it only counts as "connected
        // to mobile" when Wi-Fi is not the network in use
        if (wifiNetwork != null) return

        connectOnMobileIfEnabled()
    }

    private fun onCellLost(network: Network) {
        if (cellNetwork != network) return
        cellNetwork = null

        if (isMasterOff()) return

        if (getBooleanPrefValue(OscPrefKey.AUTO_DISCONNECT_ON_MOBILE_LOSS, prefs)) {
            // rule 4 only outranks rule 3, not rules 5/6: wait a moment to see whether Wi-Fi
            // actually took over and settles on a network, then defer to 5/6's verdict on it
            handler.postDelayed({ resolveMobileLossDisconnect() }, FALLBACK_DELAY)
        }
    }

    private fun resolveMobileLossDisconnect() {
        if (cellNetwork != null) return // mobile data came back in the meantime
        if (isMasterOff()) return
        if (!getBooleanPrefValue(OscPrefKey.AUTO_DISCONNECT_ON_MOBILE_LOSS, prefs)) return

        if (wifiNetwork != null && isCurrentWifiApprovedByConnectRules()) return // defer to 5/6

        disconnectNow()
    }
}
