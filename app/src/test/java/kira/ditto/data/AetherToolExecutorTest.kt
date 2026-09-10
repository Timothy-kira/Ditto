package kira.ditto.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherToolExecutorTest {
    @Test
    fun hostToolDefinitionsDoNotDuplicatePiNativeTools() {
        val definitions = AetherToolExecutor.hostToolDefinitions()
        assertEquals(0, definitions.length())
    }

    @Test
    fun sanitizeAgentDisplayOutputRemovesScreenshotBytes() {
        val sanitized = AetherToolExecutor.sanitizeToolOutputForConversation(
            toolName = "agent_display",
            output = JSONObject().apply {
                put("ok", true)
                put("screenshot_base64", "abc123")
                put("screenshot_mime_type", "image/png")
            }.toString(),
        )

        val json = JSONObject(sanitized)
        assertFalse(json.has("screenshot_base64"))
        assertFalse(json.has("screenshot_injected_into_next_model_request"))
        assertEquals("image/png", json.getString("screenshot_mime_type"))
    }

    @Test
    fun dynamicHostToolDefinitionsExcludeMcpChromeAndAgentDisplay() {
        val definitions = AetherToolExecutor.hostToolDefinitions(
            selfManagementTool = null,
            agentModeEnabled = true,
        )
        val names = (0 until definitions.length())
            .map { definitions.getJSONObject(it).getString("name") }

        assertFalse("mcp_list_tools" in names)
        assertFalse("mcp__docs__search" in names)
        assertFalse("agent_display" in names)
        assertFalse("chrome" in names)
        assertFalse("open_app" in names)
        assertFalse("type_text" in names)
        assertFalse("clear_text" in names)
    }

    @Test
    fun inferToolOutputOkHonorsAetherJsonFlags() {
        assertTrue(AetherToolExecutor.inferToolOutputOk("""{"ok":true}"""))
        assertFalse(AetherToolExecutor.inferToolOutputOk("""{"ok":false}"""))
        assertFalse(AetherToolExecutor.inferToolOutputOk("""{"err":true}"""))
        assertTrue(AetherToolExecutor.inferToolOutputOk("plain text"))
        assertTrue(
            AetherToolExecutor.inferToolOutputOk(
                """{"ok":false,"code":"ASR_CAPTURE_UNAVAILABLE","visual_only":true}""",
            ),
        )
    }

    @Test
    fun workspaceFileRoutingRecognizesAlpineAndTermuxRoots() {
        assertEquals(
            LocalRuntimeId.Alpine,
            resolveWorkspaceRuntimeId(
                path = "/workspace/agent-mode/capture.png",
                workingDirectory = "",
                defaultRuntimeId = LocalRuntimeId.Termux,
            ),
        )
        assertEquals(
            LocalRuntimeId.Termux,
            resolveWorkspaceRuntimeId(
                path = "/data/data/com.termux/files/home/.aether/workspace/uploads/image.png",
                workingDirectory = "",
                defaultRuntimeId = LocalRuntimeId.Alpine,
            ),
        )
        assertEquals(
            LocalRuntimeId.Alpine,
            resolveWorkspaceRuntimeId(
                path = "relative.png",
                workingDirectory = "/workspace",
                defaultRuntimeId = LocalRuntimeId.Termux,
            ),
        )
        assertEquals(
            LocalRuntimeId.Termux,
            resolveWorkspaceRuntimeId(
                path = "/data/data/com.termux/files/home/.aether/workspace/output.png",
                workingDirectory = "/workspace",
                defaultRuntimeId = LocalRuntimeId.Alpine,
            ),
        )
        assertEquals(
            LocalRuntimeId.Alpine,
            resolveWorkspaceRuntimeId(
                path = "file:///workspace/agent-mode/capture.png",
                workingDirectory = "/data/data/com.termux/files/home/.aether/workspace",
                defaultRuntimeId = LocalRuntimeId.Termux,
            ),
        )
    }

}
