package kira.ditto.data

import kira.ditto.browser.detectLoginWall
import kira.ditto.browser.looksLikeUrl
import kira.ditto.browser.normalizeBrowserAddress
import kira.ditto.browser.normalizeBrowserImageSearch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMcpMcpTest {
    @Test
    fun toolCatalogHasFixedNames() {
        // Fixed within a build, not fixed forever. Adding a tool changes the system layer once, at
        // ship time, which is a cost paid deliberately; what must never happen is the list changing
        // *during* a conversation, which is what `McpToolAvailability` and the shipped-server
        // constancy in `buildAcpMcpServerArray` exist to prevent. This assertion is the reminder
        // that an edit here is a real decision.
        val names = WebMcpMcp.ToolNames
        assertEquals(
            listOf(
                "browser_tasks",
                "browser_events",
                "browser_capabilities",
                "browser_execute",
                "browser_open",
                "tabs_navigate",
                "tabs_list",
                "tabs_manage",
                "page_snapshot",
                "page_inspect",
                "page_read",
                "page_grep",
                "page_image",
                "page_form",
                "page_click",
                "page_fill",
                "page_select",
                "page_scroll",
                "page_keys",
                "page_hover",
                "page_file",
                "page_dialog",
                "page_screenshot",
                "page_recall",
                "tool_recall",
                "browser_fetch_many",
                "browser_find_signup",
                "page_settle",
                "page_wait",
                "page_batch",
                "page_js",
                "passwords",
                "history_search",
                "resources_list",
                "webmcp_list",
                "webmcp_call",
                "search_images",
            ),
            names,
        )
        val listed = WebMcpMcp.listToolsResult().getJSONArray("tools")
        assertEquals(names.size, listed.length())
        for (index in 0 until listed.length()) {
            assertEquals(names[index], listed.getJSONObject(index).getString("name"))
        }
    }

    @Test
    fun dualEraPrefersMcpMethodHeader() {
        assertEquals(
            "server/discover",
            WebMcpMcp.resolveMethod("tools/list", mapOf("mcp-method" to "server/discover")),
        )
        assertEquals("tools/list", WebMcpMcp.resolveMethod("tools/list", emptyMap()))
    }

    @Test
    fun dualEraProtocolFromHeaderAndMeta() {
        assertEquals(
            WebMcpMcp.Protocol2026,
            WebMcpMcp.resolveProtocol(mapOf("mcp-protocol-version" to "2026-07-28"), JSONObject()),
        )
        assertEquals(
            WebMcpMcp.Protocol2026,
            WebMcpMcp.resolveProtocol(
                emptyMap(),
                JSONObject().put("_meta", JSONObject().put("protocolVersion", "2026-07-28")),
            ),
        )
        assertEquals(
            WebMcpMcp.Protocol2025,
            WebMcpMcp.resolveProtocol(emptyMap(), JSONObject()),
        )
    }

    @Test
    fun inputRequiredSetsMetaAndIsNotToolError() {
        val wrapped = WebMcpMcp.wrapCallResult(
            JSONObject().put("ok", false).put("code", "input_required").put("reason", "login").toString(),
        )
        assertFalse(wrapped.getBoolean("isError"))
        assertTrue(wrapped.getJSONObject("_meta").getBoolean("input_required"))
    }

    @Test
    fun matchesPrefixedToolNames() {
        assertTrue(WebMcpMcp.matchesToolName("tabs_navigate"))
        assertTrue(WebMcpMcp.matchesToolName("mcp__webmcp__page_snapshot"))
        assertTrue(WebMcpMcp.matchesToolName("webmcp_call"))
        assertTrue(WebMcpMcp.matchesToolName("passwords"))
        assertTrue(WebMcpMcp.matchesToolName("mcp__webmcp__page_form"))
        assertTrue(WebMcpMcp.matchesToolName("network_recent"))
        assertFalse(WebMcpMcp.matchesToolName("agent_display_execute"))
    }

    @Test
    fun snapshotTreeIsWhatTheModelSeesNotNodeJson() {
        val wrapped = WebMcpMcp.wrapCallResult(
            JSONObject()
                .put("ok", true)
                .put("url", "https://example.com")
                .put("title", "Example")
                .put("total", 2)
                .put("offset", 0)
                .put("shown", 2)
                .put("tree", "@e1 h1 28px \"Hello\"\n@e2 button \"Go\"")
                .put(
                    "nodes",
                    org.json.JSONArray().put(
                        JSONObject().put("ref", "@e1").put("outerHTML", "<h1>".repeat(400)),
                    ),
                )
                .toString(),
        )
        val text = wrapped.getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(text.contains("@e1 h1 28px"))
        assertFalse(text.contains("outerHTML"))
        assertFalse(wrapped.getJSONObject("structuredContent").has("nodes"))
    }

    @Test
    fun wrapCallResultPutsImageBesideTextAndStripsBytesFromJson() {
        val wrapped = WebMcpMcp.wrapCallResult(
            JSONObject()
                .put("ok", true)
                .put("ref", "@e4")
                .put("alt", "logo")
                .put("image_base64", "abc123")
                .put("image_mime", "image/jpeg")
                .toString(),
        )
        val content = wrapped.getJSONArray("content")
        assertEquals("image", content.getJSONObject(1).getString("type"))
        assertEquals("abc123", content.getJSONObject(1).getString("data"))
        assertFalse(wrapped.getJSONObject("structuredContent").has("image_base64"))
        assertFalse(content.getJSONObject(0).getString("text").contains("abc123"))
    }
}

