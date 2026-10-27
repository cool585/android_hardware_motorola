/*
 * SPDX-FileCopyrightText: 2015-2016 The CyanogenMod Project
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device

import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.android.settingslib.widget.SettingsBasePreferenceFragment

class ActionsPreferenceFragment : SettingsBasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.actions_panel, rootKey)

        if (!resources.getBoolean(R.bool.config_device_support_assistant_key)) {
            findPreference<PreferenceCategory>(KEY_ASSISTANT_CATEGORY)?.let {
                preferenceScreen.removePreference(it)
            }
        } else {
            findPreference<ListPreference>(KEY_ASSISTANT_BUTTON)?.let { pref ->
                val currentAction = Settings.System.getInt(
                    requireContext().contentResolver,
                    KEY_ASSISTANT_BUTTON,
                    DEFAULT_ACTION,
                )
                pref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                pref.value = currentAction.toString()
                pref.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, newValue ->
                        val action = (newValue as? String)?.toIntOrNull() ?: DEFAULT_ACTION
                        Settings.System.putInt(
                            requireContext().contentResolver,
                            KEY_ASSISTANT_BUTTON,
                            action,
                        )
                        true
                    }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            activity?.onBackPressedDispatcher?.onBackPressed()
            true
        } else {
            false
        }
    }

    companion object {
        private const val KEY_ASSISTANT_CATEGORY = "assistant_button_category"
        private const val KEY_ASSISTANT_BUTTON = "assistant_button"
        private const val DEFAULT_ACTION = 106 // ACTION_VOICE_ASSISTANT
    }
}
