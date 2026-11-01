package com.techno.kurdtube.backend.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the part of the resolver that does not need the network: reading the video id out
 * of a link and choosing the audio stream of a player response.
 */
class YoutubeApiTest {

    @Test
    fun readsTheVideoIdOfAWatchLink() {
        assertEquals(
            "aqz-KE-bpKQ",
            YoutubeApi.extractVideoId("https://www.youtube.com/watch?v=aqz-KE-bpKQ")
        )
        assertEquals(
            "aqz-KE-bpKQ",
            YoutubeApi.extractVideoId(
                "https://m.youtube.com/watch?v=aqz-KE-bpKQ&list=PL123&index=4&t=12s"
            )
        )
    }

    @Test
    fun readsTheVideoIdOfAShortLink() {
        assertEquals("dQw4w9WgXcQ", YoutubeApi.extractVideoId("https://youtu.be/dQw4w9WgXcQ?t=42"))
    }

    @Test
    fun ignoresPagesWithoutAVideo() {
        assertNull(YoutubeApi.extractVideoId("https://www.youtube.com/"))
        assertNull(YoutubeApi.extractVideoId("https://www.youtube.com/results?search_query=music"))
        assertNull(YoutubeApi.extractVideoId("https://www.youtube.com/watch?v=short"))
        assertNull(YoutubeApi.extractVideoId("https://example.com/watch?v=aqz-KE-bpKQ"))
        assertNull(YoutubeApi.extractVideoId("not a url"))
    }

    @Test
    fun picksTheHighestAacStream() {
        val response = playerResponse(
            title = "Big Buck Bunny",
            formats = listOf(
                format(251, "audio/webm; codecs=\"opus\"", bitrate = 143452, url = "https://x/webm"),
                format(140, "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = 130992, url = "https://x/140"),
                format(258, "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = 389665, url = "https://x/258"),
                format(380, "audio/mp4; codecs=\"ac-3\"", bitrate = 385273, url = "https://x/380")
            )
        )

        val stream = YoutubeApi.parsePlayerResponse("aqz-KE-bpKQ", response)

        assertEquals("https://x/258", stream.downloadUrl)
        assertEquals("audio/mp4", stream.mimeType)
        assertEquals("Big Buck Bunny", stream.title)
        assertEquals(30_767_611L, stream.contentLength)
    }

    @Test
    fun fallsBackToWebmWhenThereIsNoAacStream() {
        val response = playerResponse(
            title = "Song",
            formats = listOf(
                format(251, "audio/webm; codecs=\"opus\"", bitrate = 143452, url = "https://x/251")
            )
        )

        val stream = YoutubeApi.parsePlayerResponse("aqz-KE-bpKQ", response)

        assertEquals("https://x/251", stream.downloadUrl)
        assertEquals("audio/webm", stream.mimeType)
    }

    @Test
    fun skipsFormatsWithoutADirectUrl() {
        val response = playerResponse(
            title = "Song",
            formats = listOf(format(140, "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = 130992, url = ""))
        )

        assertThrows<YoutubeResolutionException> {
            YoutubeApi.parsePlayerResponse("aqz-KE-bpKQ", response)
        }
    }

    @Test
    fun reportsABlockedVideo() {
        val response = """
            {"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you are not a bot"},
             "videoDetails":{"title":"Song"}}
        """.trimIndent()

        val error = assertThrows<YoutubeResolutionException> {
            YoutubeApi.parsePlayerResponse("aqz-KE-bpKQ", response)
        }

        val message = error.message.orEmpty()
        assertEquals(true, message.contains("LOGIN_REQUIRED"))
        assertEquals(true, message.contains("not a bot"))
    }

    private fun playerResponse(title: String, formats: List<String>): String {
        return """
            {
              "playabilityStatus": {"status": "OK"},
              "videoDetails": {"title": "$title"},
              "streamingData": {"adaptiveFormats": [${formats.joinToString(",")}]}
            }
        """.trimIndent()
    }

    private fun format(itag: Int, mimeType: String, bitrate: Long, url: String): String {
        val escapedMime = mimeType.replace("\"", "\\\"")
        return """
            {"itag": $itag, "mimeType": "$escapedMime", "bitrate": $bitrate,
             "contentLength": 30767611, "url": "$url"}
        """.trimIndent()
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

}
