package kittoku.osc.preference

import android.content.Context
import android.content.SharedPreferences
import kittoku.osc.R
import kittoku.osc.extension.toUri
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.getSetPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.getURIPrefValue
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setIntPrefValue
import kittoku.osc.preference.accessor.setSetPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.preference.accessor.setURIPrefValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json


private val EXCLUDED_BOOLEAN_PREFERENCES = arrayOf(
    OscPrefKey.ROOT_STATE,
    OscPrefKey.HOME_CONNECTOR,
    OscPrefKey.HOME_STATUS,
    OscPrefKey.REMOTE_CONFIG_ENABLED,
)

private val EXCLUDED_STRING_PREFERENCES = arrayOf(
    OscPrefKey.HOME_STATUS,
    OscPrefKey.REMOTE_CONFIG_URL,
    OscPrefKey.REMOTE_CONFIG_STATUS,
)

// Session state, device-local paths, and the remote-config settings themselves.
// A downloaded file must not flip the connection or replace the URL it came from.
private val REMOTE_CONFIG_BLOCKED_KEYS = setOf(
    OscPrefKey.ROOT_STATE,
    OscPrefKey.HOME_CONNECTOR,
    OscPrefKey.HOME_STATUS,
    OscPrefKey.RECONNECTION_LIFE,
    OscPrefKey.SSL_CERT_DIR,
    OscPrefKey.LOG_DIR,
    OscPrefKey.REMOTE_CONFIG_ENABLED,
    OscPrefKey.REMOTE_CONFIG_URL,
    OscPrefKey.REMOTE_CONFIG_STATUS,
)

@Serializable
internal class Profile(
    internal val booleanSetting: MutableMap<String, Boolean> = mutableMapOf(),
    internal val intSetting: MutableMap<String, Int> = mutableMapOf(),
    internal val stringSetting: MutableMap<String, String> = mutableMapOf(),
    internal val setSetting: MutableMap<String, Set<String>> = mutableMapOf(),
    internal val uriSetting: MutableMap<String, String> = mutableMapOf(),
)

internal fun serializeProfile(prefs: SharedPreferences): String {
    val profile = Profile()

    DEFAULT_BOOLEAN_MAP.keys.filter { it !in EXCLUDED_BOOLEAN_PREFERENCES }.forEach {
        profile.booleanSetting[it.name] = getBooleanPrefValue(it, prefs)
    }


    DEFAULT_INT_MAP.keys.forEach {
        profile.intSetting[it.name] = getIntPrefValue(it, prefs)
    }

    DEFAULT_STRING_MAP.keys.filter { it !in EXCLUDED_STRING_PREFERENCES }.forEach {
        profile.stringSetting[it.name] = getStringPrefValue(it, prefs)
    }

    DEFAULT_SET_MAP.keys.forEach {
        profile.setSetting[it.name] = getSetPrefValue(it, prefs)
    }

    DEFAULT_URI_MAP.keys.forEach {
        getURIPrefValue(it, prefs)?.also { uri ->
            profile.uriSetting[it.name] = uri.toString()
        }
    }

    return Json.encodeToString(profile)
}

internal fun deserializeProfile(serialized: String): Profile? {
    return try {
        Json.decodeFromString<Profile>(serialized)
    } catch (_: SerializationException) {
        null
    }
}

internal fun importProfile(profile: Profile?, prefs: SharedPreferences) {
    DEFAULT_BOOLEAN_MAP.keys.filter { it !in EXCLUDED_BOOLEAN_PREFERENCES }.forEach {
        val value = profile?.booleanSetting[it.name] ?: DEFAULT_BOOLEAN_MAP.getValue(it)
        setBooleanPrefValue(value, it, prefs)
    }

    DEFAULT_INT_MAP.keys.forEach {
        val value = profile?.intSetting[it.name] ?: DEFAULT_INT_MAP.getValue(it)
        setIntPrefValue(value, it, prefs)
    }

    DEFAULT_STRING_MAP.keys.filter { it !in EXCLUDED_STRING_PREFERENCES }.forEach {
        val value = profile?.stringSetting[it.name] ?: DEFAULT_STRING_MAP.getValue(it)
        setStringPrefValue(value, it, prefs)
    }

    DEFAULT_SET_MAP.keys.forEach {
        val value = profile?.setSetting[it.name] ?: DEFAULT_SET_MAP.getValue(it)

        setSetPrefValue(value, it, prefs)
    }

    DEFAULT_URI_MAP.keys.forEach {
        val value = profile?.uriSetting[it.name]?.toUri() ?: DEFAULT_URI_MAP.getValue(it)
        setURIPrefValue(value, it, prefs)
    }
}

