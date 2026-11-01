package com.techno.kurdtube.backend.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/**
 * Runs the downloader against a real HTTP server, so resume, retry and size verification
 * are tested instead of only compiled. The server is a tiny plain socket implementation
 * because the Android unit test classpath does not contain com.sun.net.httpserver.
 */
class YoutubeDownloadTest {

    private val data = ByteArray(256 * 1024) { index -> (index % 251).toByte() }

    private var server: TestServer? = null

    @After
    fun tearDown() {
        server?.close()
        server = null
    }

    @Test
    fun downloadsTheWholeStream() {
        val url = startServer { _, output ->
            output.writeResponse(200, data.size.toLong())
            output.write(data)
        }

        val download = YoutubeDownload("song", "$url?clen=${data.size}&mime=audio%2Fmp4")
        val output = ByteArrayOutputStream()

        val written = download.start(output, 64 * 1024L) { }

        assertEquals(data.size.toLong(), written)
        assertTrue(data.contentEquals(output.toByteArray()))
        assertEquals(".m4a", download.fileExtension)
        assertEquals(1, server!!.requests.size)
    }

    @Test
    fun resumesAfterTheConnectionDrops() {
        val url = startServer { request, output ->
            if (server!!.requests.size == 1) {
                //announce the whole file, then close the connection in the middle
                output.writeResponse(200, data.size.toLong())
                output.write(data, 0, 64 * 1024)
            } else {
                val start = request.rangeStart() ?: 0

                output.writeResponse(
                    code = 206,
                    contentLength = (data.size - start).toLong(),
                    contentRange = "bytes $start-${data.size - 1}/${data.size}"
                )
                output.write(data, start, data.size - start)
            }
        }

        val download = YoutubeDownload("song", "$url?clen=${data.size}")
        val output = ByteArrayOutputStream()

        download.start(output, 64 * 1024L) { }

        assertTrue(data.contentEquals(output.toByteArray()))
        assertEquals("bytes=${64 * 1024}-", server!!.requests[1].headers["range"])
    }

    @Test
    fun failsWhenTheLinkExpired() {
        val url = startServer { _, output ->
            output.writeResponse(403, 0L)
        }

        val download = YoutubeDownload("song", "$url?clen=1000")

        val error = assertThrows<ExpiredLinkException> {
            download.start(ByteArrayOutputStream(), 64 * 1024L) { }
        }

        assertTrue(error.message.orEmpty().contains("403"))
    }

    @Test
    fun reportsAHostWithoutRangeSupport() {
        val url = startServer { _, output ->
            output.writeResponse(200, data.size.toLong())

            if (server!!.requests.size == 1) {
                output.write(data, 0, 32 * 1024)
            } else {
                //ignores the Range header and sends everything again
                output.write(data)
            }
        }

        val download = YoutubeDownload("song", "$url?clen=${data.size}")

        assertThrows<RangeNotSupportedException> {
            download.start(ByteArrayOutputStream(), 64 * 1024L) { }
        }
    }

    @Test
    fun rewritesTheRangeQueryParameter() {
        val url = startServer { request, output ->
            val start = request.query.substringAfter("range=").substringBefore("-").toInt()
            val first = server!!.requests.size == 1

            output.writeResponse(
                code = 206,
                contentLength = (data.size - start).toLong(),
                contentRange = "bytes $start-${data.size - 1}/${data.size}"
            )

            if (first) {
                output.write(data, start, 48 * 1024)
            } else {
                output.write(data, start, data.size - start)
            }
        }

        val download = YoutubeDownload("song", "$url?clen=${data.size}&range=0-${data.size - 1}")
        val output = ByteArrayOutputStream()

        download.start(output, 64 * 1024L) { }

        assertTrue(data.contentEquals(output.toByteArray()))
        assertTrue(server!!.requests[0].query.contains("range=0-"))
        assertTrue(
            "second request was ${server!!.requests[1].query}",
            server!!.requests[1].query.contains("range=${48 * 1024}-")
        )
    }

    @Test
    fun failsWhenTheFileIsShorterThanAnnounced() {
        val url = startServer { _, output ->
            output.writeResponse(200, data.size.toLong())
            output.write(data, 0, 16 * 1024)
        }

        val download = YoutubeDownload("song", "$url?clen=${data.size + 4096}")

        assertThrows<IOException> {
            download.start(ByteArrayOutputStream(), 64 * 1024L) { }
        }
    }

    private fun startServer(handler: (Request, OutputStream) -> Unit): String {
        val testServer = TestServer(handler)
        server = testServer
        return testServer.url
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("expected ${T::class.java.name} but was ${error::class.java.name}", error)
        }
        throw AssertionError("expected ${T::class.java.name} but nothing was thrown")
    }

    data class Request(
        val method: String,
        val target: String,
        val headers: Map<String, String>
    ) {
        val query: String
            get() = target.substringAfter('?', "")

        fun rangeStart(): Int? {
            return headers["range"]
                ?.substringAfter("bytes=", "")
                ?.substringBefore('-')
                ?.toIntOrNull()
        }
    }

    private fun OutputStream.writeResponse(
        code: Int,
        contentLength: Long,
        contentRange: String? = null
    ) {
        val headers = buildString {
            append("HTTP/1.1 $code ${if (code == 206) "Partial Content" else "Response"}\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Type: audio/webm\r\n")
            if (contentRange != null) append("Content-Range: $contentRange\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        write(headers.toByteArray())
        flush()
    }

    /** Minimal HTTP/1.1 server: one request per connection, always closes afterwards. */
    private class TestServer(handler: (Request, OutputStream) -> Unit) {

        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))

        val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

        val url: String
            get() = "http://127.0.0.1:${socket.localPort}/audio"

        init {
            val thread = Thread {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { client ->
                            val request = readRequest(client.getInputStream()) ?: return@use
                            requests.add(request)

                            val output = client.getOutputStream()
                            try {
                                handler(request, output)
                            } catch (error: Exception) {
                                //the client closed the connection, nothing to do here
                            }
                            output.flush()
                        }
                    } catch (error: Exception) {
                        break
                    }
                }
            }
            thread.isDaemon = true
            thread.start()
        }

        fun close() {
            socket.close()
        }

        private fun readRequest(input: InputStream): Request? {
            val reader = BufferedInputStream(input)

            val requestLine = readLine(reader) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 2) return null

            val headers = mutableMapOf<String, String>()

            while (true) {
                val line = readLine(reader) ?: break
                if (line.isEmpty()) break

                val name = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':').trim()
                headers[name] = value
            }

            return Request(method = parts[0], target = parts[1], headers = headers)
        }

        private fun readLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()

            while (true) {
                val byte = input.read()
                if (byte == -1) return if (bytes.size() == 0) null else bytes.toString("UTF-8")

                if (byte == '\n'.code) break
                if (byte != '\r'.code) bytes.write(byte)
            }

            return bytes.toString("UTF-8")
        }

    }

}
