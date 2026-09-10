package kira.ditto.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceFileMentionQueryTest {
    @Test
    fun emptyAtQueryIsNotSpecificEnoughForFiles() {
        assertFalse(workspaceFileMentionLooksSpecific(""))
        assertFalse(workspaceFileMentionLooksSpecific("a"))
        assertTrue(workspaceFileMentionLooksSpecific("ab"))
        assertTrue(workspaceFileMentionLooksSpecific("App.kt"))
        assertTrue(workspaceFileMentionLooksSpecific("src/"))
        assertTrue(workspaceFileMentionLooksSpecific("."))
    }

    @Test
    fun pluginMentionEmptyQueryListsEveryServer() {
        assertTrue(
            pluginMentionQueryMatches(
                query = "",
                id = "upa.memory",
                displayName = "Memory",
                actionLabel = "Memory",
            ),
        )
        assertTrue(
            pluginMentionQueryMatches(
                query = "git",
                id = "mcp.github",
                displayName = "GitHub",
                actionLabel = "GitHub",
            ),
        )
        assertFalse(
            pluginMentionQueryMatches(
                query = "git",
                id = "upa.memory",
                displayName = "Memory",
                actionLabel = "Memory",
            ),
        )
    }

    @Test
    fun fileMentionSuggestionsStillReturnAllFilesForEmptyQuery() {
        val files = listOf(
            FileMentionSuggestion("@demo_data/review_1.txt", "review_1.txt", "/workspace/demo_data/review_1.txt"),
            FileMentionSuggestion("@notes.md", "notes.md", "/workspace/notes.md"),
        )
        assertEquals(2, fileMentionSuggestions("@", files).size)
        assertTrue(workspaceFileMentionLooksSpecific("").not())
    }
}
