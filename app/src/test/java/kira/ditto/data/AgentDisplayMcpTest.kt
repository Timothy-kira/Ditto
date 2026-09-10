package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDisplayMcpTest {
    @Test
    fun acpServerUsesLoopbackHttpAndAgentDisplayName() {
        val server = AgentDisplayMcp.toAcpServer()
        requireNotNull(server)
        assertEquals("agent_display", server.getString("name"))
        assertEquals("http", server.getString("type"))
        assertEquals(AgentDisplayMcp.httpUrl(), server.getString("url"))
        assertTrue(server.getString("url").contains("/mcp/aether-agent-display"))
    }

    @Test
    fun listToolsExposesAgentDisplayForRealInstalledApps() {
        val tools = AgentDisplayMcp.listToolsResult().getJSONArray("tools")
        assertEquals(1, tools.length())
        val tool = tools.getJSONObject(0)
        assertEquals("agent_display", tool.getString("name"))
        assertTrue(tool.getString("description").contains("installed on this Android phone"))
        assertTrue(tool.getString("description").contains("SHIZUKU_NOT_RUNNING"))
        val schema = tool.getJSONObject("inputSchema")
        assertEquals("action", schema.getJSONArray("required").getString(0))
        assertTrue(
            schema.getJSONObject("properties")
                .getJSONObject("action")
                .getString("description")
                .contains("clear_text"),
        )
        assertTrue(schema.getJSONObject("properties").has("target"))
        assertTrue(schema.getJSONObject("properties").has("actions"))
        assertTrue(schema.getJSONObject("properties").has("observe"))
        assertTrue(
            schema.getJSONObject("properties")
                .getJSONObject("action")
                .getString("description")
                .contains("list_targets"),
        )
        assertTrue(
            schema.getJSONObject("properties")
                .getJSONObject("action")
                .getString("description")
                .contains("undo"),
        )
        assertTrue(schema.getJSONObject("properties").has("simplify"))
        assertTrue(
            schema.getJSONObject("properties")
                .getJSONObject("action")
                .getString("description")
                .contains("dump_tree"),
        )
        assertTrue(schema.getJSONObject("properties").has("region"))
        assertTrue(schema.getJSONObject("properties").has("crop_region"))
        assertTrue(schema.getJSONObject("properties").has("persist"))
        assertTrue(schema.getJSONObject("properties").has("persist_path"))
        assertTrue(
            schema.getJSONObject("properties")
                .getJSONObject("observe")
                .getString("description")
                .contains("som"),
        )
        assertTrue(
            schema.getJSONObject("properties")
                .getJSONObject("action")
                .getString("description")
                .contains("listen_start"),
        )
        assertTrue(schema.getJSONObject("properties").has("duration_sec"))
        assertTrue(schema.getJSONObject("properties").has("language"))
        assertTrue(tool.getString("description").contains("listen_start"))
        assertTrue(tool.getString("description").contains("listen_start before tapping"))
        assertTrue(tool.getString("description").contains("supplement"))
        assertFalse(tool.getString("description").contains("Audio is optional"))
        assertTrue(
            schema.getJSONObject("properties")
                .getJSONObject("action")
                .getString("description")
                .contains("listen_start before tapping"),
        )
        assertTrue(tool.getString("description").contains("ASR_CAPTURE_UNAVAILABLE"))
        assertTrue(tool.getString("description").contains("visual_only"))
        assertTrue(tool.getString("description").contains("dump_tree"))
        assertTrue(tool.getString("description").contains("observe=som"))
        assertTrue(
            schema.getJSONObject("properties")
                .getJSONObject("action")
                .getString("description")
                .contains("visual_only"),
        )
    }

    @Test
    fun wrapCallResultReturnsScreenshotAsMcpImageWithoutLeakingItIntoText() {
        val wrapped = AgentDisplayMcp.wrapCallResult(
            org.json.JSONObject()
                .put("ok", true)
                .put("screenshot_base64", "abc123")
                .toString(),
        )
        assertFalse(wrapped.getBoolean("isError"))
        val text = wrapped.getJSONArray("content").getJSONObject(0).getString("text")
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("screenshot_base64"))
        val image = wrapped.getJSONArray("content").getJSONObject(1)
        assertEquals("image", image.getString("type"))
        assertEquals("abc123", image.getString("data"))
        assertEquals("image/jpeg", image.getString("mimeType"))
        val structured = wrapped.getJSONObject("structuredContent")
        assertFalse(structured.has("screenshot_base64"))
        assertFalse(structured.optString("screenshot_base64").isNotEmpty())
    }
}
