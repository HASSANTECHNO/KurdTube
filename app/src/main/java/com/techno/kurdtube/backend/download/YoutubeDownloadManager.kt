package com.techno.kurdtube.backend.download

import android.net.Uri
import com.techno.kurdtube.backend.library.Song
import com.techno.kurdtube.backend.library.SongManager
import com.techno.kurdtube.backend.utils.LOG
import com.techno.kurdtube.backend.utils.async
import java.util.concurrent.Future

class YoutubeDownloadManager(private val songManager: SongManager) {

    private val downloadState = YoutubeDownloadState()

    fun getDownloadState(): YoutubeDownloadState {
        return downloadState
    }

    private var downloadThread: Future<*>? = null
    private var lastFileUri: Uri? = null

    fun start(
        downloadInfo: YoutubeDownloadInfo,
        bufferBytes: Long,
        onSuccess: (song: Song) -> Unit
    ) {
        val download = YoutubeDownload(downloadInfo)

        downloadState.active = true
        downloadState.fileName = download.fileName
        downloadState.progressBytes = 0L
        downloadState.sizeBytes = download.contentLength
        downloadState.startTime = System.currentTimeMillis()

        downloadThread = async {
            var uri: Uri? = null

            try {
                uri = songManager.createSong(download.fileName)
                if (uri == null) {
                    throw IllegalStateException("createFile error")
                }

                lastFileUri = uri

                val outputStream = songManager.openSongForWriting(uri)
                    ?: throw IllegalStateException("openFileForWriting error")

                download.start(
                    outputStream = outputStream,
                    buffer = bufferBytes,
                    onProgress = { progress ->
                        downloadState.progressBytes = progress
                    }
                )

                downloadState.progressBytes =
                    if (download.contentLength > 0L) download.contentLength
                    else downloadState.progressBytes
                downloadState.active = false

                val song = songManager.getSingleSong(uri)
                onSuccess(song)
            } catch (error: Throwable) {
                LOG("DOWNLOAD FAILED ${error.message}")
                downloadState.active = false
                downloadState.progressBytes = -1L

                uri?.let { failedUri ->
                    async {
                        songManager.deleteSong(failedUri)
                    }
                }
            }
        }
    }

    fun cancel() {
        val canceled = downloadThread?.cancel(true) ?: false
        downloadState.active = false
        if (canceled) {
            val uri = lastFileUri ?: return
            async {
                songManager.deleteSong(uri)
            }
        }
    }
}
