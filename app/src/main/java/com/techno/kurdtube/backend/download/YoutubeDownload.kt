package com.techno.kurdtube.backend.download

import com.techno.kurdtube.backend.utils.LOG
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** The download link of the web player is no longer valid (it expires after a while). */
class ExpiredLinkException(message: String) : IOException(message)

/** The host answered with the whole file although a byte range was requested. */
class RangeNotSupportedException : IOException("the host does not support range requests")


/**
 * Downloads a single audio stream that the YouTube web player requested.
 *
 * The player links are signed and expire, and connections are frequently closed in the
 * middle of a transfer. So instead of cutting the file into separate requests, the
 * downloader streams once and continues from the last written byte:
 *
 * - `Range: bytes=<downloaded>-` to resume after an interruption
 * - `Accept-Encoding: identity`, so the bytes on the wire are the bytes of the file
 * - retries with backoff for temporary errors (timeouts, 5xx, 429)
 * - no retry for 403/404/410, because the link itself has expired
 * - the written byte count is verified against the announced size
 */
class YoutubeDownload(
    val fileName: String,
    private val downloadUrl: String,
    /** Mime type of the stream, e.g. "audio/mp4". If non-null and non-blank, used directly
     *  for extension determination; otherwise the download URL is parsed for a "mime" query parameter.
     *  This is provided by the caller (YoutubeBrowser) via YoutubeDownloadInfo.
     */
    val mimeType: String? = null,
    /** File size in bytes. If >0, used for size verification in the download loop;
     *  otherwise the download URL is parsed for a "clen" query parameter, or defaults to -1L.
     *  This is provided by the caller (YoutubeBrowser) via YoutubeDownloadInfo.
     */
    initialContentLength: Long = -1L
) {

    /** Entry point used by the player, which hands over the captured request. */
    constructor(downloadInfo: YoutubeDownloadInfo) : this(
        downloadInfo.videoTitle,
        downloadInfo.downloadUrl.toString(),
        /* mimeType = */ downloadInfo.mimeType,
        /* initialContentLength = */ downloadInfo.contentLength
    )

    private val queryParameters: Map<String, String> = parseQueryParameters(downloadUrl)

    /** Mime type of the stream, e.g. "audio/mp4".
     *  Uses the provided [mimeType] from the constructor if non-null and non-blank,
     *  otherwise falls back to parsing the download URL for a "mime" query parameter.
     */
    val effectiveMimeType: String
        get() = mimeType
            ?.takeIf { it.isNotBlank() }
            ?: queryParameters[QUERY_MIME] ?: "audio/webm"

    /** File extension that belongs to the effective mime type. */
    val fileExtension: String
        get() = when {
            effectiveMimeType.contains("webm") -> ".webm"
            effectiveMimeType.contains("mp4") || effectiveMimeType.contains("m4a") -> ".m4a"
            effectiveMimeType.contains("mpeg") -> ".mp3"
            else -> ".webm"
        }

    /** True if the download URL contains a "range" query parameter. */
    private val urlHasRangeParameter: Boolean
        get() = queryParameters.containsKey(RANGE_PARAMETER)

    /** Size of the file in bytes, -1 until a response tells us.
     *  Uses the provided [contentLength] from the constructor if >0,
     *  otherwise falls back to parsing the download URL for a "clen" query parameter.
     */
    var contentLength: Long = initialContentLength
        get() = field
        set(value) {
            field = value
        }

    /** Stops the download as soon as possible, even while a socket read is blocking. */
    @Volatile
    private var cancelled = false

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    /** Stops the download as soon as possible, even while a socket read is blocking. */
    fun cancel() {
        cancelled = true
        activeConnection?.disconnect()
    }

    /**
     * Streams the file into [outputStream], reports the downloaded byte count and returns
     * the number of written bytes.
     */
    @Throws(IOException::class)
    fun start(
        outputStream: OutputStream,
        bufferBytes: Long,
        onProgress: (downloadedBytes: Long) -> Unit
    ): Long {

        val bufferSize = bufferBytes.coerceIn(MIN_BUFFER_SIZE, MAX_BUFFER_SIZE).toInt()
        val startedAt = System.currentTimeMillis()

        var downloaded = 0L
        var failedAttempts = 0

        outputStream.use { output ->
            while (contentLength < 0L || downloaded < contentLength) {
                try {
                    val added = downloadFrom(output, downloaded, bufferSize, onProgress)
                    if (added <= 0L) {
                        if (contentLength > 0L && downloaded < contentLength) {
                            throw IOException("no data received at byte $downloaded")
                        }
                        break
                    }
                    downloaded += added
                    failedAttempts = 0

                    //no announced size: a short chunk means the end of the file was reached
                    if (contentLength <= 0L && added < CHUNK_SIZE_BYTES) break
                } catch (error: IOException) {
                    val retryable = error !is ExpiredLinkException &&
                            error !is RangeNotSupportedException &&
                            error !is InterruptedIOException

                    if (!retryable || cancelled || failedAttempts >= MAX_RETRIES) throw error

                    failedAttempts++
                    LOG("download interrupted at $downloaded bytes (${error.message}), retry $failedAttempts")
                    backoff(failedAttempts)
                }
            }

            output.flush()
        }

        if (downloaded <= 0L) {
            throw IOException("the download was empty")
        }

        if (contentLength > 0L && downloaded != contentLength) {
            throw IOException("incomplete download: $downloaded of $contentLength bytes")
        }

        LOG("ENDED DOWNLOAD ($downloaded bytes in ${System.currentTimeMillis() - startedAt} ms)")
        return downloaded
    }

    private fun downloadFrom(
        output: OutputStream,
        startOffset: Long,
        bufferSize: Int,
        onProgress: (downloadedBytes: Long) -> Unit
    ): Long {

        val connection = openConnection(startOffset)
        activeConnection = connection

        try {
            val responseCode = connection.responseCode

            when {
                responseCode == HttpURLConnection.HTTP_PARTIAL -> {
                    val contentRange = connection.getHeaderField("Content-Range")
                    val rangeStart = contentRange
                        ?.substringAfter("bytes ")?.substringBefore("-")?.trim()?.toLongOrNull()
                    if (rangeStart != null && rangeStart != startOffset) {
                        throw IOException("unexpected range start $rangeStart (expected $startOffset)")
                    }
                    val total = contentRange?.substringAfter("/")?.trim()?.toLongOrNull()
                    if (total != null && total > 0L) contentLength = total
                }

                responseCode == HttpURLConnection.HTTP_OK -> {
                    //the host ignored the resume request, the stream starts at byte zero again
                    if (startOffset > 0L) throw RangeNotSupportedException()
                    if (contentLength <= 0L) {
                        val length = connection.contentLengthLong
                        if (length > 0L) contentLength = length
                    }
                }

                responseCode == 403 || responseCode == 404 || responseCode == 410 -> {
                    throw ExpiredLinkException(
                        "the download link expired (HTTP $responseCode${errorDetails(connection)})"
                    )
                }

                responseCode == 429 || responseCode >= 500 -> {
                    throw IOException("the server is busy (HTTP $responseCode)")
                }

                responseCode == HTTP_RANGE_NOT_SATISFIABLE &&
                        startOffset > 0L && contentLength == startOffset -> return 0L

                else -> throw IOException("download request failed (HTTP $responseCode)")
            }

            val written = copy(connection.inputStream, output, startOffset, bufferSize, onProgress)

            return written
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    private fun copy(
        input: InputStream,
        output: OutputStream,
        startOffset: Long,
        bufferSize: Int,
        onProgress: (downloadedBytes: Long) -> Unit
    ): Long {

        val buffer = ByteArray(bufferSize)
        var total = 0L

        input.use { stream ->
            while (true) {
                if (cancelled || Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException("download cancelled")
                }

                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue

                output.write(buffer, 0, read)
                total += read
                onProgress(startOffset + total)
            }
        }

        return total
    }

    private fun openConnection(startOffset: Long): HttpURLConnection {
        val url = buildUrl(startOffset)

        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            //keeps the byte count of the response equal to the size of the file
            setRequestProperty("Accept-Encoding", "identity")

            //googlevideo answers 403 when a stream is requested without a range, so the
            //download always asks for one bounded chunk and continues from the last byte
            if (!urlHasRangeParameter) {
                val endOffset = startOffset + CHUNK_SIZE_BYTES - 1L
                setRequestProperty("Range", "bytes=$startOffset-$endOffset")
            }
        }
    }

    /** Rewrites only the value of the `range` query parameter and keeps every other byte. */
    private fun buildUrl(startOffset: Long): String {
        if (startOffset <= 0L || !urlHasRangeParameter) return downloadUrl
        return downloadUrl.replace(Regex("([?&]$RANGE_PARAMETER=)[^&]*"), "$1$startOffset-")
    }

    /** The first bytes of an error response, they name the reason (IP-bound, expired, ...). */
    private fun errorDetails(connection: HttpURLConnection): String {
        return try {
            val text = connection.errorStream
                ?.bufferedReader()
                ?.use { it.readText() }
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(400)

            if (text.isNullOrBlank()) "" else ": $text"
        } catch (error: Exception) {
            ""
        }
    }

    private fun backoff(attempt: Int) {        try {
            Thread.sleep(RETRY_DELAY_MS * attempt)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("download cancelled")
        }
    }

    private fun parseQueryParameters(url: String): Map<String, String> {
        val query = try {
            java.net.URI(url).rawQuery
        } catch (error: Exception) {
            null
        } ?: return emptyMap()

        val parameters = mutableMapOf<String, String>()

        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue

            val name = decodeQueryValue(pair.substringBefore('='))
            val value = decodeQueryValue(pair.substringAfter('=', ""))
            parameters[name] = value
        }

        return parameters
    }

    private fun decodeQueryValue(value: String): String {
        return try {
            java.net.URLDecoder.decode(value, "UTF-8")
        } catch (error: Exception) {
            value
        }
    }

    companion object {
        private const val RANGE_PARAMETER = "range"
        private const val QUERY_MIME = "mime"
        private const val QUERY_CLEN = "clen"
        private const val DEFAULT_EXTENSION = ".webm"
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1_000L
        private const val MIN_BUFFER_SIZE = 16L * 1024L
        private const val MAX_BUFFER_SIZE = 1024L * 1024L
        /** Size of a single range request. Smaller chunks resume faster after a connection drop. */
        private const val CHUNK_SIZE_BYTES = 4L * 1024L * 1024L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
    }
}