package com.techno.kurdtube.backend.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun sameVersionIsNotAnUpdate() {
        assertFalse(VersionComparator.isNewerVersion("1.1.8", "1.1.8"))
        assertFalse(VersionComparator.isNewerVersion("1.2", "1.2.0"))
    }

    @Test
    fun githubTagPrefixIsIgnored() {
        // Regression: "v1.1.8" used to be parsed as [1, 8] and was offered as an
        // update over the installed 1.1.8.
        assertFalse(VersionComparator.isNewerVersion("v1.1.8", "1.1.8"))
        assertFalse(VersionComparator.isNewerVersion("V1.1.8", "1.1.8"))
        assertTrue(VersionComparator.isNewerVersion("v1.2", "1.1.8"))
    }

    @Test
    fun olderReleaseIsNotAnUpdate() {
        assertFalse(VersionComparator.isNewerVersion("1.1.7", "1.1.8"))
        assertFalse(VersionComparator.isNewerVersion("1.1.5", "1.1.8"))
    }

    @Test
    fun newerReleaseIsAnUpdate() {
        assertTrue(VersionComparator.isNewerVersion("1.1.9", "1.1.8"))
        assertTrue(VersionComparator.isNewerVersion("1.2", "1.1.8"))
        assertTrue(VersionComparator.isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun versionsAreComparedNumerically() {
        assertTrue(VersionComparator.isNewerVersion("1.10", "1.9"))
        assertFalse(VersionComparator.isNewerVersion("1.9", "1.10"))
    }
}
