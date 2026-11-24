package com.techno.kurdtube

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.techno.kurdtube.backend.analytics.AnalyticsManager
import com.techno.kurdtube.backend.download.YoutubeApi
import com.techno.kurdtube.backend.download.YoutubeDownloadInfo
import com.techno.kurdtube.backend.download.YoutubeDownloadManager
import com.techno.kurdtube.backend.library.LibraryManager
import com.techno.kurdtube.backend.locale.AppLanguage
import com.techno.kurdtube.backend.locale.LocaleManager
import com.techno.kurdtube.backend.permission.AppPermissionManager
import com.techno.kurdtube.backend.player.PlayerManager
import com.techno.kurdtube.backend.proxy.PublicProxyManager
import com.techno.kurdtube.backend.screen.Screen
import com.techno.kurdtube.backend.screen.ScreenManager
import com.techno.kurdtube.backend.setting.Setting
import com.techno.kurdtube.backend.setting.SettingManager
import com.techno.kurdtube.backend.update.UpdateManager
import com.techno.kurdtube.backend.utils.LOG
import com.techno.kurdtube.backend.utils.sync
import com.techno.kurdtube.frontend.components.currentBrowser

class MainViewModel(application: Application) : AndroidViewModel(application) {

    init {
        LOG("init MainViewModel")
    }

    private fun getAppContext(): Context {
        return getApplication<Application>().applicationContext
    }

    val screenManager = ScreenManager(getAppContext())

    val libraryManager = LibraryManager(getAppContext())

    val analyticsManager = AnalyticsManager(getAppContext())

    val playerManager = PlayerManager(analyticsManager)

    val settingManager = SettingManager(getAppContext())

    val permissionManager = AppPermissionManager(getAppContext())

    private val publicProxyManager = PublicProxyManager()

    private var searchReadyState by mutableStateOf(false)
    private var proxyLoadingState by mutableStateOf(false)

    init {
        if (settingManager.getSetting(Setting.USE_PUBLIC_PROXY).toBoolean()) {
            configurePublicProxy()
        } else {
            searchReadyState = true
        }
    }

    fun isSearchReady(): Boolean = searchReadyState

    fun isProxyLoading(): Boolean = proxyLoadingState

    fun configurePublicProxy() {
        proxyLoadingState = true
        searchReadyState = false

        publicProxyManager.configure { result ->
            proxyLoadingState = false
            searchReadyState = result.isSuccess
            result.exceptionOrNull()?.let { LOG("public proxy setup failed: ${it.message}") }
        }
    }

    fun setPublicProxyEnabled(enabled: Boolean) {
        if (enabled) {
            configurePublicProxy()
        } else {
            proxyLoadingState = true
            publicProxyManager.clear {
                proxyLoadingState = false
                searchReadyState = true
            }
        }
    }

    fun continueWithoutPublicProxy() {
        publicProxyManager.clear {
            proxyLoadingState = false
            searchReadyState = true
        }
    }

    // download info ==========================================

    private var downloadInfoState by mutableStateOf(null as YoutubeDownloadInfo?)

    private val autoDownloadUrls = mutableListOf<String>()

    /** Video shared from YouTube that the user can confirm for download. */
    var shareDownloadPrompt by mutableStateOf(null as YoutubeDownloadInfo?)
        private set

    private var pendingSharedVideoId: String? = null
    private var pendingDownload = false
    private var pendingDownloadInfo: YoutubeDownloadInfo? = null

    fun hasFoundDownloadInfo(): Boolean {
        return downloadInfoState != null
    }

