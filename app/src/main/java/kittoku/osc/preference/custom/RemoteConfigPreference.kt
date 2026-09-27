package kittoku.osc.preference.custom

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import androidx.preference.Preference
import kittoku.osc.R
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getStringPrefValue


internal class RemoteConfigEnabledPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.REMOTE_CONFIG_ENABLED
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = R.string.pref_remote_config

    override fun onAttached() {
        super.onAttached()
        summary = context.getString(R.string.summary_remote_config)
    }
}

internal class RemoteConfigUrlPreference(context: Context, attrs: AttributeSet) : StringPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.REMOTE_CONFIG_URL
    override val parentKey = OscPrefKey.REMOTE_CONFIG_ENABLED
    override val preferenceTitle = R.string.pref_remote_config_url
    override val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI

    override fun onAttached() {
        dialogMessage = context.getString(R.string.dialog_remote_config_warning)
        super.onAttached()
    }
}

internal class RemoteConfigFetchPreference(context: Context, attrs: AttributeSet) : Preference(context, attrs) {
    override fun onAttached() {
        super.onAttached()
        title = context.getString(R.string.pref_remote_config_fetch)
        dependency = OscPrefKey.REMOTE_CONFIG_ENABLED.name
    }
}

internal class RemoteConfigStatusPreference(context: Context, attrs: AttributeSet) : SummaryPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.REMOTE_CONFIG_STATUS
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = R.string.pref_remote_config_status

    override fun updateView() {
        summary = getStringPrefValue(oscPrefKey, sharedPreferences!!).ifEmpty {
            context.getString(R.string.summary_remote_config_never)
        }
    }
}
