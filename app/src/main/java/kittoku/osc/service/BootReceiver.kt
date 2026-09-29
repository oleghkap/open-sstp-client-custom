package kittoku.osc.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.preference.PreferenceManager
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.PROFILE_KEY_HEADER
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.checkPreferences
import kittoku.osc.preference.deserializeProfile
import kittoku.osc.preference.importProfile


// Auto-connects the profile that was last active, right after the device finishes booting.
// Requires "AUTO_CONNECT_ENABLED" to be on, a remembered active profile to exist, and (since
// Android cannot show the VPN consent dialog from a boot receiver) the person to have already
// granted this app VPN permission in an earlier session.
internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        if (!getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ENABLED, prefs)) return

        val activeName = getStringPrefValue(OscPrefKey.ACTIVE_PROFILE_NAME, prefs)
        if (activeName.isEmpty()) return

        val json = prefs.getString(PROFILE_KEY_HEADER + activeName, null) ?: return

        if (VpnService.prepare(context) != null) return

        importProfile(deserializeProfile(json), prefs)

        if (checkPreferences(prefs) != null) return

        startVpnService(context, ACTION_VPN_CONNECT)
    }
}
