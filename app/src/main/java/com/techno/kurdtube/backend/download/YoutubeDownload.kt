package com.techno.kurdtube.backend.download

import com.techno.kurdtube.backend.utils.LOG
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class YoutubeDownload(downloadInfo: YoutubeDownloadInfo) {

    val fileName = downloadInfo.videoTitle

    private val downloadUrl = downloadInfo.downloadUrl.toString()
    private val rangeParameter = downloadInfo.downloadUrl.getQueryParameter("range")
    val contentLength = downloadInfo.downloadUrl.getQueryParameter("clen")?.toLongOrNull() ?: -1L

    fun start(
        outputStream: OutputStream,
        buffer: Long = 1000000L,
        onProgress: (progress: Long) -> Unit
    ) {
        val startTime = System.currentTimeMillis()
        LOG("STARTED DOWNLOAD $fileName ($contentLength bytes)")

        outputStream.use { output ->
            if (contentLength > 0L) {
                downloadInParts(output, buffer.coerceAtLeast(1L), onProgress)
            } else {
                downloadWholeFile(output, onProgress)
            }
        }

        val time = System.currentTimeMillis() - startTime
        LOG("ENDED DOWNLOAD ($time ms)")
    }

    private fun downloadInParts(
        output: OutputStream,
        buffer: Long,
        onProgress: (progress: Long) -> Unit
    ) {
        var totalProgress = 0L
        var start = 0L

        while (start < contentLength) {
            val end = minOf(start + buffer - 1L, contentLength - 1L)
            downloadPart(output, start, end) { progress ->
                totalProgress += progress
                onProgress(totalProgress)
            }
            start = end + 1L
        }
    }

    private fun downloadWholeFile(
        output: OutputStream,
        onProgress: (progress: Long) -> Unit
    ) {
        val connection = openConnection(downloadUrl)
        try {
            checkResponse(connection)
            connection.inputStream.use { input ->
                copy(input, output) { progress ->
                    onProgress(progress.toLong())
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadPart(
        output: OutputStream,
        startRange: Long,
        endRange: Long,
        onProgress: (progress: Int) -> Unit
    ) {
        val url = if (rangeParameter != null) {
            downloadUrl.replace(rangeParameter, "$startRange-$endRange")
        } else {
            downloadUrl
        }

        val connection = openConnection(url)
        if (rangeParameter == null) {
            connection.setRequestProperty("Range", "bytes=$startRange-$endRange")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("Download request failed: HTTP $responseCode")
            }
            if (startRange > 0L && rangeParameter == null && responseCode == HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Server ignored the byte range request")
            }

            connection.inputStream.use { input ->
                copy(input, output, onProgress)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
        }
    }

    private fun checkResponse(connection: HttpURLConnection) {
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw IllegalStateException("Download request failed: HTTP $responseCode")
        }
    }

    private fun copy(
        inputStream: InputStream,
        outputStream: OutputStream,
        onProgress: (progress: Int) -> Unit,
        bufferSize: Int = DEFAULT_BUFFER_SIZE
    ) {
        val buffer = ByteArray(bufferSize)
        var bytes = inputStream.read(buffer)
        while (bytes >= 0) {
            if (bytes > 0) {
                outputStream.write(buffer, 0, bytes)
                onProgress(bytes)
            }
            bytes = inputStream.read(buffer)
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
    }
}
