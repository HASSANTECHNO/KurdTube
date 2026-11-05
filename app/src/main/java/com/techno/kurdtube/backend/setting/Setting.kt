package com.techno.kurdtube.backend.setting

import com.techno.kurdtube.R
import com.techno.kurdtube.backend.locale.AppLanguage

enum class Setting(
    val title: Int,
    val description: Int,
    val category: Int,
    val type: SettingType
) {

    PLAY_SONG_AFTER_DOWNLOAD(
        R.string.setting_title_playsongafterdownload,
        R.string.setting_description_playsongafterdownload,
        R.string.setting_category_downloads,
        SettingType.Switch(true)
    ),

    AUTO_DOWNLOAD(
        R.string.setting_title_autodownload,
        R.string.setting_description_autodownload,
        R.string.setting_category_downloads,
        SettingType.Switch(false)
    ),

    DOWNLOAD_BUFFER(
        R.string.setting_title_downloadbuffer,
        R.string.setting_description_downloadbuffer,
        R.string.setting_category_downloads,
        SettingType.Slider(1000, 100..10000, 100)
    ),

    THEME(
        R.string.setting_title_theme,
        R.string.setting_description_theme,
        R.string.setting_category_theme,
        SettingType.Select(
            "Auto",
            arrayOf("Auto", "Dark", "Light"),
            intArrayOf(
                R.string.setting_theme_option_auto,
                R.string.setting_theme_option_dark,
                R.string.setting_theme_option_light
            )
        )
    ),

    RESET_BROWSER(
        R.string.setting_title_resetbrowser,
        R.string.setting_description_resetbrowser,
        R.string.setting_category_browser,
        SettingType.Switch(false)
    ),

    USE_PUBLIC_PROXY(
        R.string.setting_title_publicproxy,
        R.string.setting_description_publicproxy,
        R.string.setting_category_browser,
        SettingType.Switch(true)
    ),

    UPDATES(
        R.string.setting_title_updates,
        R.string.setting_description_updates,
        R.string.setting_category_app,
        SettingType.Switch(true)
    ),

    LANGUAGE(
        R.string.setting_title_language,
        R.string.setting_description_language,
        R.string.setting_category_app,
        SettingType.Select(
            AppLanguage.DEFAULT.code,
            AppLanguage.values().map { it.code }.toTypedArray(),
            AppLanguage.values().map { it.label }.toIntArray()
        )
    );

    companion object {
        fun getSettingValueFromSettingsMap(
            setting: Setting,
            settings: Map<String, String>
        ): String {
            return settings[setting.name] ?: setting.type.default.toString()
        }
    }

}

