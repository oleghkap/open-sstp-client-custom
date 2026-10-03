package kittoku.osc.preference.custom

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getSetPrefValue
import kittoku.osc.service.collectKnownSsids
import kittoku.osc.service.hasLocationPermission


internal class AutoConnectDisabledPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_CONNECT_DISABLED
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Выключить авто-подключение"
}

internal class AutoConnectOnMobilePreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_CONNECT_ON_MOBILE
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Подключаться при подключении к мобильным сетям"
}

internal class AutoDisconnectOnMobileLossPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_DISCONNECT_ON_MOBILE_LOSS
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Отключаться при отключении от мобильных сетей"
}

internal class AutoConnectOnWifiIncludePreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_CONNECT_ON_WIFI_INCLUDE
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Подключаться, когда WiFi подключен к этим SSID"
}

internal class AutoConnectOnWifiExcludePreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_CONNECT_ON_WIFI_EXCLUDE
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Подключаться, когда WiFi подключен к любым SSID кроме этих"
}

internal class AutoDisconnectOnWifiLossPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_DISCONNECT_ON_WIFI_LOSS
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Отключаться при отключении от сетей WiFi"
}

// list of Wi-Fi networks to pick from: the ones already chosen, the one the phone is on now and,
// with the location permission, nearby and remembered networks
internal abstract class SsidListPreference(context: Context, attrs: AttributeSet) : ModifiedMultiSelectListPreference(context, attrs) {
    override val entryValues: Array<String>
        get() = collectKnownSsids(context, getSetPrefValue(oscPrefKey, sharedPreferences!!)).toTypedArray()

    override val provider = SummaryProvider<Preference> {
        val count = getSetPrefValue(oscPrefKey, it.sharedPreferences!!).size

        val base = when (count) {
            0 -> "[Сети не выбраны]"
            1 -> "Выбрана 1 сеть"
            else -> "Выбрано сетей: $count"
        }

        if (hasLocationPermission(it.context)) {
            base
        } else {
            base + "\nЧтобы увидеть список доступных сетей, нужен доступ к геопозиции"
        }
    }
}

internal class AutoConnectWifiIncludeSsidsPreference(context: Context, attrs: AttributeSet) : SsidListPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_CONNECT_WIFI_INCLUDE_SSIDS
    override val parentKey = OscPrefKey.AUTO_CONNECT_ON_WIFI_INCLUDE
    override val preferenceTitle = "Выбрать сети WiFi"
}

internal class AutoConnectWifiExcludeSsidsPreference(context: Context, attrs: AttributeSet) : SsidListPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_CONNECT_WIFI_EXCLUDE_SSIDS
    override val parentKey = OscPrefKey.AUTO_CONNECT_ON_WIFI_EXCLUDE
    override val preferenceTitle = "Выбрать сети WiFi-исключения"
}
