package kittoku.osc.activity

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.forEach
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import kittoku.osc.BuildConfig
import kittoku.osc.R
import kittoku.osc.databinding.ActivityMainBinding
import kittoku.osc.extension.firstEditText
import kittoku.osc.fragment.HomeFragment
import kittoku.osc.fragment.SettingFragment
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.PROFILE_KEY_HEADER
import kittoku.osc.preference.RemoteConfigResult
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.applyRemoteConfigUrl
import kittoku.osc.preference.custom.OscPreference
import kittoku.osc.preference.deserializeProfile
import kittoku.osc.preference.downloadLinkedProfile
import kittoku.osc.preference.fetchRemoteConfigOnProcessStart
import kittoku.osc.preference.importProfile
import kittoku.osc.preference.parseProfileLink
import kittoku.osc.preference.serializeProfile
import kittoku.osc.preference.storeNamedProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.URI


class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences

    private lateinit var homeFragment: PreferenceFragmentCompat
    private lateinit var settingFragment: PreferenceFragmentCompat

    private val dialogResource: Int by lazy { EditTextPreference(this).dialogLayoutResource }
    private val linkScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val profileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            return@registerForActivityResult
        }

        updatePreferenceView()
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.also {
            val profile = contentResolver.openInputStream(it)?.let { stream ->
                BufferedInputStream(stream).let {
                    deserializeProfile(it.reader(Charsets.UTF_8).readText())
                }
            }

            if (profile == null) {
                Toast.makeText(this, R.string.toast_import_failed, Toast.LENGTH_SHORT).show()
            } else {
                importProfile(profile,prefs)
                updatePreferenceView()
                Toast.makeText(this, R.string.toast_profile_imported, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.also {
            contentResolver.openOutputStream(it)?.also { stream ->
                BufferedOutputStream(stream).use {
                    it.write(serializeProfile(prefs).toByteArray(Charsets.UTF_8))
                }
            }

            Toast.makeText(this, R.string.toast_profile_exported, Toast.LENGTH_SHORT).show()
        }
    }

    private val remoteStatusListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == OscPrefKey.REMOTE_CONFIG_STATUS.name && !isDestroyed) {
            updatePreferenceView()
        }
    }

    internal fun updatePreferenceView() {
        listOf(homeFragment, settingFragment).forEach { fragment ->
            if (fragment.isAdded) {
                val preferenceGroups = mutableListOf<PreferenceGroup>(fragment.preferenceScreen)

                while (preferenceGroups.isNotEmpty()) {
                    preferenceGroups.removeAt(0).forEach {
                        if (it is OscPreference) {
                            it.updateView()
                        }

                        if (it is PreferenceGroup) {
                            preferenceGroups.add(it)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "${getString(R.string.app_name)}: ${BuildConfig.VERSION_NAME}"
        val binding = ActivityMainBinding.inflate(layoutInflater)
        binding.root.fitsSystemWindows = true
        setContentView(binding.root)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        homeFragment = HomeFragment()
        settingFragment = SettingFragment()

        object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2

            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> homeFragment
                    1 -> settingFragment
                    else -> throw NotImplementedError(position.toString())
                }
            }
        }.also {
            binding.pager.adapter = it
        }


        TabLayoutMediator(binding.tabBar, binding.pager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_home)
                1 -> getString(R.string.tab_setting)
                else -> throw NotImplementedError(position.toString())
            }
        }.attach()


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        prefs.registerOnSharedPreferenceChangeListener(remoteStatusListener)
        val profileLink = intent?.dataString?.let { parseProfileLink(it) }
        if (profileLink == null) {
            if (intent?.dataString != null && intent?.data?.host == "profile") {
                Toast.makeText(this, R.string.error_remote_config_invalid_url, Toast.LENGTH_LONG).show()
            }
            fetchRemoteConfigOnProcessStart(applicationContext, prefs) {
                if (!isDestroyed) updatePreferenceView()
            }
        } else {
            showProfileLinkDialog(profileLink)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val profileLink = intent.dataString?.let { parseProfileLink(it) }
        if (profileLink == null) {
            if (intent.data?.host == "profile") {
                Toast.makeText(this, R.string.error_remote_config_invalid_url, Toast.LENGTH_LONG).show()
            }
            return
        }
        showProfileLinkDialog(profileLink)
    }

    override fun onDestroy() {
        linkScope.cancel()
        prefs.unregisterOnSharedPreferenceChangeListener(remoteStatusListener)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        MenuInflater(this).inflate(R.menu.home_menu, menu)

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.load_profile -> {
                profileLauncher.launch(Intent(this, BlankActivity::class.java).putExtra(
                    EXTRA_KEY_TYPE,
                    BLANK_ACTIVITY_TYPE_PROFILES
                ))
            }

            R.id.save_profile -> showSaveDialog()

            R.id.import_profile -> importLauncher.launch(arrayOf("application/json"))

            R.id.export_profile -> showExportDialog()

            R.id.reload_defaults -> showReloadDialog()
        }

        return true
    }

    private fun showProfileLinkDialog(url: String) {
        AlertDialog.Builder(this).also {
            it.setMessage(getString(R.string.dialog_profile_link, url))
            it.setPositiveButton(R.string.action_current_profile) { _, _ ->
                clearProfileLinkIntent()
                applyProfileLinkToCurrent(url)
            }
            it.setNeutralButton(R.string.action_new_profile) { _, _ ->
                clearProfileLinkIntent()
                showNewProfileLinkDialog(url)
            }
            it.setNegativeButton(R.string.action_cancel) { _, _ ->
                clearProfileLinkIntent()
                fetchRemoteConfigAfterLink()
            }
            it.setOnCancelListener {
                clearProfileLinkIntent()
                fetchRemoteConfigAfterLink()
            }
            it.show()
        }
    }

    private fun showNewProfileLinkDialog(url: String) {
        val inflated = layoutInflater.inflate(dialogResource, null)
        val editText = inflated.firstEditText()
        val host = URI(url).host ?: url

        editText.inputType = InputType.TYPE_CLASS_TEXT
        editText.hint = host
        editText.requestFocus()

        AlertDialog.Builder(this).also {
            it.setView(inflated)
            it.setMessage(getString(R.string.dialog_save_profile))
            it.setPositiveButton(R.string.action_save) { _, _ ->
                val name = editText.text.toString().ifEmpty { host }
                saveProfileLink(name, url)
            }
            it.setNegativeButton(R.string.action_cancel) { _, _ ->
                fetchRemoteConfigAfterLink()
            }
            it.setOnCancelListener { fetchRemoteConfigAfterLink() }
            it.show()
        }
    }

    private fun fetchRemoteConfigAfterLink() {
        fetchRemoteConfigOnProcessStart(applicationContext, prefs) {
            if (!isDestroyed) updatePreferenceView()
        }
    }

    private fun applyProfileLinkToCurrent(url: String) {
        linkScope.launch {
            val result = applyRemoteConfigUrl(applicationContext, prefs, url)
            if (isDestroyed) return@launch
            updatePreferenceView()
            val message = when (result) {
                RemoteConfigResult.Disabled -> return@launch
                RemoteConfigResult.Applied -> getString(R.string.toast_remote_config_applied)
                RemoteConfigResult.Unchanged -> getString(R.string.toast_remote_config_unchanged)
                is RemoteConfigResult.Failed -> result.message
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveProfileLink(name: String, url: String) {
        linkScope.launch {
            val linked = downloadLinkedProfile(applicationContext, url)
            storeNamedProfile(prefs, name, linked.profile)
            if (isDestroyed) return@launch
            val message = if (linked.errorMessage == null) {
                getString(R.string.toast_profile_saved)
            } else {
                getString(R.string.toast_profile_link_saved_offline)
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            fetchRemoteConfigOnProcessStart(applicationContext, prefs) {
                if (!isDestroyed) updatePreferenceView()
            }
        }
    }

    private fun clearProfileLinkIntent() {
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun showSaveDialog() {
        val inflated = layoutInflater.inflate(dialogResource, null)
        val editText = inflated.firstEditText()

        val hostname = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)

        editText.inputType = InputType.TYPE_CLASS_TEXT
        editText.hint = hostname
        editText.requestFocus()

        AlertDialog.Builder(this).also {
            it.setView(inflated)
            it.setMessage(getString(R.string.dialog_save_profile))

            it.setPositiveButton(R.string.action_save) { _, _ ->
                prefs.edit().also { editor ->
                    editor.putString(
                        PROFILE_KEY_HEADER + editText.text.ifEmpty { hostname },
                        serializeProfile(prefs)
                    )
                    editor.apply()
                }

                Toast.makeText(this, R.string.toast_profile_saved, Toast.LENGTH_SHORT).show()
            }

            it.setNegativeButton(R.string.action_cancel) { _, _ -> }

            it.show()
        }
    }

    private fun showExportDialog() {
        val filename = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs) + ".json"

        AlertDialog.Builder(this).also {
            it.setMessage(getString(R.string.dialog_export_warning))

            it.setPositiveButton(R.string.action_proceed) { _, _ ->
                exportLauncher.launch(filename)
            }

            it.setNegativeButton(R.string.action_cancel) { _, _ -> }

            it.show()
        }
    }

    private fun showReloadDialog() {
        AlertDialog.Builder(this).also {
            it.setMessage(getString(R.string.dialog_reload_confirm))

            it.setPositiveButton(R.string.action_yes) { _, _ ->
                importProfile(null, prefs)

                updatePreferenceView()

                Toast.makeText(this, R.string.toast_defaults_reloaded, Toast.LENGTH_SHORT).show()
            }

            it.setNegativeButton(R.string.action_no) { _, _ -> }

            it.show()
        }
    }
}
