package kira.ditto.browser

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class BrowserFetchLimitsTest {
    @Test fun concurrentBatchesShareTheHostLimit() {
        val limits = BrowserFetchLimits(8, 2)
        val pool = Executors.newFixedThreadPool(8)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        try {
            val jobs = (1..8).map {
                pool.submit<Int?> {
                    limits.withPermit("https://example.org/$it", 5000) {
                        val count = active.incrementAndGet()
                        peak.accumulateAndGet(count, ::maxOf)
                        entered.countDown()
                        try { release.await(3, TimeUnit.SECONDS); 1 }
                        finally { active.decrementAndGet() }
                    }
                }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(2, active.get())
            release.countDown()
            jobs.forEach { assertEquals(1, it.get(5, TimeUnit.SECONDS)) }
            assertEquals(2, peak.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun exceptionReleasesBothPermits() {
        val limits = BrowserFetchLimits(1, 1)
        runCatching { limits.withPermit("https://example.org", 100) { error("failure") } }
        assertEquals("ok", limits.withPermit("https://example.org", 100) { "ok" })
    }
}
