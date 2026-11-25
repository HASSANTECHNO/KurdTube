package com.techno.kurdtube.backend.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.techno.kurdtube.MainActivity
import com.techno.kurdtube.R
import com.techno.kurdtube.backend.locale.LocaleManager
import com.techno.kurdtube.backend.utils.LOG

/**
 * Keeps a running download in the foreground.
 *
 * A normal background thread is paused or killed when the app leaves the
 * foreground (Doze, app standby, low memory), which made long downloads take
 * much longer. The foreground service keeps the process alive and holds a
 * partial wake lock while bytes are being transferred. It also shows the
 * progress and the "download complete" notifications.
 */
class DownloadService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    private var lastNotificationTime = 0L
    private var foregroundStarted = false
    private var fileName = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels(this)
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelHandler?.invoke()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                fileName = intent?.getStringExtra(EXTRA_FILE_NAME).orEmpty()
                showForeground()
            }
        }

        return START_NOT_STICKY
    }

    private fun showForeground() {
        val notification = buildProgressNotification(
            progressBytes = 0L,
            totalBytes = 0L,
            speedBytesPerSecond = 0L,
            indeterminate = true
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    PROGRESS_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(PROGRESS_NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } catch (error: Throwable) {
            LOG("could not start the download foreground service: ${error.message}")
            stopSelf()
        }
    }

    /** Updates the progress notification, at most about once per second. */
    fun updateProgress(progressBytes: Long, totalBytes: Long, speedBytesPerSecond: Long) {
        if (!foregroundStarted) return

        val now = System.currentTimeMillis()
        if (now - lastNotificationTime < NOTIFICATION_UPDATE_INTERVAL_MS) return
        lastNotificationTime = now

        notifyProgress(
            buildProgressNotification(
                progressBytes = progressBytes,
                totalBytes = totalBytes,
                speedBytesPerSecond = speedBytesPerSecond,
                indeterminate = false
            )
        )
    }

    /** The bytes are downloaded and the MP3 is prepared in the background. */
    fun showPreparing() {
        if (!foregroundStarted) return

        lastNotificationTime = System.currentTimeMillis()
        notifyProgress(
            buildProgressNotification(
                progressBytes = 0L,
                totalBytes = 0L,
                speedBytesPerSecond = 0L,
                indeterminate = true,
                preparing = true
            )
        )
    }

    private fun notifyProgress(notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(PROGRESS_NOTIFICATION_ID, notification)
        }.onFailure { error ->
            LOG("could not update the download notification: ${error.message}")
        }
    }

    private fun buildProgressNotification(
        progressBytes: Long,
        totalBytes: Long,
        speedBytesPerSecond: Long,
        indeterminate: Boolean,
        preparing: Boolean = false
    ): Notification {

        val localized = LocaleManager.wrap(this)

        val percent = if (totalBytes > 0L) {
            (progressBytes * 100L / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }

        val progressText = when {
            preparing -> localized.getString(R.string.notification_download_preparing)
            indeterminate || totalBytes <= 0L ->
                localized.getString(R.string.notification_download_in_progress)
            else -> localized.getString(
                R.string.notification_download_progress_text,
                percent,
                progressBytes / 1_000_000f,
                totalBytes / 1_000_000f
            )
        }

        val builder = NotificationCompat.Builder(this, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.icon_download)
            .setContentTitle(fileName.ifBlank { localized.getString(R.string.notification_download_title) })
            .setContentText(progressText)
            .setContentIntent(openAppPendingIntent(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setProgress(100, percent, indeterminate || totalBytes <= 0L)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        // The byte transfer can be cancelled, the MP3 conversion cannot.
        if (!preparing) {
            builder.addAction(
                R.drawable.icon_download,
                localized.getString(R.string.notification_download_cancel),
                cancelPendingIntent(this)
            )
        }

        if (speedBytesPerSecond > 0L) {
            builder.setSubText(
                localized.getString(
                    R.string.dialog_download_speed,
                    speedBytesPerSecond / 1_000_000f
                )
            )
        }

        return builder.build()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (error: Throwable) {
            LOG("could not acquire the download wake lock: ${error.message}")
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        wakeLock = null
    }

    private fun removeForegroundNotification() {
        if (!foregroundStarted) return
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        foregroundStarted = false
    }

    override fun onDestroy() {
        instance = null
        removeForegroundNotification()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {

        const val ACTION_START = "com.techno.kurdtube.download.action.START"
        const val ACTION_CANCEL = "com.techno.kurdtube.download.action.CANCEL"

        private const val EXTRA_FILE_NAME = "file_name"

        private const val PROGRESS_CHANNEL_ID = "download_progress"
        private const val COMPLETE_CHANNEL_ID = "download_complete"
        private const val PROGRESS_NOTIFICATION_ID = 4101
        private const val COMPLETE_NOTIFICATION_ID = 4102

        private const val REQUEST_OPEN_APP = 4103
        private const val REQUEST_CANCEL = 4104

        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
        private const val WAKE_LOCK_TAG = "KurdTube:download"

        /** Set by [YoutubeDownloadManager] while a download is running. */
        @Volatile
        var cancelHandler: (() -> Unit)? = null

        @Volatile
        private var instance: DownloadService? = null

        fun start(context: Context, fileName: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FILE_NAME, fileName)
            }

            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { error ->
                LOG("could not start the download service: ${error.message}")
            }
        }

        fun updateProgress(progressBytes: Long, totalBytes: Long, speedBytesPerSecond: Long) {
            instance?.updateProgress(progressBytes, totalBytes, speedBytesPerSecond)
        }

        fun showPreparing() {
            instance?.showPreparing()
        }

        /** Removes the progress notification and posts the completion notification. */
        fun complete(context: Context, songTitle: String) {
            val service = instance
            if (service != null) {
                service.removeForegroundNotification()
                postCompletionNotification(service, songTitle)
                service.stopSelf()
            } else {
                // The foreground service could not be started; still tell the user.
                postCompletionNotification(context, songTitle)
            }
        }

        /** Removes the progress notification without posting a completion message. */
        fun stop() {
            val service = instance ?: return
            service.removeForegroundNotification()
            service.stopSelf()
        }

        private fun postCompletionNotification(context: Context, songTitle: String) {
            if (!canPostNotifications(context)) return

            createNotificationChannels(context)

            val localized = LocaleManager.wrap(context)
            val text = localized.getString(
                R.string.notification_download_completed_text,
                songTitle
            )

            val notification = NotificationCompat.Builder(context, COMPLETE_CHANNEL_ID)
                .setSmallIcon(R.drawable.icon_download)
                .setContentTitle(localized.getString(R.string.notification_download_completed_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openAppPendingIntent(context))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            runCatching {
                NotificationManagerCompat.from(context)
                    .notify(COMPLETE_NOTIFICATION_ID, notification)
            }.onFailure { error ->
                LOG("could not post the completion notification: ${error.message}")
            }
        }

        private fun canPostNotifications(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true

            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }

        private fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val localized = LocaleManager.wrap(context)

            val progressChannel = NotificationChannel(
                PROGRESS_CHANNEL_ID,
                localized.getString(R.string.notification_channel_download_progress),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = localized.getString(
                    R.string.notification_channel_download_progress_description
                )
                setShowBadge(false)
            }

            val completeChannel = NotificationChannel(
                COMPLETE_CHANNEL_ID,
                localized.getString(R.string.notification_channel_download_complete),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = localized.getString(
                    R.string.notification_channel_download_complete_description
                )
            }

            manager.createNotificationChannel(progressChannel)
            manager.createNotificationChannel(completeChannel)
        }

        private fun openAppPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            return PendingIntent.getActivity(
                context,
                REQUEST_OPEN_APP,
                intent,
                pendingIntentFlags()
            )
        }

        private fun cancelPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
            }

            return PendingIntent.getService(
                context,
                REQUEST_CANCEL,
                intent,
                pendingIntentFlags()
            )
        }

        private fun pendingIntentFlags(): Int {
            return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
    }
}
