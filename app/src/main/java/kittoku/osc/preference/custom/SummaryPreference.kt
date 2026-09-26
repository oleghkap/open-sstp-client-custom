package kittoku.osc.preference.custom

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import kittoku.osc.R
import kittoku.osc.preference.LIST_TYPE_ALLOWED
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getSetPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue


internal abstract class SummaryPreference(context: Context, attrs: AttributeSet) : Preference(context, attrs), OscPreference {
    protected open val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == oscPrefKey.name) {
            updateView()
        }
    }

    override fun onAttached() {
        sharedPreferences!!.registerOnSharedPreferenceChangeListener(listener)

        initialize()
    }

    override fun onDetached() {
        sharedPreferences!!.unregisterOnSharedPreferenceChangeListener(listener)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.findViewById(android.R.id.summary)?.also {
            it as TextView
            it.maxLines = Int.MAX_VALUE
        }
    }
}

internal class HomeStatusPreference(context: Context, attrs: AttributeSet) : SummaryPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.HOME_STATUS
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = R.string.pref_status

    override fun updateView() {
        summary = getStringPrefValue(oscPrefKey, sharedPreferences!!).ifEmpty {
            context.getString(R.string.summary_no_connection)
        }
    }
}

internal class RouteSelectedAppsPreference(context: Context, attrs: AttributeSet) : SummaryPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.ROUTE_SELECTED_APPS
    override val parentKey = OscPrefKey.ROUTE_DO_ENABLE_APP_BASED_RULE
    override val preferenceTitle = R.string.pref_select_apps

    override fun updateView() {
        val isAllowedList = getStringPrefValue(OscPrefKey.ROUTE_APP_LIST_TYPE, sharedPreferences!!) == LIST_TYPE_ALLOWED
        val size = getSetPrefValue(oscPrefKey, sharedPreferences!!).size

        summary = if (size == 0) {
            context.getString(
                if (isAllowedList) R.string.summary_no_app_allowed else R.string.summary_no_app_disallowed
            )
        } else {
            context.resources.getQuantityString(
                if (isAllowedList) R.plurals.summary_apps_allowed else R.plurals.summary_apps_disallowed,
                size,
                size,
            )
        }
    }
}
