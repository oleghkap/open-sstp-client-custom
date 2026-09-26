package kittoku.osc.fragment

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kittoku.osc.R
import kittoku.osc.activity.BLANK_ACTIVITY_TYPE_APPS
import kittoku.osc.activity.BlankActivity
import kittoku.osc.activity.EXTRA_KEY_TYPE
import kittoku.osc.activity.MainActivity
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.RemoteConfigResult
import kittoku.osc.preference.accessor.setURIPrefValue
import kittoku.osc.preference.custom.DirectoryPreference
import kittoku.osc.preference.custom.RouteSelectedAppsPreference
import kittoku.osc.preference.fetchRemoteConfigIfEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


internal class SettingFragment : PreferenceFragmentCompat() {
    private lateinit var prefs: SharedPreferences

    private lateinit var certDirPref: DirectoryPreference
    private lateinit var logDirPref: DirectoryPreference
    private lateinit var selectAppsPref: RouteSelectedAppsPreference

    private val certDirLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data?.also {
            requireContext().contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } else null

        setURIPrefValue(uri, OscPrefKey.SSL_CERT_DIR, prefs)

        certDirPref.updateView()
    }

    private val logDirLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data?.also {
            requireContext().contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } else null

        setURIPrefValue(uri, OscPrefKey.LOG_DIR, prefs)

        logDirPref.updateView()
    }

    private val selectAppsLauncher = registerForActivityResult(StartActivityForResult()) {
        selectAppsPref.updateView()
    }

    private val fetchScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var fetchJob: Job? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)
        prefs = preferenceManager.sharedPreferences!!

        certDirPref = findPreference(OscPrefKey.SSL_CERT_DIR.name)!!
        logDirPref = findPreference(OscPrefKey.LOG_DIR.name)!!
        selectAppsPref = findPreference(OscPrefKey.ROUTE_SELECTED_APPS.name)!!

        setCertDirListener()
        setLogDirListener()
        setSelectAppsListener()
        setRemoteFetchListener()
    }

    override fun onDestroy() {
        fetchJob?.cancel()
        fetchScope.cancel()
        super.onDestroy()
    }

    private fun setCertDirListener() {
        certDirPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).also {
                it.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                certDirLauncher.launch(it)
            }

            true
        }
    }

    private fun setLogDirListener() {
        logDirPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).also {
                it.flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                logDirLauncher.launch(it)
            }

            true
        }
    }

    private fun setRemoteFetchListener() {
        findPreference<Preference>("REMOTE_CONFIG_FETCH")!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                if (fetchJob?.isActive == true) {
                    return@OnPreferenceClickListener true
                }

                fetchJob = fetchScope.launch {
                    val result = fetchRemoteConfigIfEnabled(requireContext().applicationContext, prefs)
                    if (!isAdded) return@launch

                    (activity as? MainActivity)?.updatePreferenceView()
                    val message = when (result) {
                        RemoteConfigResult.Disabled -> return@launch
                        RemoteConfigResult.Applied -> getString(R.string.toast_remote_config_applied)
                        RemoteConfigResult.Unchanged -> getString(R.string.toast_remote_config_unchanged)
                        is RemoteConfigResult.Failed -> result.message
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }

                true
            }
    }

    private fun setSelectAppsListener() {
        selectAppsPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            Intent(requireContext(), BlankActivity::class.java).also {
                it.putExtra(EXTRA_KEY_TYPE, BLANK_ACTIVITY_TYPE_APPS)
                selectAppsLauncher.launch(it)
            }

            true
        }
    }
}
