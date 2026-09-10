package kira.ditto.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeDisplayGateTest {
    @Test
    fun launchContentionReturnsDisplayBusyWithoutRunningTheBlock() {
        val gate = AgentModeDisplayGate()
        val started = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val busyCalls = AtomicInteger(0)
        val worker = Thread {
            gate.runExclusive(treatContentionAsBusy = false) {
                started.countDown()
                hold.await(2, TimeUnit.SECONDS)
                """{"ok":true}"""
            }
        }
        worker.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val busy = gate.runExclusive(treatContentionAsBusy = true) {
            busyCalls.incrementAndGet()
            """{"ok":true}"""
        }
        hold.countDown()
        worker.join(2_000)
        assertEquals(0, busyCalls.get())
        assertTrue(busy.contains(AgentModeDisplayGate.DisplayBusyCode))
        assertFalse(gate.isLocked())
    }

    @Test
    fun nonLaunchReturnsDisplayBusyWithoutWaiting() {
        val gate = AgentModeDisplayGate()
        val started = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val busyCalls = AtomicInteger(0)
        val worker = Thread {
            gate.runExclusive(treatContentionAsBusy = false) {
                started.countDown()
                hold.await(2, TimeUnit.SECONDS)
                """{"ok":true}"""
            }
        }
        worker.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val busy = gate.runExclusive(treatContentionAsBusy = true) {
            busyCalls.incrementAndGet()
            """{"ok":true}"""
        }
        hold.countDown()
        worker.join(2_000)
        assertEquals(0, busyCalls.get())
        assertTrue(busy.contains(AgentModeDisplayGate.DisplayBusyCode))
        assertTrue(busy.contains(AgentModeSafety.ReplanDegradedTo))
        assertFalse(gate.isLocked())
    }
}
