package kira.ditto.browser

import kira.ditto.data.BrowserLeadReminder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDeskTest {
    @After
    fun tearDown() {
        BrowserDesk.clear()
        BrowserDesk.clearReadLedger()
        BrowserTopicGraph.clear()
    }

    @Test
    fun searchWebBeginUsesSearchingVerb() {
        val id = BrowserDesk.begin("mcp__webmcp__history_search", JSONObject().put("query", "西湖"))
        val activity = BrowserDesk.state.value.activities.single()
        assertEquals(id, activity.id)
        assertEquals(BrowserDeskVerb.Searching, activity.verb)
        assertEquals("西湖", activity.detail)
    }

    @Test
    fun pageReadCompleteShowsArticlePreview() {
        val id = BrowserDesk.begin("page_read", JSONObject().put("url", "https://example.com/a"))
        BrowserDesk.complete(
            id,
            "page_read",
            JSONObject()
                .put("ok", true)
                .put("url", "https://example.com/a")
                .put("text", "西湖是杭州的一处湖泊。")
                .toString(),
        )
        val state = BrowserDesk.state.value
        assertTrue(state.activities.isEmpty())
        assertEquals(BrowserDeskPreviewKind.Article, state.preview.kind)
        assertTrue(state.preview.body.contains("西湖"))
    }

    @Test
    fun searchResultPreviewKeepsHits() {
        val payload = JSONObject()
            .put("ok", true)
            .put(
                "queries",
                JSONArray().put(
                    JSONObject()
                        .put("query", "西湖")
                        .put(
                            "hits",
                            JSONArray().put(
                                JSONObject()
                                    .put("title", "West Lake")
                                    .put("url", "https://example.com/west-lake")
                                    .put("snippet", "Hangzhou"),
                            ),
                        ),
                ),
            )
        val preview = previewFromResult("search_web", payload.toString())
        requireNotNull(preview)
        assertEquals(BrowserDeskPreviewKind.Search, preview.kind)
        assertEquals("West Lake", preview.hits.single().title)
        assertEquals("https://example.com/west-lake", preview.hits.single().url)
    }

    @Test
    fun verbForToolMapsPageReadAndNavigate() {
        assertEquals(
            BrowserDeskVerb.Reading,
            verbForTool("page_read", JSONObject(), running = true),
        )
        assertEquals(
            BrowserDeskVerb.Opening,
            verbForTool("tabs_navigate", JSONObject(), running = true),
        )
        assertEquals(
            BrowserDeskVerb.Noting,
            verbForTool("unknown_tool", JSONObject(), running = true),
        )
        assertEquals(
            BrowserDeskVerb.Searching,
            verbForTool(
                "tabs_navigate",
                JSONObject().put("query", "西湖"),
                running = true,
            ),
        )
        assertEquals(
            "西湖",
            detailForTool("tabs_navigate", JSONObject().put("query", "西湖")),
        )
    }

    @Test
    fun browserCaptionIgnoresLeadReminderLeak() {
        assertEquals(
            "西湖门票",
            browserDeskCaption(
                description = BrowserLeadReminder,
                prompt = BrowserLeadReminder,
                deskDetail = "西湖门票",
            ),
        )
        assertTrue(isBrowserLeadReminderLeak(BrowserLeadReminder))
        assertFalse(isBrowserLeadReminderLeak("西湖"))
    }

    @Test
    fun tabsNavigateSerpHitsBecomeSearchPreview() {
        val preview = previewFromResult(
            "tabs_navigate",
            JSONObject()
                .put("ok", true)
                .put("url", "https://www.bing.com/search?q=%E8%A5%BF%E6%B9%96")
                .put("title", "西湖 - 搜索")
                .put("query", "西湖")
                .put("tree", "heading 西湖")
                .put(
                    "hits",
                    JSONArray().put(
                        JSONObject()
                            .put("title", "西湖景区")
                            .put("url", "https://travel.hangzhou.cn/west-lake")
                            .put("snippet", "杭州西湖"),
                    ),
                )
                .toString(),
        )
        requireNotNull(preview)
        assertEquals(BrowserDeskPreviewKind.Search, preview.kind)
        assertEquals("西湖", preview.title)
        assertEquals("https://travel.hangzhou.cn/west-lake", preview.hits.single().url)
    }

    @Test
    fun pageReadImagesUseSrcAndAlt() {
        val payload = JSONObject()
            .put("ok", true)
            .put("url", "https://example.com/xian")
            .put("title", "西安")
            .put("text", "大雁塔与钟楼")
            .put(
                "images",
                JSONArray()
                    .put(JSONObject().put("src", "https://cdn.example/dayan.jpg").put("alt", "大雁塔"))
                    .put(JSONObject().put("src", "https://cdn.example/bell.jpg").put("alt", "钟楼")),
            )
        val preview = previewFromResult("page_read", payload.toString())
        requireNotNull(preview)
        assertEquals(2, preview.images.size)
        assertEquals("大雁塔", preview.images[0].alt)
        assertEquals("钟楼", preview.images[1].alt)
    }

    @Test
    fun collectInlineImagesKeepsEveryPageKind() {
        val dayanId = BrowserDesk.begin("page_read", JSONObject())
        BrowserDesk.complete(
            dayanId,
            "page_read",
            JSONObject()
                .put("ok", true)
                .put("url", "https://example.com/dayan")
                .put("title", "大雁塔")
                .put(
                    "images",
                    JSONArray().put(
                        JSONObject().put("src", "https://cdn.example/dayan.jpg").put("alt", "大雁塔"),
                    ),
                )
                .toString(),
        )
        val bellId = BrowserDesk.begin("page_read", JSONObject())
        BrowserDesk.complete(
            bellId,
            "page_read",
            JSONObject()
                .put("ok", true)
                .put("url", "https://example.com/bell")
                .put("title", "钟楼")
                .put(
                    "images",
                    JSONArray().put(
                        JSONObject().put("src", "https://cdn.example/bell.jpg").put("alt", "钟楼"),
                    ),
                )
                .toString(),
        )
        val images = collectBrowserInlineImages(BrowserDesk.state.value)
        assertEquals(setOf("大雁塔", "钟楼"), images.map { it.alt }.toSet())
        val curated = collectBrowserInlineImages(BrowserDesk.state.value, curatedOnly = true)
        assertTrue(curated.isEmpty())

        val searchId = BrowserDesk.begin("search_images", JSONObject().put("query", "钟楼夜景"))
        BrowserDesk.complete(
            searchId,
            "search_images",
            JSONObject()
                .put("ok", true)
                .put("query", "钟楼夜景")
                .put("url", "https://www.bing.com/images/search?q=%E9%92%9F%E6%A5%BC")
                .put(
                    "images",
                    JSONArray().put(
                        JSONObject().put("src", "https://cdn.example/bell-night.jpg").put("alt", "钟楼夜景"),
                    ),
                )
                .toString(),
        )
        val afterSearch = collectBrowserInlineImages(BrowserDesk.state.value, curatedOnly = true)
        assertEquals(listOf("钟楼夜景"), afterSearch.map { it.alt })
    }

    @Test
    fun mosaicTilesKeepOperatingCardsAndCurrentPreview() {
        val tiles = browserDeskMosaicTiles(
            BrowserDeskState(
                activities = listOf(
                    BrowserDeskActivity(id = "a1", verb = BrowserDeskVerb.Searching, detail = "钟楼"),
                ),
                preview = BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Article,
                    url = "https://travel.hangzhou.cn/dayan",
                    title = "大雁塔",
                    body = "唐代",
                ),
                pages = listOf(
                    BrowserDeskPreview(
                        kind = BrowserDeskPreviewKind.Article,
                        url = "https://travel.hangzhou.cn/idle",
                        title = "闲置页",
                        body = "不应出现",
                    ),
                    BrowserDeskPreview(
                        kind = BrowserDeskPreviewKind.Article,
                        url = "https://travel.hangzhou.cn/dayan",
                        title = "大雁塔",
                        body = "唐代",
                    ),
                ),
            ),
        )
        assertEquals(2, tiles.size)
        assertTrue(tiles.none { it.fullWidth })
        assertEquals("钟楼", tiles.first().activity?.detail)
        assertEquals("大雁塔", tiles.last().preview.title)
        assertTrue(tiles.none { it.preview.title == "闲置页" })
        assertEquals("act-searching-钟楼", tiles.first().key)
        assertEquals("page-https://travel.hangzhou.cn/dayan", tiles.last().key)
    }

    @Test
    fun mosaicTilesDropOldestIdleActivitiesWhenOverBudget() {
        val tiles = browserDeskMosaicTiles(
            BrowserDeskState(
                activities = (1..5).map { index ->
                    BrowserDeskActivity(
                        id = "a$index",
                        verb = BrowserDeskVerb.Searching,
                        detail = "查询$index",
                    )
                },
                preview = BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Search,
                    title = "当前页",
                    hits = listOf(BrowserDeskHit(title = "当前页", url = "https://travel.hangzhou.cn/now")),
                ),
            ),
        )
        assertEquals(4, tiles.size)
        assertTrue(tiles.none { it.activity?.detail == "查询1" })
        assertTrue(tiles.none { it.activity?.detail == "查询2" })
        assertEquals("查询3", tiles.first().activity?.detail)
        assertEquals("当前页", tiles.last().preview.title)
    }

    @Test
    fun mosaicTilesDropAccessibilityTrees() {
        val tiles = browserDeskMosaicTiles(
            BrowserDeskState(
                preview = BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Snapshot,
                    url = "https://zhihu.com/a",
                    title = "",
                    body = "@e1 h1 28px \"Hello\"\n@e2 button \"Go\"",
                ),
            ),
        )
        assertEquals(1, tiles.size)
        assertEquals("zhihu.com", tiles.single().preview.title)
        assertTrue(tiles.single().preview.body.isEmpty())
        val titled = sanitizeBrowserDeskPreviewForCard(
            BrowserDeskPreview(
                kind = BrowserDeskPreviewKind.Snapshot,
                url = "https://zhihu.com/a",
                title = "西湖",
                body = "@e1 heading \"西湖\"",
            ),
        )
        requireNotNull(titled)
        assertEquals(BrowserDeskPreviewKind.Article, titled.kind)
        assertTrue(titled.body.isEmpty())
        assertFalse(looksLikeBrowserAccessibilityTree("西湖是杭州的湖泊。"))
        assertTrue(looksLikeBrowserAccessibilityTree("@e1 button \"Go\""))
        val pictured = sanitizeBrowserDeskPreviewForCard(
            BrowserDeskPreview(
                kind = BrowserDeskPreviewKind.Snapshot,
                url = "https://zhihu.com/a",
                title = "",
                body = "@e1 image \"封面\" https://cdn.example/cover.jpg",
                images = listOf(BrowserDeskImage(url = "https://cdn.example/cover.jpg", alt = "封面")),
            ),
        )
        requireNotNull(pictured)
        assertEquals(BrowserDeskPreviewKind.Images, pictured.kind)
        assertTrue(pictured.body.isEmpty())
        assertEquals("封面", pictured.images.single().alt)
    }

    @Test
    fun searchImagesPreviewIsImagesKind() {
        val preview = previewFromResult(
            "search_images",
            JSONObject()
                .put("ok", true)
                .put("query", "钟楼")
                .put("url", "https://www.bing.com/images/search?q=%E9%92%9F%E6%A5%BC")
                .put(
                    "images",
                    JSONArray().put(
                        JSONObject().put("src", "https://cdn.example/bell.jpg").put("alt", "钟楼"),
                    ),
                )
                .toString(),
        )
        requireNotNull(preview)
        assertEquals(BrowserDeskPreviewKind.Images, preview.kind)
        assertEquals("钟楼", preview.images.single().alt)
    }

    @Test
    fun snapshotTreeExtractsBingThumbnailsWithoutImageRole() {
        val images = extractImagesFromSnapshotTree(
            """
            @e1 generic "小笼包 474x355" https://tse1.mm.bing.net/th?id=OIP.xiaolong
            @e2 link "隐私" https://www.bing.com/search?q=x
            @e3 image "封面" https://cdn.example/cover.jpg
            """.trimIndent(),
            pageTitle = "上海美食",
        )
        assertEquals(2, images.size)
        assertTrue(images[0].url.contains("tse1.mm.bing.net"))
        assertEquals("小笼包 474x355", images[0].alt)
        assertEquals("https://cdn.example/cover.jpg", images[1].url)
    }

    @Test
    fun completeRecordsNativeSourcesAndPages() {
        val id = BrowserDesk.begin("page_read", JSONObject())
        BrowserDesk.complete(
            id,
            "page_read",
            JSONObject()
                .put("ok", true)
                .put("url", "https://www.zhihu.com/question/1")
                .put("title", "西湖")
                .put("text", "西湖很大")
                .toString(),
        )
        val state = BrowserDesk.state.value
        assertEquals(1, state.sources.size)
        assertEquals("https://www.zhihu.com/question/1", state.sources.single().url)
        assertEquals(BrowserDeskPreviewKind.Article, state.pages.single().kind)
    }

    @Test
    fun normalizeBrowsedUrlStripsTrackingAndWww() {
        assertEquals(
            "https://example.com/a",
            normalizeBrowsedUrl("https://www.example.com/a/?utm_source=x&utm_medium=y"),
        )
        assertEquals(
            "https://example.com/a",
            normalizeBrowsedUrl("https://m.example.com/a/#section"),
        )
        assertEquals(
            "https://example.com/a",
            normalizeBrowsedUrl("https://example.com/a/"),
        )
        assertEquals(
            "https://mail.example.com/inbox",
            normalizeBrowsedUrl("https://mail.example.com/inbox"),
        )
    }

    @Test
    fun readLedgerSurvivesClearAndSkipsDuplicates() {
        BrowserDesk.recordRead("https://example.com/west-lake", "西湖", "西湖是杭州的一处湖泊。")
        assertEquals("西湖", BrowserDesk.lookupRead("https://www.example.com/west-lake/?utm_source=x")?.title)
        BrowserDesk.clear()
        assertEquals("西湖", BrowserDesk.lookupRead("https://example.com/west-lake")?.title)
        val skipped = browserFetchSkippedMarkdown(BrowserDesk.lookupRead("https://example.com/west-lake")!!)
        assertTrue(skipped.contains("skipped_duplicate"))
        assertTrue(skipped.contains("西湖"))
        BrowserDesk.clearReadLedger()
        assertEquals(null, BrowserDesk.lookupRead("https://example.com/west-lake"))
    }

    @Test
    fun annotatePageReadMarksRepeatVisits() {
        val first = BrowserDesk.annotateToolResult(
            "page_read",
            JSONObject()
                .put("ok", true)
                .put("url", "https://example.com/a")
                .put("title", "A")
                .put("text", "hello world")
                .toString(),
        )
        assertFalse(JSONObject(first).optBoolean("already_read"))
        val second = BrowserDesk.annotateToolResult(
            "tabs_navigate",
            JSONObject()
                .put("ok", true)
                .put("url", "https://example.com/a")
                .put("title", "A")
                .toString(),
        )
        assertTrue(JSONObject(second).optBoolean("already_read"))
    }

    @Test
    fun placeholderExampleHostDetectsIanaDomains() {
        assertTrue(isPlaceholderExampleHost("https://example.com"))
        assertTrue(isPlaceholderExampleHost("https://www.example.org/path"))
        assertTrue(isPlaceholderExampleHost("example.net"))
        assertTrue(isPlaceholderExampleHost("https://foo.example.com/docs"))
        assertFalse(isPlaceholderExampleHost("https://zhihu.com/question/1"))
        assertFalse(isPlaceholderExampleHost("https://travel.hangzhou.cn/dayan"))
        assertTrue(isStalePlaceholderDocument("https://example.com/", "https://zhihu.com/q/1"))
        assertFalse(isStalePlaceholderDocument("https://zhihu.com/q/1", "https://zhihu.com/q/1"))
        assertFalse(isStalePlaceholderDocument("https://example.com/", "https://example.org/x"))
    }

    @Test
    fun mosaicActivityTileKeyStaysStableAcrossBeginIds() {
        val first = BrowserDeskActivity(
            id = "uuid-1",
            verb = BrowserDeskVerb.Searching,
            detail = "西湖",
        )
        val second = BrowserDeskActivity(
            id = "uuid-2",
            verb = BrowserDeskVerb.Searching,
            detail = "西湖",
        )
        assertEquals(browserDeskActivityTileKey(first), browserDeskActivityTileKey(second))
        assertEquals("act-searching-西湖", browserDeskActivityTileKey(first))
        val reading = BrowserDeskActivity(
            id = "r1",
            verb = BrowserDeskVerb.Reading,
            detail = "https://www.zhihu.com/question/1",
        )
        val readingAgain = BrowserDeskActivity(
            id = "r2",
            verb = BrowserDeskVerb.Reading,
            detail = "https://zhihu.com/question/2",
        )
        assertEquals("act-reading-zhihu.com", browserDeskActivityTileKey(reading))
        assertEquals(browserDeskActivityTileKey(reading), browserDeskActivityTileKey(readingAgain))
    }

    @Test
    fun mosaicPageTileKeyIgnoresTitle() {
        val titled = BrowserDeskPreview(
            kind = BrowserDeskPreviewKind.Article,
            url = "https://www.zhihu.com/q/1?utm_source=x",
            title = "A",
        )
        val renamed = BrowserDeskPreview(
            kind = BrowserDeskPreviewKind.Article,
            url = "https://zhihu.com/q/1",
            title = "B",
        )
        assertEquals(browserDeskPageTileKey(titled), browserDeskPageTileKey(renamed))
        assertEquals("page-https://zhihu.com/q/1", browserDeskPageTileKey(titled))
    }

    @Test
    fun sanitizeDropsExampleDomainPreview() {
        assertNull(
            sanitizeBrowserDeskPreviewForCard(
                BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Article,
                    url = "https://example.com/",
                    title = "Example Domain",
                    body = "This domain is for use in illustrative examples in documents.",
                ),
            ),
        )
        val kept = sanitizeBrowserDeskPreviewForCard(
            BrowserDeskPreview(
                kind = BrowserDeskPreviewKind.Search,
                title = "西湖",
                hits = listOf(
                    BrowserDeskHit(title = "Example", url = "https://example.com/a"),
                    BrowserDeskHit(title = "西湖", url = "https://travel.hangzhou.cn/west-lake"),
                ),
            ),
        )
        requireNotNull(kept)
        assertEquals(1, kept.hits.size)
        assertEquals("https://travel.hangzhou.cn/west-lake", kept.hits.single().url)
    }

    @Test
    fun recordSearchPublishesHitsAndWaitBudgetCounts() {
        BrowserDesk.recordSearch(
            query = "西湖",
            url = "https://www.bing.com/search?q=%E8%A5%BF%E6%B9%96",
            hits = listOf(
                BrowserDeskHit(title = "西湖", url = "https://travel.hangzhou.cn/west-lake"),
            ),
        )
        val preview = BrowserDesk.state.value.preview
        assertEquals(BrowserDeskPreviewKind.Search, preview.kind)
        assertEquals("西湖", preview.title)
        assertEquals("https://travel.hangzhou.cn/west-lake", preview.hits.single().url)
        assertEquals(1, BrowserDesk.noteWaitTimeout())
        assertEquals(2, BrowserDesk.noteWaitTimeout())
        assertEquals(2, BrowserDesk.waitTimeouts())
        BrowserDesk.clear()
        assertEquals(0, BrowserDesk.waitTimeouts())
        assertEquals(0, BrowserDesk.searchCount())
    }

    @Test
    fun previewsByTopicStayIsolatedAndUiSelectDoesNotNeedGecko() {
        val first = BrowserDesk.begin(
            "tabs_navigate",
            JSONObject().put("query", "上海本帮菜").put("topic_id", "上海本帮菜"),
        )
        BrowserDesk.complete(
            first,
            "tabs_navigate",
            JSONObject()
                .put("ok", true)
                .put("url", "https://example.com/benbang")
                .put("title", "本帮菜")
                .put("text", "蟹粉小笼")
                .toString(),
        )
        val second = BrowserDesk.begin(
            "tabs_navigate",
            JSONObject().put("query", "上海小吃").put("topic_id", "上海小吃"),
        )
        BrowserDesk.complete(
            second,
            "tabs_navigate",
            JSONObject()
                .put("ok", true)
                .put("url", "https://example.com/xiaochi")
                .put("title", "上海小吃")
                .put("text", "生煎")
                .toString(),
        )
        val state = BrowserDesk.state.value
        assertEquals(2, state.previewsByTopic.size)
        assertTrue(browserDeskShowsTopicRail(state))
        assertEquals("本帮菜", state.previewsByTopic.getValue("上海本帮菜").title)
        assertEquals("上海小吃", state.previewsByTopic.getValue("上海小吃").title)
        assertEquals("上海本帮菜", state.uiPreviewTopicId)
        assertEquals("本帮菜", state.preview.title)
        BrowserDesk.selectUiTopic("上海小吃")
        val shown = BrowserDesk.state.value
        assertEquals("上海小吃", shown.uiPreviewTopicId)
        assertEquals("上海小吃", shown.preview.title)
        assertEquals("本帮菜", shown.previewsByTopic.getValue("上海本帮菜").title)
    }

    @Test
    fun singleTopicDoesNotShowRail() {
        val id = BrowserDesk.begin("tabs_navigate", JSONObject().put("query", "西湖"))
        BrowserDesk.complete(
            id,
            "tabs_navigate",
            JSONObject().put("ok", true).put("title", "西湖").put("url", "https://example.com/west").toString(),
        )
        assertFalse(browserDeskShowsTopicRail(BrowserDesk.state.value))
    }

    @Test
    fun swarmItemsOrderTheTopicRail() {
        val first = BrowserDesk.begin(
            "tabs_navigate",
            JSONObject().put("query", "上海小吃").put("topic_id", "上海小吃"),
        )
        BrowserDesk.complete(
            first,
            "tabs_navigate",
            JSONObject().put("ok", true).put("title", "上海小吃").put("text", "生煎").toString(),
        )
        val second = BrowserDesk.begin(
            "tabs_navigate",
            JSONObject().put("query", "上海本帮菜").put("topic_id", "上海本帮菜"),
        )
        BrowserDesk.complete(
            second,
            "tabs_navigate",
            JSONObject().put("ok", true).put("title", "本帮菜").put("text", "蟹粉").toString(),
        )
        val items = browserTopicRailItems(
            BrowserDesk.state.value,
            swarmItems = listOf("上海本帮菜", "上海小吃"),
        )
        assertEquals(listOf("上海本帮菜", "上海小吃"), items.map { it.topicId })
        assertTrue(
            browserDeskShowsTopicRail(
                BrowserDeskState(),
                swarmItems = listOf("上海本帮菜", "上海小吃"),
            ),
        )
        assertFalse(
            browserDeskShowsTopicRail(
                BrowserDeskState(),
                swarmItems = listOf("西湖"),
            ),
        )
    }

    @Test
    fun moonshotSearchResultsBecomeSearchPreview() {
        val preview = previewFromResult(
            "Launching browser agent: 西湖",
            JSONObject()
                .put(
                    "search_results",
                    JSONArray().put(
                        JSONObject()
                            .put("title", "西湖景区")
                            .put("url", "https://travel.hangzhou.cn/west-lake")
                            .put("snippet", "杭州西湖"),
                    ),
                )
                .toString(),
        )
        requireNotNull(preview)
        assertEquals(BrowserDeskPreviewKind.Search, preview.kind)
        assertEquals("https://travel.hangzhou.cn/west-lake", preview.hits.single().url)
        assertEquals("西湖景区", preview.hits.single().title)
    }

    @Test
    fun lookupLoopAllowsOneFirstHopThenThreeExpands() {
        BrowserDesk.clear()
        assertEquals(WebSearchAdmit.Allow, BrowserDesk.admitWebSearch())
        assertEquals(WebSearchAdmit.WaitForFirstHop, BrowserDesk.admitWebSearch())
        assertEquals(WebSearchAdmit.WaitForFirstHop, BrowserDesk.admitWebSearch())
        assertEquals(1, BrowserDesk.searchCount())
        BrowserDesk.markWebSearchReturned()
        assertEquals(WebSearchAdmit.Allow, BrowserDesk.admitWebSearch())
        assertEquals(WebSearchAdmit.Allow, BrowserDesk.admitWebSearch())
        assertEquals(WebSearchAdmit.Allow, BrowserDesk.admitWebSearch())
        assertEquals(WebSearchAdmit.Budget, BrowserDesk.admitWebSearch())
        assertEquals(4, BrowserDesk.searchCount())
    }

    @Test
    fun expandRejectsParaphraseQueriesAndKeepsDistinctAngles() {
        BrowserDesk.clear()
        assertEquals(WebSearchAdmit.Allow, BrowserDesk.admitWebSearch("西湖门票"))
        assertEquals(WebSearchAdmit.WaitForFirstHop, BrowserDesk.admitWebSearch("西湖开放时间"))
        BrowserDesk.markWebSearchReturned()
        assertEquals(WebSearchAdmit.Similar, BrowserDesk.admitWebSearch("西湖门票价格"))
        assertEquals(WebSearchAdmit.Similar, BrowserDesk.admitWebSearch("西湖门票多少钱"))
        assertEquals(WebSearchAdmit.Allow, BrowserDesk.admitWebSearch("杭州西湖开放时间"))
        assertTrue(searchQueriesTooSimilar("西湖门票", "西湖门票多少钱"))
        assertFalse(searchQueriesTooSimilar("西湖门票", "杭州天气"))
        assertFalse(searchQueriesTooSimilar("西湖门票", "杭州西湖开放时间"))
        assertFalse(
            searchQueriesTooSimilar(
                "第三届NVIDIA DGX Spark 黑客松",
                "第三届NVIDIA DGX Spark 黑客松 报名 官网",
            ),
        )
        assertFalse(
            searchQueriesTooSimilar(
                "第三届NVIDIA DGX Spark 黑客松",
                "NVIDIA DGX Spark Hackathon registration site:nvidia.com",
            ),
        )
    }

    @Test
    fun extraSearchWaitsForFirstHopThenAllowsDistinctAngle() {
        BrowserDesk.clear()
        assertEquals(WebSearchAdmit.Allow, BrowserDesk.admitWebSearch("西湖门票"))
        assertEquals(
            WebSearchAdmit.WaitForFirstHop,
            BrowserDesk.admitWebSearchAfterWait("杭州西湖开放时间", timeoutMs = 0),
        )
        BrowserDesk.markWebSearchReturned()
        assertEquals(
            WebSearchAdmit.Allow,
            BrowserDesk.admitWebSearchAfterWait("杭州西湖开放时间", timeoutMs = 0),
        )
        assertEquals(
            WebSearchAdmit.Similar,
            BrowserDesk.admitWebSearchAfterWait("西湖门票价格", timeoutMs = 0),
        )
    }

    @Test
    fun deepSearchAllowsParallelFirstHop() {
        BrowserDesk.clear()
        BrowserDesk.configureLookupLoop(deepSearch = true)
        assertEquals(WebSearchAdmit.Allow, BrowserDesk.admitWebSearch())
        assertEquals(WebSearchAdmit.Allow, BrowserDesk.admitWebSearch())
        assertEquals(WebSearchAdmit.Allow, BrowserDesk.admitWebSearch())
        assertEquals(3, BrowserDesk.searchCount())
    }

    @Test
    fun deepSearchStillRejectsParaphrases() {
        BrowserDesk.clear()
        BrowserDesk.configureLookupLoop(deepSearch = true)
        assertEquals(WebSearchAdmit.Allow, BrowserDesk.admitWebSearch("西湖门票"))
        assertEquals(WebSearchAdmit.Similar, BrowserDesk.admitWebSearch("西湖门票价格"))
        assertEquals(WebSearchAdmit.Allow, BrowserDesk.admitWebSearch("西湖开放时间"))
    }

    @Test
    fun operateUrlThisTurnKeepsOfficialUrlAndDropsSerp() {
        BrowserDesk.clear()
        BrowserDesk.configureLookupLoop(
            deepSearch = false,
            operateUrl = "https://www.nvidia.com/dgx-spark-hackathon",
        )
        assertEquals(
            "https://www.nvidia.com/dgx-spark-hackathon",
            BrowserDesk.operateUrlThisTurn(),
        )
        BrowserDesk.configureLookupLoop(
            deepSearch = false,
            operateUrl = "https://www.bing.com/search?q=nvidia",
        )
        assertEquals("", BrowserDesk.operateUrlThisTurn())
        assertFalse(BrowserDesk.isLookupThenOperateThisTurn())
    }

    @Test
    fun lookupThenOperateResetsPageJsAndPicksOfficialUrl() {
        BrowserDesk.clear()
        repeat(4) { BrowserDesk.notePageJs() }
        BrowserDesk.noteEmptyFormList()
        BrowserDesk.configureLookupLoop(
            deepSearch = false,
            lookupThenOperate = true,
        )
        assertTrue(BrowserDesk.isLookupThenOperateThisTurn())
        assertEquals(0, BrowserDesk.pageJsCount())
        assertEquals(0, BrowserDesk.emptyFormLists())
        BrowserDesk.recordSearch(
            query = "第三届 NVIDIA DGX Spark 黑客松",
            url = "https://www.bing.com/search?q=hackathon",
            hits = listOf(
                BrowserDeskHit(
                    title = "NVIDIA DGX",
                    url = "https://www.nvidia.com/en-us/data-center/dgx/",
                ),
                BrowserDeskHit(
                    title = "第三届 NVIDIA DGX Spark 黑客松",
                    url = "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
                ),
            ),
        )
        assertEquals(
            "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
            BrowserDesk.officialActUrl(),
        )
        BrowserDesk.configureLookupLoop(
            deepSearch = false,
            operateUrl = "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
            lookupThenOperate = true,
        )
        assertFalse(BrowserDesk.isLookupThenOperateThisTurn())
        assertEquals(
            "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
            BrowserDesk.operateUrlThisTurn(),
        )
    }

    @Test
    fun lookupThenOperateBlocksWaitFormAndJs() {
        BrowserDesk.clear()
        BrowserDesk.configureLookupLoop(deepSearch = false, lookupThenOperate = true)
        assertTrue(BrowserDesk.isLookupThenOperateThisTurn())
        assertTrue(BrowserDesk.lookupOnlyBlockedMessage("page_wait").orEmpty().contains("page_wait"))
        assertTrue(BrowserDesk.lookupOnlyBlockedMessage("mcp__webmcp__page_form").orEmpty().contains("page_form"))
        assertTrue(BrowserDesk.lookupOnlyBlockedMessage("page_js").orEmpty().contains("page_js"))
        assertNull(BrowserDesk.lookupOnlyBlockedMessage("tabs_navigate"))
        assertNull(BrowserDesk.lookupOnlyBlockedMessage("page_snapshot"))
        assertNull(BrowserDesk.lookupOnlyBlockedMessage("page_read"))
        BrowserDesk.configureLookupLoop(deepSearch = false, lookupThenOperate = false)
        assertNull(BrowserDesk.lookupOnlyBlockedMessage("page_form"))
    }

    @Test
    fun userTakeoverOpensLivePageAndVerifyContinueKeepsIt() {
        BrowserDesk.clear()
        BrowserDesk.requestUserTakeover(
            "https://www.x-techcon.com/article/183222.html",
            "challenge",
        )
        val blocked = BrowserDesk.state.value
        assertTrue(blocked.userTakeover)
        assertEquals("challenge", blocked.userTakeoverReason)
        assertEquals("https://www.x-techcon.com/article/183222.html", blocked.openedUrl)
        BrowserDesk.recordFailed(
            "https://www.google.com/search?q=nvidia",
            "not_found",
            "Page Not Found | NVIDIA",
        )
        assertNull(BrowserDesk.lookupFailed("https://www.google.com/search?q=nvidia"))
        BrowserDesk.configureLookupLoop(deepSearch = false, keepLivePage = true)
        val resumed = BrowserDesk.state.value
        assertFalse(resumed.userTakeover)
        assertTrue(resumed.keepLiveSurface)
        assertEquals("https://www.x-techcon.com/article/183222.html", resumed.openedUrl)
        BrowserDesk.clearUserTakeover()
        BrowserDesk.clear()
        BrowserDesk.holdLivePage("https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920")
        val held = BrowserDesk.state.value
        assertFalse(held.userTakeover)
        assertTrue(held.keepLiveSurface)
        assertEquals(
            "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
            held.openedUrl,
        )
        BrowserDesk.configureLookupLoop(
            deepSearch = false,
            operateUrl = "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
            keepLivePage = true,
        )
        val operateLive = BrowserDesk.state.value
        assertTrue(operateLive.keepLiveSurface)
        assertEquals(
            "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
            operateLive.openedUrl,
        )
        BrowserDesk.clear()
        BrowserDesk.requestUserTakeover("https://scrm.nvidia.cn/lp/login", "login")
        assertEquals("login", BrowserDesk.state.value.userTakeoverReason)
        assertTrue(BrowserDesk.state.value.userTakeover)
        BrowserDesk.clear()
        BrowserDesk.requestUserTakeover("", "challenge")
        assertTrue(BrowserDesk.state.value.userTakeover)
        assertTrue(BrowserDesk.state.value.keepLiveSurface)
        BrowserDesk.configureLookupLoop(deepSearch = false, keepLivePage = true)
        val blankUrl = BrowserDesk.state.value
        assertFalse(blankUrl.userTakeover)
        assertTrue(blankUrl.keepLiveSurface)
    }

    @Test
    fun displayBrowserSearchHitsKeepsTitleAndSnippetDropsDomainRepeats() {
        val hits = displayBrowserSearchHits(
            listOf(
                BrowserDeskHit(
                    title = "chatgpt-chinese.com",
                    url = "https://chatgpt-chinese.com/",
                    snippet = "chatgpt-chinese.com",
                ),
                BrowserDeskHit(
                    title = "如何使用 ChatGPT",
                    url = "https://chatgpt-chinese.com/guide",
                    snippet = "chatgpt-chinese.com 如何使用 ChatGPT 的入门说明",
                ),
                BrowserDeskHit(
                    title = "西湖景区",
                    url = "https://travel.hangzhou.cn/west-lake",
                    snippet = "杭州西湖十景",
                ),
            ),
        )
        assertEquals(2, hits.size)
        assertEquals("如何使用 ChatGPT", hits[0].title)
        assertEquals("如何使用 ChatGPT 的入门说明", hits[0].snippet)
        assertFalse(hits[0].snippet.contains("chatgpt-chinese.com"))
        assertEquals("西湖景区", hits[1].title)
        assertEquals("杭州西湖十景", hits[1].snippet)
    }

    @Test
    fun displayBrowserSearchHitsStripsInlineUrlsAndDomainLines() {
        val hits = displayBrowserSearchHits(
            listOf(
                BrowserDeskHit(
                    title = "tech-insider.org",
                    url = "https://tech-insider.org/",
                    snippet = "https://tech-insider.org\n2 days ago · Anthropic pushed out two new flagship models",
                ),
                BrowserDeskHit(
                    title = "Anthropic https://www.anthropic.com",
                    url = "https://www.anthropic.com/news",
                    snippet = "Aug 14, 2026 · Media assetsDownload press kit Introducing Claude Fable 5.1",
                ),
            ),
        )
        assertEquals(1, hits.size)
        assertEquals("Anthropic", hits[0].title)
        assertFalse(hits[0].title.contains("http", ignoreCase = true))
        assertFalse(hits[0].snippet.contains("http", ignoreCase = true))
        assertFalse(hits[0].snippet.contains("anthropic.com", ignoreCase = true))
    }

    @Test
    fun citedHitsMoveToTheTop() {
        val ordered = orderCitedHits(
            listOf(
                BrowserDeskHit("A", "https://a.example/1", "one"),
                BrowserDeskHit("B", "https://b.example/2", "two", cited = true, citedAtMillis = 20L),
                BrowserDeskHit("C", "https://c.example/3", "three", cited = true, citedAtMillis = 10L),
            ),
        )
        assertEquals(listOf("C", "B", "A"), ordered.map { it.title })
    }

    @Test
    fun articlePreviewDoesNotReplaceSearchHits() {
        val existing = BrowserDeskPreview(
            kind = BrowserDeskPreviewKind.Search,
            title = "Claude",
            hits = listOf(
                BrowserDeskHit(
                    title = "Anthropic news",
                    url = "https://www.anthropic.com/news",
                    snippet = "flagship models",
                ),
            ),
        )
        val merged = mergeBrowserTopicPreview(
            existing = existing,
            incoming = BrowserDeskPreview(
                kind = BrowserDeskPreviewKind.Article,
                url = "https://www.anthropic.com/news",
                title = "News",
                body = "Anthropic pushed out two new flagship models on September 1.",
            ),
            tool = "page_read",
        )
        assertEquals(BrowserDeskPreviewKind.Search, merged.kind)
        assertEquals(1, merged.hits.size)
        assertTrue(merged.hits.single().read)
        assertFalse(merged.hits.single().cited)
        assertTrue(merged.hits.single().readExcerpt.contains("flagship"))
    }

    @Test
    fun snapshotPreviewDoesNotReplaceSearchHits() {
        val existing = BrowserDeskPreview(
            kind = BrowserDeskPreviewKind.Search,
            title = "第三届 NVIDIA DGX Spark 黑客松",
            hits = listOf(
                BrowserDeskHit(
                    title = "官方报名页",
                    url = "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
                    snippet = "立即报名",
                ),
            ),
        )
        val merged = mergeBrowserTopicPreview(
            existing = existing,
            incoming = BrowserDeskPreview(
                kind = BrowserDeskPreviewKind.Snapshot,
                url = "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
                title = "报名",
                body = "@e1 button 立即报名",
            ),
            tool = "page_snapshot",
        )
        assertEquals(BrowserDeskPreviewKind.Search, merged.kind)
        assertEquals("第三届 NVIDIA DGX Spark 黑客松", merged.title)
        assertEquals(1, merged.hits.size)
        assertEquals(
            "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
            merged.hits.single().url,
        )
        val persisted = mergeBrowserPreviewIntoOutput(
            outputJson = JSONObject()
                .put("ok", true)
                .put(
                    "hits",
                    JSONArray().put(
                        JSONObject()
                            .put("title", "官方报名页")
                            .put("url", "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920"),
                    ),
                )
                .toString(),
            preview = BrowserDeskPreview(
                kind = BrowserDeskPreviewKind.Snapshot,
                title = "报名",
                body = "@e1 button 立即报名",
            ),
        )
        val restored = previewFromResult("page_snapshot", persisted)
        requireNotNull(restored)
        assertEquals("官方报名页", restored.hits.single().title)
    }

    @Test
    fun parkedDeskRestoresAfterClearAndSkipsEmptyPark() {
        val id = BrowserDesk.begin(
            "search_web",
            JSONObject().put("query", "西湖"),
        )
        BrowserDesk.complete(
            id,
            "search_web",
            JSONObject()
                .put("ok", true)
                .put(
                    "hits",
                    JSONArray().put(
                        JSONObject()
                            .put("title", "西湖景区")
                            .put("url", "https://travel.hangzhou.cn/west-lake")
                            .put("snippet", "杭州西湖"),
                    ),
                )
                .toString(),
        )
        // A session switch used to restore a saved copy of the desk. It now replays the log, which
        // is the same result by a route that has only one writer.
        val log = BrowserDesk.eventLog()
        BrowserDesk.clear()
        assertEquals(BrowserDeskPreviewKind.Empty, BrowserDesk.state.value.preview.kind)
        assertEquals("西湖景区", replayBrowserDesk(log).preview.hits.single().title)
    }

    @Test
    fun persistedPreviewRoundTripsThroughToolOutput() {
        val preview = BrowserDeskPreview(
            kind = BrowserDeskPreviewKind.Search,
            title = "西湖门票",
            hits = listOf(
                BrowserDeskHit(
                    title = "西湖景区",
                    url = "https://travel.hangzhou.cn/west-lake",
                    snippet = "杭州西湖",
                ),
            ),
        )
        val output = mergeBrowserPreviewIntoOutput("GUI_TASK_SUCCEEDED", preview)
        val restored = previewFromResult("Launching browser agent: 西湖门票", output)
        requireNotNull(restored)
        assertEquals(BrowserDeskPreviewKind.Search, restored.kind)
        assertEquals("西湖景区", restored.hits.single().title)
        assertEquals("杭州西湖", restored.hits.single().snippet)
    }

    @Test
    fun recordReadMarksMatchingHitReadAndMovesItFirst() {
        val id = BrowserDesk.begin(
            "search_web",
            JSONObject().put("query", "GPT6"),
        )
        BrowserDesk.complete(
            id,
            "search_web",
            JSONObject()
                .put("ok", true)
                .put(
                    "hits",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("title", "A")
                                .put("url", "https://www.pcmag.com/news/a")
                                .put("snippet", "one"),
                        )
                        .put(
                            JSONObject()
                                .put("title", "B")
                                .put("url", "https://www.theverge.com/news/b")
                                .put("snippet", "two"),
                        )
                        .put(
                            JSONObject()
                                .put("title", "C")
                                .put("url", "https://techcrunch.com/news/c")
                                .put("snippet", "three"),
                        ),
                )
                .toString(),
        )
        BrowserDesk.recordRead("https://www.theverge.com/news/b", "B", "fetched body about B")
        val hits = BrowserDesk.state.value.preview.hits
        assertEquals(listOf("B", "A", "C"), hits.map { it.title })
        // Reading a page marks it read. Nothing here has seen an answer yet, so nothing is cited.
        assertTrue(hits.first().read)
        assertFalse(hits.first().cited)
        assertTrue(hits.first().readExcerpt.contains("fetched body"))
        assertFalse(hits[1].read)
        assertFalse(hits[2].read)
        assertEquals(1, BrowserDesk.state.value.sources.size)
        assertEquals("https://www.theverge.com/news/b", BrowserDesk.state.value.sources.single().url)
    }

    @Test
    fun searchCompleteDoesNotCiteUnreadSerpHits() {
        val id = BrowserDesk.begin("search_web", JSONObject().put("query", "GPT6"))
        BrowserDesk.complete(
            id,
            "search_web",
            JSONObject()
                .put("ok", true)
                .put(
                    "hits",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("title", "A")
                                .put("url", "https://www.pcmag.com/news/a")
                                .put("snippet", "one"),
                        )
                        .put(
                            JSONObject()
                                .put("title", "B")
                                .put("url", "https://www.theverge.com/news/b")
                                .put("snippet", "two"),
                        ),
                )
                .toString(),
        )
        assertTrue(BrowserDesk.state.value.sources.isEmpty())
    }

    @Test
    fun requestOpenUrlStaysUntilCleared() {
        BrowserDesk.requestOpenUrl("https://www.cnbc.com/2026/09/03/open-ai-astra-gpt-6-cyber.html")
        assertEquals(
            "https://www.cnbc.com/2026/09/03/open-ai-astra-gpt-6-cyber.html",
            BrowserDesk.state.value.openedUrl,
        )
        BrowserDesk.clearOpenedUrl()
        assertEquals("", BrowserDesk.state.value.openedUrl)
    }
}
