package com.techno.kurdtube.backend.update

import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 helpers used to verify a downloaded update APK against the
 * `*.apk.sha256` asset published with the GitHub release.
 */
internal object Checksum {

    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    private const val HEX = "0123456789abcdef"

    /** Parses the first token of a `sha256sum`-style file. */
    fun parseExpectedHash(text: String): String? =
        text.trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            ?.lowercase()
            ?.takeIf { SHA256_PATTERN.matches(it) }

    /** Returns the lowercase SHA-256 hex digest of [file]. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }

        val bytes = digest.digest()
        val result = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            result.append(HEX[value ushr 4])
            result.append(HEX[value and 0x0F])
        }
        return result.toString()
    }
}
