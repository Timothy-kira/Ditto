package kira.ditto.upa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UpaGrantTest {
    @Test
    fun classifiesGuestAndHostPermissions() {
        val guest = classifyUpaPermission("storage.write")
        assertEquals(UpaPermissionScope.Guest, guest?.scope)
        assertEquals(UpaPermissionAccess.Write, guest?.access)
        val host = classifyUpaPermission("host.apps.read")
        assertEquals(UpaPermissionScope.Host, host?.scope)
        assertEquals(UpaPermissionAccess.Read, host?.access)
        assertTrue(isForbiddenHostMutation("host.storage.write"))
        assertTrue(isForbiddenHostMutation("host.apps.delete"))
        assertFalse(isForbiddenHostMutation("host.apps.read"))
        assertFalse(isForbiddenHostMutation("storage.write"))
    }

    @Test
    fun parsesReleasedAtAndRejectsRollback() {
        val epoch = parseReleasedAtEpochMs("2026-08-24T10:00:00Z")
        assertNotNull(epoch)
        assertTrue(isUpaVersionNewer("1.2.0", "1.1.1"))
        assertFalse(isUpaVersionNewer("1.1.1", "1.2.0"))
        assertTrue(
            shouldUpdateUpaPlugin(
                localVersion = "1.1.1",
                localReleasedAt = "",
                remoteVersion = "1.2.0",
                remoteReleasedAt = "2026-08-24T10:00:00Z",
            ),
        )
        assertFalse(
            shouldUpdateUpaPlugin(
                localVersion = "1.2.0",
                localReleasedAt = "2026-08-24T12:00:00Z",
                remoteVersion = "1.3.0",
                remoteReleasedAt = "2026-08-24T10:00:00Z",
            ),
        )
        assertFalse(
            shouldUpdateUpaPlugin(
                localVersion = "1.2.0",
                localReleasedAt = "2026-08-24T10:00:00Z",
                remoteVersion = "1.1.9",
                remoteReleasedAt = "2026-08-24T12:00:00Z",
            ),
        )
    }
}
