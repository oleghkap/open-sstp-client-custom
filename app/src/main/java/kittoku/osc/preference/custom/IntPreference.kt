package kittoku.osc.preference.custom

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import kittoku.osc.preference.OscPrefKey


internal abstract class IntPreference(context: Context, attrs: AttributeSet) : OscEditTextPreference(context, attrs), OscPreference {
    override val inputType = InputType.TYPE_CLASS_NUMBER
}

internal class SSLPortPreference(context: Context, attrs: AttributeSet) : IntPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.SSL_PORT
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Номер порта"
}

internal class ProxyPortPreference(context: Context, attrs: AttributeSet) : IntPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.PROXY_PORT
    override val parentKey = OscPrefKey.PROXY_DO_USE_PROXY
    override val preferenceTitle = "Порт прокси-сервера"
}

internal class PPPMruPreference(context: Context, attrs: AttributeSet) : IntPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.PPP_MRU
    override val parentKey = OscPrefKey.PPP_MRU_MANUAL
    override val preferenceTitle = "MRU"
}

internal class PPPMtuPreference(context: Context, attrs: AttributeSet) : IntPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.PPP_MTU
    override val parentKey = OscPrefKey.PPP_MTU_MANUAL
    override val preferenceTitle = "MTU"
}

internal class PPPMssValuePreference(context: Context, attrs: AttributeSet) : IntPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.PPP_MSS_VALUE
    override val parentKey = OscPrefKey.PPP_MSS_DO_CLAMP
    override val preferenceTitle = "Максимальный размер сегмента (MSS)"
}

internal class PPPAuthTimeoutPreference(context: Context, attrs: AttributeSet) : IntPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.PPP_AUTH_TIMEOUT
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Тайм-аут (сек)"
}

// the number of attempts only matters while attempts are limited
internal class ReconnectionCountPreference(context: Context, attrs: AttributeSet) : IntPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.RECONNECTION_COUNT
    override val parentKey = OscPrefKey.RECONNECTION_ENABLED
    override val preferenceTitle = "Количество попыток"
}

// the pause between attempts applies whether or not the number of attempts is limited
internal class ReconnectionIntervalPreference(context: Context, attrs: AttributeSet) : IntPreference(context, attrs) {
    override val oscPrefKey = OscPrefKey.RECONNECTION_INTERVAL
    override val parentKey: OscPrefKey? = null
    override val preferenceTitle = "Интервал между попытками (сек)"
}
