package kittoku.osc.fragment

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kittoku.osc.R
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.checkPreferences
import kittoku.osc.preference.custom.HomeConnectorPreference
import kittoku.osc.preference.toastInvalidSetting
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.SstpVpnService


class HomeFragment : PreferenceFragmentCompat() {
    private val preparationLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(ACTION_VPN_CONNECT)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.home, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        attachConnectorListener()
    }

    private fun startVpnService(action: String) {
        val intent = Intent(requireContext(), SstpVpnService::class.java).setAction(action)

        if (action == ACTION_VPN_CONNECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }

    private fun attachConnectorListener() {
        findPreference<HomeConnectorPreference>(OscPrefKey.HOME_CONNECTOR.name)!!.also {
            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newState ->
                if (newState == true) {
                    val prefs = preferenceManager.sharedPreferences!!
                    // The service downloads the remote profile before reading settings.
                    // Checking here would reject a hostname that the file is about to fill in.
                    if (!getBooleanPrefValue(OscPrefKey.REMOTE_CONFIG_ENABLED, prefs)) {
                        checkPreferences(prefs, requireContext())?.also { message ->
                            toastInvalidSetting(message, requireContext())
                            return@OnPreferenceChangeListener false
                        }
                    }

                    VpnService.prepare(requireContext())?.also { intent ->
                        preparationLauncher.launch(intent)
                    } ?: startVpnService(ACTION_VPN_CONNECT)
                } else {
                    startVpnService(ACTION_VPN_DISCONNECT)
                }

                true
            }
        }
    }
}
