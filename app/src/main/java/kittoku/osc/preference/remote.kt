package kittoku.osc.preference

import android.content.Context
import android.content.SharedPreferences
import kittoku.osc.BuildConfig
import kittoku.osc.R
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean


internal const val REMOTE_CONFIG_MAX_BYTES = 256 * 1024
private const val REMOTE_CONFIG_MAX_REDIRECTS = 5
private const val REMOTE_CONFIG_CONNECT_TIMEOUT_MS = 10_000
private const val REMOTE_CONFIG_READ_TIMEOUT_MS = 15_000

private val remoteJson = Json { ignoreUnknownKeys = true }

private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private val fetchGate = Mutex()
private val fetchedOnProcessStart = AtomicBoolean(false)

internal enum class RemoteConfigFailure {
    URL_MISSING,
    NOT_HTTPS,
    INVALID_URL,
    HTTP,
    TOO_LARGE,
    REDIRECT,
    NETWORK,
    INVALID_JSON,
}

internal class RemoteConfigException(
    internal val reason: RemoteConfigFailure,
    internal val httpCode: Int = 0,
) : Exception()

internal sealed class RemoteConfigResult {
    data object Disabled : RemoteConfigResult()
    data object Unchanged : RemoteConfigResult()
    data object Applied : RemoteConfigResult()
    data class Failed(val message: String) : RemoteConfigResult()
}

internal fun fetchRemoteConfigOnProcessStart(
    context: Context,
    prefs: SharedPreferences,
    onFinished: () -> Unit,
) {
    if (!fetchedOnProcessStart.compareAndSet(false, true)) return
    if (!getBooleanPrefValue(OscPrefKey.REMOTE_CONFIG_ENABLED, prefs)) return
    if (getStringPrefValue(OscPrefKey.REMOTE_CONFIG_URL, prefs).isBlank()) return

    mainScope.launch {
        fetchRemoteConfigIfEnabled(context.applicationContext, prefs)
        onFinished()
    }
}

internal suspend fun fetchRemoteConfigIfEnabled(
    context: Context,
    prefs: SharedPreferences,
): RemoteConfigResult {
    if (!getBooleanPrefValue(OscPrefKey.REMOTE_CONFIG_ENABLED, prefs)) {
        return RemoteConfigResult.Disabled
    }

    return fetchGate.withLock {
        withContext(Dispatchers.IO) {
            downloadAndApply(context.applicationContext, prefs)
        }
    }
}

internal fun requireHttps(raw: String): URI {
    val uri = try {
        URI(raw.trim())
    } catch (_: URISyntaxException) {
        throw RemoteConfigException(RemoteConfigFailure.INVALID_URL)
    }

    return requireHttps(uri)
}

internal fun resolveHttps(current: URI, location: String): URI {
    val resolved = try {
        current.resolve(location.trim())
    } catch (_: IllegalArgumentException) {
        throw RemoteConfigException(RemoteConfigFailure.INVALID_URL)
    }

    return requireHttps(resolved)
}

internal fun readCapped(input: InputStream, maxBytes: Int): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0
    while (true) {
        val read = input.read(chunk)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw RemoteConfigException(RemoteConfigFailure.TOO_LARGE)
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}

internal fun decodeRemoteProfile(bytes: ByteArray): Profile {
    var text = bytes.toString(Charsets.UTF_8)
    if (text.startsWith("\uFEFF")) {
        text = text.substring(1)
    }
    return remoteJson.decodeFromString(text)
}

private fun requireHttps(uri: URI): URI {
    val scheme = uri.scheme?.lowercase(Locale.US)
    if (scheme == null || uri.rawAuthority.isNullOrEmpty() || uri.host.isNullOrBlank()) {
        throw RemoteConfigException(RemoteConfigFailure.INVALID_URL)
    }
    if (scheme != "https") {
        throw RemoteConfigException(RemoteConfigFailure.NOT_HTTPS)
    }
    return uri
}

