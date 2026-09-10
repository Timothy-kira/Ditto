package kira.ditto.ui

import kira.ditto.data.SessionExecutionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepScreenOnTest {
    @Test
    fun keepsScreenOnDuringTurnOrVirtualDisplay() {
        assertTrue(
            shouldKeepAgentScreenOn(
                isSending = true,
                pendingResponseSessionId = null,
                agentModeDisplayActive = false,
                sessionExecutionStates = emptyMap(),
            ),
        )
        assertTrue(
            shouldKeepAgentScreenOn(
                isSending = false,
                pendingResponseSessionId = "session-1",
                agentModeDisplayActive = false,
                sessionExecutionStates = emptyMap(),
            ),
        )
        assertTrue(
            shouldKeepAgentScreenOn(
                isSending = false,
                pendingResponseSessionId = null,
                agentModeDisplayActive = true,
                sessionExecutionStates = emptyMap(),
            ),
        )
        assertTrue(
            shouldKeepAgentScreenOn(
                isSending = false,
                pendingResponseSessionId = null,
                agentModeDisplayActive = false,
                sessionExecutionStates = mapOf(
                    "s1" to SessionExecutionState(
                        sessionId = "s1",
                        activeTurnStartedAtMillis = 1_700_000_000_000L,
                    ),
                ),
            ),
        )
        assertFalse(
            shouldKeepAgentScreenOn(
                isSending = false,
                pendingResponseSessionId = null,
                agentModeDisplayActive = false,
                sessionExecutionStates = mapOf("s1" to SessionExecutionState(sessionId = "s1")),
            ),
        )
    }
}
