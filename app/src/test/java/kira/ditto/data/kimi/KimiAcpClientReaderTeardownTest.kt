package kira.ditto.data.kimi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KimiAcpClientReaderTeardownTest {
    @Test
    fun deadProcessIsExpectedTeardown() {
        assertTrue(
            isExpectedReaderTeardown(
                processAlive = false,
                coroutineActive = true,
                currentGeneration = 3L,
                readerGeneration = 3L,
            ),
        )
    }

    @Test
    fun cancelledReaderIsExpectedTeardown() {
        assertTrue(
            isExpectedReaderTeardown(
                processAlive = true,
                coroutineActive = false,
                currentGeneration = 3L,
                readerGeneration = 3L,
            ),
        )
    }

    @Test
    fun replacedGenerationIsExpectedTeardown() {
        assertTrue(
            isExpectedReaderTeardown(
                processAlive = true,
                coroutineActive = true,
                currentGeneration = 4L,
                readerGeneration = 3L,
            ),
        )
        assertTrue(
            isExpectedReaderTeardown(
                processAlive = true,
                coroutineActive = true,
                currentGeneration = null,
                readerGeneration = 3L,
            ),
        )
    }

    @Test
    fun liveProcessSameGenerationIsRealError() {
        assertFalse(
            isExpectedReaderTeardown(
                processAlive = true,
                coroutineActive = true,
                currentGeneration = 3L,
                readerGeneration = 3L,
            ),
        )
    }
}
