package kira.ditto.data.chatdb

import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenTurnHashTest {
    @Test
    fun matchesNodeJsonStringifySha256() {
        assertEquals(
            "b5344ebd07750817371cf4670e556b15a4babb975bde955ff4ce24bca09731ef",
            spokenTurnHash("user", "hello"),
        )
    }

    @Test
    fun trimsAndNormalizesNewlinesBeforeHashing() {
        val left = spokenTurnHash("assistant", "done\r\n")
        val right = spokenTurnHash("assistant", "done")
        assertEquals(left, right)
    }

    @Test
    fun mapsChatAuthorsToSpokenRoles() {
        assertEquals("user", spokenRoleFromAuthor("User"))
        assertEquals("assistant", spokenRoleFromAuthor("Agent"))
        assertEquals(null, spokenRoleFromAuthor("UNKNOWN"))
    }
}
