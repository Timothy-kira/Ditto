package kira.ditto.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUiMcpTest {
    @Test
    fun acpServerIsLoopbackButNotForTheLeadSession() {
        val server = PhoneUiMcp.toAcpServer()
        requireNotNull(server)
        assertEquals("phone_ui", server.getString("name"))
        assertTrue(server.getString("url").contains("/mcp/aether-phone-ui"))
    }

    @Test
    fun listToolsExposesOpenTypeAndClearOnly() {
        val tools = PhoneUiMcp.listToolsResult().getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertEquals(listOf("open_app", "type_text", "clear_text", "undo"), names)
    }

    @Test
    fun mapsOperatorToolNamesOntoExecuteActions() {
        val open = PhoneUiMcp.toExecuteArguments(
            "open_app",
            JSONObject().put("target", "com.tencent.mm"),
        )
        assertEquals("launch", open.getString("action"))
        assertEquals("com.tencent.mm", open.getString("target"))
        assertTrue(open.getBoolean("skip_capture"))

        val type = PhoneUiMcp.toExecuteArguments(
            "type_text",
            JSONObject().put("text", "你好"),
        )
        assertEquals("text", type.getString("action"))
        assertEquals("你好", type.getString("text"))

        val clear = PhoneUiMcp.toExecuteArguments("clear_text", JSONObject())
        assertEquals("clear_text", clear.getString("action"))
        assertTrue(clear.getBoolean("skip_capture"))
        val undo = PhoneUiMcp.toExecuteArguments("undo", JSONObject())
        assertEquals("undo", undo.getString("action"))
        assertTrue(undo.getBoolean("skip_capture"))
        assertEquals("launch", PhoneUiMcp.normalizeAction("open_app"))
        assertEquals("text", PhoneUiMcp.normalizeAction("type_text"))
        assertEquals("clear_text", PhoneUiMcp.normalizeAction("clear_text"))
        assertEquals("undo", PhoneUiMcp.normalizeAction("undo"))
        assertEquals("tap", PhoneUiMcp.normalizeAction("tap"))
        assertEquals("search", PhoneUiMcp.normalizeAction("search"))
        assertEquals("search", PhoneUiMcp.normalizeAction("submit_search"))
        assertEquals("search", PhoneUiMcp.normalizeAction("ime_enter"))
        assertEquals("swipe_left", PhoneUiMcp.normalizeAction("swipe_left"))
        assertEquals("swipe_left", PhoneUiMcp.normalizeAction("left_swipe"))
        assertEquals("swipe_right", PhoneUiMcp.normalizeAction("right-swipe"))
        assertEquals("swipe_up", PhoneUiMcp.normalizeAction("swipe_up"))
        assertEquals("swipe_down", PhoneUiMcp.normalizeAction("down_swipe"))
    }

    @Test
    fun wrapCallResultStripsScreenshotBytes() {
        val wrapped = PhoneUiMcp.wrapCallResult(
            JSONObject()
                .put("ok", true)
                .put("screenshot_base64", "abc123")
                .toString(),
        )
        assertFalse(wrapped.getBoolean("isError"))
        val text = wrapped.getJSONArray("content").getJSONObject(0).getString("text")
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("screenshot_base64"))
    }
}
