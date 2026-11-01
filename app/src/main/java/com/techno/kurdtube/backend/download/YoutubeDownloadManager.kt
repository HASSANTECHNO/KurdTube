package com.techno.kurdtube.backend.download

import android.net.Uri
import com.techno.kurdtube.backend.library.Song
import com.techno.kurdtube.backend.library.SongManager
import com.techno.kurdtube.backend.utils.LOG
import com.techno.kurdtube.backend.utils.async
import com.techno.kurdtube.backend.utils.sync
import java.util.concurrent.Future

class YoutubeDownloadManager(private val songManager: SongManager) {

    private val downloadState = YoutubeDownloadState()

    fun getDownloadState(): YoutubeDownloadState {
        return downloadState
    }

    private var downloadThread: Future<*>? = null
    private var lastFileUri: Uri? = null

    private var currentDownload: YoutubeDownload? = null
    private var cancelled = false

    private var lastReportedBytes = 0L
    private var lastReportTime = 0L

    fun start(
        downloadInfo: YoutubeDownloadInfo,
        bufferBytes: Long,
        onSuccess: (song: Song) -> Unit
    ) {
        //only one download at a time
        if (downloadState.active) return

        val download = YoutubeDownload(downloadInfo)

        currentDownload = download
        cancelled = false
        lastReportedBytes = 0L
        lastReportTime = 0L

        downloadState.prepare(download.fileName, download.contentLength)

        downloadThread = async {
            var uri: Uri? = null

            try {
                uri = createSongFile(download)

                val written = downloadFile(download, uri, bufferBytes)
                val finishedUri = lastFileUri ?: uri

                sync {
                    downloadState.updateProgress(written, download.contentLength)
                    downloadState.finish()
                }

                val song = songManager.getSingleSong(finishedUri)
                sync { onSuccess(song) }
            } catch (error: Throwable) {
                LOG("DOWNLOAD FAILED ${error.message}")

                if (!cancelled) {
                    sync { downloadState.fail() }
                }

                (lastFileUri ?: uri)?.let { failedUri ->
                    async {
                        songManager.deleteSong(failedUri)
                    }
                }
            } finally {
                currentDownload = null
            }
        }
    }

    /**
     * Downloads into the file behind [uri]. When the host ignores the resume request the
     * file is thrown away and downloaded from the beginning once.
     */
    private fun downloadFile(
        download: YoutubeDownload,
        uri: Uri,
        bufferBytes: Long
    ): Long {

        val onProgress: (Long) -> Unit = { downloadedBytes ->
            reportProgress(downloadedBytes, download.contentLength)
        }

        val outputStream = songManager.openSongForWriting(uri)
            ?: throw IllegalStateException("openFileForWriting error")

        return try {
            download.start(outputStream, bufferBytes, onProgress)
        } catch (error: RangeNotSupportedException) {
            LOG("host does not support resuming, downloading from the beginning again")

            songManager.deleteSong(uri)

            val newUri = createSongFile(download)
            lastFileUri = newUri

            val newOutputStream = songManager.openSongForWriting(newUri)
                ?: throw IllegalStateException("openFileForWriting error")

            lastReportedBytes = 0L
            download.start(newOutputStream, bufferBytes, onProgress)
        }
    }

    private fun createSongFile(download: YoutubeDownload): Uri {
        val uri = songManager.createSong(
            fileName = sanitizeFileName(download.fileName),
            extension = download.fileExtension,
            mimeType = download.mimeType
        ) ?: throw IllegalStateException("createFile error")

        lastFileUri = uri
        return uri
    }

    private fun reportProgress(downloadedBytes: Long, totalBytes: Long) {
        val now = System.currentTimeMillis()
        val enoughData = downloadedBytes - lastReportedBytes >= PROGRESS_STEP_BYTES
        val enoughTime = now - lastReportTime >= PROGRESS_INTERVAL_MS

        if (!enoughData && !enoughTime) return

        lastReportedBytes = downloadedBytes
        lastReportTime = now

        //compose state must only be changed on the main thread
        sync { downloadState.updateProgress(downloadedBytes, totalBytes) }
    }

    fun cancel() {
        if (!downloadState.active) return

        cancelled = true

        currentDownload?.cancel()
        downloadThread?.cancel(true)

        downloadState.active = false
        downloadState.speedBytesPerSecond = 0L
    }

    /** File names come from the video title, so they can contain characters MediaStore rejects. */
    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace(ILLEGAL_FILE_NAME_CHARS, " ")
            .replace(WHITESPACES, " ")
            .trim()
            .trim('.')

        val limited =
            if (cleaned.length > MAX_FILE_NAME_LENGTH) cleaned.substring(0, MAX_FILE_NAME_LENGTH)
            else cleaned

        return limited.ifBlank { FALLBACK_FILE_NAME }
    }

    companion object {
        private const val PROGRESS_STEP_BYTES = 32L * 1024L
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val MAX_FILE_NAME_LENGTH = 120
        private const val FALLBACK_FILE_NAME = "audio"

        private val ILLEGAL_FILE_NAME_CHARS = Regex("""[\\/:*?"<>|]""")
        private val WHITESPACES = Regex("\\s+")
    }

}
