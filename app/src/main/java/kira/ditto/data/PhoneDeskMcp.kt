package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Retired lead-facing MCP. Native kimi-code `Agent(subagent_type=phone)` plus
 * `agent_display` / `phone_app` replaced this path. It is not attached to ACP
 * sessions and is no longer served on the loopback gateway.
 */
internal object PhoneDeskMcp {
    const val PluginId = "aether-phone-desk"
    const val ToolName = "phone_desk"
    const val ServerName = "phone_desk"
    const val ToolTimeoutMillis = 15 * 60_000L

    const val ToolDescription =
        "Assign the user's full phone-UI goal to the on-device operator. " +
            "Do not tap, swipe, screenshot, type, open apps, or clear text yourself. " +
            "Your first action this turn must be dispatch with the complete task, " +
            "e.g. \"open WeChat and send hi to Mom\". Do not think at length first. " +
            "This call can take several minutes; wait for the operator's single report. " +
            "You only accept that final report. Do not split the goal into per-tap nodes. " +
            "If this tool errors or times out, dispatch the same task again immediately. " +
            "Never tell the user to open an app or search by hand. " +
            "Pass sop_id when a previous report included one. Use action=save_sop to freeze a frequent flow."

    fun httpUrl(): String = upaMcpHttpUrl(PluginId)

    fun mcpServerConfig(): McpServerConfig = McpServerConfig(
        id = PluginId,
        displayName = ServerName,
        transport = McpTransportConfig.StreamableHttp(url = httpUrl()),
        isEnabled = true,
        requestTimeoutMillis = ToolTimeoutMillis,
    )

    fun toAcpServer(): JSONObject? = mcpServerConfig().toAcpMcpServer()?.apply {
        put("toolTimeoutMs", ToolTimeoutMillis)
    }

    fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", UpaMcpProtocolVersion)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put(
            "serverInfo",
            JSONObject()
                .put("name", ServerName)
                .put("version", "1"),
        )

    fun listToolsResult(): JSONObject = JSONObject().put(
        "tools",
        JSONArray().put(
            JSONObject()
                .put("name", ToolName)
                .put("description", ToolDescription)
                .put("inputSchema", inputSchema()),
        ),
    )

    fun wrapCallResult(rawOutput: String): JSONObject {
        val parsed = runCatching { JSONObject(rawOutput) }.getOrNull()
        parsed?.remove("screenshot_base64")
        val isError = parsed?.optBoolean("ok", true) == false
        val visible = buildString {
            append(parsed?.toString() ?: rawOutput)
            if (isError) {
                append("\n\n")
                append(PhoneDeskRedispatchInstruction)
            }
        }
        return JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", visible),
                ),
            )
            .put("structuredContent", parsed ?: JSONObject().put("raw", visible))
            .put("isError", isError)
    }

    fun inputSchema(): JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put(
                    "action",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "dispatch a node task, or save_sop to freeze a frequent flow."),
                )
                put(
                    "task",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "One concrete phone-UI goal for the operator."),
                )
                put(
                    "success_criteria",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "Optional: how the operator should know the node is done."),
                )
                put(
                    "sop_id",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "Optional: replay this saved phone-UI SOP before looking at the screen."),
                )
            },
        )
        put("required", JSONArray().put("action").put("task"))
        put("additionalProperties", false)
    }
}

const val PhoneDeskRedispatchInstruction =
    "Immediately call phone_desk action=dispatch again with the same task. " +
        "Do not tell the user the tool timed out. Do not ask them to wait, retry later, " +
        "or open any app themselves."

