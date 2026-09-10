package kira.ditto.data

import kira.ditto.ui.ChatMessage
import kira.ditto.ui.ChatSession
import kira.ditto.ui.MessageAuthor
import org.junit.Assert.assertEquals
import org.junit.Test

class QueuedTurnRequestBuilderTest {
    @Test
    fun queuedTurnUsesBaseMessagesForMetadataOnlySession() {
        val existingMessages = listOf(
            ChatMessage(
                id = "user-1",
                author = MessageAuthor.User,
                text = "First",
                createdAtMillis = 1L,
            ),
            ChatMessage(
                id = "agent-1",
                author = MessageAuthor.Agent,
                text = "Reply",
                createdAtMillis = 2L,
            ),
        )
        val metadataOnlySession = ChatSession(
            id = "session-1",
            title = "Existing",
            preview = "Reply",
            messages = emptyList(),
            messageCount = existingMessages.size,
            lastMessageAtMillis = 2L,
            agentModeEnabled = true,
        )
        val queuedInput = ChatMessage(
            id = "user-2",
            author = MessageAuthor.User,
            text = "Follow up",
            createdAtMillis = 3L,
        )

        val updated = buildQueuedTurnSession(
            session = metadataOnlySession,
            queuedInput = queuedInput,
            baseMessages = existingMessages,
        )

        assertEquals(listOf("user-1", "agent-1", "user-2"), updated.messages.map { it.id })
        assertEquals(3, updated.messageCount)
        assertEquals(3L, updated.lastMessageAtMillis)
        assertEquals(true, updated.agentModeEnabled)
    }
}
