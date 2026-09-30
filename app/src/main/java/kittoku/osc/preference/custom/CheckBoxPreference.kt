package kittoku.osc.preference.custom

import android.content.Context
import android.util.AttributeSet
import androidx.preference.CheckBoxPreference
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue


internal abstract class ModifiedCheckBoxPreference(context: Context, attrs: AttributeSet) : CheckBoxPreference(context, attrs), OscPreference {
    override fun updateView() {
        isChecked = getBooleanPrefValue(oscPrefKey, sharedPreferences!!)
    }

    override fun onAttached() {
        initialize()
    }
}

internal class SSLDoVerifyPreference(context: Context, attrs: AttributeSet) : ModifiedCheckBoxPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.SSL_DO_VERIFY
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Проверять имя хоста"
}

internal class PPPIPv4EnabledPreference(context: Context, attrs: AttributeSet) : ModifiedCheckBoxPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.PPP_IPv4_ENABLED
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Включить IPv4"
}

internal class PPPIPv6EnabledPreference(context: Context, attrs: AttributeSet) : ModifiedCheckBoxPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.PPP_IPv6_ENABLED
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Включить IPv6"
}

internal class PPPMtuManualPreference(context: Context, attrs: AttributeSet) : ModifiedCheckBoxPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.PPP_MTU_MANUAL
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Установить MTU туннеля"
}

internal class PPPMruManualPreference(context: Context, attrs: AttributeSet) : ModifiedCheckBoxPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.PPP_MRU_MANUAL
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Установить MRU туннеля"
}

internal class PPPMssDoClampPreference(context: Context, attrs: AttributeSet) : ModifiedCheckBoxPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.PPP_MSS_DO_CLAMP
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Ограничивать максимальный размер сегмента (MSS)"
}

internal class AutoConnectEnabledPreference(context: Context, attrs: AttributeSet) : ModifiedCheckBoxPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.AUTO_CONNECT_ENABLED
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Автоподключение при загрузке"
}

internal class RouteDoAddDefaultRoutePreference(context: Context, attrs: AttributeSet) : ModifiedCheckBoxPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.ROUTE_DO_ADD_DEFAULT_ROUTE
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Добавить маршрут по умолчанию"
}

internal class RouteDoRoutePrivateAddresses(context: Context, attrs: AttributeSet) : ModifiedCheckBoxPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.ROUTE_DO_ROUTE_PRIVATE_ADDRESSES
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Маршрутизировать приватные адреса"
}
