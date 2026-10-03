package kittoku.osc.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat


private const val UNKNOWN_SSID = "<unknown ssid>"

internal fun hasLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun cleanSsid(raw: String?): String? {
    if (raw.isNullOrEmpty() || raw == UNKNOWN_SSID) return null

    return raw.removeSurrounding("\"")
}

private fun wifiManagerOf(context: Context): WifiManager? {
    return context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
}

// Android only reveals the SSID to apps holding the location permission (and, while the app is
// not in the foreground, the background-location permission); without it this returns null
@Suppress("DEPRECATION")
internal fun readCurrentSsid(context: Context): String? {
    val wifi = wifiManagerOf(context) ?: return null

    return try {
        cleanSsid(wifi.connectionInfo?.ssid)
    } catch (_: SecurityException) {
        null
    }
}

// networks to offer in the SSID pickers: already-selected ones, the current one, and whatever the
// system will tell us about nearby / remembered networks
@Suppress("DEPRECATION")
internal fun collectKnownSsids(context: Context, selected: Set<String>): List<String> {
    val result = sortedSetOf<String>()
    result.addAll(selected)
    readCurrentSsid(context)?.also { result.add(it) }

    val wifi = wifiManagerOf(context) ?: return result.toList()

    if (hasLocationPermission(context)) {
        try {
            wifi.scanResults?.forEach { scan ->
                cleanSsid(scan.SSID)?.also { result.add(it) }
            }
        } catch (_: SecurityException) { }

        try {
            wifi.configuredNetworks?.forEach { config ->
                cleanSsid(config.SSID)?.also { result.add(it) }
            }
        } catch (_: SecurityException) { }
    }

    return result.toList()
}
