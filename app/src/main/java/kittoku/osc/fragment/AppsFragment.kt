package kittoku.osc.fragment

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kittoku.osc.R
import kittoku.osc.extension.removeTemporaryPreferences
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.TEMP_KEY_HEADER
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getSetPrefValue
import kittoku.osc.preference.accessor.setSetPrefValue
import kittoku.osc.preference.custom.RouteDoShowBackgroundAppsPreference
import kittoku.osc.preference.getInstalledAppInfos
import java.util.Locale


internal class AppsFragment : PreferenceFragmentCompat() {
    private lateinit var prefs: SharedPreferences
    private lateinit var pm: PackageManager
    private val shownCheckBoxes = mutableSetOf<CheckBoxPreference>()
    private var currentQuery = ""

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.apps, rootKey)
        setHasOptionsMenu(true)
        prefs = preferenceManager.sharedPreferences!!
        pm = requireContext().applicationContext.packageManager

        updateEachAppPreference(getBooleanPrefValue(OscPrefKey.ROUTE_DO_SHOW_BACKGROUND_APPS, prefs))
        attachSwitchListener()
    }

    private fun updateEachAppPreference(doShowBackgroundApps: Boolean) {
        shownCheckBoxes.removeAll {
            preferenceScreen.removePreference(it)
            true
        }

        val selected = getSetPrefValue(OscPrefKey.ROUTE_SELECTED_APPS, prefs)

        getInstalledAppInfos(doShowBackgroundApps, pm).forEach { info ->
            val checkBox = CheckBoxPreference(requireContext()).also {
                it.key = TEMP_KEY_HEADER + info.packageName
                it.icon = pm.getApplicationIcon(info)
                it.title = pm.getApplicationLabel(info)
                it.isChecked = selected.contains(info.packageName)
            }

            shownCheckBoxes.add(checkBox)
            preferenceScreen.addPreference(checkBox)
        }

        applySearchFilter(currentQuery)
    }

    // only hides/shows preferences already added to the screen - the underlying checked state
    // (and so the saved selection) is untouched by filtering, same as VPN Client Pro's picker
    private fun applySearchFilter(query: String) {
        currentQuery = query
        val needle = query.trim().lowercase(Locale.getDefault())

        shownCheckBoxes.forEach { checkBox ->
            val title = checkBox.title?.toString()?.lowercase(Locale.getDefault()) ?: ""
            val packageName = checkBox.key.substring(TEMP_KEY_HEADER.length).lowercase(Locale.getDefault())

            checkBox.isVisible = needle.isEmpty() || title.contains(needle) || packageName.contains(needle)
        }
    }

    private fun processCurrentPreferences(f: (CheckBoxPreference) -> Unit) {
        (0 until preferenceScreen.preferenceCount).forEach { i ->
            val pref = preferenceScreen.getPreference(i)
            if (pref is CheckBoxPreference) {
                f(pref)
            }
        }
    }

    private fun changeAllPreferencesStates(newState: Boolean) {
        processCurrentPreferences {
            // "select/unselect all" only acts on what the current search actually shows, mirroring
            // what the person can see on screen rather than silently touching hidden entries too
            if (it.isVisible) {
                it.isChecked = newState
            }
        }
    }

    private fun memorizeSelectedApps() {
        val selected = mutableSetOf<String>()

        // use Checkbox preferences to ensure that only currently-installed apps are memorized
        processCurrentPreferences {
            if (it.isChecked) {
                selected.add(it.key.substring(TEMP_KEY_HEADER.length))
            }
        }

        setSetPrefValue(selected, OscPrefKey.ROUTE_SELECTED_APPS, prefs)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        // IMPORTANT: must use the inflater passed in here (AppCompat-aware, supplied by the
        // hosting AppCompatActivity), NOT a manually-constructed android.view.MenuInflater -
        // the latter doesn't understand the app: namespace (actionViewClass/showAsAction), so
        // the SearchView action view would never actually get created and the cast below would
        // crash on a null view.
        inflater.inflate(R.menu.apps_menu, menu)

        val searchItem = menu.findItem(R.id.search_apps)
        (searchItem.actionView as? SearchView)?.also {
            it.queryHint = "Поиск приложений"
            it.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    applySearchFilter(newText.orEmpty())
                    return true
                }
            })
        }

        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                applySearchFilter("")
                return true
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.select_all -> changeAllPreferencesStates(true)
            R.id.unselect_all -> changeAllPreferencesStates(false)
        }

        return true
    }

    private fun attachSwitchListener() {
        findPreference<RouteDoShowBackgroundAppsPreference>(OscPrefKey.ROUTE_DO_SHOW_BACKGROUND_APPS.name)!!.also {
            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newState ->
                updateEachAppPreference(newState as Boolean)

                true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        memorizeSelectedApps()
        prefs.removeTemporaryPreferences()
    }
}
