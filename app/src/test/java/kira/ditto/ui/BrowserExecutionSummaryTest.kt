package kira.ditto.ui

import kira.ditto.browser.BrowserDeskState
import kira.ditto.browser.BrowserDeskTask
import kira.ditto.browser.BrowserTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserExecutionSummaryTest {
    @Test
    fun takeoverIsWaitingUserEvenWithoutAReceipt() {
        val state = BrowserDeskState(userTakeover = true, uiPreviewTopicId = "alpha")
        assertEquals(BrowserTaskState.WaitingUser, browserExecutionStatus(state, "alpha"))
        assertNull(browserExecutionStatus(state, "beta"))
    }

    @Test
    fun latestDeskTaskDrivesTheLabel() {
        val state = BrowserDeskState(
            tasks = listOf(
                BrowserDeskTask(
                    id = "a",
                    topic = "alpha",
                    state = BrowserTaskState.Completed,
                    updatedAtMillis = 1,
                ),
                BrowserDeskTask(
                    id = "b",
                    topic = "beta",
                    state = BrowserTaskState.NeedsVerification,
                    updatedAtMillis = 2,
                ),
            ),
        )
        assertEquals(BrowserTaskState.Completed, browserExecutionStatus(state, "alpha"))
        assertEquals(BrowserTaskState.NeedsVerification, browserExecutionStatus(state, "beta"))
        assertNull(browserExecutionStatus(BrowserDeskState(), "alpha"))
    }
}