internal sealed class RemoteSettingWrite {
    abstract val key: OscPrefKey

    data class Bool(override val key: OscPrefKey, val value: Boolean) : RemoteSettingWrite()
    data class IntVal(override val key: OscPrefKey, val value: Int) : RemoteSettingWrite()
    data class Str(override val key: OscPrefKey, val value: String) : RemoteSettingWrite()
    data class StrSet(override val key: OscPrefKey, val value: Set<String>) : RemoteSettingWrite()
}

// Only keys present in the profile are returned. Missing keys are left as they are.
// uriSetting is ignored: certificate and log directories are paths on this device.
internal fun remoteSettingWrites(profile: Profile): List<RemoteSettingWrite> {
    val keys = OscPrefKey.entries.associateBy { it.name }
    val writes = mutableListOf<RemoteSettingWrite>()

    profile.booleanSetting.forEach { (name, value) ->
        val key = keys[name] ?: return@forEach
        if (key !in DEFAULT_BOOLEAN_MAP || key in REMOTE_CONFIG_BLOCKED_KEYS) return@forEach
        writes.add(RemoteSettingWrite.Bool(key, value))
    }

    profile.intSetting.forEach { (name, value) ->
        val key = keys[name] ?: return@forEach
        if (key !in DEFAULT_INT_MAP || key in REMOTE_CONFIG_BLOCKED_KEYS) return@forEach
        writes.add(RemoteSettingWrite.IntVal(key, value))
    }

    profile.stringSetting.forEach { (name, value) ->
        val key = keys[name] ?: return@forEach
        if (key !in DEFAULT_STRING_MAP || key in REMOTE_CONFIG_BLOCKED_KEYS) return@forEach
        writes.add(RemoteSettingWrite.Str(key, value))
    }

    profile.setSetting.forEach { (name, value) ->
        val key = keys[name] ?: return@forEach
        if (key !in DEFAULT_SET_MAP || key in REMOTE_CONFIG_BLOCKED_KEYS) return@forEach
        writes.add(RemoteSettingWrite.StrSet(key, value))
    }

    return writes
}

internal fun applyPresentSettings(profile: Profile, prefs: SharedPreferences): Boolean {
    val writes = remoteSettingWrites(profile)
    if (writes.isEmpty()) return false

    var changed = false
    val editor = prefs.edit()
    writes.forEach { write ->
        when (write) {
            is RemoteSettingWrite.Bool -> {
                if (getBooleanPrefValue(write.key, prefs) != write.value) {
                    editor.putBoolean(write.key.name, write.value)
                    changed = true
                }
            }

            is RemoteSettingWrite.IntVal -> {
                if (getIntPrefValue(write.key, prefs) != write.value) {
                    editor.putString(write.key.name, write.value.toString())
                    changed = true
                }
            }

            is RemoteSettingWrite.Str -> {
                if (getStringPrefValue(write.key, prefs) != write.value) {
                    editor.putString(write.key.name, write.value)
                    changed = true
                }
            }

            is RemoteSettingWrite.StrSet -> {
                if (getSetPrefValue(write.key, prefs) != write.value) {
                    editor.putStringSet(write.key.name, write.value.toSet())
                    changed = true
                }
            }
        }
    }

    if (changed) editor.apply()
    return changed
}

internal fun summarizeProfile(profile: Profile, context: Context): String {
    val hostname = profile.stringSetting[OscPrefKey.HOME_HOSTNAME.name]
    val username = profile.stringSetting[OscPrefKey.HOME_USERNAME.name]
    val portNumber = profile.intSetting[OscPrefKey.SSL_PORT.name].toString()

    return context.getString(R.string.profile_summary, hostname, username, portNumber)
}
