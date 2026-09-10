package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCoalesceTest {
    @Test
    fun firstTokenFlushesImmediately() {
        val previous = SessionExecutionState(sessionId = "s")
        val next = previous.copy(pendingAssistantText = "Hello")
        assertEquals(0L, streamCoalesceDelayMillis(previous, next))
    }

    @Test
    fun idleTrickleUsesCoalesceWindow() {
        val previous = SessionExecutionState(sessionId = "s", pendingAssistantText = "Hello")
        val next = previous.copy(pendingAssistantText = "Hello!")
        assertEquals(48L, streamCoalesceDelayMillis(previous, next))
    }

    @Test
    fun burstKeepsCoalesceWindow() {
        val previous = SessionExecutionState(sessionId = "s", pendingAssistantText = "a")
        val next = previous.copy(pendingAssistantText = "a".repeat(80))
        assertTrue(streamCoalesceDelayMillis(previous, next) > 0L)
    }
}
