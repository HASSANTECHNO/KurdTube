@file:Suppress("DEPRECATION")

package com.techno.kurdtube.backend.update

import android.app.DownloadManager
import android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.techno.kurdtube.R
import com.techno.kurdtube.backend.locale.LocaleManager
import com.techno.kurdtube.backend.utils.LOG
import com.techno.kurdtube.backend.utils.async
import org.json.JSONArray
import java.io.File
import java.net.URL


class UpdateManager(private val context: Context) {

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
            if (VersionComparator.isNewerVersion(update.version, currentVersion)) {
                updateAvailableState = update
            }
        }
    }

    private fun getLastUpdate(): Update? {
        val text = URL(githubReleasesUrl).readText()

        val releasesJson = JSONArray(text)
        for (i in 0 until releasesJson.length()) {
            val releaseJson = releasesJson.getJSONObject(i)

            // drafts and prereleases must never be offered to a stable build
            if (releaseJson.optBoolean("draft") || releaseJson.optBoolean("prerelease")) {
                continue
            }

            val version = releaseJson.optString("tag_name")
            if (version.isBlank()) {
                continue
            }

            val title = releaseJson.optString("name").ifBlank { version }
            val description = releaseJson.optString("body")
            val githubUrl = releaseJson.optString("html_url")

            val assetsJson = releaseJson.getJSONArray("assets")
            for (j in 0 until assetsJson.length()) {
                val assetJson = assetsJson.getJSONObject(j)
                val downloadUrl = assetJson.getString("browser_download_url")
                if (downloadUrl.endsWith(".apk", ignoreCase = true)) {
                    return Update(title, version, description, downloadUrl, githubUrl)
                }
            }
        }
        return null
    }

    // download & install ==========================================

    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private fun preferences() =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun getSavedDownloadId(): Long = preferences().getLong(KEY_DOWNLOAD_ID, -1L)

    private fun setPendingInstall(pending: Boolean) {
        preferences().edit().putBoolean(KEY_PENDING_INSTALL, pending).apply()
    }

    /** Forgets the saved download once it has been handed to the installer. */
    private fun clearSavedUpdate() {
        preferences().edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_APK_PATH)
            .putBoolean(KEY_PENDING_INSTALL, false)
            .apply()
    }

    private fun isInstallAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    private fun isDownloadSuccessful(downloadId: Long): Boolean {
        val cursor = downloadManager.query(
            DownloadManager.Query().setFilterById(downloadId)
        ) ?: return false

        cursor.use {
            if (!it.moveToFirst()) return false
            val statusColumn = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusColumn == -1) return false
            return it.getInt(statusColumn) == DownloadManager.STATUS_SUCCESSFUL
        }
    }

    /**
     * The installer broadcast is sent by the Downloads provider (not by the
     * system uid), so the receiver has to be registered as exported. Only the
     * download id stored by this app is acted on.
     */
    private fun registerDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(
            context,
            downloadReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private val downloadReceiver = object : BroadcastReceiver() {

        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (downloadId == -1L || downloadId != getSavedDownloadId()) return

            if (!isDownloadSuccessful(downloadId)) {
                LOG("update download failed")
                clearSavedUpdate()
                return
            }

            //the download can finish while the app is in the background; the
            //installer is then opened when the app comes back to the foreground
            setPendingInstall(true)
            installDownloadedUpdate()
        }

    }

    fun enqueueUpdateDownload(update: Update) {
        //ask for "install unknown apps" first, while the app is in the foreground
        if (!isInstallAllowed()) {
            showInstallPermissionToast()
            openInstallPermissionSettings()
        }

        val url = Uri.parse(update.downloadUrl)
        val fileName = url.lastPathSegment ?: return
        val apkPath = "updates_$fileName"
        val downloadsDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return
        val apkFile = File(downloadsDirectory, apkPath)

        //DownloadManager reports a failure when the destination already exists
        apkFile.delete()

        val request = DownloadManager
            .Request(url)
            .setNotificationVisibility(VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                apkPath
            )
        request.allowScanningByMediaScanner()

        val downloadId = downloadManager.enqueue(request)

        preferences().edit()
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .putString(KEY_APK_PATH, apkFile.absolutePath)
            .putBoolean(KEY_PENDING_INSTALL, false)
            .apply()
    }

    /** Opens the package installer for a downloaded update, if there is one. */
    fun installDownloadedUpdate() {
        val apkPath = preferences().getString(KEY_APK_PATH, null)
        val apkFile = if (apkPath.isNullOrBlank()) null else File(apkPath)

        if (apkFile == null || !apkFile.exists()) {
            clearSavedUpdate()
            return
        }

        if (!isInstallAllowed()) {
            //the user has to allow it first; resumePendingInstall() retries afterwards
            showInstallPermissionToast()
            return
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
            //the installer already has the URI; do not reopen it on every resume
            clearSavedUpdate()
        } catch (e: Exception) {
            LOG("could not start the package installer: ${e.message}")
        }
    }

    /** Called when the app comes back to the foreground (e.g. from the settings screen). */
    fun resumePendingInstall() {
        val downloadId = getSavedDownloadId()
        val pending = preferences().getBoolean(KEY_PENDING_INSTALL, false)

        if (!pending) {
            //The DOWNLOAD_COMPLETE broadcast is not delivered when the process was
            //killed while downloading, so check the saved download on every resume.
            if (downloadId == -1L || !isDownloadSuccessful(downloadId)) return
            setPendingInstall(true)
        }

        if (!isInstallAllowed()) {
            //do not reopen the settings screen in a loop, only remind the user once
            if (!hasShownInstallPermissionNotice) {
                hasShownInstallPermissionNotice = true
                showInstallPermissionToast()
            }
            return
        }

        installDownloadedUpdate()
    }

    private var hasShownInstallPermissionNotice = false

    private fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            LOG("could not open the unknown sources settings: ${e.message}")
        }
    }

    private fun showInstallPermissionToast() {
        Toast.makeText(
            context,
            LocaleManager.wrap(context).getString(R.string.toast_update_allow_install),
            Toast.LENGTH_LONG
        ).show()
    }

    //declared at the end of the class so the receiver property exists before it is registered
    init {
        registerDownloadReceiver()
    }

    private companion object {
        const val PREFERENCES_NAME = "update"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_APK_PATH = "apk_path"
        const val KEY_PENDING_INSTALL = "pending_install"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }

}
