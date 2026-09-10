package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Operator-only MCP for Agent Mode. The desk-lead ACP session never receives
 * this server; only the on-device phone operator may open apps, paste text,
 * or clear a focused field. Text is clipboard-pasted. The host IME is not used.
 */
internal object PhoneUiMcp {
    const val PluginId = "aether-phone-ui"
    const val ServerName = "phone_ui"

    const val OpenAppTool = "open_app"
    const val TypeTextTool = "type_text"
    const val ClearTextTool = "clear_text"
    const val UndoTool = "undo"

    val ToolNames: Set<String> = setOf(OpenAppTool, TypeTextTool, ClearTextTool, UndoTool)

    fun httpUrl(): String = upaMcpHttpUrl(PluginId)

    fun mcpServerConfig(): McpServerConfig = McpServerConfig(
        id = PluginId,
        displayName = ServerName,
        transport = McpTransportConfig.StreamableHttp(url = httpUrl()),
        isEnabled = true,
    )

    fun toAcpServer(): JSONObject? = mcpServerConfig().toAcpMcpServer()

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
        JSONArray()
            .put(openAppTool())
            .put(typeTextTool())
            .put(clearTextTool())
            .put(undoTool()),
    )

    fun matchesToolName(name: String): Boolean {
        val normalized = name.trim().lowercase().replace('-', '_')
        return ToolNames.any { tool ->
            normalized == tool || normalized.endsWith("_$tool")
        }
    }

    fun normalizeAction(raw: String): String = when (raw.trim().lowercase().replace('-', '_')) {
        OpenAppTool, "open", "launch_app", "launch" -> "launch"
        TypeTextTool, "type", "paste", "input_text", "text" -> "text"
        ClearTextTool, "delete_text", "clear", "delete" -> "clear_text"
        UndoTool, "undo_text", "ctrl_z" -> "undo"
        "search", "submit_search", "ime_search", "ime_enter" -> "search"
        "swipe_left", "left_swipe" -> "swipe_left"
        "swipe_right", "right_swipe" -> "swipe_right"
        "swipe_up", "up_swipe" -> "swipe_up"
        "swipe_down", "down_swipe" -> "swipe_down"
        else -> raw.trim().lowercase()
    }

    fun toExecuteArguments(name: String, arguments: JSONObject): JSONObject {
        val mapped = JSONObject(arguments.toString())
        val normalizedName = name.trim().lowercase().replace('-', '_')
        val action = when {
            normalizedName == OpenAppTool || normalizedName.endsWith("_$OpenAppTool") -> "launch"
            normalizedName == TypeTextTool || normalizedName.endsWith("_$TypeTextTool") -> "text"
            normalizedName == ClearTextTool || normalizedName.endsWith("_$ClearTextTool") -> "clear_text"
            normalizedName == UndoTool || normalizedName.endsWith("_$UndoTool") -> "undo"
            else -> normalizeAction(name)
        }
        mapped.put("action", action)
        if (action == "launch" && mapped.optString("target").isBlank()) {
            mapped.put(
                "target",
                mapped.optString("app").ifBlank {
                    mapped.optString("package").ifBlank { mapped.optString("package_name") }
                },
            )
        }
        mapped.put("skip_capture", true)
        return mapped
    }

    fun wrapCallResult(rawOutput: String): JSONObject {
        val parsed = runCatching { JSONObject(rawOutput) }.getOrNull()
        parsed?.remove("screenshot_base64")
        val visible = parsed?.toString() ?: rawOutput
        val isError = parsed?.optBoolean("ok", true) == false
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

    private fun openAppTool(): JSONObject = JSONObject()
        .put("name", OpenAppTool)
        .put(
            "description",
            "Open an app that is installed on this phone onto the Agent Mode virtual display. " +
                "Pass the package name or the launcher label. Do not use the real Home screen.",
        )
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put(
                        "target",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "Package name or app label, e.g. com.tencent.mm or 微信."),
                    ),
                )
                .put("required", JSONArray().put("target"))
                .put("additionalProperties", false),
        )

    private fun typeTextTool(): JSONObject = JSONObject()
        .put("name", TypeTextTool)
        .put(
            "description",
            "Paste the complete string into the focused field on the virtual display. " +
                "Uses ACTION_SET_TEXT or clipboard + KEYCODE_PASTE. Do not tap the field first " +
                "unless it is not already focused. Never opens the IME.",
        )
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put(
                        "text",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "Full string to paste. Include CJK as-is."),
                    ),
                )
                .put("required", JSONArray().put("text"))
                .put("additionalProperties", false),
        )

    private fun clearTextTool(): JSONObject = JSONObject()
        .put("name", ClearTextTool)
        .put(
            "description",
            "Clear the focused input field on the virtual display (select-all then delete). " +
                "Does not open the IME.",
        )
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject())
                .put("additionalProperties", false),
        )

    private fun undoTool(): JSONObject = JSONObject()
        .put("name", UndoTool)
        .put(
            "description",
            "Undo the last text/clear_text on the focused virtual-display field " +
                "(restores the previous snapshot, otherwise Ctrl+Z). Does not open the IME.",
        )
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject())
                .put("additionalProperties", false),
        )
}