private suspend fun downloadAndApply(context: Context, prefs: SharedPreferences): RemoteConfigResult {
    val result = try {
        val url = getStringPrefValue(OscPrefKey.REMOTE_CONFIG_URL, prefs)
        if (url.isBlank()) {
            throw RemoteConfigException(RemoteConfigFailure.URL_MISSING)
        }

        val profile = downloadRemoteProfile(url)

        if (applyPresentSettings(profile, prefs)) {
            RemoteConfigResult.Applied
        } else {
            RemoteConfigResult.Unchanged
        }
    } catch (error: RemoteConfigException) {
        RemoteConfigResult.Failed(failureMessage(context, error))
    } catch (_: IOException) {
        RemoteConfigResult.Failed(context.getString(R.string.error_remote_config_network))
    }

    coroutineContext.ensureActive()
    recordStatus(context, prefs, result)
    return result
}

internal suspend fun downloadRemoteProfile(url: String): Profile {
    return try {
        decodeRemoteProfile(downloadRemoteConfig(url))
    } catch (_: SerializationException) {
        throw RemoteConfigException(RemoteConfigFailure.INVALID_JSON)
    } catch (_: IllegalArgumentException) {
        throw RemoteConfigException(RemoteConfigFailure.INVALID_JSON)
    }
}

internal suspend fun <T> blockingCall(abort: () -> Unit, block: () -> T): T {
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            try {
                abort()
            } catch (_: Exception) {
            }
        }
        val result = runCatching(block)
        if (continuation.isActive) {
            continuation.resumeWith(result)
        }
    }
}

internal suspend fun downloadRemoteConfig(url: String): ByteArray {
    var current = requireHttps(url)
    var redirects = 0

    while (true) {
        coroutineContext.ensureActive()
        val connection = (current.toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = REMOTE_CONFIG_CONNECT_TIMEOUT_MS
            readTimeout = REMOTE_CONFIG_READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "OpenSSTPClient/${BuildConfig.VERSION_NAME}")
        }

        try {
            val code = blockingCall({ connection.disconnect() }) { connection.responseCode }
            if (code in 300..399) {
                if (++redirects > REMOTE_CONFIG_MAX_REDIRECTS) {
                    throw RemoteConfigException(RemoteConfigFailure.REDIRECT)
                }
                val location = connection.getHeaderField("Location")
                    ?: throw RemoteConfigException(RemoteConfigFailure.INVALID_URL)
                current = resolveHttps(current, location)
                continue
            }

            if (code != HttpURLConnection.HTTP_OK) {
                throw RemoteConfigException(RemoteConfigFailure.HTTP, code)
            }

            val length = connection.contentLengthLong
            if (length > REMOTE_CONFIG_MAX_BYTES) {
                throw RemoteConfigException(RemoteConfigFailure.TOO_LARGE)
            }

            return blockingCall({ connection.disconnect() }) {
                connection.inputStream.use { readCapped(it, REMOTE_CONFIG_MAX_BYTES) }
            }
        } finally {
            connection.disconnect()
        }
    }
}

private fun failureMessage(context: Context, error: RemoteConfigException): String {
    return when (error.reason) {
        RemoteConfigFailure.URL_MISSING -> context.getString(R.string.error_remote_config_url_missing)
        RemoteConfigFailure.NOT_HTTPS -> context.getString(R.string.error_remote_config_https)
        RemoteConfigFailure.INVALID_URL -> context.getString(R.string.error_remote_config_invalid_url)
        RemoteConfigFailure.HTTP -> context.getString(R.string.error_remote_config_http, error.httpCode)
        RemoteConfigFailure.TOO_LARGE -> context.getString(R.string.error_remote_config_too_large)
        RemoteConfigFailure.REDIRECT -> context.getString(R.string.error_remote_config_redirect)
        RemoteConfigFailure.NETWORK -> context.getString(R.string.error_remote_config_network)
        RemoteConfigFailure.INVALID_JSON -> context.getString(R.string.error_remote_config_invalid_json)
    }
}

private fun recordStatus(context: Context, prefs: SharedPreferences, result: RemoteConfigResult) {
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
    val text = when (result) {
        RemoteConfigResult.Disabled -> return
        RemoteConfigResult.Applied -> context.getString(R.string.remote_config_status_applied, time)
        RemoteConfigResult.Unchanged -> context.getString(R.string.remote_config_status_unchanged, time)
        is RemoteConfigResult.Failed -> context.getString(
            R.string.remote_config_status_failed,
            time,
            result.message,
        )
    }
    setStringPrefValue(text, OscPrefKey.REMOTE_CONFIG_STATUS, prefs)
}