class BrowserAddressAndLoginTest {
    @Test
    fun searchQueryUsesEngineTemplate() {
        val prefs = BrowserPreferences(searchEngineId = "ddg")
        val url = normalizeBrowserAddress("kotlin coroutines", prefs)
        assertTrue(url.startsWith("https://duckduckgo.com/?q="))
        assertTrue(url.contains("kotlin"))
    }

    @Test
    fun imageSearchUsesSameEngineTemplate() {
        val bing = normalizeBrowserImageSearch("西湖", BrowserPreferences(searchEngineId = "bing"))
        assertTrue(bing.startsWith("https://www.bing.com/images/search?q="))
        val google = normalizeBrowserImageSearch("西湖", BrowserPreferences(searchEngineId = "google"))
        assertTrue(google.contains("tbm=isch"))
        val ddg = normalizeBrowserImageSearch("西湖", BrowserPreferences(searchEngineId = "ddg"))
        assertTrue(ddg.contains("iax=images"))
        val baidu = normalizeBrowserImageSearch("西湖", BrowserPreferences(searchEngineId = "baidu"))
        assertTrue(baidu.contains("image.baidu.com"))
    }

    @Test
    fun hostWithoutSchemeBecomesHttps() {
        assertTrue(looksLikeUrl("example.com/path"))
        assertEquals(
            "https://example.com/path",
            normalizeBrowserAddress("example.com/path", BrowserPreferences()),
        )
    }

    @Test
    fun loginWallRequiresPasswordAndForm() {
        assertTrue(
            detectLoginWall(
                JSONObject()
                    .put("has_password_field", true)
                    .put("has_login_form", true),
            ),
        )
        assertFalse(
            detectLoginWall(
                JSONObject().put("has_password_field", true).put("url", "https://example.com"),
            ),
        )
        assertTrue(
            detectLoginWall(
                JSONObject()
                    .put("url", "https://accounts.google.com/signin")
                    .put("has_password_field", true),
            ),
        )
    }

    @Test
    fun browserPreferencesRoundTrip() {
        val original = BrowserPreferences(
            searchEngineId = "baidu",
            homepage = "https://www.baidu.com",
            acceptCookies = false,
            lastTabUrls = listOf("https://example.com"),
        )
        val parsed = parseBrowserPreferences(serializeBrowserPreferences(original))
        assertEquals(original, parsed)
    }
}

class BrowserHistoryAndFetchTest {
    @Test
    fun rankHistoryPrefersTitleMatchOverUrl() {
        val items = listOf(
            kira.ditto.browser.BrowserVisit("https://a.example/docs", "Unrelated", 1L, 1),
            kira.ditto.browser.BrowserVisit("https://kotlinlang.org", "Kotlin docs", 3L, 2),
            kira.ditto.browser.BrowserVisit("https://other.com/kotlin-news", "News", 2L, 1),
        )
        val ranked = kira.ditto.browser.rankHistory(items, "kotlin")
        assertEquals("https://kotlinlang.org", ranked.first().url)
        assertTrue(ranked.any { it.url.contains("kotlin-news") })
        assertFalse(ranked.any { it.title == "Unrelated" })
    }

    @Test
    fun extractSearchHitsSkipsSameHostAndKeepsExternalTitles() {
        val html = """
            <html><body>
              <h2><a href="https://example.com/about">About us</a></h2>
              <h2><a href="https://kotlinlang.org/docs">Kotlin language</a></h2>
              <a href="/relative">Skip relative without abs</a>
            </body></html>
        """.trimIndent()
        val hits = kira.ditto.browser.extractSearchHits(html, "https://bing.com/search?q=k", 8)
        assertTrue(hits.any { it.getString("url").contains("kotlinlang.org") })
        assertTrue(hits.none { it.getString("url").contains("bing.com") })
    }

