package kira.ditto.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarqueeTextTest {
    @Test
    fun overflowIsZeroWhenContentFitsContainer() {
        assertEquals(0, marqueeOverflowPx(contentWidthPx = 100, containerWidthPx = 100))
        assertEquals(0, marqueeOverflowPx(contentWidthPx = 80, containerWidthPx = 100))
        assertEquals(0, marqueeOverflowPx(contentWidthPx = 0, containerWidthPx = 0))
    }

    @Test
    fun overflowIsTheExceededPixelsWhenContentIsWider() {
        assertEquals(40, marqueeOverflowPx(contentWidthPx = 140, containerWidthPx = 100))
        assertEquals(1, marqueeOverflowPx(contentWidthPx = 101, containerWidthPx = 100))
    }

    @Test
    fun scrollDurationIsZeroWithoutDistance() {
        assertEquals(0, marqueeScrollDurationMillis(0))
        assertEquals(0, marqueeScrollDurationMillis(-10))
    }

    @Test
    fun scrollDurationHasLowerBoundForShortDistances() {
        // 5px at 48px/s would be ~104ms, clamped up to the 600ms minimum.
        assertEquals(600, marqueeScrollDurationMillis(5))
        assertEquals(600, marqueeScrollDurationMillis(28))
    }

    @Test
    fun scrollDurationGrowsLinearlyWithDistance() {
        val short = marqueeScrollDurationMillis(480)
        val long = marqueeScrollDurationMillis(960)
        assertEquals(10_000, short)
        assertEquals(20_000, long)
        assertTrue(long > short)
    }

    @Test
    fun scrollDurationRespectsCustomSpeed() {
        assertEquals(5_000, marqueeScrollDurationMillis(500, speedPxPerSecond = 100f))
    }
}
