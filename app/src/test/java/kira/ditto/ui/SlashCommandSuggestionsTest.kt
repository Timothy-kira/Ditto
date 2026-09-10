package kira.ditto.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandSuggestionsTest {
    @Test
    fun builtinCommandsAreTheFallbackWhenNoAgentCommands() {
        val suggestions = slashCommandSuggestions("/").map { it.command }
        assertTrue(
            suggestions.containsAll(
                listOf(
                    "/status",
                    "/usage",
                    "/mcp",
                    "/tasks",
                    "/help",
                    "/clear",
                    "/yolo",
                    "/ask-when-needed",
                    "/never-ask",
                ),
            ),
        )
        assertTrue(suggestions.none { it == "/compact" })
    }

    @Test
    fun agentCommandsMergeWithBuiltinAndFilterByPrefix() {
        val agentCommands = listOf(
            SlashCommandSuggestion("/clear", "Clear the session"),
            SlashCommandSuggestion("/skill:review", "Review code", argumentHint = "<path>"),
        )

        val all = slashCommandSuggestions("/", agentCommands = agentCommands).map { it.command }
        assertTrue(all.contains("/clear"))
        assertTrue(all.contains("/skill:review"))
        assertTrue(all.none { it == "/compact" })

        val filtered = slashCommandSuggestions("/cl", agentCommands = agentCommands)
        assertEquals(listOf("/clear"), filtered.map { it.command })

        val skillFiltered = slashCommandSuggestions("/skill", agentCommands = agentCommands)
        assertEquals(listOf("/skills", "/skill:review"), skillFiltered.map { it.command })
    }

    @Test
    fun duplicateCommandsAreDeduplicatedWithBuiltinWinning() {
        val agentCommands = listOf(
            SlashCommandSuggestion("/status", "Agent-side status"),
            SlashCommandSuggestion("/Status", "case variant"),
        )

        val suggestions = slashCommandSuggestions("/stat", agentCommands = agentCommands)

        assertEquals(1, suggestions.size)
        assertEquals("/status", suggestions[0].command)
        assertEquals("查看当前会话状态", suggestions[0].description)
    }

    @Test
    fun fileMentionsFilterWorkspaceFiles() {
        val files = listOf(
            FileMentionSuggestion("@src/App.kt", "App.kt", "/workspace/src/App.kt"),
            FileMentionSuggestion("@notes.md", "notes.md", "/workspace/notes.md"),
        )
        assertTrue(fileMentionSuggestions("hello", files).isEmpty())
        assertEquals(listOf("@notes.md"), fileMentionSuggestions("@no", files).map { it.insertToken })
        assertEquals(2, fileMentionSuggestions("@", files).size)
        assertTrue(fileMentionSuggestions("user@host.com", files).isEmpty())
    }

    @Test
    fun exactAgentCommandWithTrailingArgumentsHidesSuggestions() {
        val agentCommands = listOf(SlashCommandSuggestion("/clear", "Clear the session"))

        assertTrue(slashCommandSuggestions("/clear now", agentCommands = agentCommands).isEmpty())
        assertTrue(slashCommandSuggestions("/clear", agentCommands = agentCommands).isNotEmpty())
    }

    @Test
    fun nonSlashInputYieldsNoSuggestions() {
        assertTrue(slashCommandSuggestions("").isEmpty())
        assertTrue(slashCommandSuggestions("hello").isEmpty())
    }

    @Test
    fun compactSlashCommandMatchesTokenAndKeepsInstruction() {
        assertTrue(isCompactSlashCommand("/compact"))
        assertTrue(isCompactSlashCommand("  /COMPACT custom  "))
        assertTrue(!isCompactSlashCommand("/clear"))
        assertEquals("keep recent tools", compactSlashInstruction("/compact keep recent tools"))
        assertEquals("", compactSlashInstruction("/compact"))
    }
}
