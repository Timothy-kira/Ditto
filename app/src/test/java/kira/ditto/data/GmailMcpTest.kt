package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailMcpTest {
    @Test
    fun acpServerIsLoopbackNamedGmail() {
        val server = GmailMcp.toAcpServer()
        requireNotNull(server)
        assertEquals("gmail", server.getString("name"))
        assertEquals("http", server.getString("type"))
        assertTrue(server.getString("url").contains("/mcp/aether-gmail"))
    }

    @Test
    fun listToolsMatchesOfficialGmailMcpNames() {
        val names = (0 until GmailMcp.listToolsResult().getJSONArray("tools").length()).map {
            GmailMcp.listToolsResult().getJSONArray("tools").getJSONObject(it).getString("name")
        }
        assertEquals(GmailMcp.ToolNames, names)
        assertTrue(names.contains("search_threads"))
        assertTrue(names.contains("create_draft"))
        assertFalse(names.contains("send_email"))
    }

    @Test
    fun matchesPrefixedToolNames() {
        assertTrue(GmailMcp.matchesToolName("search_threads"))
        assertTrue(GmailMcp.matchesToolName("mcp__gmail__get_thread"))
        assertTrue(GmailMcp.matchesToolName("mcp__gmail__create_draft"))
        assertEquals("label_thread", GmailMcp.canonicalToolName("mcp__gmail__label_thread"))
        assertFalse(GmailMcp.matchesToolName("tabs_navigate"))
        assertFalse(GmailMcp.matchesToolName("list_apps"))
    }

    @Test
    fun wrapCallResultMarksAuthAsInputRequiredNotError() {
        val wrapped = GmailMcp.wrapCallResult(
            JSONObject()
                .put("ok", false)
                .put("code", "input_required")
                .put("reason", "login")
                .toString(),
        )
        assertFalse(wrapped.getBoolean("isError"))
        assertTrue(wrapped.getJSONObject("_meta").getBoolean("input_required"))
    }

    @Test
    fun mergeShippedMcpServersCountsGmailAsANormalServer() {
        val docs = McpServerConfig(
            id = "docs",
            displayName = "docs",
            transport = McpTransportConfig.StreamableHttp(url = "http://127.0.0.1/docs"),
        )
        val merged = mergeShippedMcpServers(listOf(docs))
        assertEquals(6, merged.size)
        assertEquals(
            listOf(
                "docs",
                GmailMcp.PluginId,
                SpotifyMcp.PluginId,
                AmapMcp.PluginId,
                GithubMcp.PluginId,
                HuggingFaceMcp.PluginId,
            ).sorted(),
            merged.map(McpServerConfig::id).sorted(),
        )
        assertEquals(5, mergeShippedMcpServers(emptyList()).size)
        assertEquals("gmail", merged.first { it.id == GmailMcp.PluginId }.displayName)
        assertEquals("spotify", merged.first { it.id == SpotifyMcp.PluginId }.displayName)
        assertEquals("amap", merged.first { it.id == AmapMcp.PluginId }.displayName)
        assertEquals("github", merged.first { it.id == GithubMcp.PluginId }.displayName)
        assertEquals("huggingface", merged.first { it.id == HuggingFaceMcp.PluginId }.displayName)
        assertTrue(mergeShippedMcpServers(listOf(docs, McpServerConfig(
            id = "aether-feishu",
            displayName = "feishu",
            transport = McpTransportConfig.StreamableHttp(url = "http://127.0.0.1/feishu"),
        ))).none { it.id == "aether-feishu" })
        val disabled = mergeShippedMcpServers(
            listOf(docs, GmailMcp.mcpServerConfig().copy(isEnabled = false)),
        )
        assertFalse(disabled.first { it.id == GmailMcp.PluginId }.isEnabled)
    }
}

class GmailCodecTest {
    @Test
    fun stringListAcceptsArrayOrCommaString() {
        assertEquals(
            listOf("a@x.com", "b@x.com"),
            GmailCodec.stringList(JSONObject().put("to", JSONArray().put("a@x.com").put("b@x.com")), "to"),
        )
        assertEquals(
            listOf("a@x.com", "b@x.com"),
            GmailCodec.stringList(JSONObject().put("to", "a@x.com, b@x.com"), "to"),
        )
    }

    @Test
    fun rfc2822AndRawAreUrlSafe() {
        val rfc = GmailCodec.rfc2822Draft(
            to = listOf("ariel@example.com"),
            cc = emptyList(),
            bcc = emptyList(),
            subject = "Hello",
            body = "Approve the plan",
            htmlBody = "",
        )
        assertTrue(rfc.contains("To: ariel@example.com"))
        assertTrue(rfc.contains("Subject: Hello"))
        assertTrue(rfc.contains("Approve the plan"))
        val raw = GmailCodec.rawUrlSafe(rfc)
        assertFalse(raw.contains("+"))
        assertFalse(raw.contains("/"))
        assertFalse(raw.contains("="))
        val decoded = GmailCodec.decodeBase64Url(raw)
        assertTrue(decoded.contains("ariel@example.com"))
    }

    @Test
    fun compactMessageReadsHeadersAndPlainBody() {
        val payload = JSONObject()
            .put(
                "headers",
                JSONArray()
                    .put(JSONObject().put("name", "From").put("value", "Ariel <ariel@example.com>"))
                    .put(JSONObject().put("name", "Subject").put("value", "Plan"))
                    .put(JSONObject().put("name", "To").put("value", "me@example.com")),
            )
            .put("mimeType", "text/plain")
            .put(
                "body",
                JSONObject().put("data", GmailCodec.rawUrlSafe("The marketing plan is ready.")),
            )
        val message = JSONObject()
            .put("id", "m1")
            .put("snippet", "The marketing")
            .put("labelIds", JSONArray().put("INBOX"))
            .put("payload", payload)
        val compact = GmailCodec.compactMessage(message, fullBody = true)
        assertEquals("m1", compact.getString("id"))
        assertEquals("Ariel <ariel@example.com>", compact.getString("sender"))
        assertEquals("Plan", compact.getString("subject"))
        assertEquals("The marketing plan is ready.", compact.getString("plaintextBody"))
    }

    @Test
    fun decodeBodiesKeepsLongestHtmlPart() {
        val payload = JSONObject()
            .put("mimeType", "multipart/alternative")
            .put(
                "parts",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("mimeType", "text/plain")
                            .put("body", JSONObject().put("data", GmailCodec.rawUrlSafe("short"))),
                    )
                    .put(
                        JSONObject()
                            .put("mimeType", "text/html")
                            .put("body", JSONObject().put("data", GmailCodec.rawUrlSafe("<p>tiny</p>"))),
                    )
                    .put(
                        JSONObject()
                            .put("mimeType", "text/html")
                            .put(
                                "body",
                                JSONObject().put(
                                    "data",
                                    GmailCodec.rawUrlSafe("<div><p>Full <b>HTML</b> body with a table.</p></div>"),
                                ),
                            ),
                    ),
            )
        val bodies = GmailCodec.decodeBodies(payload)
        assertEquals("short", bodies.first)
        assertTrue(bodies.second.contains("Full <b>HTML</b> body"))
        assertFalse(bodies.second.contains("tiny"))
    }
}