    fun updateDownloadInfo(downloadInfo: YoutubeDownloadInfo?) {
        if (downloadInfoState == downloadInfo) return

        if (downloadInfo == null) {
            // Keep a shared-video prompt (and its info) stable while the browser
            // reloads; it is cleared when the user acts or another video resolves.
            if (shareDownloadPrompt != null) return

            LOG("url is null")
            downloadInfoState = null
            return
        }

        LOG("url is ${downloadInfo.downloadUrl}")
        downloadInfoState = downloadInfo

        val videoId = YoutubeApi.extractVideoId(downloadInfo.videoUrl.toString())
        val downloadKey = videoId ?: downloadInfo.videoUrl.toString()
        val isSharedVideo = pendingSharedVideoId != null && videoId == pendingSharedVideoId
        val autoDownload = settingManager.getSetting(Setting.AUTO_DOWNLOAD).toBoolean()

        if (shareDownloadPrompt != null && !isSharedVideo) {
            // the browser left the shared video while its confirmation dialog was open
            pendingSharedVideoId = null
            shareDownloadPrompt = null
        }

        if (isSharedVideo) {
            if (!autoDownload) {
                shareDownloadPrompt = downloadInfo
                return
            }

            // auto download is enabled, so no confirmation dialog is needed
            pendingSharedVideoId = null
        }

        if (autoDownload && !autoDownloadUrls.contains(downloadKey)) {
            autoDownloadUrls.add(downloadKey)
            startDownload()
        }
    }

    // download ==========================================

    val downloadManager = YoutubeDownloadManager(libraryManager.songManager)

    fun startDownload() {
        val downloadInfo = downloadInfoState ?: return

        if (downloadManager.isBusy()) return

        if (permissionManager.getMissingPermission() != null) {
            pendingDownload = true
            pendingDownloadInfo = downloadInfo
            return
        }

        pendingDownload = false
        pendingDownloadInfo = null
        pendingSharedVideoId = null
        shareDownloadPrompt = null

        val bufferBytes = settingManager.getSetting(Setting.DOWNLOAD_BUFFER).toInt() * 1000L
        analyticsManager.logDownloadStarted()
        downloadManager.start(downloadInfo, bufferBytes) { song, removedUris ->

            libraryManager.addToSongs(song, removedUris)

            analyticsManager.logDownloadCompleted()
            showToast(R.string.toast_download_completed)

            sync {
                if (settingManager.getSetting(Setting.PLAY_SONG_AFTER_DOWNLOAD).toBoolean()) {
                    playerManager.play(libraryManager.getSongs(), song)
                    screenManager.openScreen(Screen.PLAYER)
                }
            }

            //insert uri & videoId into database for later usage (e.g. loading thumbnails from yt)
            val videoId = YoutubeApi.extractVideoId(downloadInfo.videoUrl.toString())
            if (videoId != null) {
                libraryManager.databaseManager.writeLine("${song.contentUri} $videoId")
            }

            // Keep the download info so the browser's download button stays
            // available after a finished download. It is cleared when the
            // browser navigates to another page.
        }

    }

    /** Starts a download that was waiting for the storage permission. */
    fun continuePendingDownload() {
        if (!pendingDownload) return
        pendingDownload = false

        // resume the exact video that was requested, even if the browser moved on
        pendingDownloadInfo?.let { downloadInfoState = it }
        pendingDownloadInfo = null

        startDownload()
    }

    fun hideDownloadDialog() {
        downloadManager.getDownloadState().hide()
        showToast(R.string.toast_download_hidden)
    }

    fun showDownloadDialog() {
        downloadManager.getDownloadState().hidden = false
    }

    fun clearShareDownloadPrompt() {
        shareDownloadPrompt = null
        pendingSharedVideoId = null
    }

    private var lastDownloadUnavailableToastTime = 0L

    /** YouTube sometimes refuses to hand out an audio stream for the current network. */
    fun showDownloadUnavailableToast() {
        val now = System.currentTimeMillis()
        if (now - lastDownloadUnavailableToastTime < 5000L) return
        lastDownloadUnavailableToastTime = now
        showToast(R.string.toast_download_unavailable)
    }

    // update ==========================================

    val updateManager = UpdateManager(getAppContext())

