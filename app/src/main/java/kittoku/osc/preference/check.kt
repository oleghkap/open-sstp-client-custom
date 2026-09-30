package kittoku.osc.preference

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.widget.Toast
import kittoku.osc.MAX_MRU
import kittoku.osc.MAX_MTU
import kittoku.osc.MIN_MRU
import kittoku.osc.MIN_MTU
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.getSetPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.getURIPrefValue


internal fun toastInvalidSetting(message: String, context: Context) {
    Toast.makeText(context, "НЕВЕРНАЯ НАСТРОЙКА: $message", Toast.LENGTH_LONG).show()
}

internal fun checkPreferences(prefs: SharedPreferences): String? {
    getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs).also {
        if (it.isEmpty()) return "Не указан адрес сервера"
    }

    getIntPrefValue(OscPrefKey.SSL_PORT, prefs).also {
        if (it !in 0..65535) return "Порт должен быть в диапазоне 0-65535"
    }

    val doSpecifyCerts = getBooleanPrefValue(OscPrefKey.SSL_DO_SPECIFY_CERT, prefs)
    val version = getStringPrefValue(OscPrefKey.SSL_VERSION, prefs)
    val certDir = getURIPrefValue(OscPrefKey.SSL_CERT_DIR, prefs)
    if (doSpecifyCerts && version == "DEFAULT") return "Для указания доверенных сертификатов нужно указать версию SSL"

    if (doSpecifyCerts && certDir == null) return "Не выбрана папка с сертификатами"

    val doSelectSuites = getBooleanPrefValue(OscPrefKey.SSL_DO_SELECT_SUITES, prefs)
    val suites = getSetPrefValue(OscPrefKey.SSL_SUITES, prefs)
    if (doSelectSuites && suites.isEmpty()) return "Не выбран ни один набор шифров"

    val doUseCustomSNI = getBooleanPrefValue(OscPrefKey.SSL_DO_USE_CUSTOM_SNI, prefs)
    val isAPILevelLacked = Build.VERSION.SDK_INT < Build.VERSION_CODES.N
    val customSNIHostname = getStringPrefValue(OscPrefKey.SSL_CUSTOM_SNI, prefs)
    if (doUseCustomSNI && isAPILevelLacked) return "Свой SNI требует API уровень 24 или выше"
    if (doUseCustomSNI && customSNIHostname.isEmpty()) return "Свой SNI-хост не может быть пустым"

    if (getBooleanPrefValue(OscPrefKey.PROXY_DO_USE_PROXY, prefs)) {
        getStringPrefValue(OscPrefKey.PROXY_HOSTNAME, prefs).also {
            if (it.isEmpty()) return "Не указан адрес прокси-сервера"
        }

        getIntPrefValue(OscPrefKey.PROXY_PORT, prefs).also {
            if (it !in 0..65535) return "Порт прокси-сервера должен быть в диапазоне 0-65535"
        }
    }

    // MRU/MTU are only validated when set to manual - in auto mode the app's own built-in
    // defaults are used instead, and those are always within range
    if (getBooleanPrefValue(OscPrefKey.PPP_MRU_MANUAL, prefs)) {
        getIntPrefValue(OscPrefKey.PPP_MRU, prefs).also {
            if (it !in MIN_MRU..MAX_MRU) return "MRU должен быть в диапазоне $MIN_MRU-$MAX_MRU"
        }
    }

    if (getBooleanPrefValue(OscPrefKey.PPP_MTU_MANUAL, prefs)) {
        getIntPrefValue(OscPrefKey.PPP_MTU, prefs).also {
            if (it !in MIN_MTU..MAX_MTU) return "MTU должен быть в диапазоне $MIN_MTU-$MAX_MTU"
        }
    }

    if (getBooleanPrefValue(OscPrefKey.PPP_MSS_DO_CLAMP, prefs)) {
        getIntPrefValue(OscPrefKey.PPP_MSS_VALUE, prefs).also {
            if (it !in 100..1460) return "MSS должен быть в диапазоне 100-1460"
        }
    }

    val isIPv4Enabled = getBooleanPrefValue(OscPrefKey.PPP_IPv4_ENABLED, prefs)
    val isIPv6Enabled = getBooleanPrefValue(OscPrefKey.PPP_IPv6_ENABLED, prefs)
    if (!isIPv4Enabled && !isIPv6Enabled) return "Не включён ни один сетевой протокол"

    val isStaticIPv4Requested = getBooleanPrefValue(OscPrefKey.PPP_DO_REQUEST_STATIC_IPv4_ADDRESS, prefs)
    if (isIPv4Enabled && isStaticIPv4Requested) {
        getStringPrefValue(OscPrefKey.PPP_STATIC_IPv4_ADDRESS, prefs).also {
            if (it.isEmpty()) return "Не указан статический IPv4-адрес"
        }
    }

    val authProtocols = getSetPrefValue(OscPrefKey.PPP_AUTH_PROTOCOLS, prefs)
    if (authProtocols.isEmpty()) return "Не выбран ни один протокол аутентификации"

    getIntPrefValue(OscPrefKey.PPP_AUTH_TIMEOUT, prefs).also {
        if (it < 1) return "Тайм-аут аутентификации PPP должен быть >= 1 секунды"
    }

    val isCustomDNSServerUsed = getBooleanPrefValue(OscPrefKey.DNS_DO_USE_CUSTOM_SERVER, prefs)
    val isCustomAddressEmpty = getStringPrefValue(OscPrefKey.DNS_CUSTOM_ADDRESS, prefs).isEmpty()
    if (isCustomDNSServerUsed && isCustomAddressEmpty) {
        return "Не указан адрес своего DNS-сервера"
    }

    getIntPrefValue(OscPrefKey.RECONNECTION_COUNT, prefs).also {
        if (it < 1) return "Количество попыток должно быть положительным числом"
    }

    val doSaveLog = getBooleanPrefValue(OscPrefKey.LOG_DO_SAVE_LOG, prefs)
    val logDir = getURIPrefValue(OscPrefKey.LOG_DIR, prefs)
    if (doSaveLog && logDir == null) return "Не выбрана папка для журнала"


    return null
}
