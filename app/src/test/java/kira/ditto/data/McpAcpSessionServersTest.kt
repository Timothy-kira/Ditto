package kira.ditto.data

import kira.ditto.upa.upaMcpServerId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpAcpSessionServersTest {
    @Test
    fun upaServersDoNotAttachWithoutComposerSelection() {
        val manual = httpServer("docs")
        val upa = httpServer(upaMcpServerId("everme"), enabled = true)
        val unselectedManual = httpServer("search")

        val attached = mcpServersForAcpSession(
            servers = listOf(manual, upa, unselectedManual),
            selectedIds = listOf("docs"),
        )

        assertEquals(listOf("docs"), attached.map(McpServerConfig::id))
    }

    @Test
    fun selectedManualServersStillRequireComposerCheck() {
        val attached = mcpServersForAcpSession(
            servers = listOf(httpServer("docs"), httpServer("search")),
            selectedIds = emptyList(),
        )

        assertEquals(emptyList<String>(), attached.map(McpServerConfig::id))
    }

    @Test
    fun leftoverUpaIdsStillNeedExplicitComposerCheck() {
        val upa = httpServer(upaMcpServerId("everme"))
        val attached = mcpServersForAcpSession(
            servers = listOf(upa, upa.copy(displayName = "EverMe copy")),
            selectedIds = emptyList(),
        )

        assertEquals(emptyList<String>(), attached.map(McpServerConfig::id))
    }

    /** Every shipped server is declared, whatever the user has toggled or signed into. */
    private fun shippedNames(
        servers: List<McpServerConfig> = emptyList(),
        selectedIds: Collection<String> = emptyList(),
        secrets: McpSecretLookup = McpSecretLookup.None,
    ): List<String> {
        val array = buildAcpMcpServerArray(
            servers = servers,
            selectedIds = selectedIds,
            secrets = secrets,
        )
        return (0 until array.length()).map { array.getJSONObject(it).getString("name") }
    }

    @Test
    fun shippedServersAreDeclaredRegardlessOfEnablementOrCredentials() {
        // The invariant this file now exists to protect. The tool list is the head of the system
        // layer and it feeds `acpMcpServersFingerprint`, which gates ACP session reuse - so a
        // server appearing or disappearing mid-conversation does not merely cost a prefix cache
        // miss, it swaps out the session. Availability is answered when a tool is called instead
        // (`McpToolAvailability`, and the `input_required` the credential-backed hosts return).
        val everythingOff = shippedNames(
            servers = listOf(
                GmailMcp.mcpServerConfig().copy(isEnabled = false),
                SpotifyMcp.mcpServerConfig().copy(isEnabled = false),
                AmapMcp.mcpServerConfig().copy(isEnabled = false),
                GithubMcp.mcpServerConfig().copy(isEnabled = false),
                HuggingFaceMcp.mcpServerConfig().copy(isEnabled = false),
            ),
        )
        val everythingOn = shippedNames(
            servers = listOf(
                GmailMcp.mcpServerConfig(),
                SpotifyMcp.mcpServerConfig(),
                AmapMcp.mcpServerConfig(),
                GithubMcp.mcpServerConfig(),
                HuggingFaceMcp.mcpServerConfig(),
            ),
            secrets = McpSecretLookup { id ->
                when (id) {
                    GithubAuth.StoreId -> "ghp_test"
                    HuggingFaceAuth.StoreId -> "hf_test"
                    AmapAuth.StoreId -> "amap_test"
                    else -> null
                }
            },
        )
        assertEquals(everythingOn, everythingOff)
        for (name in listOf(
            "gmail", "spotify", "amap", "github", "huggingface",
            "device_catalog", "session_memory", "agent_display", "phone_app", "webmcp",
        )) {
            assertTrue("$name must always be declared", everythingOff.contains(name))
            assertEquals("$name must be declared exactly once", 1, everythingOff.count { it == name })
        }
    }

    @Test
    fun shippedFingerprintDoesNotMoveWhenTheUserTogglesOrSignsIn() {
        // The consequence the invariant exists for: same fingerprint means `canReuse` stays true in
        // AlpineRuntime.resolveKimiSession, so flipping a switch mid-conversation cannot recreate
        // the session.
        val off = buildAcpMcpServerArray(
            servers = listOf(GmailMcp.mcpServerConfig().copy(isEnabled = false)),
            selectedIds = emptyList(),
            learningSessionId = "session-one",
        )
        val on = buildAcpMcpServerArray(
            servers = listOf(GmailMcp.mcpServerConfig()),
            selectedIds = listOf(GmailMcp.mcpServerConfig().id),
            learningSessionId = "session-one",
            secrets = McpSecretLookup { id -> if (id == GithubAuth.StoreId) "ghp_test" else null },
        )
        assertEquals(acpMcpServersFingerprint(off), acpMcpServersFingerprint(on))
    }

    @Test
    fun userConfiguredServersStillFollowTheComposerSelection() {
        // Deliberately unchanged: adding or removing a server the user configured themselves is an
        // explicit configuration action, not something that happens in the middle of a turn, and we
        // cannot express availability on their behalf.
        assertTrue(shippedNames(servers = listOf(httpServer("docs")), selectedIds = listOf("docs")).contains("docs"))
        assertFalse(shippedNames(servers = listOf(httpServer("docs"))).contains("docs"))
    }

    @Test
    fun disabledShippedServerRefusesAtCallTimeInsteadOfDisappearing() {
        McpToolAvailability.setDisabledServerIds(setOf(GmailMcp.PluginId))
        try {
            assertFalse(McpToolAvailability.isEnabled(GmailMcp.PluginId))
            val refusal = JSONObject(
                McpToolAvailability.gate(GmailMcp.PluginId, "Gmail") { "{\"ok\":true}" },
            )
            // `input_required`, not an error: "switch this on" is something the user can fix, and
            // the model already knows how to surface that code rather than retrying around it.
            assertEquals("input_required", refusal.getString("code"))
            assertFalse(refusal.getBoolean("ok"))
            assertTrue(McpToolAvailability.isEnabled(SpotifyMcp.PluginId))
        } finally {
            McpToolAvailability.setDisabledServerIds(emptySet())
        }
    }

    @Test
    fun learningSessionHeaderParticipatesInMcpFingerprintWithoutLeakingItsValue() {
        val first = buildAcpMcpServerArray(
            servers = emptyList(),
            selectedIds = emptyList(),
            learningSessionId = "session-one",
        )
        val second = buildAcpMcpServerArray(
            servers = emptyList(),
            selectedIds = emptyList(),
            learningSessionId = "session-two",
        )
        val firstFingerprint = acpMcpServersFingerprint(first)
        assertFalse(firstFingerprint.contains("session-one"))
        assertFalse(firstFingerprint.contains("session-two"))
        assertFalse(firstFingerprint == acpMcpServersFingerprint(second))
    }

    @Test
    fun mcpFingerprintIgnoresServerOrder() {
        val first = buildAcpMcpServerArray(
            servers = listOf(httpServer("docs"), httpServer("search")),
            selectedIds = listOf("docs", "search"),
        )
        val second = JSONArray()
        for (index in first.length() - 1 downTo 0) {
            second.put(first.getJSONObject(index))
        }
        assertEquals(acpMcpServersFingerprint(first), acpMcpServersFingerprint(second))
        assertTrue(acpMcpServersFingerprint(first).contains("agent_display"))
    }

    @Test
    fun omitsSelectedHttpServerWhenSecretSlotIsEmpty() {
        val amap = McpServerConfig(
            id = "amap",
            displayName = "amap",
            transport = McpTransportConfig.StreamableHttp(url = "https://mcp.amap.com/mcp"),
        )
        val attached = buildAcpMcpServerArray(
            servers = listOf(amap),
            selectedIds = listOf("amap"),
        )
        // Identified by URL, not by name: the shipped Amap server is now always declared and shares
        // the display name, so a name check would pass for the wrong reason. What must not attach
        // is this remote endpoint, whose secret slot is empty.
        val urls = (0 until attached.length()).map { attached.getJSONObject(it).optString("url") }
        assertFalse(urls.any { it.startsWith("https://mcp.amap.com/") })
    }

    @Test
    fun injectsStoredSecretBeforeAttachingHttpServer() {
        val amap = McpServerConfig(
            id = "amap",
            displayName = "amap",
            transport = McpTransportConfig.StreamableHttp(url = "https://mcp.amap.com/mcp"),
        )
        val attached = buildAcpMcpServerArray(
            servers = listOf(amap),
            selectedIds = listOf("amap"),
            secrets = McpSecretLookup { id ->
                if (id.contains("url.query.key")) "stored-key" else null
            },
        )
        val amapUrl = (0 until attached.length()).map { attached.getJSONObject(it).optString("url") }
            .first { it.contains("mcp.amap.com") }
        assertTrue(amapUrl.contains("key=stored-key"))
    }

    private fun httpServer(
        id: String,
        enabled: Boolean = true,
    ): McpServerConfig = McpServerConfig(
        id = id,
        displayName = id,
        transport = McpTransportConfig.StreamableHttp(url = "http://127.0.0.1/$id"),
        isEnabled = enabled,
    )
}
