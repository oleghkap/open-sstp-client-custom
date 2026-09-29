package kittoku.osc.activity

import android.app.Activity
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
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
import kittoku.osc.R
import kittoku.osc.databinding.ActivityProfileEditBinding
import kittoku.osc.extension.firstEditText
import kittoku.osc.extension.sum
import kittoku.osc.fragment.HomeFragment
import kittoku.osc.fragment.SettingFragment
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.PROFILE_KEY_HEADER
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.preference.checkPreferences
import kittoku.osc.preference.custom.OscPreference
import kittoku.osc.preference.deserializeProfile
import kittoku.osc.preference.importProfile
import kittoku.osc.preference.serializeProfile
import kittoku.osc.preference.toastInvalidSetting
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.startVpnService
import java.io.BufferedInputStream
import java.io.BufferedOutputStream


internal const val EXTRA_PROFILE_NAME = "kittoku.osc.PROFILE_NAME"

// preference keys that are bookkeeping, not user-visible settings; changing them must not
// trigger the "unsaved changes" state
private val NON_DIRTYING_KEYS = setOf(
    OscPrefKey.ROOT_STATE.name,
    OscPrefKey.HOME_CONNECTOR.name,
    OscPrefKey.HOME_STATUS.name,
    OscPrefKey.ACTIVE_PROFILE_NAME.name,
)


class ProfileEditActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences

    private lateinit var homeFragment: PreferenceFragmentCompat
    private lateinit var settingFragment: PreferenceFragmentCompat

    private val dialogResource: Int by lazy { EditTextPreference(this).dialogLayoutResource }

    // name of the profile being edited, null while creating a brand-new one
    private var originalName: String? = null

    // true if this exact profile was already connected/loaded when the screen was opened;
    // in that case we keep it running while editing and reconnect it on save
    private var wasActiveOnEntry = false

    private var isDirty = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key !in NON_DIRTYING_KEYS && !isDirty) {
            isDirty = true
        }
    }

    private val preparationLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(this, ACTION_VPN_CONNECT)
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.also {
            val profile = contentResolver.openInputStream(it)?.let { stream ->
                BufferedInputStream(stream).let { buffered ->
                    deserializeProfile(buffered.reader(Charsets.UTF_8).readText())
                }
            }

            if (profile == null) {
                Toast.makeText(this, "IMPORT FAILED", Toast.LENGTH_SHORT).show()
            } else {
                importProfile(profile, prefs)
                updatePreferenceView()
                isDirty = true
                Toast.makeText(this, "PROFILE IMPORTED", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.also {
            contentResolver.openOutputStream(it)?.also { stream ->
                BufferedOutputStream(stream).use { buffered ->
                    buffered.write(serializeProfile(prefs).toByteArray(Charsets.UTF_8))
                }
            }

            Toast.makeText(this, "PROFILE EXPORTED", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePreferenceView() {
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

        val binding = ActivityProfileEditBinding.inflate(layoutInflater)
        binding.root.fitsSystemWindows = true
        setContentView(binding.root)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        originalName = intent.getStringExtra(EXTRA_PROFILE_NAME)

        val currentActiveName = getStringPrefValue(OscPrefKey.ACTIVE_PROFILE_NAME, prefs)
        val isConnected = getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)
        val isEditingActiveProfile = originalName != null && originalName == currentActiveName

        wasActiveOnEntry = isEditingActiveProfile && isConnected

        // this app keeps a single shared set of "current" settings. Opening the profile that
        // is already loaded/connected is safe to view and edit in place - it already IS the
        // live settings. Switching to a DIFFERENT profile (or creating a new one) is about to
        // overwrite those live settings, so any existing connection must be dropped first.
        if (isConnected && !isEditingActiveProfile) {
            startVpnService(this, ACTION_VPN_DISCONNECT)
        }

        if (!isEditingActiveProfile) {
            val storedName = originalName
            if (storedName != null) {
                val json = prefs.getString(PROFILE_KEY_HEADER + storedName, null)
                importProfile(json?.let { deserializeProfile(it) }, prefs)
            } else {
                importProfile(null, prefs)
            }
        }

        title = originalName ?: "New Profile"

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

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
                0 -> "HOME"
                1 -> "SETTING"
                else -> throw NotImplementedError(position.toString())
            }
        }.attach()

        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        // must use the Activity's own (AppCompat-aware) inflater, not a raw platform
        // MenuInflater, or app:showAsAction/app:icon on menu items are silently ignored
        // and the save icon never renders in the toolbar
        menuInflater.inflate(R.menu.profile_edit_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }

            R.id.save_profile -> performSave()

            R.id.import_profile -> importLauncher.launch(arrayOf("application/json"))

            R.id.export_profile -> showExportDialog()

            R.id.reload_defaults -> showReloadDialog()
        }

        return true
    }

    private fun performSave() {
        val inflated = layoutInflater.inflate(dialogResource, null)
        val editText = inflated.firstEditText()

        val hostname = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)

        editText.inputType = InputType.TYPE_CLASS_TEXT
        editText.setText(originalName ?: "")
        editText.hint = hostname
        editText.requestFocus()

        AlertDialog.Builder(this).also {
            it.setView(inflated)
            it.setMessage(sum(
                "Enter the profile's name.\n",
                "If blank, the hostname will be used.\n",
                "If duplicated, the existing profile will be overwritten."
            ))

            it.setPositiveButton("SAVE") { _, _ ->
                val finalName = editText.text.toString().ifBlank { hostname }

                if (finalName.isBlank()) {
                    Toast.makeText(this, "PROFILE NEEDS A NAME OR HOSTNAME", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val previousName = originalName
                if (previousName != null && previousName != finalName) {
                    prefs.edit().remove(PROFILE_KEY_HEADER + previousName).apply()
                }

                prefs.edit().putString(PROFILE_KEY_HEADER + finalName, serializeProfile(prefs)).apply()
                setStringPrefValue(finalName, OscPrefKey.ACTIVE_PROFILE_NAME, prefs)
                originalName = finalName

                Toast.makeText(this, "PROFILE SAVED", Toast.LENGTH_SHORT).show()
                isDirty = false

                // this profile was already running (or the app is already connected using it):
                // reconnect immediately so the running tunnel picks up the new settings.
                // The service kills any existing controller (without stopping itself) before
                // reconnecting, so this is safe to call whether or not it was already connected.
                if (wasActiveOnEntry) {
                    checkPreferences(prefs)?.also { message ->
                        toastInvalidSetting(message, this)
                    } ?: run {
                        VpnService.prepare(this)?.also { intent ->
                            preparationLauncher.launch(intent)
                        } ?: startVpnService(this, ACTION_VPN_CONNECT)
                    }
                }

                setResult(RESULT_OK)
                finish()
            }

            it.setNegativeButton("CANCEL") { _, _ -> }

            it.show()
        }
    }

    private fun showExportDialog() {
        val filename = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs) + ".json"

        AlertDialog.Builder(this).also {
            it.setMessage(
                "Password will be also exported as plain text. If you don't want that, blank Password before exporting."
            )

            it.setPositiveButton("PROCEED") { _, _ ->
                exportLauncher.launch(filename)
            }

            it.setNegativeButton("CANCEL") { _, _ -> }

            it.show()
        }
    }

    private fun showReloadDialog() {
        AlertDialog.Builder(this).also {
            it.setMessage("Are you sure to reload the default settings?")

            it.setPositiveButton("YES") { _, _ ->
                importProfile(null, prefs)

                updatePreferenceView()

                isDirty = true

                Toast.makeText(this, "DEFAULTS RELOADED", Toast.LENGTH_SHORT).show()
            }

            it.setNegativeButton("NO") { _, _ -> }

            it.show()
        }
    }

    override fun onBackPressed() {
        if (isDirty) {
            AlertDialog.Builder(this).also {
                it.setMessage("Discard unsaved changes?")

                it.setPositiveButton("DISCARD") { _, _ -> super.onBackPressed() }

                it.setNegativeButton("KEEP EDITING") { _, _ -> }

                it.show()
            }
        } else {
            super.onBackPressed()
        }
    }
}
