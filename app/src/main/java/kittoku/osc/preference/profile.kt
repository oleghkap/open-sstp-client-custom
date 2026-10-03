package kittoku.osc.preference

import android.content.SharedPreferences
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

// app-wide settings (not per-profile): they must never be saved into / overwritten by a profile
private val EXCLUDED_BOOLEAN_PREFERENCES = arrayOf(
    OscPrefKey.ROOT_STATE,
    OscPrefKey.HOME_CONNECTOR,
    OscPrefKey.HOME_STATUS,
    OscPrefKey.ACTIVE_PROFILE_NAME,
    OscPrefKey.AUTO_CONNECT_ENABLED,
    OscPrefKey.AUTO_CONNECT_DISABLED,
    OscPrefKey.AUTO_CONNECT_ON_MOBILE,
    OscPrefKey.AUTO_DISCONNECT_ON_MOBILE_LOSS,
    OscPrefKey.AUTO_CONNECT_ON_WIFI_INCLUDE,
    OscPrefKey.AUTO_CONNECT_ON_WIFI_EXCLUDE,
    OscPrefKey.AUTO_DISCONNECT_ON_WIFI_LOSS,
)

private val EXCLUDED_STRING_PREFERENCES = arrayOf(
    OscPrefKey.HOME_STATUS,
    OscPrefKey.ACTIVE_PROFILE_NAME,
)

private val EXCLUDED_SET_PREFERENCES = arrayOf(
    OscPrefKey.AUTO_CONNECT_WIFI_INCLUDE_SSIDS,
    OscPrefKey.AUTO_CONNECT_WIFI_EXCLUDE_SSIDS,
)

@Serializable
internal class Profile() {
    internal val booleanSetting = mutableMapOf<String, Boolean>()
    internal val intSetting = mutableMapOf<String, Int>()
    internal val stringSetting = mutableMapOf<String, String>()
    internal val setSetting = mutableMapOf<String, Set<String>>()
    internal val uriSetting = mutableMapOf<String, String>()
}

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
    DEFAULT_SET_MAP.keys.filter { it !in EXCLUDED_SET_PREFERENCES }.forEach {
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
        setBooleanPrefValue(
            profile?.booleanSetting[it.name] ?: DEFAULT_BOOLEAN_MAP.getValue(it),
            it,
            prefs,
        )
    }
    DEFAULT_INT_MAP.keys.forEach {
        setIntPrefValue(
            profile?.intSetting[it.name] ?: DEFAULT_INT_MAP.getValue(it),
            it,
            prefs,
        )
    }
    DEFAULT_STRING_MAP.keys.filter { it !in EXCLUDED_STRING_PREFERENCES }.forEach {
        setStringPrefValue(
            profile?.stringSetting[it.name] ?: DEFAULT_STRING_MAP.getValue(it),
            it,
            prefs,
        )
    }
    DEFAULT_SET_MAP.keys.filter { it !in EXCLUDED_SET_PREFERENCES }.forEach {
        setSetPrefValue(
            profile?.setSetting[it.name] ?: DEFAULT_SET_MAP.getValue(it),
            it,
            prefs,
        )
    }
    DEFAULT_URI_MAP.keys.forEach {
        setURIPrefValue(
            profile?.uriSetting[it.name]?.toUri() ?: DEFAULT_URI_MAP.getValue(it),
            it,
            prefs,
        )
    }
}

internal fun summarizeProfile(profile: Profile): String {
    val hostname = profile.stringSetting[OscPrefKey.HOME_HOSTNAME.name]
    val username = profile.stringSetting[OscPrefKey.HOME_USERNAME.name]
    val portNumber = profile.intSetting[OscPrefKey.SSL_PORT.name].toString()
    return "[Hostname]\n$hostname\n\n[Username]\n$username\n\n[Port Number]\n$portNumber"
}
