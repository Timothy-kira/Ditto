package kira.ditto.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubMcpTest {
    @Test
    fun acpServerIsLoopbackNamedGithub() {
        val server = GithubMcp.toAcpServer()
        requireNotNull(server)
        assertEquals("github", server.getString("name"))
        assertEquals("http", server.getString("type"))
        assertTrue(server.getString("url").contains("/mcp/aether-github"))
    }

    @Test
    fun listToolsIsTwoCompressedEntries() {
        val tools = GithubMcp.listToolsResult().getJSONArray("tools")
        assertEquals(2, tools.length())
        assertEquals(GithubMcp.ToolNames, (0 until tools.length()).map {
            tools.getJSONObject(it).getString("name")
        })
        val catalog = GithubMcp.catalogText()
        assertTrue(catalog.length < 3_000)
        assertTrue(catalog.contains("get_me"))
        assertTrue(catalog.contains("create_pull_request"))
        val call = (0 until tools.length()).map { tools.getJSONObject(it) }
            .first { it.getString("name") == GithubMcp.CallTool }
        assertTrue(call.getString("description").contains("search_repositories"))
    }

    @Test
    fun matchesPrefixedToolNames() {
        assertTrue(GithubMcp.matchesToolName("github_catalog"))
        assertTrue(GithubMcp.matchesToolName("github_call"))
        assertTrue(GithubMcp.matchesToolName("mcp__github__github_call"))
        assertEquals(GithubMcp.CallTool, GithubMcp.canonicalToolName("mcp__github__github_call"))
        assertFalse(GithubMcp.matchesToolName("search_threads"))
        assertFalse(GithubMcp.matchesToolName("huggingface_call"))
    }

    @Test
    fun wrapCallResultMarksAuthAsInputRequiredNotError() {
        val wrapped = GithubMcp.wrapCallResult(
            JSONObject()
                .put("ok", false)
                .put("code", "input_required")
                .put("reason", "paste PAT")
                .toString(),
        )
        assertFalse(wrapped.getBoolean("isError"))
        assertTrue(wrapped.getJSONObject("_meta").getBoolean("input_required"))
    }

    @Test
    fun normalizeAccessTokenStripsLabels() {
        assertEquals("ghp_abc", normalizeAccessToken(" token: ghp_abc "))
        assertEquals("github_pat_1", normalizeAccessToken("\"github_pat_1\""))
    }
}

class HuggingFaceMcpTest {
    @Test
    fun acpServerIsLoopbackNamedHuggingFace() {
        val server = HuggingFaceMcp.toAcpServer()
        requireNotNull(server)
        assertEquals("huggingface", server.getString("name"))
        assertTrue(server.getString("url").contains("/mcp/aether-huggingface"))
    }

    @Test
    fun listToolsIsTwoCompressedEntries() {
        val tools = HuggingFaceMcp.listToolsResult().getJSONArray("tools")
        assertEquals(2, tools.length())
        assertTrue(HuggingFaceMcp.catalogText().contains("hf_fs"))
        assertTrue(HuggingFaceMcp.catalogText().contains("search_models"))
        assertTrue(HuggingFaceMcp.matchesToolName("huggingface_call"))
        assertEquals(
            HuggingFaceMcp.CatalogTool,
            HuggingFaceMcp.canonicalToolName("mcp__huggingface__huggingface_catalog"),
        )
        assertFalse(HuggingFaceMcp.matchesToolName("github_call"))
    }

    @Test
    fun hfFsExpandsToHubSearch() {
        val json = HuggingFaceMcpHost.dispatch(
            HuggingFaceMcp.CallTool,
            JSONObject().put("method", "schema").put("params", JSONObject().put("method", "hf_fs")),
            "unused",
        )
        assertTrue(json.getString("schema").contains("action"))
    }
}
