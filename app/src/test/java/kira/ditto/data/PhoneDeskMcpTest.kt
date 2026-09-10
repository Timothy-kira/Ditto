package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDeskMcpTest {
    @Test
    fun acpServerUsesLoopbackHttpAndPhoneDeskName() {
        val server = PhoneDeskMcp.toAcpServer()
        requireNotNull(server)
        assertEquals("phone_desk", server.getString("name"))
        assertEquals("http", server.getString("type"))
        assertTrue(server.getString("url").contains("/mcp/aether-phone-desk"))
        assertEquals(PhoneDeskMcp.ToolTimeoutMillis, server.getLong("toolTimeoutMs"))
        assertTrue(server.getLong("toolTimeoutMs") > 60_000L)
    }

    @Test
    fun listToolsExposesDispatchOnly() {
        val tools = PhoneDeskMcp.listToolsResult().getJSONArray("tools")
        assertEquals(1, tools.length())
        val tool = tools.getJSONObject(0)
        assertEquals("phone_desk", tool.getString("name"))
        assertTrue(tool.getString("description").contains("operator"))
        assertTrue(tool.getString("description").contains("accept"))
        assertTrue(tool.getString("description").contains("several minutes"))
        assertTrue(tool.getString("description").contains("Do not think at length"))
        val schema = tool.getJSONObject("inputSchema")
        assertTrue(schema.getJSONObject("properties").has("task"))
        assertTrue(schema.getJSONObject("properties").has("sop_id"))
    }

    @Test
    fun wrapCallResultNeverIncludesScreenshotBytes() {
        val wrapped = PhoneDeskMcp.wrapCallResult(
            org.json.JSONObject()
                .put("ok", true)
                .put("report", "opened WeChat")
                .toString(),
        )
        assertFalse(wrapped.getBoolean("isError"))
        val text = wrapped.getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(text.contains("opened WeChat"))
        assertFalse(text.contains("screenshot_base64"))
        assertFalse(text.contains("dispatch again"))
    }

    @Test
    fun wrapCallResultTellsLeadToRedispatchOnError() {
        val wrapped = PhoneDeskMcp.wrapCallResult(
            org.json.JSONObject()
                .put("ok", false)
                .put("errmsg", "timeout")
                .toString(),
        )
        assertTrue(wrapped.getBoolean("isError"))
        val text = wrapped.getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(text.contains("dispatch again"))
        assertTrue(text.contains("Do not tell the user the tool timed out"))
    }

    @Test
    fun leadReminderDelegatesThroughKimiNativePhoneAgent() {
        assertTrue(AgentModeLeadReminder.contains("native Agent and AgentSwarm"))
        assertTrue(AgentModeLeadReminder.contains("subagent_type=\"browser\""))
        assertTrue(AgentModeLeadReminder.contains("mcp__webmcp__"))
        assertTrue(AgentModeLeadReminder.contains("<invoke>"))
        assertTrue(AgentModeLeadReminder.contains("multiple kinds"))
        assertFalse(AgentModeLeadReminder.contains("carousel"))
        assertTrue(AgentModeLeadReminder.contains("subagent_type=\"phone\""))
        assertTrue(AgentModeLeadReminder.contains("AgentSwarm"))
        assertTrue(AgentModeLeadReminder.contains("{{item}}"))
        assertTrue(AgentModeLeadReminder.contains("completed_this_turn"))
        assertTrue(AgentModeLeadReminder.contains("foreground execution"))
        assertTrue(AgentModeLeadReminder.contains("resume that same agent"))
        assertTrue(AgentModeLeadReminder.contains("at most twice"))
        assertTrue(AgentModeLeadReminder.contains("简体中文"))
        assertTrue(AgentModeLeadReminder.contains("Do not think at length"))
        assertTrue(AgentModeLeadReminder.contains("first action"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("mcp__agent_display__agent_display"))
        assertFalse(KimiPhoneSubagentProfileMarkdown.contains("Never call `mcp__webmcp__*`"))
        assertFalse(kimiAgentProfileAllowsTool(KimiPhoneSubagentProfileMarkdown, "mcp__webmcp__page_read"))
        assertFalse(kimiAgentProfileAllowsTool(KimiPhoneSubagentProfileMarkdown, "page_snapshot"))
        assertFalse(kimiAgentProfileAllowsTool(KimiPhoneSubagentProfileMarkdown, "tabs_navigate"))
        assertTrue(kimiAgentProfileAllowsTool(KimiPhoneSubagentProfileMarkdown, "mcp__agent_display__agent_display"))
        assertTrue(kimiAgentProfileAllowsTool(KimiPhoneSubagentProfileMarkdown, "mcp__phone_app__recall_gui_flows"))
        assertTrue(kimiAgentProfileAllowsTool(KimiImageSubagentProfileMarkdown, "mcp__webmcp__search_images"))
        assertFalse(kimiAgentProfileAllowsTool(KimiImageSubagentProfileMarkdown, "mcp__webmcp__page_read"))
        assertFalse(kimiAgentProfileAllowsTool(KimiImageSubagentProfileMarkdown, "tabs_navigate"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("mcp__phone_app__*"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("GUI_TASK_SUCCEEDED:"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("observe=true"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("actions"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("undo"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("list_targets"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("simplify=true"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("GUI_TASK_NEEDS_TEACHING:"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("request_teaching"))
        assertTrue(AgentModeLeadReminder.contains("mem_search"))
        assertTrue(AgentModeLeadReminder.contains("run_gui_flow"))
        assertTrue(AgentModeLeadReminder.contains("never a scene-"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("dump_tree"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("click_node"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("swipe_left"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("IME search"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("host Ditto package"))
        assertTrue(AgentModeLeadReminder.contains("virtual screen"))
        assertFalse(KimiPhoneSubagentProfileMarkdown.contains("phone_desk"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("GUI_TASK_WATCHING:"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("listen_start"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("listen_start before tapping"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("listen_status"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("ASR_CAPTURE_UNAVAILABLE"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("visual_only"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("简介"))
        assertFalse(KimiPhoneSubagentProfileMarkdown.contains("Listening is optional"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("dump_tree"))
        assertTrue(AgentModeLeadReminder.contains("listen_status"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("persist_path"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("workspace_path"))
        assertTrue(AgentModeLeadReminder.contains("GUI_TASK_WATCHING"))
        assertTrue(AgentModeLeadReminder.contains("先盯着课"))
        assertTrue(AgentModeLeadReminder.contains("测验出现再处理"))
        assertTrue(AgentModeLeadReminder.contains("NOT success"))
        assertFalse(AgentModeLeadReminder.contains("CronCreate"))
        assertFalse(AgentModeLeadReminder.contains("CronDelete"))
        assertTrue(AgentModeLeadReminder.contains("never tap launcher"))
        assertTrue(AgentModeLeadReminder.contains("门票"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("Never open an app by tapping home-screen"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("美团 or 携程"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("DISPLAY_BUSY"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("CAPTURE_TIMEOUT"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("SCENE_NOT_SOP"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("replan"))
        assertTrue(KimiPhoneSubagentProfileMarkdown.contains("MCP server died"))
        assertFalse(KimiPhoneSubagentProfileMarkdown.contains("MCP backend is unavailable"))
        assertFalse(KimiPhoneSubagentProfileMarkdown.contains("wait for the service to recover"))
        assertFalse(KimiPhoneSubagentProfileMarkdown.contains("degrades to `agent_display`"))
        assertFalse(KimiPhoneSubagentProfileMarkdown.contains("CronCreate"))
        assertFalse(KimiPhoneSubagentProfileMarkdown.contains("CronDelete"))
    }
}
