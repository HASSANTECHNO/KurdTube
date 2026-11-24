package com.techno.kurdtube.backend.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ChecksumTest {

    @Test
    fun parsesSha256SumFileLine() {
        val hash = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"

        assertEquals(hash, Checksum.parseExpectedHash("$hash  KurdTube-1.2.apk\n"))
    }

    @Test
    fun acceptsHashWithoutFileName() {
        val hash = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"

        assertEquals(hash, Checksum.parseExpectedHash(hash))
    }

    @Test
    fun rejectsInvalidHash() {
        assertNull(Checksum.parseExpectedHash("not-a-sha256"))
    }

    @Test
    fun computesSha256OfFile() {
        val file = File.createTempFile("kurdtube-checksum", ".bin")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))

            assertEquals(
                "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                Checksum.sha256(file)
            )
        } finally {
            file.delete()
        }
    }
}
