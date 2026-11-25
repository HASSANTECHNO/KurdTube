package com.techno.kurdtube.backend.setting

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateMap
import com.techno.kurdtube.backend.utils.async

class SettingManager(private val context: Context) {

    private var currentPreferencesStateMap: SnapshotStateMap<String, String>

    init {
        migrateChangedDefaults()
        currentPreferencesStateMap = loadPreferences()
    }

    private fun getSharedPreferences(): SharedPreferences {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    /**
     * Version 1.3 turns off two switches that used to default to on. A stored
     * "true" cannot tell an explicit user choice from the old default, so it is
     * migrated once; changes made after this migration are kept.
     */
    private fun migrateChangedDefaults() {
        val migrations = context.getSharedPreferences(
            MIGRATIONS_PREFERENCES,
            Context.MODE_PRIVATE
        )
        if (migrations.getInt(KEY_DEFAULTS_VERSION, 0) >= DEFAULTS_VERSION) return

        val settings = getSharedPreferences()
        val editor = settings.edit()

        listOf(
            Setting.PLAY_SONG_AFTER_DOWNLOAD,
            Setting.KURDISH_ONLY_SEARCH
        ).forEach { setting ->
            if (settings.getString(setting.name, null) == "true") {
                editor.putString(setting.name, "false")
            }
        }

        editor.apply()
        migrations.edit()
            .putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION)
            .apply()
    }

    private fun loadPreferences(): SnapshotStateMap<String, String> {
        //async {
        return getSharedPreferences().all.entries
            .map { it.key to it.value.toString() }.toMutableStateMap()
        //}
    }

    fun getSettings(): Map<String, String> {
        return currentPreferencesStateMap
    }

    fun getSetting(setting: Setting): String {
        return Setting.getSettingValueFromSettingsMap(setting, currentPreferencesStateMap)
    }

    fun setSetting(key: String, value: String) {
        async {
            val sharedPreferences = getSharedPreferences()
            val editor = sharedPreferences.edit()
            editor.putString(key, value)
            val success = editor.commit()
            if (success) currentPreferencesStateMap[key] = value
        }
    }

    private companion object {
        const val MIGRATIONS_PREFERENCES = "migrations"
        const val KEY_DEFAULTS_VERSION = "defaults_version"
        const val DEFAULTS_VERSION = 130
    }

}