    @Test
    fun extractSearchImagesReadsBingPayloadAndOgImage() {
        val html = """
            <html><head><meta property="og:image" content="https://cdn.example/hero.jpg"></head>
            <body>
              <a class="iusc" m='{"murl":"https://photos.example/full.png","turl":"https://thumbs.example/t.jpg","t":"West Lake","purl":"https://photos.example/page"}'></a>
              <img src="https://cdn.example/logo.png" width="16" height="16" alt="skip tiny">
              <img src="https://cdn.example/scene.webp" alt="scene">
            </body></html>
        """.trimIndent()
        val images = kira.ditto.browser.extractSearchImages(html, "https://www.bing.com/images/search?q=x", 8)
        assertTrue(images.any { it.getString("image").contains("full.png") })
        assertTrue(images.any { it.optString("title").contains("West Lake") })
        assertTrue(images.any { it.getString("image").contains("hero.jpg") })
        assertTrue(images.any { it.getString("image").contains("scene.webp") })
        assertTrue(images.none { it.getString("image").contains("logo.png") })
    }

    @Test
    fun classifyResourceUsesExtensionAndHint() {
        assertEquals("image", kira.ditto.browser.classifyResource("https://cdn.example/a.png", ""))
        assertEquals("media", kira.ditto.browser.classifyResource("https://x.com/v.m3u8", ""))
        assertEquals("xhr", kira.ditto.browser.classifyResource("https://api.example/data", "xmlhttprequest"))
        assertEquals("document", kira.ditto.browser.classifyResource("https://example.com/", "main_frame"))
    }

    @Test
    fun rejectNonHttpFetchTargets() {
        assertEquals(null, kira.ditto.browser.normalizeHttpUrl("file:///etc/passwd"))
        assertEquals(null, kira.ditto.browser.normalizeHttpUrl("javascript:alert(1)"))
        assertTrue(kira.ditto.browser.normalizeHttpUrl("example.com/x")!!.startsWith("https://"))
    }

