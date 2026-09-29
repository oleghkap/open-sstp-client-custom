package kittoku.osc.activity

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.TextView
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


class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var binding: ActivityMainBinding

    private val preparationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(this, ACTION_VPN_CONNECT)
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == OscPrefKey.HOME_CONNECTOR.name || key == OscPrefKey.ACTIVE_PROFILE_NAME.name) {
            refreshList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "${getString(R.string.app_name)}: ${BuildConfig.VERSION_NAME}"

        binding = ActivityMainBinding.inflate(layoutInflater)
        binding.root.fitsSystemWindows = true
        setContentView(binding.root)

        prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)

        binding.fabAddProfile.setOnClickListener { openEditor(null) }
        binding.addProfileButton.setOnClickListener { openEditor(null) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
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

    private fun profileNames(): List<String> {
        return prefs.all.keys
            .filter { it.startsWith(PROFILE_KEY_HEADER) }
            .map { it.removePrefix(PROFILE_KEY_HEADER) }
            .sorted()
    }

    private fun refreshList() {
        val names = profileNames()
        binding.profileListContainer.removeAllViews()

        if (names.isEmpty()) {
            binding.emptyState.visibility = android.view.View.VISIBLE
            binding.fabAddProfile.visibility = android.view.View.GONE
        } else {
            binding.emptyState.visibility = android.view.View.GONE
            binding.fabAddProfile.visibility = android.view.View.VISIBLE

            val activeName = getStringPrefValue(OscPrefKey.ACTIVE_PROFILE_NAME, prefs)
            val isConnected = getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)

            val inflater = LayoutInflater.from(this)
            names.forEach { name ->
                val row = inflater.inflate(R.layout.item_profile, binding.profileListContainer, false)

                row.findViewById<TextView>(R.id.profileName).text = name

                val switch = row.findViewById<SwitchMaterial>(R.id.profileSwitch)
                switch.setOnCheckedChangeListener(null)
                switch.isChecked = (activeName == name && isConnected)
                switch.setOnCheckedChangeListener { _, isChecked ->
                    onToggleProfile(name, isChecked, switch)
                }

                row.findViewById<ImageButton>(R.id.profileDelete).setOnClickListener {
                    confirmDelete(name)
                }

                row.setOnClickListener { openEditor(name) }

                binding.profileListContainer.addView(row)
            }
        }
    }

    private fun openEditor(name: String?) {
        val intent = Intent(this, ProfileEditActivity::class.java)
        if (name != null) {
            intent.putExtra(EXTRA_PROFILE_NAME, name)
        }
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
            importProfile(json?.let { deserializeProfile(it) }, prefs)
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

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this).also {
            it.setMessage("Delete profile \"$name\"?")

            it.setPositiveButton("DELETE") { _, _ ->
                val activeName = getStringPrefValue(OscPrefKey.ACTIVE_PROFILE_NAME, prefs)
                if (activeName == name && getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)) {
                    startVpnService(this, ACTION_VPN_DISCONNECT)
                }

                prefs.edit().remove(PROFILE_KEY_HEADER + name).apply()
                refreshList()
            }

            it.setNegativeButton("CANCEL") { _, _ -> }

            it.show()
        }
    }
}
