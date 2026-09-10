package kira.ditto.browser

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTopicGraphTest {
    @After
    fun tearDown() {
        BrowserTopicGraph.clear()
    }

    @Test
    fun normalizeStripsPhotoAndRecommendWords() {
        assertEquals("上海美食", normalizeBrowserTopicId("上海美食图片推荐"))
        assertEquals("本帮菜", normalizeBrowserTopicId("本帮菜 必吃"))
    }

    @Test
    fun overlappingQueriesMergeIntoOneTopic() {
        val first = BrowserTopicGraph.openOrGet("上海美食")
        val second = BrowserTopicGraph.openOrGet("上海美食推荐")
        assertEquals("上海美食", first)
        assertEquals(first, second)
        assertEquals(1, BrowserTopicGraph.snapshot().size)
    }

    @Test
    fun searchHitsAndImagesBindToTheSameTopicId() {
        BrowserTopicGraph.recordHits(
            query = "上海本帮菜",
            hits = listOf(
                BrowserDeskHit("本帮菜", "https://example.com/food", "蟹粉"),
            ),
        )
        BrowserTopicGraph.recordImages(
            query = "上海本帮菜 图片",
            images = listOf(
                BrowserDeskImage("https://cdn.example/xiaolongbao.jpg", "上海小笼包"),
                BrowserDeskImage("https://cdn.example/unrelated.png", "杭州西湖"),
                BrowserDeskImage("https://cdn.example/favicon.ico", "icon"),
            ),
        )
        val tab = BrowserTopicGraph.snapshot().single()
        assertEquals("上海本帮菜", tab.topicId)
        assertEquals(1, tab.hits.size)
        assertEquals(listOf("https://cdn.example/xiaolongbao.jpg"), tab.images.map { it.url })
    }

    @Test
    fun imageMatchesRequiresPrimaryAnchor() {
        assertTrue(imageMatchesTopic("大同的美食并附图", "大同刀削面", "https://cdn.example/food.jpg"))
        assertFalse(imageMatchesTopic("大同的美食并附图", "杭州西湖", "https://cdn.example/west-lake.jpg"))
        assertFalse(imageMatchesTopic("大同的美食并附图", "", "https://cdn.example/random.png"))
        assertEquals("大同", topicPrimaryAnchor("大同的美食并附图"))
    }

    @Test
    fun articleNavigateReusesLastTopicInsteadOfOpeningATitle() {
        val topic = BrowserTopicGraph.openOrGet("Claude 新闻")
        val reused = BrowserTopicGraph.ensure(
            JSONObject()
                .put("url", "https://tech-insider.org/claude")
                .put("query", "Introducing Claude Fable"),
        )
        assertEquals(topic, reused)
        assertEquals(1, BrowserTopicGraph.snapshot().size)
    }

    @Test
    fun topicIdArgumentWinsOverQuery() {
        val id = BrowserTopicGraph.ensure(
            JSONObject()
                .put("query", "best xiaolongbao photos")
                .put("topic_id", "上海小笼包"),
        )
        assertEquals("上海小笼包", id)
    }

    @Test
    fun researchTabsAreExcludedFromLastTabUrls() {
        BrowserTopicGraph.attachGeckoTab("gecko-research-1", "上海美食")
        assertTrue(BrowserTopicGraph.ownsGeckoTab("gecko-research-1"))
        assertFalse(shouldPersistBrowserTab("gecko-research-1", "https://travel.hangzhou.cn/west-lake"))
        assertTrue(shouldPersistBrowserTab("user-tab", "https://travel.hangzhou.cn/west-lake"))
        val closed = BrowserTopicGraph.takeGeckoTabIdsAndClear()
        assertEquals(setOf("gecko-research-1"), closed)
        assertTrue(BrowserTopicGraph.snapshot().isEmpty())
        assertFalse(BrowserTopicGraph.ownsGeckoTab("gecko-research-1"))
    }

    @Test
    fun headingMatchesSealedTopicId() {
        assertTrue(headingMatchesTopic("上海美食图鉴", "上海美食"))
        assertTrue(headingMatchesTopic("本帮菜", "上海本帮菜"))
        assertFalse(headingMatchesTopic("杭州西湖", "上海美食"))
    }

    @Test
    fun shanghaiSubtopicsDoNotMergeByContains() {
        val first = BrowserTopicGraph.openOrGet("上海本帮菜")
        val second = BrowserTopicGraph.openOrGet("上海小吃")
        assertEquals("上海本帮菜", first)
        assertEquals("上海小吃", second)
        assertEquals(2, BrowserTopicGraph.snapshot().size)
    }

    @Test
    fun primaryAndImageTabsStayDistinct() {
        BrowserTopicGraph.openOrGet("上海本帮菜")
        BrowserTopicGraph.bindGeckoTab("上海本帮菜", "tab-research", asImageTab = false)
        BrowserTopicGraph.bindGeckoTab("上海本帮菜", "tab-images", asImageTab = true)
        val tab = BrowserTopicGraph.snapshot().single()
        assertEquals("tab-research", tab.primaryTabId)
        assertEquals("tab-images", tab.imageTabId)
        assertEquals(setOf("tab-research", "tab-images"), tab.geckoTabIds)
        assertEquals("tab-research", BrowserTopicGraph.primaryTabId("上海本帮菜"))
        assertEquals("tab-images", BrowserTopicGraph.imageTabId("上海本帮菜"))
    }

    @Test
    fun sessionsKeepSeparateTopicBuckets() {
        BrowserTopicGraph.openOrGet("本帮菜", sessionId = "session-a")
        BrowserTopicGraph.openOrGet("西湖", sessionId = "session-b")
        assertEquals(listOf("本帮菜"), BrowserTopicGraph.snapshot("session-a").map { it.topicId })
        assertEquals(listOf("西湖"), BrowserTopicGraph.snapshot("session-b").map { it.topicId })
        val closed = BrowserTopicGraph.takeGeckoTabIdsAndClear("session-a")
        assertTrue(closed.isEmpty())
        assertTrue(BrowserTopicGraph.snapshot("session-a").isEmpty())
        assertEquals(listOf("西湖"), BrowserTopicGraph.snapshot("session-b").map { it.topicId })
    }

    @Test
    fun browserSwarmItemsPreopenTopics() {
        BrowserTopicGraph.noteBrowserSwarm(
            sessionId = "session-food",
            toolName = "AgentSwarm",
            argumentsJson = """{"items":["上海本帮菜","上海小吃"],"subagent_type":"browser"}""",
        )
        assertEquals(
            listOf("上海本帮菜", "上海小吃"),
            BrowserTopicGraph.snapshot("session-food").map { it.topicId },
        )
    }

    @Test
    fun resolveWorkspacePrefersImageTabForImageSearch() {
        BrowserTopicGraph.openOrGet("本帮菜")
        BrowserTopicGraph.bindGeckoTab("本帮菜", "tab-a", asImageTab = false)
        BrowserTopicGraph.bindGeckoTab("本帮菜", "tab-img", asImageTab = true)
        val target = resolveBrowserTopicTarget(
            arguments = JSONObject().put("topic_id", "本帮菜").put("query", "本帮菜"),
            forImages = true,
        )
        assertEquals("本帮菜", target.topicId)
        assertEquals("tab-img", target.tabId)
        assertTrue(target.asImageTab)
        val research = resolveBrowserTopicTarget(
            arguments = JSONObject().put("topic_id", "本帮菜"),
            forImages = false,
        )
        assertEquals("tab-a", research.tabId)
        assertFalse(research.asImageTab)
    }

    @Test
    fun laterPrimaryTabReplacesSeedTab() {
        BrowserTopicGraph.openOrGet("本帮菜")
        BrowserTopicGraph.bindGeckoTab("本帮菜", "seed-blank", asImageTab = false)
        BrowserTopicGraph.bindGeckoTab("本帮菜", "opened-official", asImageTab = false)
        assertEquals("opened-official", BrowserTopicGraph.primaryTabId("本帮菜"))
    }
}
