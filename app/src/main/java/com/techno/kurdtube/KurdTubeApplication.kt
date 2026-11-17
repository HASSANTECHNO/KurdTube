package com.techno.kurdtube

import android.app.Application
import android.content.Context
import com.techno.kurdtube.backend.theme.ThemeManager

/**
 * Applies the saved theme before the first activity (and its splash screen) is
 * created. The splash window is resolved from resources, so the night-mode
 * qualifier must already match the in-app Dark/Light setting at this point.
 */
class KurdTubeApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(ThemeManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()

        // Android 12+ draws the system splash before MainActivity.onCreate().
        // Keep the stored app night mode in sync with the in-app setting.
        ThemeManager.applyNightMode(this)
    }
}
