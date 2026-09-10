package kira.ditto.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the classes and methods touched by the journeys we care about most:
 * cold start to first frame, opening the conversation drawer, scrolling a long
 * transcript, and the Chat <-> Settings round trip.
 *
 * Run with `gradlew :baselineprofile:generateBaselineProfile` on the API 34 managed
 * device; the physical API 31 handset can only consume the result, not produce it.
 */
@RunWith(AndroidJUnit4::class)
class StartupBaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle(FIRST_FRAME_SETTLE_MS)

        toggleConversationDrawer()
        scrollTranscript()
        visitSettingsAndBack()
    }

    private fun MacrobenchmarkScope.toggleConversationDrawer() {
        val width = device.displayWidth
        val height = device.displayHeight
        device.swipe(2, height / 2, width * 3 / 4, height / 2, 12)
        device.waitForIdle(SETTLE_MS)
        device.pressBack()
        device.waitForIdle(SETTLE_MS)
    }

    private fun MacrobenchmarkScope.scrollTranscript() {
        val list = device.findObject(By.scrollable(true)) ?: return
        list.setGestureMargin(device.displayWidth / 5)
        repeat(20) {
            list.fling(Direction.DOWN)
            device.waitForIdle(SETTLE_MS)
        }
        repeat(10) {
            list.fling(Direction.UP)
            device.waitForIdle(SETTLE_MS)
        }
    }

    private fun MacrobenchmarkScope.visitSettingsAndBack() {
        val settings = device.findObject(By.descContains("Settings"))
            ?: device.findObject(By.descContains("设置"))
            ?: return
        settings.click()
        device.wait(Until.hasObject(By.scrollable(true)), SETTLE_MS)
        repeat(4) {
            device.findObject(By.scrollable(true))?.fling(Direction.DOWN)
            device.waitForIdle(SETTLE_MS)
        }
        device.pressBack()
        device.waitForIdle(SETTLE_MS)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.kira.ditto"
        const val FIRST_FRAME_SETTLE_MS = 3_000L
        const val SETTLE_MS = 1_500L
    }
}
