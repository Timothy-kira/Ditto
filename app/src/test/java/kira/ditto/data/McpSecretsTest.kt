package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSecretsTest {
    @Test
    fun infersAmapHttpKeyWhenMissing() {
        val slots = inferMcpSecretSlots(
            http("amap", "https://mcp.amap.com/mcp"),
        )
        assertEquals(listOf("key"), slots.map { it.injectKey })
        assertEquals(McpSecretKind.UrlQuery, slots.single().kind)
        assertFalse(slots.single().hasInlineValue)
    }

    @Test
    fun treatsInlineQueryKeyAsConfigured() {
        val slots = inferMcpSecretSlots(
            http("amap", "https://mcp.amap.com/mcp?key=abc123"),
        )
        assertTrue(slots.single().hasInlineValue)
        assertEquals("abc123", slots.single().inlineValue)
    }

    @Test
    fun infersStdioApiKeyAndPassword() {
        val slots = inferMcpSecretSlots(
            stdio(
                environment = listOf(
                    McpKeyValue("AMAP_MAPS_API_KEY", ""),
                    McpKeyValue("PATH", "/usr/bin"),
                    McpKeyValue("PASSWORD", "secret"),
                ),
            ),
        )
        assertEquals(listOf("AMAP_MAPS_API_KEY", "PASSWORD"), slots.map { it.injectKey })
        assertTrue(slots.first { it.injectKey == "PASSWORD" }.hasInlineValue)
        assertFalse(slots.first { it.injectKey == "AMAP_MAPS_API_KEY" }.hasInlineValue)
    }

    @Test
    fun injectsStoredQueryIntoUrl() {
        val applied = applyMcpSecrets(
            http("amap", "https://mcp.amap.com/mcp"),
            McpSecretLookup { if (it.endsWith(".url.query.key")) "stored-key" else null },
        )
        val url = (applied.transport as McpTransportConfig.StreamableHttp).url
        assertTrue(url.contains("key=stored-key"))
    }

    @Test
    fun extractsInlineKeyOutOfUrl() {
        val stored = mutableMapOf<String, String>()
        val cleaned = extractInlineMcpSecrets(
            http("amap", "https://mcp.amap.com/mcp?key=abc123"),
        ) { id, value -> stored[id] = value }
        val url = (cleaned.transport as McpTransportConfig.StreamableHttp).url
        assertFalse(url.contains("abc123"))
        assertTrue(stored.values.single() == "abc123")
    }

    @Test
    fun missingSlotsSkipFilledInline() {
        val missing = missingMcpSecretSlots(
            servers = listOf(
                http("amap", "https://mcp.amap.com/mcp"),
                http("docs", "https://example.com/mcp?key=present"),
            ),
            selectedIds = listOf("amap", "docs"),
            lookup = McpSecretLookup.None,
        )
        assertEquals(listOf("amap"), missing.map { it.serverId }.distinct())
    }

    @Test
    fun sanitizesColonInServerId() {
        val slot = inferMcpSecretSlots(
            http("upa:example.plugin.card", "https://mcp.amap.com/mcp"),
        ).single()
        assertFalse(slot.id.contains(":"))
        assertTrue(slot.id.startsWith("mcp.upa.example.plugin.card."))
    }

    @Test
    fun foldsDraftKeyIntoAmapUrl() {
        val folded = foldMcpSecretDrafts(
            http("amap", "https://mcp.amap.com/mcp"),
            mapOf(mcpSecretDraftKey(McpSecretKind.UrlQuery, "key") to "draft-key"),
        )
        val url = (folded.transport as McpTransportConfig.StreamableHttp).url
        assertTrue(url.contains("key=draft-key"))
    }

    @Test
    fun infersAuthorizationHeader() {
        val slots = inferMcpSecretSlots(
            http(
                "docs",
                "https://example.com/mcp",
                headers = listOf(McpKeyValue("Authorization", "")),
            ),
        )
        assertEquals(listOf("Authorization"), slots.map { it.injectKey })
        assertEquals(McpSecretKind.Header, slots.single().kind)
    }

    private fun http(id: String, url: String, headers: List<McpKeyValue> = emptyList()) =
        McpServerConfig(
            id = id,
            displayName = id,
            transport = McpTransportConfig.StreamableHttp(url = url, headers = headers),
        )

    private fun stdio(environment: List<McpKeyValue>) =
        McpServerConfig(
            id = "cli",
            displayName = "cli",
            transport = McpTransportConfig.StdIo(command = "npx", environment = environment),
        )
}
