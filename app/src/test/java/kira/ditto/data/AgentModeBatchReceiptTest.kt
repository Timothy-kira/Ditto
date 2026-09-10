package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-3 / P1 / P2 / P3 pure-logic tests. AgentModeController.execute() needs an
 * instrumented context, so the batch receipt and failure-signal contracts are
 * asserted against the JSON shape the controller produces.
 */
class AgentModeBatchReceiptTest {
    @Test
    fun batchFailureShortCircuitsWithPipelineReceiptShape() {
        val failed = JSONObject()
            .put("ok", false)
            .put("code", AgentModeSafety.StalledCode)
            .put("completed_steps", JSONArray().put(JSONObject().put("index", 0).put("action", "tap").put("ok", true)))
            .put("failed_step", 1)
            .put("failed_action", "click_node")
            .put("consistent", "no")
            .put("degraded_to", AgentModeSafety.ReplanDegradedTo)
        assertEquals("no", failed.getString("consistent"))
        assertEquals("replan", failed.getString("degraded_to"))
        assertEquals(1, failed.getInt("failed_step"))
        assertEquals(1, failed.getJSONArray("completed_steps").length())
    }

    @Test
    fun failureSignalAttachesTypedRepairHint() {
        val snapshot = AgentModePortal.buildSnapshot(
            displayId = 1,
            width = 1080,
            height = 2400,
            packageName = "com.example.app",
            nodes = listOf(
                AgentModeUiTree.RichNode(
                    index = 0, label = "搜索", text = "搜索", desc = "", hint = "",
                    klass = "TextView", id = "id_0", clickable = true, longClickable = false,
                    checkable = false, checked = false, selected = false, enabled = true,
                    scrollable = false, focused = false, editable = false, left = 10,
                    top = 20, right = 90, bottom = 80, parent = -1, depth = 1,
                    drawingOrder = 0, password = false, packageName = "com.example.app",
                ),
            ),
        )
        val result = AgentModePortal.attachReceipt(
            result = JSONObject().put("ok", false).put("code", AgentModeSafety.StalledCode),
            snapshot = snapshot,
            previous = snapshot,
        )
        val failure = result.getJSONObject("failure")
        assertEquals(AgentModeSafety.StalledCode, failure.getString("code"))
        assertEquals("change_input_method", failure.getString("repair_strategy"))
        assertEquals("error", failure.getString("severity"))
        assertFalse(failure.getBoolean("retryable"))
    }

    @Test
    fun sopNotReadySuggestsL0Fallback() {
        val result = JSONObject()
            .put("ok", false)
            .put("code", "SOP_NOT_READY")
            .put("suggested_mode", "l0")
        assertEquals("l0", result.getString("suggested_mode"))
    }

    @Test
    fun structureBlindDetectionTriggersOnSurfaceOnlyPage() {
        val surface = AgentModeUiTree.RichNode(
            index = 0, label = "Surface", text = "", desc = "", hint = "",
            klass = "android.view.SurfaceView", id = "", clickable = false, longClickable = false,
            checkable = false, checked = false, selected = false, enabled = true,
            scrollable = false, focused = false, editable = false, left = 0,
            top = 0, right = 1000, bottom = 1000, parent = -1, depth = 0,
            drawingOrder = 0, password = false, packageName = "com.game.app",
        )
        assertTrue(AgentModeUiTree.isStructureBlind(listOf(surface)))
        val normal = AgentModeUiTree.RichNode(
            index = 1, label = "搜索", text = "搜索", desc = "", hint = "",
            klass = "TextView", id = "id_1", clickable = true, longClickable = false,
            checkable = false, checked = false, selected = false, enabled = true,
            scrollable = false, focused = false, editable = false, left = 10,
            top = 20, right = 90, bottom = 80, parent = -1, depth = 1,
            drawingOrder = 0, password = false, packageName = "com.example.app",
        )
        assertFalse(AgentModeUiTree.isStructureBlind(listOf(surface, normal, normal, normal, normal)))
    }
}
