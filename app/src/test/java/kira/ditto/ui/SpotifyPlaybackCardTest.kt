package kira.ditto.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyPlaybackCardTest {
    private fun playback(
        id: String,
        timelineOrder: Long = 0L,
        startedAtMillis: Long = 0L,
    ) = ChatToolInvocation(
        id = id,
        toolName = "SpotifyPlayback",
        argumentsJson = "{}",
        timelineOrder = timelineOrder,
        startedAtMillis = startedAtMillis,
    )

    @Test
    fun latestPlaybackUsesTimelineOrder() {
        val latest = latestSpotifyPlaybackInvocation(
            listOf(
                playback("old", timelineOrder = 1),
                playback("mid", timelineOrder = 2),
                playback("new", timelineOrder = 4),
                ChatToolInvocation(
                    id = "search",
                    toolName = "SpotifySearch",
                    argumentsJson = "{}",
                    timelineOrder = 9,
                ),
            ),
        )
        assertEquals("new", latest?.id)
    }

    @Test
    fun latestPlaybackFallsBackToStartedAt() {
        val latest = latestSpotifyPlaybackInvocation(
            listOf(
                playback("older", startedAtMillis = 10),
                playback("newer", startedAtMillis = 40),
            ),
        )
        assertEquals("newer", latest?.id)
    }

    @Test
    fun conversationLatestPrefersPendingOverHistory() {
        val id = latestConversationSpotifyPlaybackId(
            messages = listOf(
                ChatMessage(
                    id = "m1",
                    author = MessageAuthor.Agent,
                    text = "",
                    toolInvocations = listOf(playback("history", timelineOrder = 1)),
                ),
            ),
            pendingToolInvocations = listOf(playback("pending", timelineOrder = 8)),
            pendingResponseBlocks = emptyList(),
        )
        assertEquals("pending", id)
    }

    @Test
    fun emptyInvocationsHaveNoLatest() {
        assertNull(latestSpotifyPlaybackInvocation(emptyList()))
        assertEquals(
            "",
            latestConversationSpotifyPlaybackId(
                messages = emptyList(),
                pendingToolInvocations = emptyList(),
                pendingResponseBlocks = emptyList(),
            ),
        )
    }
}
