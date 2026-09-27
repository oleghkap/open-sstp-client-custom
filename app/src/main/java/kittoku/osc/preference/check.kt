package kittoku.osc.preference

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.widget.Toast
import kittoku.osc.MAX_MRU
import kittoku.osc.MAX_MTU
import kittoku.osc.MIN_MRU
import kittoku.osc.MIN_MTU
import kittoku.osc.R
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.getSetPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.getURIPrefValue


internal fun toastInvalidSetting(message: String, context: Context) {
    Toast.makeText(context, context.getString(R.string.toast_invalid_setting, message), Toast.LENGTH_LONG).show()
}

internal fun checkPreferences(prefs: SharedPreferences, context: Context): String? {
    getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs).also {
        if (it.isEmpty()) return context.getString(R.string.error_hostname_missing)
    }

    getIntPrefValue(OscPrefKey.SSL_PORT, prefs).also {
        if (it !in 0..65535) return context.getString(R.string.error_port_range)
    }

    val doSpecifyCerts = getBooleanPrefValue(OscPrefKey.SSL_DO_SPECIFY_CERT, prefs)
    val version = getStringPrefValue(OscPrefKey.SSL_VERSION, prefs)
    val certDir = getURIPrefValue(OscPrefKey.SSL_CERT_DIR, prefs)
    if (doSpecifyCerts && version == "DEFAULT") return context.getString(R.string.error_certs_need_ssl_version)

    if (doSpecifyCerts && certDir == null) return context.getString(R.string.error_certs_dir_missing)

    val doSelectSuites = getBooleanPrefValue(OscPrefKey.SSL_DO_SELECT_SUITES, prefs)
    val suites = getSetPrefValue(OscPrefKey.SSL_SUITES, prefs)
    if (doSelectSuites && suites.isEmpty()) return context.getString(R.string.error_no_cipher_suite)

    val doUseCustomSNI = getBooleanPrefValue(OscPrefKey.SSL_DO_USE_CUSTOM_SNI, prefs)
    val isAPILevelLacked = Build.VERSION.SDK_INT < Build.VERSION_CODES.N
    val customSNIHostname = getStringPrefValue(OscPrefKey.SSL_CUSTOM_SNI, prefs)
    if (doUseCustomSNI && isAPILevelLacked) return context.getString(R.string.error_custom_sni_api)
    if (doUseCustomSNI && customSNIHostname.isEmpty()) return context.getString(R.string.error_custom_sni_blank)

    if (getBooleanPrefValue(OscPrefKey.PROXY_DO_USE_PROXY, prefs)) {
        getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs).also {
            if (it.isEmpty()) return context.getString(R.string.error_proxy_hostname_missing)
        }

        getIntPrefValue(OscPrefKey.PROXY_PORT, prefs).also {
            if (it !in 0..65535) return context.getString(R.string.error_proxy_port_range)
        }
    }

    getIntPrefValue(OscPrefKey.PPP_MRU, prefs).also {
        if (it !in MIN_MRU..MAX_MRU) return context.getString(R.string.error_mru_range, MIN_MRU, MAX_MRU)
    }

    getIntPrefValue(OscPrefKey.PPP_MTU, prefs).also {
        if (it !in MIN_MTU..MAX_MTU) return context.getString(R.string.error_mtu_range, MIN_MTU, MAX_MTU)
    }

    val isIPv4Enabled = getBooleanPrefValue(OscPrefKey.PPP_IPv4_ENABLED, prefs)
    val isIPv6Enabled = getBooleanPrefValue(OscPrefKey.PPP_IPv6_ENABLED, prefs)
    if (!isIPv4Enabled && !isIPv6Enabled) return context.getString(R.string.error_no_network_protocol)

    val isStaticIPv4Requested = getBooleanPrefValue(OscPrefKey.PPP_DO_REQUEST_STATIC_IPv4_ADDRESS, prefs)
    if (isIPv4Enabled && isStaticIPv4Requested) {
        getStringPrefValue(OscPrefKey.PPP_STATIC_IPv4_ADDRESS, prefs).also {
            if (it.isEmpty()) return context.getString(R.string.error_static_ipv4_missing)
        }
    }

    val authProtocols = getSetPrefValue(OscPrefKey.PPP_AUTH_PROTOCOLS, prefs)
    if (authProtocols.isEmpty()) return context.getString(R.string.error_no_auth_protocol)

    getIntPrefValue(OscPrefKey.PPP_AUTH_TIMEOUT, prefs).also {
        if (it < 1) return context.getString(R.string.error_auth_timeout)
    }

    val isCustomDNSServerUsed = getBooleanPrefValue(OscPrefKey.DNS_DO_USE_CUSTOM_SERVER, prefs)
    val isCustomAddressEmpty = getStringPrefValue(OscPrefKey.DNS_CUSTOM_ADDRESS, prefs).isEmpty()
    if (isCustomDNSServerUsed && isCustomAddressEmpty) {
        return context.getString(R.string.error_custom_dns_missing)
    }

    getIntPrefValue(OscPrefKey.RECONNECTION_COUNT, prefs).also {
        if (it < 1) return context.getString(R.string.error_retry_count)
    }

    val doSaveLog = getBooleanPrefValue(OscPrefKey.LOG_DO_SAVE_LOG, prefs)
    val logDir = getURIPrefValue(OscPrefKey.LOG_DIR, prefs)
    if (doSaveLog && logDir == null) return context.getString(R.string.error_log_dir_missing)


    return null
}
