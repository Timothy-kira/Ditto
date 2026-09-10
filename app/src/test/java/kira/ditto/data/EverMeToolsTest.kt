package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EverMeToolsTest {
    @Test
    fun matchesPrefixedAndBareEverMeNames() {
        assertTrue(EverMeTools.matches("mem_search"))
        assertTrue(EverMeTools.matches("mcp__everme__mem_search"))
        assertTrue(EverMeTools.matches("mem_context"))
        assertTrue(EverMeTools.matches("memory-recall"))
        assertTrue(EverMeTools.matches("mem_save_fact"))
        assertTrue(EverMeTools.isRead("mcp__everme__mem_search"))
        assertTrue(EverMeTools.isWrite("savePersonalMemory"))
        assertFalse(EverMeTools.matches("notes"))
        assertFalse(EverMeTools.matches("page_read"))
    }

    @Test
    fun sessionNotesHideMcpPrefix() {
        assertEquals("notes", SessionNoteTools.canonical("mcp__session_memory__notes"))
        assertEquals("new_context", SessionNoteTools.canonical("new_context"))
        assertEquals("read_original", SessionNoteTools.canonical("mcp__aether-session-memory__read_original"))
        assertTrue(SessionNoteTools.matches("notes"))
        assertFalse(SessionNoteTools.matches("mem_search"))
    }
}