    fun hasUpdatesEnabled(): Boolean {
        return settingManager.getSetting(Setting.UPDATES).toBoolean()
    }

    // browser ==========================================

    /**
     * A shared link can arrive at the activity before the browser exists, so it is kept
     * until the search screen creates the WebView.
     */
    private var pendingUrl: String? = null

    fun loadUrl(url: String) {
        pendingUrl = url
        currentBrowser?.let {
            pendingUrl = null
            it.loadUrl(url)
        }
    }

    /** Returns the link that was waiting for the browser, and forgets it. */
    fun consumePendingUrl(): String? {
        val url = pendingUrl
        pendingUrl = null
        return url
    }

    fun isResetBrowserEnabled(): Boolean {
        return settingManager.getSetting(Setting.RESET_BROWSER).toBoolean()
    }

    fun isKurdishOnlySearchEnabled(): Boolean {
        return settingManager.getSetting(Setting.KURDISH_ONLY_SEARCH).toBoolean()
    }

    fun getKurdishMusicSearchUrl(): String {
        val searchQuery = listOf(
            "kurdish music",
            "kurdi music",
            "kurmanji song",
            "kurmancî song",
            "sorani song",
            "soranî song",
            "badini song",
            "gorani song",
            "goranî song",
            "hawrami song",
            "hawramî song",
            "zazaki song",
            "zazakî song",
            "feyli song",
            "laki song"
        ).joinToString(" | ")

        return Uri.parse("https://www.youtube.com/results")
            .buildUpon()
            .appendQueryParameter("search_query", searchQuery)
            // YouTube's upload-date sort filter; newer uploads appear first.
            .appendQueryParameter("sp", "CAI=")
            .build()
            .toString()
    }

    // theme ==========================================

    //check isSystemInDarkMode
    private fun checkDarkMode(): Boolean {
        val uiMode = getAppContext().resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun getTheme(): Boolean {
        return when (settingManager.getSetting(Setting.THEME)) {
            "Dark" -> true
            "Light" -> false
            else -> checkDarkMode()
        }
    }

    // language ==========================================

    fun getLanguage(): AppLanguage {
        return LocaleManager.getLanguage(getAppContext())
    }

    fun setLanguage(languageCode: String) {
        LocaleManager.setLanguage(getAppContext(), languageCode)
        analyticsManager.logLanguageChanged(languageCode)
    }

    // other ==========================================

    var hasRequestedFinish by mutableStateOf(false)

    fun showToast(stringId: Int) {
        //the application context has the system locale, so resolve the string with the app language
        showToast(LocaleManager.wrap(getAppContext()).getString(stringId))
    }

    fun showToast(text: String) {
        Toast.makeText(getAppContext(), text, Toast.LENGTH_SHORT).show()
    }

    fun openUrlInStandardBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        getAppContext().startActivity(intent)
    }

    fun loadSharedYoutubeUrlInBrowser(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return

        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (sharedText.isNullOrBlank()) {
            showToast(R.string.toast_share_no_youtube_link)
            return
        }

        val youtubeUrl = YoutubeApi.extractYoutubeUrl(sharedText)
        val videoId = youtubeUrl?.let(YoutubeApi::extractVideoId)

        if (youtubeUrl == null || videoId == null) {
            showToast(R.string.toast_share_no_youtube_link)
            return
        }

        val currentPromptVideoId = shareDownloadPrompt?.let {
            YoutubeApi.extractVideoId(it.videoUrl.toString())
        }
        if (videoId == pendingSharedVideoId || videoId == currentPromptVideoId) return

        shareDownloadPrompt = null
        pendingSharedVideoId = videoId
        loadUrl(youtubeUrl)
        screenManager.openScreen(Screen.SEARCH)
    }

    fun getGithubUrl(): String {
        val appName = getAppContext().getString(R.string.app_name)
        val accountName = getAppContext().getString(R.string.github_account)
        return "https://github.com/$accountName/$appName"
    }

}
