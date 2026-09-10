package kira.ditto.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DittoWebSearchMapperTest {
    @Test
    fun requestReadsTextQueryOrQuery() {
        assertEquals(
            "ox alpha",
            DittoWebSearchMapper.queryFromRequest(JSONObject().put("text_query", "ox alpha")),
        )
        assertEquals(
            "ox alpha",
            DittoWebSearchMapper.queryFromRequest(JSONObject().put("query", "ox alpha")),
        )
    }

    @Test
    fun moonshotResponseUsesSearchResults() {
        val payload = DittoWebSearchMapper.moonshotResponse(
            listOf(
                DittoWebSearchHit(
                    title = "Ox",
                    url = "https://example.com/ox",
                    snippet = "alpha model",
                    siteName = "example.com",
                ),
            ),
        )
        val results = payload.getJSONArray("search_results")
        assertEquals(1, results.length())
        assertEquals("Ox", results.getJSONObject(0).getString("title"))
        assertEquals("https://example.com/ox", results.getJSONObject(0).getString("url"))
    }

    @Test
    fun tavilyResultsMapToHits() {
        val hits = DittoWebSearchMapper.fromTavily(
            JSONObject(
                """
                {"results":[{"title":"Alpha","url":"https://docs.openai.com/a","content":"Ox Alpha"}]}
                """.trimIndent(),
            ),
        )
        assertEquals(1, hits.size)
        assertEquals("Alpha", hits[0].title)
        assertEquals("https://docs.openai.com/a", hits[0].url)
        assertTrue(hits[0].snippet.contains("Ox Alpha"))
    }

    @Test
    fun htmlResultsUnwrapDuckDuckGoRedirects() {
        val html = """
            <a rel="nofollow" class="result__a" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fopenai.com%2Findex%2Fox">Ox model</a>
        """.trimIndent()
        val hits = DittoWebSearchMapper.fromHtml(html)
        assertEquals(1, hits.size)
        assertEquals("https://openai.com/index/ox", hits[0].url)
        assertEquals("Ox model", hits[0].title)
    }

    @Test
    fun snapshotTreeExtractsOutboundLinks() {
        val hits = DittoWebSearchMapper.fromSnapshotTree(
            """
            @e1 heading "搜索"
            @e2 link "大雁塔" https://travel.hangzhou.cn/dayan
            @e3 link "Bing" https://www.bing.com/search?q=x
            @e4 link "文档占位" https://example.com/docs
            @e5 link "钟楼" https://travel.hangzhou.cn/bell
            @e6 link "Example Org" https://www.example.org/bell
            """.trimIndent(),
            limit = 8,
        )
        assertEquals(2, hits.size)
        assertEquals("大雁塔", hits[0].title)
        assertEquals("https://travel.hangzhou.cn/dayan", hits[0].url)
        assertEquals("钟楼", hits[1].title)
        assertEquals("https://travel.hangzhou.cn/bell", hits[1].url)
        assertTrue(hits.none { it.url.contains("example.") })
    }

    @Test
    fun snapshotTreeUnwrapsBingAndGoogleRedirects() {
        val dayan = java.util.Base64.getEncoder()
            .encodeToString("https://travel.hangzhou.cn/dayan".toByteArray())
            .trimEnd('=')
        val hits = DittoWebSearchMapper.fromSnapshotTree(
            """
            @e1 link "大雁塔" https://www.bing.com/ck/a?!&&p=1&u=a1$dayan
            @e2 link "钟楼" https://www.google.com/url?q=https%3A%2F%2Ftravel.hangzhou.cn%2Fbell
            """.trimIndent(),
            limit = 8,
        )
        assertEquals(2, hits.size)
        assertEquals("https://travel.hangzhou.cn/dayan", hits[0].url)
        assertEquals("https://travel.hangzhou.cn/bell", hits[1].url)
    }

    @Test
    fun snapshotKeepsBingClickHopsWhenUnwrapFails() {
        val hits = DittoWebSearchMapper.fromSnapshotTree(
            """
            @e1 link "小笼包" https://www.bing.com/ck/a?!&&p=1&ig=deadbeef
            @e2 link "SERP" https://www.bing.com/search?q=%E4%B8%8A%E6%B5%B7%E7%BE%8E%E9%A3%9F
            """.trimIndent(),
            limit = 8,
        )
        assertEquals(1, hits.size)
        assertTrue(hits.single().url.contains("/ck/a"))
        assertTrue(hits.none { it.url.contains("/search?") })
    }

    @Test
    fun fromHarvestUnwrapsAndDropsSerpChrome() {
        val hits = DittoWebSearchMapper.fromHarvest(
            org.json.JSONArray()
                .put(
                    org.json.JSONObject()
                        .put("title", "南翔馒头")
                        .put("url", "https://www.dianping.com/shop/1"),
                )
                .put(
                    org.json.JSONObject()
                        .put("title", "Bing")
                        .put("url", "https://www.bing.com/search?q=x"),
                )
                .put(
                    org.json.JSONObject()
                        .put("title", "跳转")
                        .put("url", "https://www.bing.com/ck/a?!&&p=1"),
                ),
            limit = 8,
        )
        assertEquals(2, hits.size)
        assertEquals("https://www.dianping.com/shop/1", hits[0].url)
        assertTrue(hits[1].url.contains("/ck/a"))
    }

    @Test
    fun fromHarvestSkipsDomainOnlyTitles() {
        val hits = DittoWebSearchMapper.fromHarvest(
            org.json.JSONArray()
                .put(
                    org.json.JSONObject()
                        .put("title", "chatgpt-chinese.com")
                        .put("url", "https://chatgpt-chinese.com/guide")
                        .put("snippet", "chatgpt-chinese.com"),
                )
                .put(
                    org.json.JSONObject()
                        .put("title", "如何使用 ChatGPT")
                        .put("url", "https://chatgpt-guides.com/start")
                        .put("snippet", "中文入门教程"),
                ),
            limit = 8,
        )
        assertEquals(1, hits.size)
        assertEquals("如何使用 ChatGPT", hits.single().title)
        assertEquals("中文入门教程", hits.single().snippet)
    }

    @Test
    fun moonshotResponseMarksAlreadyReadHits() {
        val payload = DittoWebSearchMapper.moonshotResponse(
            listOf(
                DittoWebSearchHit(
                    title = "Ox",
                    url = "https://example.com/ox",
                    snippet = "alpha model",
                    siteName = "example.com",
                ),
            ),
            alreadyRead = { url -> url == "https://example.com/ox" },
        )
        assertTrue(
            payload.getJSONArray("search_results")
                .getJSONObject(0)
                .getString("snippet")
                .startsWith("[already read]"),
        )
    }

    @Test
    fun fetchBodyTurnsExecutorFailureIntoMarkdown() {
        val body = dittoWebFetchBody(
            url = "https://example.com/gone",
            result = Result.failure(IllegalStateException("gecko down")),
        )
        assertTrue(body.contains("status: failed"))
        assertTrue(body.contains("gecko down"))
        assertTrue(dittoWebFetchBody("https://example.com/gone", Result.success("")).contains("status: failed"))
    }

    @Test
    fun searchHitsAppendBudgetAfterFourthCall() {
        val ok = dittoWebSearchHits(
            result = Result.success(
                listOf(
                    DittoWebSearchHit(title = "A", url = "https://example.com/a", snippet = "x"),
                ),
            ),
            searchCount = 4,
        )
        assertEquals("about:search-budget", ok.last().url)
        val refused = dittoWebSearchHits(
            result = Result.success(
                listOf(
                    DittoWebSearchHit(title = "A", url = "https://example.com/a", snippet = "x"),
                ),
            ),
            searchCount = 5,
        )
        assertEquals(1, refused.size)
        assertEquals("about:search-budget", refused.single().url)
        assertTrue(refused.single().snippet.contains("STOP"))
        val failed = dittoWebSearchHits(
            result = Result.failure(IllegalStateException("boom")),
            searchCount = 1,
        )
        assertEquals("about:search-failed", failed.single().url)
        assertTrue(failed.single().snippet.contains("boom"))
        val loop = dittoSearchLoopHit()
        assertEquals("about:lookup-loop", loop.url)
        assertTrue(loop.snippet.contains("Lookup loop"))
        assertTrue(loop.snippet.contains("at most 3 items"))
    }
}
