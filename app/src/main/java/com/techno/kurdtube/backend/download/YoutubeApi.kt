package com.techno.kurdtube.backend.download

import com.techno.kurdtube.backend.utils.LOG
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

/** The player API did not hand out a usable stream. */
class YoutubeResolutionException(message: String) : IOException(message)

/** An audio stream of a video, ready to be downloaded. */
data class YoutubeAudioStream(
    val videoId: String,
    val title: String,
    val downloadUrl: String,
    val mimeType: String,
    val contentLength: Long
)


/**
 * Resolves the download link of a video with the InnerTube player API.
 *
 * The web player of YouTube switched to SABR: it fetches the media in opaque chunks from
 * `/videoplayback` without `mime`/`itag`/`range`, so the old trick of sniffing the request
 * of the WebView no longer produces a file. The player API of the mobile clients still
 * answers with plain signed URLs, so the link is requested here instead.
 *
 * Several clients are tried, because YouTube sometimes blocks a single client for a
 * network without being signed in.
 */
object YoutubeApi {

    private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    private data class Client(
        val name: String,
        val version: String,
        val clientId: String,
        val userAgent: String,
        val contextValues: Map<String, Any>
    )

    private val clients = listOf(
        Client(
            name = "ANDROID",
            version = "20.05.46",
            clientId = "3",
            userAgent = "com.google.android.youtube/20.05.46 (Linux; U; Android 13) gzip",
            contextValues = mapOf(
                "androidSdkVersion" to 33,
                "osName" to "Android",
                "osVersion" to "13"
            )
        ),
        Client(
            name = "IOS",
            version = "20.10.4",
            clientId = "5",
            userAgent =
                "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            contextValues = mapOf(
                "deviceModel" to "iPhone16,2",
                "osName" to "iPhone",
                "osVersion" to "18.1.0.22B83"
            )
        )
    )

    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")

    /**
     * Reads the video id out of a watch/share link, `null` for every other page
     * (home, search, channel, ...).
     */
    fun extractVideoId(videoUrl: String): String? {
        val uri = try {
            URI(videoUrl)
        } catch (error: Exception) {
            return null
        }

        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null

        val id = when {
            host == "youtu.be" -> uri.path?.trim('/')?.substringBefore('/')

            host == "youtube.com" || host.endsWith(".youtube.com") -> {
                if (uri.path != "/watch") return null
                parseQuery(uri.rawQuery)["v"]
            }

            else -> null
        }

        return id?.takeIf { videoIdPattern.matches(it) }
    }

    /**
     * Blocking: asks every client and keeps the best audio stream, so the user gets the
     * highest quality stream that the network currently serves.
     *
     * @throws YoutubeResolutionException when no client returned an audio stream.
     */
    fun resolveAudio(videoId: String): YoutubeAudioStream {
        var lastError: Exception? = null
        var bestStream: YoutubeAudioStream? = null

        for (client in clients) {
            try {
                val stream = resolveWith(client, videoId)

                if (bestStream == null) {
                    bestStream = stream
                } else if (isBetter(stream, bestStream)) {
                    bestStream = stream
                }
            } catch (error: Exception) {
                LOG("YoutubeApi ${client.name} failed: ${error.message}")
                lastError = error
            }
        }

        return bestStream
            ?: throw YoutubeResolutionException(
                "no client returned an audio stream (${lastError?.message})"
            )
    }

    /**
     * Picks the better stream: AAC in an MP4 container first (every device can play it and
     * it fits into the music collection), then the higher bitrate.
     */
    private fun isBetter(
        stream: YoutubeAudioStream,
        other: YoutubeAudioStream
    ): Boolean {
        val streamPreferred = stream.mimeType.startsWith("audio/mp4")
        val otherPreferred = other.mimeType.startsWith("audio/mp4")

        if (streamPreferred != otherPreferred) return streamPreferred

        return stream.contentLength > other.contentLength
    }

    private fun resolveWith(client: Client, videoId: String): YoutubeAudioStream {
        val response = request(client, videoId)
        return parsePlayerResponse(videoId, response)
    }

    private fun request(client: Client, videoId: String): String {
        val body = buildRequestBody(client, videoId)

        val connection = (URL(PLAYER_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", client.userAgent)
            setRequestProperty("X-YouTube-Client-Name", client.clientId)
            setRequestProperty("X-YouTube-Client-Version", client.version)
        }

        try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val stream =
                if (responseCode in 200..299) connection.inputStream
                else connection.errorStream

            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (responseCode !in 200..299) {
                throw YoutubeResolutionException("player request failed (HTTP $responseCode)")
            }

            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun buildRequestBody(client: Client, videoId: String): String {
        val clientContext = JSONObject().apply {
            put("clientName", client.name)
            put("clientVersion", client.version)
            put("hl", "en")
            put("gl", "US")
            put("userAgent", client.userAgent)

            client.contextValues.forEach { (key, value) -> put(key, value) }
        }

        return JSONObject().apply {
            put("context", JSONObject().put("client", clientContext))
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }.toString()
    }

    /**
     * Picks the audio stream out of a player response. Kept free of network code so the
     * format selection can be unit tested.
     *
     * AAC in an MP4 container is preferred: it is decodable on every device and the file
     * can be stored in the music collection without a transcoding step.
     */
    internal fun parsePlayerResponse(videoId: String, response: String): YoutubeAudioStream {
        val json = JSONObject(response)

        val playabilityStatus = json.optJSONObject("playabilityStatus")
        val status = playabilityStatus?.optString("status").orEmpty()

        if (status != "OK") {
            val reason = playabilityStatus?.optString("reason").orEmpty()
            throw YoutubeResolutionException(
                if (status.isBlank()) "the player response has no status"
                else "the video is not playable ($status${if (reason.isBlank()) "" else ": $reason"})"
            )
        }

        val title = json.optJSONObject("videoDetails")
            ?.optString("title")
            ?.takeIf { it.isNotBlank() }
            ?: videoId

        val formats = json.optJSONObject("streamingData")
            ?.optJSONArray("adaptiveFormats")
            ?: throw YoutubeResolutionException("the player response has no streaming data")

        val audioFormats = (0 until formats.length())
            .mapNotNull { formats.optJSONObject(it) }
            .filter { format ->
                format.optString("mimeType").startsWith("audio/") &&
                        format.optString("url").isNotBlank()
            }

        if (audioFormats.isEmpty()) {
            throw YoutubeResolutionException("the player response has no audio stream")
        }

        val best = audioFormats
            .sortedWith(
                compareByDescending<JSONObject> { isPreferred(it.optString("mimeType")) }
                    .thenByDescending { it.optLong("bitrate") }
            )
            .first()

        return YoutubeAudioStream(
            videoId = videoId,
            title = title,
            downloadUrl = best.optString("url"),
            mimeType = best.optString("mimeType").substringBefore(';').trim(),
            contentLength = best.optLong("contentLength", -1L)
        )
    }

    /** MP4/AAC first, then WebM/Opus, so the file fits into the music collection. */
    private fun isPreferred(mimeType: String): Boolean {
        if (!mimeType.startsWith("audio/mp4")) return false

        //ec-3 (Dolby) is not decodable on most devices
        val codecs = mimeType.substringAfter("codecs=", "")
        return codecs.contains("mp4a")
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()

        return rawQuery.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val name = decode(pair.substringBefore('='))
                val value = decode(pair.substringAfter('=', ""))
                name to value
            }
    }

    private fun decode(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (error: Exception) {
            value
        }
    }

}
