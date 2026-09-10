package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPagesTest {
    @Test
    fun readsAMarkdownNote() {
        val page = parseMemoryNoteFile(
            id = "h-abc123",
            markdown = """
                ### History
                Applied 2026-03, decided 2026-11.

                ### Current task
                Drafting the appeal.
            """.trimIndent(),
            updatedAtMillis = 200L,
        )
        requireNotNull(page)
        assertTrue(page.isSessionNote)
        assertEquals("Session note", page.displayTitle)
        assertTrue(page.summary.contains("Drafting the appeal."))
        assertEquals(200L, page.updatedAtMillis)
        assertEquals("h-abc123", page.id)
    }

    @Test
    fun blankIdsAreSkipped() {
        assertNull(parseMemoryNoteFile("", "### History\n\n### Current task\n", 1L))
    }

    @Test
    fun newerNotesSortFirst() {
        val older = parseMemoryNoteFile("h-old", "### History\nold\n\n### Current task\nx", 10L)!!
        val newer = parseMemoryNoteFile("h-new", "### History\nnew\n\n### Current task\ny", 90L)!!
        val sorted = sortMemoryPages(listOf(older, newer))
        assertEquals(listOf("h-new", "h-old"), sorted.map { it.id })
    }
}
