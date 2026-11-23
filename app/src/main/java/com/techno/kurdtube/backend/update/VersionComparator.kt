package com.techno.kurdtube.backend.update

/**
 * Compares dotted app versions such as `1.1.8`, `v1.2` or `1.2.0`.
 *
 * GitHub release tags usually carry a leading `v` (`v1.1.8`, `v1.2`). That
 * prefix must be ignored: comparing `v1.1.8` with the installed `1.1.8` has to
 * report "not newer", otherwise the app offers an update to the exact same
 * version it is already running.
 */
internal object VersionComparator {

    /** Returns true when [remote] is a strictly newer dotted version than [current]. */
    fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts = parseVersion(remote)
        val currentParts = parseVersion(current)

        val size = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until size) {
            val remotePart = remoteParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }
            if (remotePart != currentPart) {
                return remotePart > currentPart
            }
        }

        return false
    }

    private fun parseVersion(version: String): List<Int> {
        val normalized = version.trim().removePrefix("v").removePrefix("V")
        if (normalized.isBlank()) {
            return emptyList()
        }

        return normalized
            .split('.', '-', '_', '+')
            .map { part -> part.filter(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
