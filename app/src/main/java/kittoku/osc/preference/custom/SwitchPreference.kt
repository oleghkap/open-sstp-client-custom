package kittoku.osc.preference.custom

import android.content.Context
import android.util.AttributeSet
import androidx.preference.SwitchPreferenceCompat
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue


internal abstract class SwitchPreference(context: Context, attrs: AttributeSet) : SwitchPreferenceCompat(context, attrs), OscPreference {
    // an optional static subtitle shown under the title, like "Запрещает автоматические
    // VPN-подключения при изменении сети" under "Запретить автоматическое подключение"
    protected open val preferenceSummary: String? = null

    override fun updateView() {
        isChecked = getBooleanPrefValue(oscPrefKey, sharedPreferences!!)
    }

    override fun onAttached() {
        initialize()
        preferenceSummary?.also { summary = it }
    }
}

internal class SSLDoSpecifyCertPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.SSL_DO_SPECIFY_CERT
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Указать доверенные сертификаты"
}

internal class SSLDoSelectSuitesPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.SSL_DO_SELECT_SUITES
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Использовать только выбранные наборы шифров"
}

internal class SSLDoUseCustomSNIPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.SSL_DO_USE_CUSTOM_SNI
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Использовать свой SNI"
}

internal class PPPDoRequestStaticIPv4AddressPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.PPP_DO_REQUEST_STATIC_IPv4_ADDRESS
    override val parentKey = OscPrefKey.PPP_IPv4_ENABLED
    override val preferenceTitle = "Запрашивать статический IPv4-адрес"
}

internal class ProxyDoUseProxyPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.PROXY_DO_USE_PROXY
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Использовать HTTP-прокси"
}

internal class DNSDoRequestAddressPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.DNS_DO_REQUEST_ADDRESS
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Запрашивать адрес DNS-сервера"
}

internal class DNSDoUseCustomServerPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.DNS_DO_USE_CUSTOM_SERVER
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Использовать свой DNS-сервер"
}

internal class RouteDoAddCustomRoutesPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.ROUTE_DO_ADD_CUSTOM_ROUTES
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Добавить ручные маршруты"
}

internal class RouteDoEnableAppBasedRulePreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.ROUTE_DO_ENABLE_APP_BASED_RULE
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Включить правила по приложениям"
}

internal class RouteDoShowBackgroundAppsPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.ROUTE_DO_SHOW_BACKGROUND_APPS
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Показывать фоновые приложения"
}

internal class ReconnectionEnabledPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.RECONNECTION_ENABLED
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Ограничивать количество попыток"
}

internal class LogDoSaveLogPreference(context: Context, attrs: AttributeSet) : SwitchPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.LOG_DO_SAVE_LOG
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Сохранять журнал"
}
