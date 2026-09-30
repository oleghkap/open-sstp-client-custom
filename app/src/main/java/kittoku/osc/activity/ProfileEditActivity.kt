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
import kittoku.osc.service.startVpnService
import java.io.BufferedInputStream
import java.io.BufferedOutputStream

internal const val EXTRA_PROFILE_NAME = "kittoku.osc.PROFILE_NAME"

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

    private var originalName: String? = null
    private var entryActiveName: String = ""
    private var entryProfileSnapshot: String = ""
    private var wasConnectedOnEntry = false
    private var wasActiveOnEntry = false
    private var isDirty = false
    private var isRestoring = false
    private var saveCompleted = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!isRestoring && key != null && key !in NON_DIRTYING_KEYS) {
            setDirty(true)
        }
    }

    private val preparationLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(this, ACTION_VPN_CONNECT)
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult

        val profile = contentResolver.openInputStream(uri)?.use { stream ->
            BufferedInputStream(stream).use { buffered ->
                deserializeProfile(buffered.reader(Charsets.UTF_8).readText())
            }
        }

        if (profile == null) {
            Toast.makeText(this, "ОШИБКА ИМПОРТА", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        importProfile(profile, prefs)
        updatePreferenceView()
        setDirty(true)
        Toast.makeText(this, "ПРОФИЛЬ ИМПОРТИРОВАН — СОХРАНИТЕ ЕГО", Toast.LENGTH_SHORT).show()
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri ?: return@registerForActivityResult

        contentResolver.openOutputStream(uri)?.use { stream ->
            BufferedOutputStream(stream).use { buffered ->
                buffered.write(serializeProfile(prefs).toByteArray(Charsets.UTF_8))
            }
        }

        Toast.makeText(this, "ПРОФИЛЬ ЭКСПОРТИРОВАН", Toast.LENGTH_SHORT).show()
    }

    private fun setDirty(value: Boolean) {
        if (isDirty == value) return
        isDirty = value
        invalidateOptionsMenu()
    }

    private fun updatePreferenceView() {
        listOf(homeFragment, settingFragment).forEach { fragment ->
            if (!fragment.isAdded) return@forEach

            val preferenceGroups = mutableListOf<PreferenceGroup>(fragment.preferenceScreen)
            while (preferenceGroups.isNotEmpty()) {
                preferenceGroups.removeAt(0).forEach {
                    if (it is OscPreference) it.updateView()
                    if (it is PreferenceGroup) preferenceGroups.add(it)
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

        entryActiveName = getStringPrefValue(OscPrefKey.ACTIVE_PROFILE_NAME, prefs)
        wasConnectedOnEntry = getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)
        wasActiveOnEntry =
            originalName != null && originalName == entryActiveName && wasConnectedOnEntry
        entryProfileSnapshot = serializeProfile(prefs)

        val isEditingActiveProfile = originalName != null && originalName == entryActiveName

        if (wasConnectedOnEntry && !isEditingActiveProfile) {
            startVpnService(this, kittoku.osc.service.ACTION_VPN_DISCONNECT)
        }

        if (!isEditingActiveProfile) {
            val storedJson = originalName?.let {
                prefs.getString(PROFILE_KEY_HEADER + it, null)
            }
            importProfile(storedJson?.let { deserializeProfile(it) }, prefs)
        }

        title = originalName ?: "Новый профиль"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        homeFragment = HomeFragment()
        settingFragment = SettingFragment()

        binding.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2

            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> homeFragment
                1 -> settingFragment
                else -> error("Invalid profile editor page: $position")
            }
        }

        TabLayoutMediator(binding.tabBar, binding.pager) { tab, position ->
            tab.text = when (position) {
                0 -> "ОСНОВНОЕ"
                1 -> "НАСТРОЙКИ"
                else -> error("Invalid profile editor tab: $position")
            }
        }.attach()

        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_edit_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.save_profile)?.isVisible = isDirty
        menu.findItem(R.id.import_profile)?.isVisible = true
        menu.findItem(R.id.export_profile)?.isVisible = true
        menu.findItem(R.id.reload_defaults)?.isVisible = true
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            handleDiscardAndFinish()
            true
        }
        R.id.save_profile -> {
            if (isDirty) performSave()
            true
        }
        R.id.import_profile -> {
            importLauncher.launch(arrayOf("application/json"))
            true
        }
        R.id.export_profile -> {
            showExportDialog()
            true
        }
        R.id.reload_defaults -> {
            showReloadDialog()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun performSave() {
        val inflated = layoutInflater.inflate(dialogResource, null)
        val editText = inflated.firstEditText()
        val hostname = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)

        editText.inputType = InputType.TYPE_CLASS_TEXT
        editText.setText(originalName ?: "")
        editText.hint = hostname
        editText.requestFocus()

        AlertDialog.Builder(this)
            .setView(inflated)
            .setMessage(
                sum(
                    "Введите название профиля.\n",
                    "Если оставить пустым, будет использован адрес сервера.\n",
                    "Если такой профиль уже есть, он будет перезаписан.",
                ),
            )
            .setPositiveButton("СОХРАНИТЬ") { _, _ ->
                val finalName = editText.text.toString().ifBlank { hostname }.trim()
                if (finalName.isBlank()) {
                    Toast.makeText(
                        this,
                        "УКАЖИТЕ ИМЯ ПРОФИЛЯ ИЛИ АДРЕС СЕРВЕРА",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }

                val previousName = originalName
                if (previousName != null && previousName != finalName) {
                    prefs.edit().remove(PROFILE_KEY_HEADER + previousName).apply()
                }

                prefs.edit()
                    .putString(PROFILE_KEY_HEADER + finalName, serializeProfile(prefs))
                    .apply()

                if (entryActiveName == previousName && wasConnectedOnEntry) {
                    setStringPrefValue(finalName, OscPrefKey.ACTIVE_PROFILE_NAME, prefs)
                }

                originalName = finalName
                setDirty(false)
                saveCompleted = true

                Toast.makeText(this, "ПРОФИЛЬ СОХРАНЁН", Toast.LENGTH_SHORT).show()

                if (wasActiveOnEntry) {
                    checkPreferences(prefs)?.also { message ->
                        toastInvalidSetting(message, this)
                    } ?: run {
                        VpnService.prepare(this)?.also { intent ->
                            preparationLauncher.launch(intent)
                        } ?: startVpnService(this, ACTION_VPN_CONNECT)
                    }
                } else if (wasConnectedOnEntry) {
                    restoreEntryState()
                }

                setResult(RESULT_OK)
                finish()
            }
            .setNegativeButton("ОТМЕНА", null)
            .show()
    }

    private fun showExportDialog() {
        val filename = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)
            .ifBlank { "profile" } + ".json"

        AlertDialog.Builder(this)
            .setMessage(
                "Пароль также будет экспортирован открытым текстом. " +
                    "Если это нежелательно, очистите поле пароля перед экспортом.",
            )
            .setPositiveButton("ПРОДОЛЖИТЬ") {
                    _, _ -> exportLauncher.launch(filename)
            }
            .setNegativeButton("ОТМЕНА", null)
            .show()
    }

    private fun showReloadDialog() {
        AlertDialog.Builder(this)
            .setMessage("Сбросить настройки текущего профиля по умолчанию?")
            .setPositiveButton("ДА") { _, _ ->
                importProfile(null, prefs)
                updatePreferenceView()
                setDirty(true)
                Toast.makeText(
                    this,
                    "НАСТРОЙКИ СБРОШЕНЫ — СОХРАНИТЕ ПРОФИЛЬ",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton("НЕТ", null)
            .show()
    }

    private fun restoreEntryState() {
        isRestoring = true
        try {
            importProfile(deserializeProfile(entryProfileSnapshot), prefs)
            setStringPrefValue(entryActiveName, OscPrefKey.ACTIVE_PROFILE_NAME, prefs)
        } finally {
            isRestoring = false
        }

        setDirty(false)

        if (wasConnectedOnEntry) {
            checkPreferences(prefs)?.also { message ->
                toastInvalidSetting(message, this)
            } ?: run {
                VpnService.prepare(this)?.also { intent ->
                    preparationLauncher.launch(intent)
                } ?: startVpnService(this, ACTION_VPN_CONNECT)
            }
        }
    }

    private fun handleDiscardAndFinish() {
        if (saveCompleted) {
            finish()
            return
        }

        if (!isDirty) {
            if (wasConnectedOnEntry && !wasActiveOnEntry) {
                restoreEntryState()
            }
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setMessage("Отменить несохранённые изменения?")
            .setPositiveButton("НЕ СОХРАНЯТЬ") { _, _ ->
                restoreEntryState()
                finish()
            }
            .setNegativeButton("ПРОДОЛЖИТЬ РЕДАКТИРОВАНИЕ", null)
            .show()
    }

    override fun onBackPressed() {
        handleDiscardAndFinish()
    }
}
