package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCatalogMcpTest {
    @Test
    fun acpServerIsLoopbackAndAlwaysNamedDeviceCatalog() {
        val server = DeviceCatalogMcp.toAcpServer()
        requireNotNull(server)
        assertEquals("device_catalog", server.getString("name"))
        assertEquals("http", server.getString("type"))
        assertTrue(server.getString("url").contains("/mcp/aether-device-catalog"))
    }

    @Test
    fun listToolsExposesReadOnlyListApps() {
        val tools = DeviceCatalogMcp.listToolsResult().getJSONArray("tools")
        assertEquals(1, tools.length())
        val tool = tools.getJSONObject(0)
        assertEquals("list_apps", tool.getString("name"))
        assertTrue(tool.getString("description").contains("Read-only"))
        assertTrue(tool.getString("description").contains("does not open a virtual display"))
        assertTrue(tool.getString("description").contains(".aether/device_apps.json"))
        val schema = tool.getJSONObject("inputSchema")
        assertTrue(schema.getJSONObject("properties").has("query"))
        assertTrue(schema.getJSONObject("properties").has("include_system"))
        assertTrue(schema.getJSONObject("properties").has("max_results"))
    }

    @Test
    fun wrapCallResultHasNoScreenshot() {
        val wrapped = DeviceCatalogMcp.wrapCallResult(
            org.json.JSONObject()
                .put("ok", true)
                .put("count", 1)
                .toString(),
        )
        assertFalse(wrapped.getBoolean("isError"))
        val text = wrapped.getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(text.contains("\"ok\":true") || text.contains("\"ok\": true"))
        assertFalse(text.contains("screenshot_base64"))
    }

    @Test
    fun matchesPrefixedMcpNames() {
        assertTrue(DeviceCatalogMcp.matchesToolName("list_apps"))
        assertTrue(DeviceCatalogMcp.matchesToolName("mcp__device_catalog__list_apps"))
        assertFalse(DeviceCatalogMcp.matchesToolName("agent_display"))
        assertFalse(DeviceCatalogMcp.matchesToolName("launch"))
    }
}
