package kittoku.osc.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import kittoku.osc.R
import kittoku.osc.SharedBridge
import kittoku.osc.control.Controller
import kittoku.osc.control.LogWriter
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.getURIPrefValue
import kittoku.osc.preference.accessor.resetReconnectionLife
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setIntPrefValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


internal const val ACTION_VPN_CONNECT = "kittoku.osc.connect"
internal const val ACTION_VPN_DISCONNECT = "kittoku.osc.disconnect"

internal const val NOTIFICATION_ERROR_CHANNEL = "ERROR"
internal const val NOTIFICATION_RECONNECT_CHANNEL = "RECONNECT"
internal const val NOTIFICATION_DISCONNECT_CHANNEL = "DISCONNECT"
internal const val NOTIFICATION_CERTIFICATE_CHANNEL = "CERTIFICATE"

internal const val NOTIFICATION_ERROR_ID = 1
internal const val NOTIFICATION_RECONNECT_ID = 2
internal const val NOTIFICATION_DISCONNECT_ID = 3
internal const val NOTIFICATION_CERTIFICATE_ID = 4

private const val STATUS_UPDATE_INTERVAL = 2_000L


internal class SstpVpnService : VpnService() {
    private lateinit var prefs: SharedPreferences
    private lateinit var listener: SharedPreferences.OnSharedPreferenceChangeListener
    private lateinit var notificationManager: NotificationManagerCompat
    internal lateinit var scope: CoroutineScope

    internal var logWriter: LogWriter? = null
    private var controller: Controller?  = null

    private var jobReconnect: Job? = null
    private var jobStatusUpdater: Job? = null

    private fun setRootState(state: Boolean) {
        setBooleanPrefValue(state, OscPrefKey.ROOT_STATE, prefs)
    }

