package kira.ditto.data.kimi

import org.junit.Assert.assertEquals
import org.junit.Test

class KimiAcpActivityTimeoutTest {
    @Test
    fun activityKeepsTheRequestAlive() {
        assertEquals(
            KimiAcpActivityTimeoutVerdict.Continue,
            kimiAcpActivityTimeoutVerdict(
                nowMillis = 700_000L,
                startedMillis = 0L,
                lastActivityMillis = 0L,
                idleMillis = 600_000L,
                ceilingMillis = 3_600_000L,
                busy = true,
            ),
        )
        assertEquals(
            KimiAcpActivityTimeoutVerdict.Continue,
            kimiAcpActivityTimeoutVerdict(
                nowMillis = 700_000L,
                startedMillis = 0L,
                lastActivityMillis = 200_000L,
                idleMillis = 600_000L,
                ceilingMillis = 3_600_000L,
                busy = false,
            ),
        )
    }

    @Test
    fun idleSilenceTimesOutBeforeTheCeiling() {
        assertEquals(
            KimiAcpActivityTimeoutVerdict.Idle,
            kimiAcpActivityTimeoutVerdict(
                nowMillis = 600_000L,
                startedMillis = 0L,
                lastActivityMillis = 0L,
                idleMillis = 600_000L,
                ceilingMillis = 3_600_000L,
                busy = false,
            ),
        )
    }

    @Test
    fun ceilingWinsEvenWhenBusy() {
        assertEquals(
            KimiAcpActivityTimeoutVerdict.Ceiling,
            kimiAcpActivityTimeoutVerdict(
                nowMillis = 3_600_000L,
                startedMillis = 0L,
                lastActivityMillis = 3_600_000L,
                idleMillis = 600_000L,
                ceilingMillis = 3_600_000L,
                busy = true,
            ),
        )
    }
}
