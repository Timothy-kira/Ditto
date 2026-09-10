package kira.ditto.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpineChromeProfileTest {
    @Test
    fun chromePresetIsRemovedFromAlpinePackages() {
        assertFalse(AlpineRuntime.AlpinePackageProfiles.containsKey("chrome"))
        assertTrue(AlpineRuntime.AlpinePackageProfiles.containsKey("python"))
        assertTrue(AlpineRuntime.AlpinePackageProfiles.containsKey("node"))
        assertTrue(AlpineRuntime.AlpinePackageProfiles.containsKey("git_search"))
        assertTrue(AlpineRuntime.AlpinePackageProfiles.containsKey("ssh"))
    }

    @Test
    fun apkInstallOutputReportsPercentageAcrossChunks() {
        val tracker = AlpinePackageInstallProgressTracker()

        tracker.onOutput("(7/32) Instal")
        val progress = tracker.onOutput("ling python (1.2.3-r0)\n")

        assertEquals(AlpineSetupActivity.Installing, progress.activity)
        assertEquals(21, progress.progressPercent)
    }

    @Test
    fun downloadRateRemainsVisibleBeforePackageInstallationStarts() {
        val tracker = AlpinePackageInstallProgressTracker()

        val progress = tracker.onRate(6L * 1024L * 1024L)

        assertEquals(AlpineSetupActivity.Downloading, progress.activity)
        assertEquals(6L * 1024L * 1024L, progress.bytesPerSecond)
    }

    @Test
    fun waitingTurnTimesOutBusyWhileMainHoldsGate() = runBlocking {
        val gate = KimiTurnGate()
        val mainStarted = CompletableDeferred<Unit>()
        val releaseMain = CompletableDeferred<Unit>()
        val main = async {
            gate.runTurn(waitTimeoutMillis = 5_000, onBusy = { "unexpected" }) {
                mainStarted.complete(Unit)
                releaseMain.await()
                "main"
            }
        }

        mainStarted.await()
        val busy = gate.runTurn(waitTimeoutMillis = 1, onBusy = { "busy" }) { "other" }
        releaseMain.complete(Unit)

        assertEquals("busy", busy)
        assertEquals("main", main.await())
    }
}