    @Test
    fun compactVisibleTextKeepsFormFieldsAndMasksPasswordJson() {
        val structured = WebMcpCompact.forModel(
            JSONObject()
                .put("url", "https://example.com/login")
                .put(
                    "fields",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("ref", "@e2")
                            .put("type", "password")
                            .put("label", "Password")
                            .put("password", true)
                            .put("value", "••••"),
                    ),
                )
                .put("password", "super-secret"),
        )
        assertFalse(structured.has("password"))
        val text = WebMcpCompact.visibleText(structured)
        assertTrue(text.contains("@e2"))
        assertTrue(text.contains("[password]"))
        assertFalse(text.contains("super-secret"))
    }

    @Test
    fun loginListVisibleTextOmitsSecrets() {
        val wrapped = WebMcpMcp.wrapCallResult(
            JSONObject()
                .put("ok", true)
                .put("count", 1)
                .put(
                    "logins",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("id", "abc")
                            .put("origin", "https://example.com")
                            .put("username", "ada")
                            .put("password", "super-secret"),
                    ),
                )
                .toString(),
        )
        val structured = wrapped.getJSONObject("structuredContent")
        assertFalse(structured.getJSONArray("logins").getJSONObject(0).has("password"))
        val text = wrapped.getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(text.contains("ada"))
        assertTrue(text.contains("example.com"))
        assertFalse(text.contains("super-secret"))
    }

    @Test
    fun passwordGetKeepsSecretOnlyWhenFlagged() {
        val hidden = WebMcpMcp.wrapCallResult(
            JSONObject().put("ok", true).put("password", "s3cret").toString(),
        )
        assertFalse(hidden.getJSONObject("structuredContent").has("password"))
        val shown = WebMcpMcp.wrapCallResult(
            JSONObject()
                .put("ok", true)
                .put("include_secret", true)
                .put("password", "s3cret")
                .toString(),
        )
        assertEquals("s3cret", shown.getJSONObject("structuredContent").getString("password"))
        val visible = shown.getJSONArray("content").getJSONObject(0).getString("text")
        assertFalse(visible.contains("s3cret"))
    }

    @Test
    fun formAndPasswordToolDescriptionsCoverSubmitAndGenerate() {
        val listed = WebMcpMcp.listToolsResult().getJSONArray("tools")
        val byName = (0 until listed.length()).associate { index ->
            val tool = listed.getJSONObject(index)
            tool.getString("name") to tool.getString("description")
        }
        assertTrue(byName.getValue("page_form").contains("submit"))
        assertTrue(byName.getValue("passwords").contains("generate"))
        assertTrue(byName.getValue("passwords").contains("fill"))
        assertTrue(byName.containsKey("page_hover"))
        assertTrue(byName.containsKey("page_file"))
        assertTrue(byName.containsKey("page_dialog"))
        assertTrue(byName.containsKey("page_screenshot"))
        assertTrue(byName.getValue("page_wait").contains("download"))
        assertTrue(byName.getValue("page_wait").contains("wait_exhausted"))
        assertTrue(byName.getValue("tabs_navigate").contains("images=true"))
        assertTrue(byName.containsKey("search_images"))
        assertTrue(byName.getValue("search_images").contains("Gecko"))
        assertFalse(byName.getValue("search_images").contains("Does not start Gecko"))
        assertFalse(byName.containsKey("search_web"))
        assertFalse(byName.containsKey("http_fetch"))
    }

    @Test
    fun searchHitsVisibleTextIncludesImageUrls() {
        val text = WebMcpCompact.visibleText(
            JSONObject()
                .put("ok", true)
                .put(
                    "queries",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("query", "西湖")
                            .put(
                                "hits",
                                org.json.JSONArray().put(
                                    JSONObject()
                                        .put("title", "West Lake")
                                        .put("url", "https://example.com/page")
                                        .put("image", "https://cdn.example/west-lake.jpg"),
                                ),
                            ),
                    ),
                ),
        )
        assertTrue(text.contains("West Lake"))
        assertTrue(text.contains("img https://cdn.example/west-lake.jpg"))
    }

    @Test
    fun timeoutIsToolError() {
        val wrapped = WebMcpMcp.wrapCallResult(
            JSONObject().put("ok", false).put("code", "timeout").put("errmsg", "Timed out waiting.").toString(),
        )
        assertTrue(wrapped.getBoolean("isError"))
        assertFalse(wrapped.getJSONArray("content").getJSONObject(0).getString("text").contains("\"ok\":true"))
    }

    @Test
    fun inspectRedactsPasswordValues() {
        val structured = WebMcpCompact.forModel(
            JSONObject()
                .put("ok", true)
                .put(
                    "details",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("ref", "@e2")
                            .put("role", "textbox")
                            .put("type", "password")
                            .put("password", true)
                            .put("value", "hunter2"),
                    ),
                ),
        )
        assertEquals("••••", structured.getJSONArray("details").getJSONObject(0).getString("value"))
        assertFalse(WebMcpCompact.visibleText(structured).contains("hunter2"))
    }

    @Test
    fun batchVisibleTextDoesNotDumpNestedJson() {
        val wrapped = WebMcpMcp.wrapCallResult(
            JSONObject()
                .put("ok", true)
                .put("steps", 2)
                .put(
                    "results",
                    org.json.JSONArray()
                        .put(JSONObject().put("ok", true).put("tree", "@e1 button \"Go\""))
                        .put(JSONObject().put("ok", false).put("code", "timeout").put("errmsg", "Timed out waiting.")),
                )
                .toString(),
        )
        val text = wrapped.getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(text.contains("batch steps: 2"))
        assertTrue(text.contains("timeout"))
        assertFalse(text.contains("\"results\""))
    }
}

class BrowserLoginVaultTest {
    @Test
    fun pickMatchesHostAndUsernameWithoutAndroid() {
        val vault = kira.ditto.browser.BrowserLoginVault.inMemory()
        vault.save("https://example.com/login", "ada", "secret-one")
        vault.save("https://other.com", "bob", "secret-two")
        val picked = vault.pick("https://example.com/account", "ada")
        assertEquals("ada", picked!!.username)
        assertEquals("secret-one", picked.password)
        assertEquals("example.com", kira.ditto.browser.BrowserLoginVault.originHost(picked.origin))
        assertEquals(
            "example.com:8443",
            kira.ditto.browser.BrowserLoginVault.originHost("https://example.com:8443/login"),
        )
        assertFalse(vault.toPublicJson().getJSONObject(0).has("password"))
    }

    @Test
    fun generatePasswordStaysWithinBounds() {
        val password = kira.ditto.browser.BrowserLoginVault.generatePassword(16)
        assertEquals(16, password.length)
        assertEquals(10, kira.ditto.browser.BrowserLoginVault.generatePassword(3).length)
        assertEquals(64, kira.ditto.browser.BrowserLoginVault.generatePassword(80).length)
    }
}
