package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnActivityClockTest {
    @Test
    fun noPauseCountsFullWallClock() {
        val clock = TurnActivityClock()
        assertEquals(5_000L, clock.activeElapsedMillis(startedAtMillis = 10_000L, nowMillis = 15_000L))
        assertFalse(clock.isPaused)
    }

    @Test
    fun pauseFreezesElapsedWhileWaiting() {
        val clock = TurnActivityClock().pause(nowMillis = 12_000L)
        assertTrue(clock.isPaused)
        // 2s of active work before the wait started; the wait itself adds nothing.
        assertEquals(2_000L, clock.activeElapsedMillis(startedAtMillis = 10_000L, nowMillis = 12_000L))
        assertEquals(2_000L, clock.activeElapsedMillis(startedAtMillis = 10_000L, nowMillis = 30_000L))
    }

    @Test
    fun resumeAccumulatesPausedSegmentAndContinues() {
        val clock = TurnActivityClock()
            .pause(nowMillis = 12_000L)
            .resume(nowMillis = 20_000L)
        assertFalse(clock.isPaused)
        assertEquals(8_000L, clock.pausedMillis)
        // Wall clock 15s, minus 8s waiting = 7s active.
        assertEquals(7_000L, clock.activeElapsedMillis(startedAtMillis = 10_000L, nowMillis = 25_000L))
    }

    @Test
    fun repeatedPauseResumeCyclesAccumulate() {
        val clock = TurnActivityClock()
            .pause(12_000L).resume(14_000L)
            .pause(18_000L).resume(23_000L)
        assertEquals(7_000L, clock.pausedMillis)
        assertEquals(13_000L, clock.activeElapsedMillis(startedAtMillis = 10_000L, nowMillis = 30_000L))
    }

    @Test
    fun doublePauseKeepsOriginalAnchor() {
        val clock = TurnActivityClock().pause(12_000L).pause(16_000L)
        assertEquals(12_000L, clock.pauseStartedAtMillis)
        assertEquals(2_000L, clock.activeElapsedMillis(startedAtMillis = 10_000L, nowMillis = 20_000L))
    }

    @Test
    fun resumeWithoutPauseIsNoOp() {
        val clock = TurnActivityClock().resume(12_000L)
        assertEquals(TurnActivityClock(), clock)
    }

    @Test
    fun elapsedNeverGoesNegative() {
        val clock = TurnActivityClock(pausedMillis = 60_000L)
        assertEquals(0L, clock.activeElapsedMillis(startedAtMillis = 10_000L, nowMillis = 15_000L))
    }
}
