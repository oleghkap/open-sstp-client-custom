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

// after Wi-Fi drops (and the tunnel is closed) give the old tunnel time to finish tearing down
// before a mobile-data connect is attempted
private const val FALLBACK_DELAY = 3_000L


internal fun isAutoConnectRequired(prefs: SharedPreferences): Boolean {
    if (getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_DISABLED, prefs)) return false

    return getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_MOBILE, prefs) ||
            getBooleanPrefValue(OscPrefKey.AUTO_DISCONNECT_ON_MOBILE_LOSS, prefs) ||
            getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_WIFI_INCLUDE, prefs) ||
            getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ON_WIFI_EXCLUDE, prefs) ||
            getBooleanPrefValue(OscPrefKey.AUTO_DISCONNECT_ON_WIFI_LOSS, prefs)
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
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cellRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
        connectLastProfile(this, prefs)
    }

    private fun disconnectNow() {
        if (isTunnelRunning()) {
            startVpnService(this, ACTION_VPN_DISCONNECT)
        }
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
        if (shouldConnect) connectNow()
    }

    private fun onWifiLost(network: Network) {
        if (wifiNetwork != network) return
        wifiNetwork = null

        if (isMasterOff()) return

        if (getBooleanPrefValue(OscPrefKey.AUTO_DISCONNECT_ON_WIFI_LOSS, prefs)) {
            disconnectNow()
        }

        // if the phone fell back to mobile data, the mobile rule decides what happens next
        handler.postDelayed({
            if (wifiNetwork == null && cellNetwork != null) connectOnMobileIfEnabled()
        }, FALLBACK_DELAY)
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

        if (wifiNetwork == null && getBooleanPrefValue(OscPrefKey.AUTO_DISCONNECT_ON_MOBILE_LOSS, prefs)) {
            disconnectNow()
        }
    }
}
