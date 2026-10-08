package kittoku.osc.preference.custom

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getSetPrefValue
import kittoku.osc.preference.accessor.setSetPrefValue


internal class AutoConnectDisabledPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_CONNECT_DISABLED
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Запретить автоматическое подключение"
    override val preferenceSummary = "Запрещает автоматические VPN-подключения при изменении сети"
}

internal class AutoConnectOnBootPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_CONNECT_ENABLED
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Автоматическое подключение при загрузке системы"
    override val preferenceSummary = "Автоматически подключаться сразу после включения или перезагрузки устройства"
}

internal class AutoConnectOnMobilePreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_CONNECT_ON_MOBILE
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Автоматически подключаться через мобильную сеть"
    override val preferenceSummary = "Подключаться при переходе телефона на мобильную сеть"
}

internal class AutoDisconnectOnMobileLossPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_DISCONNECT_ON_MOBILE_LOSS
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Отключаться при отключении от мобильной сети"
    override val preferenceSummary = "Отключать SSTP-туннель при потере мобильной сети"
}

internal class AutoConnectOnWifiIncludePreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_CONNECT_ON_WIFI_INCLUDE
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Подключаться, когда WiFi подключен к этим SSID"
    override val preferenceSummary = "Автоматически подключаться только если текущий SSID Wi-Fi находится в списке разрешённых"
}

internal class AutoConnectOnWifiExcludePreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_CONNECT_ON_WIFI_EXCLUDE
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Подключаться, когда WiFi подключен к любым SSID, кроме этих"
    override val preferenceSummary = "Автоматически подключаться к Wi-Fi, кроме SSID из списка запрещённых"
}

internal class AutoDisconnectOnWifiLossPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_DISCONNECT_ON_WIFI_LOSS
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Отключаться при отключении от сетей WiFi"
    override val preferenceSummary = "Отключать SSTP-туннель при потере активной сети Wi-Fi"
}

// A free-text, newline-separated list of SSIDs. Deliberately NOT a "pick from scanned networks"
// picker: scanning only finds networks that are in range right now, so configuring a rule for a
// network that isn't nearby (e.g. a work Wi-Fi while setting this up from home) would be
// impossible. Typing the exact network name works everywhere, with or without Wi-Fi/location.
internal abstract class SsidListPreference(context: Context, attrs: AttributeSet) : EditTextPreference(context, attrs), OscPreference {
    override fun updateView() {
        text = getSetPrefValue(oscPrefKey, sharedPreferences!!).sorted().joinToString("\n")
    }

    override fun onAttached() {
        setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            it.hint = "Домашний WiFi\nРабочий WiFi"
            it.setSelection(it.text?.length ?: 0)
        }

        // EditTextPreference persists its value as a single String by default, but this
        // preference's key actually stores a Set<String>; returning false here stops that
        // default persistence (we write the parsed set ourselves instead, see below)
        setOnPreferenceChangeListener { _, newValue ->
            val ssids = (newValue as String)
                .split("\n", ",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

            setSetPrefValue(ssids, oscPrefKey, sharedPreferences!!)
            updateView()

            false
        }

        summaryProvider = SummaryProvider<Preference> {
            when (val count = getSetPrefValue(oscPrefKey, it.sharedPreferences!!).size) {
                0 -> "[Сети не выбраны]"
                1 -> "Указана 1 сеть"
                else -> "Указано сетей: $count"
            }
        }

        initialize()
    }
}

internal class AutoConnectWifiIncludeSsidsPreference(context: Context, attrs: AttributeSet) : SsidListPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_CONNECT_WIFI_INCLUDE_SSIDS
    override val parentKey = OscPrefKey.AUTO_CONNECT_ON_WIFI_INCLUDE
    override val preferenceTitle = "Разрешённые сети Wi-Fi"
}

internal class AutoConnectWifiExcludeSsidsPreference(context: Context, attrs: AttributeSet) : SsidListPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_CONNECT_WIFI_EXCLUDE_SSIDS
    override val parentKey = OscPrefKey.AUTO_CONNECT_ON_WIFI_EXCLUDE
    override val preferenceTitle = "Запрещённые сети Wi-Fi"
}
