package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only installed-app catalog for the desk lead. Available without Agent
 * Mode and without a virtual display. GUI launch/tap stays on `agent_display`.
 */
internal object DeviceCatalogMcp {
    const val PluginId = "aether-device-catalog"
    const val ServerName = "device_catalog"
    const val ToolName = "list_apps"
    const val SnapshotGuestPath = "/workspace/.aether/device_apps.json"

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
        JSONArray().put(
            JSONObject()
                .put("name", ToolName)
                .put(
                    "description",
                    "List apps installed on this Android phone (launcher labels and packages). " +
                        "Read-only: does not open a virtual display or launch anything. " +
                        "Use while planning which app handles hotels, maps, notes, etc. " +
                        "Optional query filters by app label or package. " +
                        "A compact snapshot is also written to .aether/device_apps.json for Plan-mode Read.",
                )
                .put("inputSchema", inputSchema()),
        ),
    )

    fun wrapCallResult(rawOutput: String): JSONObject {
        val parsed = runCatching { JSONObject(rawOutput) }.getOrNull()
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
            .put("structuredContent", parsed ?: JSONObject().put("raw", rawOutput))
            .put("isError", isError)
    }

    fun matchesToolName(name: String): Boolean {
        val n = name.trim().lowercase().replace('-', '_')
        return n == ToolName ||
            n.endsWith("_$ToolName") ||
            n.endsWith("__$ToolName") ||
            n.contains("device_catalog")
    }

    private fun inputSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put(
                    "query",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "Optional app label, package, or activity filter."),
                )
                .put(
                    "include_system",
                    JSONObject()
                        .put("type", "boolean")
                        .put("description", "Include system/launcher apps. Defaults to false."),
                )
                .put(
                    "max_results",
                    JSONObject()
                        .put("type", "integer")
                        .put("description", "Maximum apps to return. Defaults to 500."),
                ),
        )
        .put("required", JSONArray())
        .put("additionalProperties", false)
}
