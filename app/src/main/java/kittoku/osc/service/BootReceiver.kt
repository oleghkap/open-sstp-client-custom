package kittoku.osc.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.setBooleanPrefValue


// After the phone boots: restart the network watcher for the auto-connect rules, and (if
// "Автоподключение при загрузке" is on) connect the profile that was last active. Android cannot
// show the VPN consent dialog from here, so connecting only works once the VPN was approved before.
internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        // nothing is running right after boot, whatever was left over from before the shutdown
        setBooleanPrefValue(false, OscPrefKey.ROOT_STATE, prefs)
        setBooleanPrefValue(false, OscPrefKey.HOME_CONNECTOR, prefs)

        syncAutoConnectService(context, fromUi = false)

        if (getBooleanPrefValue(OscPrefKey.AUTO_CONNECT_ENABLED, prefs)) {
            connectLastProfile(context, prefs)
        }
    }
}
