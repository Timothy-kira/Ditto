package kira.ditto.agentmode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuStartPlannerTest {
    @Test
    fun doesNotPromptWhenSilentStartAlreadyWorked() {
        assertFalse(
            ShizukuStartPlanner.shouldPromptWirelessPairing(
                binderReady = false,
                silentStartSucceeded = true,
                adbWifiEnabled = true,
                pairingPort = 37123,
            ),
        )
        assertFalse(
            ShizukuStartPlanner.shouldPromptWirelessPairing(
                binderReady = true,
                silentStartSucceeded = false,
                adbWifiEnabled = true,
                pairingPort = 0,
            ),
        )
    }

    @Test
    fun promptsOnlyWhenSystemExposesWirelessDebugging() {
        assertTrue(
            ShizukuStartPlanner.shouldPromptWirelessPairing(
                binderReady = false,
                silentStartSucceeded = false,
                adbWifiEnabled = true,
                pairingPort = 0,
            ),
        )
        assertTrue(
            ShizukuStartPlanner.shouldPromptWirelessPairing(
                binderReady = false,
                silentStartSucceeded = false,
                adbWifiEnabled = false,
                pairingPort = 37123,
            ),
        )
        assertFalse(
            ShizukuStartPlanner.shouldPromptWirelessPairing(
                binderReady = false,
                silentStartSucceeded = false,
                adbWifiEnabled = false,
                pairingPort = 0,
            ),
        )
    }
}
