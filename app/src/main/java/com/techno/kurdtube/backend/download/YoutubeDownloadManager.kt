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

    /** True while bytes are downloaded or the MP3 conversion is still running. */
    fun isBusy(): Boolean {
        return downloadState.active || processing
    }

    private var downloadThread: Future<*>? = null
    private var lastFileUri: Uri? = null

    private var currentDownload: YoutubeDownload? = null
    private var cancelled = false

    /** Set on the download thread, read on the main thread to block a second download. */
    @Volatile
    private var processing = false

    private var lastReportedBytes = 0L
    private var lastReportTime = 0L

    fun start(
        downloadInfo: YoutubeDownloadInfo,
        bufferBytes: Long,
        onSuccess: (song: Song, removedUris: Set<Uri>) -> Unit
    ) {
        //only one download at a time
        if (downloadState.active || processing) return

        val download = YoutubeDownload(downloadInfo)

        currentDownload = download
        cancelled = false
        lastReportedBytes = 0L
        lastReportTime = 0L

        downloadState.prepare(download.fileName, download.contentLength)

        downloadThread = async {
            var uri: Uri? = null
            var current = download
            // Intermediate files that were deleted and must disappear from the
            // library if a concurrent refresh had already listed them.
            val removedUris = mutableSetOf<Uri>()

            try {
                var currentUri = createSongFile(current)
                uri = currentUri

                var written = 0L
                var renewals = 0

                while (true) {
                    try {
                        written = downloadFile(current, currentUri, bufferBytes, removedUris)
                        break
                    } catch (error: ExpiredLinkException) {
                        //googlevideo signs the link for the requesting IP, so a new network
                        //path (proxy, mobile data, VPN) makes it invalid. Ask for a new one.
                        val videoId =
                            YoutubeApi.extractVideoId(downloadInfo.videoUrl.toString())

                        if (cancelled || videoId == null || renewals >= MAX_LINK_RENEWALS) {
                            throw error
                        }

                        renewals++
                        LOG("download link expired, resolving a fresh one ($renewals)")

                        val stream = YoutubeApi.resolveAudio(videoId)
                        current = YoutubeDownload(
                            stream.title,
                            stream.downloadUrl,
                            stream.mimeType,
                            stream.contentLength
                        )

                        deleteIntermediate(currentUri, removedUris)
                        lastReportedBytes = 0L
                        currentUri = createSongFile(current)
                        uri = currentUri
                        reportProgress(0L, current.contentLength)
                    }
                }

                val downloadedUri = lastFileUri ?: currentUri

                // The bytes are on disk. Close the progress dialog now; the MP3 is
                // prepared in the background. The manager stays busy until it is done.
                processing = true
                sync {
                    downloadState.updateProgress(written, current.contentLength)
                    downloadState.processing = true
                    downloadState.finish()
                }

                val finishedUri = convertToMp3(downloadedUri, current.fileName)
                    ?: downloadedUri

                if (finishedUri != downloadedUri) {
                    deleteIntermediate(downloadedUri, removedUris)
                }

                lastFileUri = finishedUri

                val song = songManager.getSingleSong(finishedUri)
                sync { onSuccess(song, removedUris.toSet()) }
            } catch (error: Throwable) {
                LOG("DOWNLOAD FAILED ${error.message}")

                if (!cancelled) {
                    sync { downloadState.fail() }
                }

                (lastFileUri ?: uri)?.let { failedUri ->
                    async {
                        runCatching { songManager.deleteSong(failedUri) }
                    }
                }
            } finally {
                processing = false
                sync { downloadState.processing = false }
                currentDownload = null
            }
        }
    }

    /**
     * Deletes an intermediate file and remembers its URI. MediaStore deletions are
     * best effort: on Android 10+ the file was pending and was never visible in the
     * library, so a failure must not break a finished download.
     */
    private fun deleteIntermediate(uri: Uri, removedUris: MutableSet<Uri>) {
        val deleted = runCatching { songManager.deleteSong(uri) }
            .onFailure { error ->
                LOG("could not delete the intermediate file: ${error.message}")
            }
            .getOrNull()

        if (deleted != null) {
            removedUris.add(uri)
        }
    }

    /**
     * Downloads into the file behind [uri]. When the host ignores the resume request the
     * file is thrown away and downloaded from the beginning once.
     */
    private fun downloadFile(
        download: YoutubeDownload,
        uri: Uri,
        bufferBytes: Long,
        removedUris: MutableSet<Uri>
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

            deleteIntermediate(uri, removedUris)

            val newUri = createSongFile(download)
            lastFileUri = newUri

            val newOutputStream = songManager.openSongForWriting(newUri)
                ?: throw IllegalStateException("openFileForWriting error")

            lastReportedBytes = 0L
            download.start(newOutputStream, bufferBytes, onProgress)
        }
    }

    /**
     * Converts the downloaded stream into an MP3 so the library holds a real `.mp3` file.
     * Returns `null` when the conversion is not possible and the downloaded file should be
     * kept as it is.
     */
    private fun convertToMp3(sourceUri: Uri, fileName: String): Uri? {
        val mp3Uri = try {
            songManager.createSong(
                fileName = sanitizeFileName(fileName),
                extension = MP3_EXTENSION,
                mimeType = MP3_MIME_TYPE,
                isPending = true
            )
        } catch (error: Throwable) {
            LOG("could not create the mp3 file: ${error.message}")
            null
        } ?: return publishSourceOrThrow(sourceUri)

        lastFileUri = mp3Uri

        val outputStream = songManager.openSongForWriting(mp3Uri)
        val inputFile = songManager.openSongForReading(sourceUri)

        if (outputStream == null || inputFile == null) {
            LOG("could not open the files for the mp3 conversion")
            runCatching { outputStream?.close() }
            runCatching { inputFile?.close() }
            runCatching { songManager.deleteSong(mp3Uri) }
            return publishSourceOrThrow(sourceUri)
        }

        val converted = try {
            Mp3Converter.convert(
                source = inputFile,
                output = outputStream,
                shouldCancel = { cancelled }
            )
        } catch (error: Throwable) {
            LOG("MP3 conversion failed: ${error.message}")
            false
        } finally {
            runCatching { outputStream.close() }
            runCatching { inputFile.close() }
        }

        if (!converted) {
            runCatching { songManager.deleteSong(mp3Uri) }
            return publishSourceOrThrow(sourceUri)
        }

        if (!songManager.publishSong(mp3Uri)) {
            LOG("could not make the mp3 file visible")
            runCatching { songManager.deleteSong(mp3Uri) }
            return publishSourceOrThrow(sourceUri)
        }

        return mp3Uri
    }

    /**
     * Publishes the downloaded container when the MP3 conversion was not possible.
     * Returns `null` because the caller keeps using [sourceUri].
     */
    private fun publishSourceOrThrow(sourceUri: Uri): Uri? {
        if (songManager.publishSong(sourceUri)) {
            lastFileUri = sourceUri
            return null
        }

        throw IllegalStateException("could not publish the downloaded file")
    }

    private fun createSongFile(download: YoutubeDownload): Uri {
        val uri = songManager.createSong(
            fileName = sanitizeFileName(download.fileName),
            extension = download.fileExtension,
            mimeType = download.mimeType,
            isPending = true
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
        private const val MAX_LINK_RENEWALS = 3
        private const val MAX_FILE_NAME_LENGTH = 120
        private const val FALLBACK_FILE_NAME = "audio"
        private const val MP3_EXTENSION = ".mp3"
        private const val MP3_MIME_TYPE = "audio/mpeg"

        private val ILLEGAL_FILE_NAME_CHARS = Regex("""[\\/:*?"<>|]""")
        private val WHITESPACES = Regex("\\s+")
    }

}
