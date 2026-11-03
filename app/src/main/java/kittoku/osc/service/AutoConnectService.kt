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

// the Wi-Fi SSID is not always readable the instant the network becomes active
private const val SSID_SETTLE_DELAY = 1_500L
private const val SSID_MAX_RETRIES = 3

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

// Whether the CURRENT default network (read fresh from ConnectivityManager, independent of
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
// otherwise. The watcher is event-driven (it just waits for Android to report the default
// network changing), so it costs next to no battery.
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

    // the network Android is actually routing traffic through right now, and which transport it
    // is. Tracking THIS (rather than each transport's own up/down state separately) is what
    // makes rules 4 and 7 reliable across devices/carriers: some phones keep the previous
    // transport (typically mobile data) technically "up" in the background for a fast fallback
    // even after Wi-Fi has fully taken over as the active route, so a plain "mobile network
    // lost" event may simply never fire there. The default-network callback instead reports
    // exactly when the SYSTEM switches which network it is using, which is the actual thing
    // these rules care about, and behaves the same way regardless of that background-keepalive
    // quirk.
    private var activeNetwork: Network? = null
    private var activeTransport: Int? = null // NetworkCapabilities.TRANSPORT_WIFI / TRANSPORT_CELLULAR / null

    private var isRegistered = false
    private var ignoreEventsUntil = 0L

    private var lastConnectAt = -ACTION_COOLDOWN
    private var lastDisconnectAt = -ACTION_COOLDOWN

    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            handler.post { onDefaultNetworkUpdated(network, caps) }
        }

        override fun onLost(network: Network) {
            handler.post { onDefaultNetworkLost(network) }
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

            try {
                connectivity.registerDefaultNetworkCallback(defaultNetworkCallback)
                isRegistered = true
            } catch (_: Exception) {
                // nothing more can be done; the notification stays so the person can see it is on
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)

        if (isRegistered) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
            } catch (_: Exception) { }
        }

        super.onDestroy()
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

    // --- the default (actually-in-use) network changed ---

    private fun onDefaultNetworkUpdated(network: Network, caps: NetworkCapabilities) {
        // our own tunnel becoming the default network must never be treated as a network change
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

        val newTransport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkCapabilities.TRANSPORT_WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkCapabilities.TRANSPORT_CELLULAR
            else -> null
        }

        val isSameNetwork = activeNetwork == network
        val oldTransport = activeTransport

        activeNetwork = network
        activeTransport = newTransport

        // just a capability refresh on the network already in use (signal strength, etc.), not
        // an actual switch - nothing to do
        if (isSameNetwork && oldTransport == newTransport) return

        if (isSuppressed() || isMasterOff()) return

        when (newTransport) {
            NetworkCapabilities.TRANSPORT_WIFI -> onSwitchedToWifi(network, oldTransport)
            NetworkCapabilities.TRANSPORT_CELLULAR -> onSwitchedToMobile(oldTransport)
        }
    }

    private fun onDefaultNetworkLost(network: Network) {
        if (activeNetwork != network) return

        val oldTransport = activeTransport
        activeNetwork = null
        activeTransport = null

        if (isMasterOff()) return

        // total connectivity loss (not a switch to some other network, but no default network
        // at all): there is nothing to defer to, so the matching disconnect rule fires outright
        when (oldTransport) {
            NetworkCapabilities.TRANSPORT_CELLULAR ->
                if (getBooleanPrefValue(OscPrefKey.AUTO_DISCONNECT_ON_MOBILE_LOSS, prefs)) disconnectNow()

            NetworkCapabilities.TRANSPORT_WIFI ->
                if (getBooleanPrefValue(OscPrefKey.AUTO_DISCONNECT_ON_WIFI_LOSS, prefs)) disconnectNow()
        }
    }

    // --- Wi-Fi became the default network ---

    private fun onSwitchedToWifi(network: Network, oldTransport: Int?) {
        // "Отключаться при отключении от мобильной сети" (rule 4) only outranks rule 3, not
        // rules 5/6: if a Wi-Fi connect rule is configured and approves of the network we just
        // switched to, defer to it instead of forcing a disconnect.
        if (oldTransport == NetworkCapabilities.TRANSPORT_CELLULAR &&
            getBooleanPrefValue(OscPrefKey.AUTO_DISCONNECT_ON_MOBILE_LOSS, prefs)
        ) {
            handler.postDelayed({ resolveSwitchedToWifiDisconnect(network) }, SSID_SETTLE_DELAY)
        }

        evaluateWifiConnectRules(network, 0)
    }

    private fun resolveSwitchedToWifiDisconnect(network: Network) {
        if (activeNetwork != network) return // things moved on already
        if (isMasterOff()) return
        if (!getBooleanPrefValue(OscPrefKey.AUTO_DISCONNECT_ON_MOBILE_LOSS, prefs)) return

        if (isCurrentWifiApprovedByConnectRules()) return // defer to 5/6

        disconnectNow()
    }

    private fun evaluateWifiConnectRules(network: Network, attempt: Int) {
        if (activeNetwork != network || isMasterOff()) return

        val useInclude = getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_WIFI_INCLUDE, prefs)
        val useExclude = getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_WIFI_EXCLUDE, prefs)
        if (!useInclude && !useExclude) return

        val ssid = readCurrentSsid(this)
        if (ssid == null) {
            // not readable yet (or the location permission is missing): try a few more times
            if (attempt < SSID_MAX_RETRIES) {
                handler.postDelayed({ evaluateWifiConnectRules(network, attempt + 1) }, SSID_SETTLE_DELAY)
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

    // --- mobile data became the default network ---

    private fun onSwitchedToMobile(oldTransport: Int?) {
        // "Отключаться при отключении от сетей WiFi" (rule 7) only outranks rules 5/6, not rule
        // 3: if rule 3 is on, let it keep/start the connection over mobile instead of forcing a
        // disconnect.
        if (oldTransport == NetworkCapabilities.TRANSPORT_WIFI &&
            getBooleanPrefValue(OscPrefKey.AUTO_DISCONNECT_ON_WIFI_LOSS, prefs) &&
            !getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_MOBILE, prefs)
        ) {
            disconnectNow()
        }

        if (getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_MOBILE, prefs)) {
            connectNow()
        }
    }
}
