package kira.ditto.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrowserDeskTaskTest {
    @Before
    fun reset() {
        BrowserDesk.clear()
        BrowserDesk.clearReadLedger()
    }

    @Test
    fun beginAndCompleteWriteReceiptsOntoTheDesk() {
        val id = BrowserDesk.begin("page_form", JSONObject().put("action", "fill"))
        assertEquals(1, BrowserDesk.listTasks().length())
        assertEquals("running", BrowserDesk.listTasks().getJSONObject(0).getString("state"))
        BrowserDesk.complete(
            id,
            "page_form",
            JSONObject().put("ok", false).put("code", "input_required").put("user_takeover", true).toString(),
        )
        val task = BrowserDesk.listTasks().getJSONObject(0)
        assertEquals("waiting_user", task.getString("state"))
        assertEquals("input_required", task.getString("reason"))
        assertEquals(BrowserTaskState.WaitingUser, replayBrowserDesk(BrowserDesk.eventLog()).tasks.single().state)
    }

    @Test
    fun failMarksNeedsVerification() {
        val id = BrowserDesk.begin("page_click", JSONObject())
        BrowserDesk.fail(id)
        assertEquals("needs_verification", BrowserDesk.listTasks().getJSONObject(0).getString("state"))
        assertTrue(BrowserDesk.taskEvents(0).length() >= 2)
    }
}
