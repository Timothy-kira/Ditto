package kira.ditto.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
    @Test
    fun comparesSemanticVersions() {
        assertTrue(isVersionNewer("v1.2.1", "1.2.0"))
        assertTrue(isVersionNewer("2.0.0", "1.9.9"))
        assertFalse(isVersionNewer("1.2.0", "1.2.0"))
        assertFalse(isVersionNewer("1.1.9", "1.2.0"))
    }

    @Test
    fun ignoresTagPrefixAndBuildMetadata() {
        assertTrue(isVersionNewer("V1.0.1+5", "1.0.0"))
        assertFalse(isVersionNewer("v1.0.0-beta01", "1.0.0"))
    }
}
