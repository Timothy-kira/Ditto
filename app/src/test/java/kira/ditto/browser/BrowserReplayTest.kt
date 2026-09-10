package kira.ditto.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserReplayTest {
    @Test fun lostMutationResponseMustNotReplay() {
        listOf("execute_capability", "form_submit", "click", "js", "webmcp_call", "batch").forEach {
            assertFalse(it, browserPageOperationCanReplay(it))
        }
    }
    @Test fun observationCanReconnect() {
        listOf("snapshot", "read", "capabilities", "form_list").forEach {
            assertTrue(it, browserPageOperationCanReplay(it))
        }
    }
}
