package kittoku.osc.preference.accessor

import android.content.SharedPreferences
import kittoku.osc.preference.DEFAULT_SET_MAP
import kittoku.osc.preference.OscPrefKey


internal fun getSetPrefValue(key: OscPrefKey, prefs: SharedPreferences): Set<String> {
    val default = DEFAULT_SET_MAP[key]!!

    return try {
        prefs.getStringSet(key.name, default)!!
    } catch (_: ClassCastException) {
        // an older build (or external edit) may have stored a different type under this key;
        // rather than crash every time this preference is read, fall back to the default and
        // heal the entry so subsequent reads succeed too
        setSetPrefValue(default, key, prefs)
        default
    }
}

internal fun setSetPrefValue(value: Set<String>, key: OscPrefKey, prefs: SharedPreferences) {
    prefs.edit().also {
        it.putStringSet(key.name, value)
        it.apply()
    }
}
