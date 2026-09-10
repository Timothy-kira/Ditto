package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject

internal data class CompactMcpMethod(
    val name: String,
    val summary: String,
    val params: String,
)

/**
 * Shared catalog+call MCP surface. Official servers that expose one tool per
 * API would blow the context window; Aether ships two tools and keeps official names.
 */
internal class CompactShippedMcp(
    val pluginId: String,
    val serverName: String,
    val actionLabel: String,
    val catalogTool: String,
    val callTool: String,
    val methods: List<CompactMcpMethod>,
    private val catalogHeader: String,
    private val matchHint: String,
) {
    val toolNames: List<String> = listOf(catalogTool, callTool)

    fun catalogText(): String = buildString {
        append(catalogHeader)
        append('\n')
        methods.forEach { spec ->
            append(spec.name)
            append(' ')
            append(spec.summary)
            append(" (")
            append(spec.params)
            append(")\n")
        }
    }.trimEnd()

    fun schemaText(method: String): String {
        val spec = methods.firstOrNull { it.name == method } ?: return "unknown method: $method"
        return "${spec.name}\n${spec.summary}\nparams: ${spec.params}"
    }

    fun knownMethod(method: String): Boolean = methods.any { it.name == method }

    fun httpUrl(): String = upaMcpHttpUrl(pluginId)

    fun mcpServerConfig(sessionId: String = ""): McpServerConfig = McpServerConfig(
        id = pluginId,
        displayName = serverName,
        actionLabel = actionLabel,
        transport = McpTransportConfig.StreamableHttp(
            url = httpUrl(),
            headers = learningSessionHeaders(sessionId),
        ),
        isEnabled = true,
    )

    fun isShippedServerId(serverId: String): Boolean = serverId == pluginId

    fun toAcpServer(sessionId: String = ""): JSONObject? =
        mcpServerConfig(sessionId).toAcpMcpServer()

    fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", UpaMcpProtocolVersion)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put(
            "serverInfo",
            JSONObject()
                .put("name", serverName)
                .put("version", "1"),
        )

    fun listToolsResult(): JSONObject = JSONObject().put("tools", toolsArray())

    fun matchesToolName(name: String): Boolean {
        val n = name.trim().lowercase().replace('-', '_')
        return n == catalogTool || n == callTool ||
            n.endsWith("_$catalogTool") || n.endsWith("__$catalogTool") ||
            n.endsWith("_$callTool") || n.endsWith("__$callTool") ||
            (n.contains(matchHint) && (n.contains("catalog") || n.contains("call")))
    }

    fun canonicalToolName(name: String): String {
        val n = name.trim().lowercase().replace('-', '_')
        return when {
            n == catalogTool || n.endsWith("_$catalogTool") || n.endsWith("__$catalogTool") ||
                (n.contains(matchHint) && n.contains("catalog")) -> catalogTool
            n == callTool || n.endsWith("_$callTool") || n.endsWith("__$callTool") ||
                (n.contains(matchHint) && n.contains("call")) -> callTool
            else -> ""
        }
    }

    fun wrapCallResult(rawOutput: String): JSONObject {
        val compact = compactJson(rawOutput)
        val parsed = runCatching { JSONObject(compact) }.getOrNull()
        val visible = parsed?.toString() ?: compact
        val code = parsed?.optString("code").orEmpty()
        val isError = parsed?.optBoolean("ok", true) == false && code != "input_required"
        val result = JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", visible),
                ),
            )
            .put("structuredContent", parsed ?: JSONObject().put("raw", compact))
            .put("isError", isError)
        if (code == "input_required") {
            result.put("_meta", JSONObject().put("input_required", true))
        }
        return result
    }

    fun compactJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length <= MaxResultChars) return trimmed
        return trimmed.take(MaxResultChars - 1) + "…"
    }

    private fun toolsArray(): JSONArray = JSONArray().apply {
        put(
            tool(
                catalogTool,
                "$actionLabel catalog. One line per method.",
            ),
        )
        put(
            tool(
                callTool,
                "Call one $actionLabel method. ${catalogText()}",
                extra = JSONObject()
                    .put("method", stringProp("Official method name, or schema"))
                    .put(
                        "params",
                        JSONObject()
                            .put("type", "object")
                            .put("description", "Fields for that method")
                            .put("additionalProperties", true),
                    ),
                required = listOf("method"),
            ),
        )
    }

    private fun tool(
        name: String,
        description: String,
        extra: JSONObject = JSONObject(),
        required: List<String> = emptyList(),
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put("properties", extra)
                .put("required", JSONArray(required))
                .put("additionalProperties", false),
        )

    private fun stringProp(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    companion object {
        const val MaxResultChars = 4_000
    }
}
