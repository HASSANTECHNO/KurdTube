package com.techno.kurdtube.backend.theme

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.techno.kurdtube.backend.locale.LocaleManager

/**
 * Applies the theme selected in the settings to the resources of a [Context].
 *
 * The splash screen is resolved from the activity theme before Compose starts,
 * so it cannot use [com.techno.kurdtube.frontend.theme.MainTheme]. Wrapping the
 * application/activity context with the saved night mode makes resource
 * qualifiers such as values-night select the correct splash resources.
 */
object ThemeManager {

    private const val PREFERENCES_NAME = "settings"
    private const val KEY_THEME = "THEME"

    fun getThemeValue(context: Context): String {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, "Auto") ?: "Auto"
    }

    fun isDark(context: Context): Boolean {
        return when (getThemeValue(context)) {
            "Dark" -> true
            "Light" -> false
            else -> (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    /**
     * Stores the app night mode used by the system splash on Android 12+.
     *
     * This must run when the user changes the theme, not only on app start:
     * the splash for the next cold start is created from the stored value.
     */
    fun applyNightMode(context: Context, themeValue: String = getThemeValue(context)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val uiModeManager = context.getSystemService(UiModeManager::class.java)
            uiModeManager.setApplicationNightMode(
                when (themeValue) {
                    "Dark" -> UiModeManager.MODE_NIGHT_YES
                    "Light" -> UiModeManager.MODE_NIGHT_NO
                    else -> UiModeManager.MODE_NIGHT_AUTO
                }
            )
        }
    }

    /** Returns a context whose resources use the saved night mode and language. */
    fun wrap(context: Context): Context {
        val nightMode = when (getThemeValue(context)) {
            "Dark" -> Configuration.UI_MODE_NIGHT_YES
            "Light" -> Configuration.UI_MODE_NIGHT_NO
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        }

        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode

        return LocaleManager.wrap(context.createConfigurationContext(configuration))
    }
}
