package kira.ditto.data

import org.json.JSONObject

/**
 * In-session notes sidecar. Always attached to ACP sessions; not shown
 * as a user-managed MCP plugin. Long-term recall is EverMe; this package
 * keeps the Codex-style Markdown note, new_context, and hash lookup.
 */
internal object SessionMemoryMcp {
    const val PluginId = "aether-session-memory"
    const val ServerName = "session_memory"
    const val Version = "11"
    const val InstallRoot = "/root/.kimi-code-mobile/node_modules/@aether/session-memory"
    const val McpGuestPath = "$InstallRoot/mcp.mjs"
    const val HookGuestPath = "$InstallRoot/hook.mjs"
    val AssetFiles = listOf(
        "package.json",
        "hook.mjs",
        "index.mjs",
        "store.mjs",
        "cards.mjs",
        "text.mjs",
        "everme.mjs",
        "mcp.mjs",
        "selftest.mjs",
    )
    val ObsoleteAssetFiles = listOf(
        "bm25.mjs",
        "embed.mjs",
        "models/potion-ml.bin",
    )

    fun mcpServerConfig(sessionId: String = ""): McpServerConfig = McpServerConfig(
        id = PluginId,
        displayName = ServerName,
        transport = McpTransportConfig.StdIo(
            command = "node",
            arguments = listOf(McpGuestPath),
            environment = listOf(
                McpKeyValue("AETHER_MEMORY_SESSION", sessionId),
            ),
        ),
        isEnabled = true,
    )

    fun toAcpServer(sessionId: String = ""): JSONObject? = mcpServerConfig(sessionId).toAcpMcpServer()
}
