package com.techno.kurdtube.backend.locale

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Stores the selected app language and applies it to a [Context].
 *
 * The language is kept in its own preference file (instead of the normal settings)
 * so that it can be read in [android.app.Activity.attachBaseContext] before any
 * other component of the app is created.
 */
object LocaleManager {

    private const val PREFERENCES_NAME = "locale"
    private const val KEY_LANGUAGE = "language"

    fun getLanguageCode(context: Context): String {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return preferences.getString(KEY_LANGUAGE, null) ?: AppLanguage.DEFAULT.code
    }

    fun getLanguage(context: Context): AppLanguage {
        return AppLanguage.fromCode(getLanguageCode(context))
    }

    /** Saves the language synchronously so it is applied by the next activity creation. */
    fun setLanguage(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language.code)
            .commit()
    }

    fun setLanguage(context: Context, languageCode: String) {
        setLanguage(context, AppLanguage.fromCode(languageCode))
    }

    /** Returns a context whose resources are resolved with the saved language. */
    fun wrap(context: Context): Context {
        return wrap(context, getLanguage(context))
    }

    fun wrap(context: Context, language: AppLanguage): Context {
        val locale = Locale.forLanguageTag(language.code)

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        //makes the layout direction (RTL for Kurdish/Persian) follow the language
        configuration.setLayoutDirection(locale)

        return context.createConfigurationContext(configuration)
    }

}
