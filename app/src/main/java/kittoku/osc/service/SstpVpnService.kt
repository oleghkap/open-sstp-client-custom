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
import kittoku.osc.preference.accessor.getURIPrefValue
import kittoku.osc.preference.accessor.resetReconnectionLife
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setIntPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.preference.checkPreferences
import kittoku.osc.preference.fetchRemoteConfigIfEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
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


internal class SstpVpnService : VpnService() {
    private lateinit var prefs: SharedPreferences
    private lateinit var listener: SharedPreferences.OnSharedPreferenceChangeListener
    private lateinit var notificationManager: NotificationManagerCompat
    internal lateinit var scope: CoroutineScope

    internal var logWriter: LogWriter? = null
    private var controller: Controller?  = null

    private var jobReconnect: Job? = null
    private var jobConnect: Job? = null
    @Volatile private var connectGeneration = 0

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
                val generation = ++connectGeneration
                jobReconnect?.cancel()
                jobConnect?.cancel()
                controller?.kill(false, null)

                beForegrounded()
                setRootState(true)
                jobConnect = scope.launch { connectAfterRemoteConfig(generation) }

                START_STICKY
            }

            else -> {
                val generation = ++connectGeneration
                val reconnectJob = jobReconnect
                val connectJob = jobConnect
                jobReconnect = null
                jobConnect = null
                reconnectJob?.cancel()
                connectJob?.cancel()
                setRootState(false)
                stopForeground(true)

                // The download coroutine returns as soon as it is cancelled, then the tunnel is closed.
                scope.launch {
                    reconnectJob?.join()
                    connectJob?.join()
                    if (generation != connectGeneration) return@launch

                    controller?.disconnect()
                    controller = null
                    close()
                }

                START_NOT_STICKY
            }
        }
    }

    private suspend fun connectAfterRemoteConfig(generation: Int) {
        try {
            openClient(generation, freshSession = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (generation != connectGeneration) return
            notifyError(getString(R.string.error_remote_config_network))
            setRootState(false)
            close()
        }
    }

    private suspend fun openClient(generation: Int, freshSession: Boolean) {
        fetchRemoteConfigIfEnabled(applicationContext, prefs)
        coroutineContext.ensureActive()
        if (generation != connectGeneration) return

        val error = checkPreferences(prefs, this)
        if (error != null) {
            if (generation != connectGeneration) return
            setStringPrefValue(error, OscPrefKey.HOME_STATUS, prefs)
            notifyError(getString(R.string.toast_invalid_setting, error))
            setRootState(false)
            close()
            return
        }

        coroutineContext.ensureActive()
        if (generation != connectGeneration) return

        if (freshSession) {
            resetReconnectionLife(prefs)
            if (getBooleanPrefValue(OscPrefKey.LOG_DO_SAVE_LOG, prefs)) {
                prepareLogWriter()
            }
            logWriter?.write(getString(R.string.log_establish))
        }

        coroutineContext.ensureActive()
        if (generation != connectGeneration) return
        initializeClient()
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
            notifyError(getString(R.string.notify_log_null_preference))
            return
        }

        val dirURI = DocumentFile.fromTreeUri(this, prefURI)
        if (dirURI == null) {
            notifyError(getString(R.string.notify_log_null_directory))
            return
        }

        val fileURI = dirURI.createFile("text/plain", filename)
        if (fileURI == null) {
            notifyError(getString(R.string.notify_log_null_file))
            return
        }

        val stream = contentResolver.openOutputStream(fileURI.uri, "wa")
        if (stream == null) {
            notifyError(getString(R.string.notify_log_null_stream))
            return
        }

        logWriter = LogWriter(stream)
    }

    internal fun launchJobReconnect() {
        val generation = connectGeneration
        jobReconnect = scope.launch {
            try {
                getIntPrefValue(OscPrefKey.RECONNECTION_LIFE, prefs).also {
                    val life = it - 1
                    setIntPrefValue(life, OscPrefKey.RECONNECTION_LIFE, prefs)

                    val message = getString(R.string.notification_reconnect, life)
                    notifyMessage(message, NOTIFICATION_RECONNECT_ID, NOTIFICATION_RECONNECT_CHANNEL)
                    logWriter?.report(message)
                }

                delay(getIntPrefValue(OscPrefKey.RECONNECTION_INTERVAL, prefs) * 1000L)

                openClient(generation, freshSession = false)
            } catch (_: CancellationException) {
            } catch (_: Exception) {
                if (generation != connectGeneration) return@launch
                notifyError(getString(R.string.error_remote_config_network))
                setRootState(false)
                close()
            } finally {
                cancelNotification(NOTIFICATION_RECONNECT_ID)
            }
        }
    }

    private fun beForegrounded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            listOf(
                NOTIFICATION_ERROR_CHANNEL to R.string.channel_error,
                NOTIFICATION_RECONNECT_CHANNEL to R.string.channel_reconnect,
                NOTIFICATION_DISCONNECT_CHANNEL to R.string.channel_disconnect,
                NOTIFICATION_CERTIFICATE_CHANNEL to R.string.channel_certificate,
            ).map { (id, nameRes) ->
                NotificationChannel(id, getString(nameRes), NotificationManager.IMPORTANCE_DEFAULT)
            }.also {
                notificationManager.createNotificationChannels(it)
            }
        }

        val pendingIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, SstpVpnService::class.java).setAction(ACTION_VPN_DISCONNECT),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_DISCONNECT_CHANNEL).also {
            it.priority = NotificationCompat.PRIORITY_DEFAULT
            it.setOngoing(true)
            it.setAutoCancel(true)
            it.setSmallIcon(R.drawable.ic_baseline_vpn_lock_24)
            it.addAction(R.drawable.ic_baseline_close_24, getString(R.string.action_disconnect), pendingIntent)
        }

        startForeground(NOTIFICATION_DISCONNECT_ID, builder.build())
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
        logWriter?.write(getString(R.string.log_terminate))
        logWriter?.close()
        logWriter = null

        controller?.kill(false, null)
        controller = null

        scope.cancel()

        setRootState(false)
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
