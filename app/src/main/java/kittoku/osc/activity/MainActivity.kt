package kittoku.osc.activity

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import kittoku.osc.BuildConfig
import kittoku.osc.R
import kittoku.osc.databinding.ActivityMainBinding
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.PROFILE_KEY_HEADER
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.preference.checkPreferences
import kittoku.osc.preference.deserializeProfile
import kittoku.osc.preference.importProfile
import kittoku.osc.preference.toastInvalidSetting
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.startVpnService
import kittoku.osc.service.syncAutoConnectService

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var binding: ActivityMainBinding

    private val preparationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(this, ACTION_VPN_CONNECT)
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (
            key == OscPrefKey.HOME_CONNECTOR.name ||
            key == OscPrefKey.ACTIVE_PROFILE_NAME.name ||
            key == OscPrefKey.HOME_STATUS.name
        ) {
            refreshList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name) + ": " + BuildConfig.VERSION_NAME

        binding = ActivityMainBinding.inflate(layoutInflater)
        binding.root.fitsSystemWindows = true
        setContentView(binding.root)

        prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)

        binding.fabAddProfile.setOnClickListener { openEditor(null) }
        binding.addProfileButton.setOnClickListener { openEditor(null) }

        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // make sure the auto-connect network watcher is running whenever its rules are on
        syncAutoConnectService(this, fromUi = true)
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        refreshList()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    private fun profileNames(): List<String> =
        prefs.all.keys
            .filter { it.startsWith(PROFILE_KEY_HEADER) }
            .map { it.removePrefix(PROFILE_KEY_HEADER) }
            .sorted()

    private fun refreshList() {
        val names = profileNames()
        binding.profileListContainer.removeAllViews()

        if (names.isEmpty()) {
            binding.emptyState.visibility = android.view.View.VISIBLE
            binding.fabAddProfile.visibility = android.view.View.GONE
            return
        }

        binding.emptyState.visibility = android.view.View.GONE
        binding.fabAddProfile.visibility = android.view.View.VISIBLE

        val activeName = getStringPrefValue(OscPrefKey.ACTIVE_PROFILE_NAME, prefs)
        val isConnected = getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)
        val statusText = getStringPrefValue(OscPrefKey.HOME_STATUS, prefs)
        val inflater = LayoutInflater.from(this)

        names.forEach { name ->
            val row = inflater.inflate(R.layout.item_profile, binding.profileListContainer, false)
            row.findViewById<TextView>(R.id.profileName).text = name

            val statusView = row.findViewById<TextView>(R.id.profileStatus)
            val isThisRowActive = activeName == name && isConnected
            if (isThisRowActive && statusText.isNotEmpty()) {
                statusView.text = statusText
                statusView.visibility = android.view.View.VISIBLE
            } else {
                statusView.visibility = android.view.View.GONE
            }

            val switch = row.findViewById<SwitchMaterial>(R.id.profileSwitch)
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = isThisRowActive
            switch.setOnCheckedChangeListener { _, isChecked ->
                onToggleProfile(name, isChecked, switch)
            }

            // single tap on the name opens the editor; the status text underneath is not
            // clickable, and a long press anywhere on the row brings up rename/edit/delete
            row.findViewById<TextView>(R.id.profileName).setOnClickListener { openEditor(name) }
            row.setOnLongClickListener { showProfileActionsDialog(name); true }

            binding.profileListContainer.addView(row)
        }
    }

    private fun showProfileActionsDialog(name: String) {
        val options = arrayOf("Переименовать", "Редактировать", "Удалить")

        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(name)
                    1 -> openEditor(name)
                    2 -> confirmDelete(name)
                }
            }
            .show()
    }

    private fun openEditor(name: String?) {
        val intent = Intent(this, ProfileEditActivity::class.java)
        if (name != null) intent.putExtra(EXTRA_PROFILE_NAME, name)
        startActivity(intent)
    }

    private fun onToggleProfile(name: String, isChecked: Boolean, switch: SwitchMaterial) {
        if (isChecked) {
            val activeName = getStringPrefValue(OscPrefKey.ACTIVE_PROFILE_NAME, prefs)
            val isConnected = getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)

            if (isConnected && activeName != name) {
                startVpnService(this, ACTION_VPN_DISCONNECT)
            }

            val json = prefs.getString(PROFILE_KEY_HEADER + name, null)
            val profile = json?.let { deserializeProfile(it) }
            if (profile == null) {
                Toast.makeText(this, "Профиль повреждён", Toast.LENGTH_SHORT).show()
                switch.isChecked = false
                return
            }

            importProfile(profile, prefs)
            setStringPrefValue(name, OscPrefKey.ACTIVE_PROFILE_NAME, prefs)

            checkPreferences(prefs)?.also { message ->
                toastInvalidSetting(message, this)
                switch.isChecked = false
                return
            }

            VpnService.prepare(this)?.also { intent ->
                preparationLauncher.launch(intent)
            } ?: startVpnService(this, ACTION_VPN_CONNECT)
        } else {
            val activeName = getStringPrefValue(OscPrefKey.ACTIVE_PROFILE_NAME, prefs)
            if (activeName == name) {
                startVpnService(this, ACTION_VPN_DISCONNECT)
            }
        }
    }

    private fun showRenameDialog(oldName: String) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(oldName)
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle("Переименовать профиль")
            .setView(input)
            .setPositiveButton("СОХРАНИТЬ") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isBlank()) {
                    Toast.makeText(this, "ИМЯ НЕ МОЖЕТ БЫТЬ ПУСТЫМ", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newName == oldName) return@setPositiveButton

                val oldKey = PROFILE_KEY_HEADER + oldName
                val newKey = PROFILE_KEY_HEADER + newName
                if (prefs.contains(newKey)) {
                    Toast.makeText(
                        this,
                        "ПРОФИЛЬ С ТАКИМ ИМЕНЕМ УЖЕ СУЩЕСТВУЕТ",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }

                val json = prefs.getString(oldKey, null) ?: return@setPositiveButton
                prefs.edit().remove(oldKey).putString(newKey, json).apply()

                if (getStringPrefValue(OscPrefKey.ACTIVE_PROFILE_NAME, prefs) == oldName) {
                    setStringPrefValue(newName, OscPrefKey.ACTIVE_PROFILE_NAME, prefs)
                }

                refreshList()
            }
            .setNegativeButton("ОТМЕНА", null)
            .show()
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setMessage("Удалить профиль «$name»?")
            .setPositiveButton("УДАЛИТЬ") { _, _ ->
                val activeName = getStringPrefValue(OscPrefKey.ACTIVE_PROFILE_NAME, prefs)
                if (activeName == name && getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)) {
                    startVpnService(this, ACTION_VPN_DISCONNECT)
                }

                prefs.edit().remove(PROFILE_KEY_HEADER + name).apply()
                if (activeName == name) {
                    setStringPrefValue("", OscPrefKey.ACTIVE_PROFILE_NAME, prefs)
                }
                refreshList()
            }
            .setNegativeButton("ОТМЕНА", null)
            .show()
    }
}
