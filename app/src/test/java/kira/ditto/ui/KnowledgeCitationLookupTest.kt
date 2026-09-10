package kira.ditto.ui

import kira.ditto.data.KnowledgeCitation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeCitationLookupTest {
    @Test
    fun precedingCitationsStopAtLatestVisibleUserTurn() {
        val citations = listOf(
            KnowledgeCitation(index = 1, sourceName = "notes.md", text = "hello"),
        )
        val messages = listOf(
            ChatMessage(
                id = "user-1",
                author = MessageAuthor.User,
                text = "first",
                knowledgeCitations = citations,
            ),
            ChatMessage(id = "agent-1", author = MessageAuthor.Agent, text = "answer [[1]]"),
            ChatMessage(id = "user-2", author = MessageAuthor.User, text = "follow up"),
            ChatMessage(id = "agent-2", author = MessageAuthor.Agent, text = "second"),
        )
        assertEquals(citations, precedingKnowledgeCitations(messages, beforeMessageId = "agent-1"))
        assertTrue(precedingKnowledgeCitations(messages, beforeMessageId = "agent-2").isEmpty())
        assertTrue(precedingKnowledgeCitations(messages, fromIndex = messages.size).isEmpty())
    }

    @Test
    fun browserImagesStayOnTheTurnThatFetchedThem() {
        val first = listOf(BrowserInlineImage("https://cdn.example/one.jpg", "西湖"))
        val second = listOf(BrowserInlineImage("https://cdn.example/two.jpg", "大雁塔"))
        val messages = listOf(
            ChatMessage(
                id = "user-1",
                author = MessageAuthor.User,
                text = "西湖",
                browserInlineImages = first,
            ),
            ChatMessage(id = "agent-1", author = MessageAuthor.Agent, text = "西湖很大"),
            ChatMessage(
                id = "user-2",
                author = MessageAuthor.User,
                text = "大雁塔",
                browserInlineImages = second,
            ),
            ChatMessage(id = "agent-2", author = MessageAuthor.Agent, text = "大雁塔很高"),
        )
        val byId = browserImagesByMessageId(messages)
        assertEquals(first, byId["agent-1"])
        assertEquals(second, byId["agent-2"])
        assertTrue(byId["user-1"].orEmpty().isEmpty())
        assertEquals(
            first,
            browserImagesForMessage(
                messageId = "agent-1",
                currentTurnIds = setOf("agent-2"),
                liveImages = second,
                imagesByMessage = byId,
                agentMode = false,
            ),
        )
        assertEquals(
            second,
            browserImagesForMessage(
                messageId = "agent-2",
                currentTurnIds = setOf("agent-2"),
                liveImages = second,
                imagesByMessage = byId,
                agentMode = false,
            ),
        )
        assertTrue(
            browserImagesForMessage(
                messageId = "agent-1",
                currentTurnIds = setOf("agent-1"),
                liveImages = second,
                imagesByMessage = byId,
                agentMode = true,
            ).isEmpty(),
        )
    }
}
