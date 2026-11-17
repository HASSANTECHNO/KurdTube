package com.techno.kurdtube.backend.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.techno.kurdtube.BuildConfig
import com.techno.kurdtube.backend.locale.LocaleManager
import com.techno.kurdtube.backend.utils.LOG

/**
 * Thin wrapper around Firebase Analytics.
 *
 * Only anonymous, aggregate usage is recorded. Song titles, search queries,
 * file names/paths and any other user content are never sent.
 *
 * If app/google-services.json is missing or invalid, [analytics] stays null and
 * every call below silently does nothing: the app keeps working, just without
 * usage statistics.
 */
class AnalyticsManager(context: Context) {

    private val appContext = context.applicationContext

    private val analytics: FirebaseAnalytics? =
        runCatching { FirebaseAnalytics.getInstance(appContext) }.getOrNull()

    init {
        if (analytics == null) {
            LOG("Firebase Analytics disabled (google-services.json missing or invalid)")
        }
    }

    /** Custom startup event; Firebase also records first_open/session_start itself. */
    fun logAppStart() {
        val params = Bundle().apply {
            putString("app_version", BuildConfig.VERSION_NAME)
            putString("app_language", LocaleManager.getLanguageCode(appContext))
        }
        analytics?.logEvent("app_start", params)
    }

    /** Screen names are the stable enum names: SEARCH, LIBRARY, PLAYER, SETTINGS. */
    fun logScreenView(screenName: String) {
        val params = Bundle().apply {
            putString("screen_name", screenName)
            putString("screen_class", "MainActivity")
        }
        analytics?.logEvent("screen_view", params)
    }

    fun logSongPlay(shuffle: Boolean, queueSize: Int) {
        val params = Bundle().apply {
            putBoolean("shuffle", shuffle)
            putInt("queue_size", queueSize)
        }
        analytics?.logEvent("song_play", params)
    }

    fun logDownloadStarted() {
        analytics?.logEvent("download_started", null)
    }

    fun logDownloadCompleted() {
        analytics?.logEvent("download_completed", null)
    }

    fun logPlaylistCreated() {
        analytics?.logEvent("playlist_created", null)
    }

    fun logLanguageChanged(languageCode: String) {
        val params = Bundle().apply {
            putString("language", languageCode)
        }
        analytics?.logEvent("language_changed", params)
    }

}
