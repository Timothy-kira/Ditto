package kira.ditto.data.pi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PiAgentRunnerTest {
    @Test
    fun reconnectStatusIncludesAttemptFailureAndSecondsDelay() {
        val status = reconnectStreamingStatus(
            JSONObject()
                .put("attempt", 2)
                .put("max_attempts", 5)
                .put("delay_ms", 10_000)
                .put("error_message", "Connection reset"),
        )

        assertEquals("Reconnecting... 2/5", status.text)
        assertEquals("Connection reset\nRetrying in 10s", status.detail)
    }

    @Test
    fun reconnectStatusUsesMillisecondsForSubsecondTestDelays() {
        val status = reconnectStreamingStatus(
            JSONObject()
                .put("attempt", 1)
                .put("max_attempts", 2)
                .put("delay_ms", 25),
        )

        assertEquals("Reconnecting... 1/2", status.text)
        assertEquals("Retrying in 25ms", status.detail)
    }
}
