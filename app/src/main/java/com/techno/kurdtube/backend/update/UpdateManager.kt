@file:Suppress("DEPRECATION")

package com.techno.kurdtube.backend.update

import android.app.DownloadManager
import android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.techno.kurdtube.R
import com.techno.kurdtube.backend.utils.async
import org.json.JSONArray
import java.net.URL


class UpdateManager(context: Context) {

    //state

    private var updateAvailableState by mutableStateOf(null as Update?)

    fun isUpdateAvailable(): Update? {
        return updateAvailableState
    }

    fun hideUpdate() {
        updateAvailableState = null
    }

    //

    private val appVersion =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName

    private val accountName = context.getString(R.string.github_account)

    private val appName = context.getString(R.string.app_name)

    private val githubReleasesUrl = "https://api.github.com/repos/$accountName/$appName/releases"

    fun checkForUpdates() {
        async {
            val update = getLastUpdate() ?: return@async
            val currentVersion = appVersion ?: return@async
            //only offer a real upgrade: comparing for inequality would let an older
            //GitHub release "update" (and downgrade) a newer local build
            if (isNewerVersion(update.version, currentVersion)) {
                updateAvailableState = update
            }
        }
    }

    /** Returns true when [remote] is a strictly newer dotted version than [current]. */
    private fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts = remote.split('.', '-', '_', '+').mapNotNull { it.toIntOrNull() }
        val currentParts = current.split('.', '-', '_', '+').mapNotNull { it.toIntOrNull() }

        val size = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until size) {
            val remotePart = remoteParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }
            if (remotePart != currentPart) {
                return remotePart > currentPart
            }
        }

        return false
    }

    private fun getLastUpdate(): Update? {
        val text = URL(githubReleasesUrl).readText()

        val releasesJson = JSONArray(text)
        if (releasesJson.length() == 0) {
            return null
        }

        val releaseJson = releasesJson.getJSONObject(0)
        val title = releaseJson.getString("name")
        val version = releaseJson.getString("tag_name")
        val description = releaseJson.getString("body")
        val githubUrl = releaseJson.getString("html_url")

        val assetsJson = releaseJson.getJSONArray("assets")
        for (i in 0 until assetsJson.length()) {
            val assetJson = assetsJson.getJSONObject(i)
            val downloadUrl = assetJson.getString("browser_download_url")
            if (downloadUrl.endsWith(".apk")) {
                return Update(title, version, description, downloadUrl, githubUrl)
            }
        }
        return null
    }

    //

    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun enqueueUpdateDownload(update: Update) {
        val url = Uri.parse(update.downloadUrl)
        val fileName = url.lastPathSegment ?: return

        val request = DownloadManager
            .Request(url)
            .setNotificationVisibility(VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        request.allowScanningByMediaScanner()
        downloadManager.enqueue(request)
    }

}