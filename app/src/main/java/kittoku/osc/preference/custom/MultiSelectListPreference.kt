package kittoku.osc.preference.custom

import android.content.Context
import android.util.AttributeSet
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import kittoku.osc.R
import kittoku.osc.preference.AUTH_PROTOCOL_EAP_MSCHAPv2
import kittoku.osc.preference.AUTH_PROTOCOL_MSCHAPv2
import kittoku.osc.preference.AUTH_PROTOCOl_PAP
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getSetPrefValue
import javax.net.ssl.SSLContext


internal abstract class ModifiedMultiSelectListPreference(context: Context, attrs: AttributeSet) : MultiSelectListPreference(context, attrs), OscPreference {
    protected abstract val entryValues: Array<String>
    protected open val entries: Array<String>? = null
    protected open val provider: SummaryProvider<Preference>? = null

    override fun updateView() {
        values = getSetPrefValue(oscPrefKey, sharedPreferences!!)
    }

    override fun onAttached() {
        setEntryValues(entryValues)
        setEntries(entries ?: entryValues)

        summaryProvider = provider

        initialize()
    }
}

internal class SSLSuitesPreference(context: Context, attrs: AttributeSet) : ModifiedMultiSelectListPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.SSL_SUITES
    override val parentKey = OscPrefKey.SSL_DO_SELECT_SUITES
    override val preferenceTitle = R.string.pref_cipher_suites
    override val entryValues = SSLContext.getDefault().supportedSSLParameters.cipherSuites as Array<String>

    override val provider = SummaryProvider<Preference> {
        val currentValue = getSetPrefValue(oscPrefKey, it.sharedPreferences!!)

        val size = currentValue.size
        if (size == 0) {
            it.context.getString(R.string.summary_no_suite)
        } else {
            it.context.resources.getQuantityString(R.plurals.summary_suites_selected, size, size)
        }
    }
}

internal class PPPAuthProtocolsPreference(context: Context, attrs: AttributeSet) : ModifiedMultiSelectListPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.PPP_AUTH_PROTOCOLS
    override val parentKey = null
    override val preferenceTitle = R.string.pref_auth_protocols
    override val entryValues = arrayOf(
        AUTH_PROTOCOl_PAP,
        AUTH_PROTOCOL_MSCHAPv2,
        AUTH_PROTOCOL_EAP_MSCHAPv2,
    )

    override val provider = SummaryProvider<Preference> {
        val currentValue = getSetPrefValue(oscPrefKey, it.sharedPreferences!!)

        val size = currentValue.size
        if (size == 0) {
            it.context.getString(R.string.summary_no_protocol)
        } else {
            it.context.resources.getQuantityString(R.plurals.summary_protocols_selected, size, size)
        }
    }
}