    private fun requestTileListening() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            TileService.requestListeningState(this,
                ComponentName(this, SstpTileService::class.java)
            )
        }
    }

    override fun onCreate() {
        notificationManager = NotificationManagerCompat.from(this)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == OscPrefKey.ROOT_STATE.name) {
                val newState = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)

                setBooleanPrefValue(newState, OscPrefKey.HOME_CONNECTOR, prefs)
                requestTileListening()
            }
        }

        prefs.registerOnSharedPreferenceChangeListener(listener)

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_VPN_CONNECT -> {
                jobStatusUpdater?.cancel()

                // if a connection is already running (editing settings live, switching
                // profiles, or saving with an immediate reconnect), tear it down and WAIT for
                // that teardown to actually finish before starting the new one. The old
                // controller must not stop this service in the process (shouldStopService =
                // false), otherwise it would kill the brand new connection started right after.
                controller?.also { old ->
                    runBlocking { old.kill(false, shouldStopService = false).join() }
                }

                beForegrounded()
                resetReconnectionLife(prefs)
                if (getBooleanPrefValue(OscPrefKey.LOG_DO_SAVE_LOG, prefs)) {
                    prepareLogWriter()
                }

                logWriter?.write("Establish VPN connection")

                initializeClient()

                setRootState(true)

                startStatusUpdater()

                START_STICKY
            }

            else -> {
                jobStatusUpdater?.cancel()

                // ensure that reconnection has been completely canceled or done
                runBlocking { jobReconnect?.cancelAndJoin() }

                controller?.disconnect()
                controller = null

                close()

                START_NOT_STICKY
            }
        }
    }

    private fun initializeClient() {
        controller = Controller(SharedBridge(this)).also {
            it.launchJobMain()
        }
    }

    private fun prepareLogWriter() {
        val currentDateTime = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())
        val filename = "log_osc_${currentDateTime}.txt"

        val prefURI = getURIPrefValue(OscPrefKey.LOG_DIR, prefs)
        if (prefURI == null) {
            notifyError("LOG: ERR_NULL_PREFERENCE")
            return
        }

        val dirURI = DocumentFile.fromTreeUri(this, prefURI)
        if (dirURI == null) {
            notifyError("LOG: ERR_NULL_DIRECTORY")
            return
        }

        val fileURI = dirURI.createFile("text/plain", filename)
        if (fileURI == null) {
            notifyError("LOG: ERR_NULL_FILE")
            return
        }

        val stream = contentResolver.openOutputStream(fileURI.uri, "wa")
        if (stream == null) {
            notifyError("LOG: ERR_NULL_STREAM")
            return
        }

        logWriter = LogWriter(stream)
    }

    internal fun launchJobReconnect() {
        jobReconnect = scope.launch {
            try {
                getIntPrefValue(OscPrefKey.RECONNECTION_LIFE, prefs).also {
                    val life = it - 1
                    setIntPrefValue(life, OscPrefKey.RECONNECTION_LIFE, prefs)

                    val message = "Будет предпринята попытка переподключения (осталось: $life)"
                    notifyMessage(message, NOTIFICATION_RECONNECT_ID, NOTIFICATION_RECONNECT_CHANNEL)
                    logWriter?.report(message)
                }

                delay(getIntPrefValue(OscPrefKey.RECONNECTION_INTERVAL, prefs) * 1000L)

                initializeClient()
            } catch (_: CancellationException) { }
            finally {
                cancelNotification(NOTIFICATION_RECONNECT_ID)
            }
        }
    }

    private fun beForegrounded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            arrayOf(
                NOTIFICATION_ERROR_CHANNEL,
                NOTIFICATION_RECONNECT_CHANNEL,
                NOTIFICATION_DISCONNECT_CHANNEL,
                NOTIFICATION_CERTIFICATE_CHANNEL,
            ).map {
                NotificationChannel(it, it, NotificationManager.IMPORTANCE_DEFAULT)
            }.also {
                notificationManager.createNotificationChannels(it)
            }
        }

        startForeground(NOTIFICATION_DISCONNECT_ID, buildStatusNotification())
    }

    // updates the ongoing notification every couple seconds with the real connection state
    // ("Соединение…" while negotiating, "Соединено" once the tunnel is fully up), which
    // profile is connected, and live incoming/outgoing traffic.
    private fun startStatusUpdater() {
        jobStatusUpdater = scope.launch {
            while (true) {
                tryNotify(buildStatusNotification(), NOTIFICATION_DISCONNECT_ID)
                delay(STATUS_UPDATE_INTERVAL)
            }
        }
    }

    private fun buildStatusNotification(): Notification {
        val profileName = getStringPrefValue(OscPrefKey.ACTIVE_PROFILE_NAME, prefs).ifEmpty { getString(R.string.app_name) }
        val isEstablished = getStringPrefValue(OscPrefKey.HOME_STATUS, prefs).isNotEmpty()
        val stateLabel = if (isEstablished) "Соединено" else "Соединение…"

        val ipTerminal = controller?.bridge?.ipTerminal
        val trafficText = if (isEstablished && ipTerminal != null) {
            "Входящий: ${formatBytes(ipTerminal.bytesIn.get())}  •  Исходящий: ${formatBytes(ipTerminal.bytesOut.get())}"
        } else {
            null
        }

        val pendingIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, SstpVpnService::class.java).setAction(ACTION_VPN_DISCONNECT),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_DISCONNECT_CHANNEL).also {
            it.priority = NotificationCompat.PRIORITY_DEFAULT
            it.setOngoing(true)
            it.setAutoCancel(false)
            it.setSmallIcon(R.drawable.ic_baseline_vpn_lock_24)
            it.setContentTitle("$profileName — $stateLabel")
            if (trafficText != null) {
                it.setContentText(trafficText)
            }
            it.addAction(R.drawable.ic_baseline_close_24, "ОТКЛЮЧИТЬ", pendingIntent)
        }.build()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes Б"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f КБ", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f МБ", mb)
        return String.format(Locale.getDefault(), "%.2f ГБ", mb / 1024.0)
    }

    internal fun notifyMessage(message: String, id: Int, channel: String) {
        NotificationCompat.Builder(this, channel).also {
            it.setSmallIcon(R.drawable.ic_baseline_vpn_lock_24)
            it.setContentText(message)
            it.priority = NotificationCompat.PRIORITY_DEFAULT
            it.setAutoCancel(true)

            tryNotify(it.build(), id)
        }
    }

    internal fun notifyError(message: String) {
        notifyMessage(message, NOTIFICATION_ERROR_ID, NOTIFICATION_ERROR_CHANNEL)
    }

    internal fun tryNotify(notification: Notification, id: Int) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager.notify(id, notification)
        }
    }

    internal fun cancelNotification(id: Int) {
        notificationManager.cancel(id)
    }

    internal fun close() {
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        jobStatusUpdater?.cancel()

        logWriter?.write("Terminate VPN connection")
        logWriter?.close()
        logWriter = null

        controller?.kill(false)
        controller = null

        scope.cancel()

        setRootState(false)
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
