package kira.ditto.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards for the harness rework: HTML parsing, cross-agent tab grouping and turn boundaries.
 */
class BrowserHarnessIsolationTest {
    @After
    fun tearDown() {
        BrowserDesk.clear()
        BrowserTopicGraph.clear()
    }

    // ---------- fetch parsing ----------

    @Test
    fun readableTextDropsCodeAndKeepsProse() {
        val html = """
            <html><head><title>西安美食</title>
            <style>.a{color:red}</style>
            <script>var x = "<p>not prose</p>";</script>
            </head>
            <body><h1>肉夹馍</h1><p>白吉馍&nbsp;酥得掉渣。</p><p>第二段</p>
            <noscript>enable js</noscript></body></html>
        """.trimIndent()
        val text = readableTextFromHtml(html)
        assertTrue(text.contains("肉夹馍"))
        assertTrue(text.contains("白吉馍 酥得掉渣。"))
        assertFalse(text.contains("color:red"))
        assertFalse(text.contains("not prose"))
        assertFalse(text.contains("enable js"))
    }

    @Test
    fun readableTextSeparatesBlocksSoChunkingHasParagraphs() {
        val text = readableTextFromHtml("<p>第一段</p><p>第二段</p>")
        assertTrue("expected a blank line between blocks: $text", text.contains("\n\n"))
        assertEquals(2, text.split("\n\n").size)
    }

    @Test
    fun readableTextDecodesEntitiesAndHonoursBudget() {
        assertEquals("a&b<c>d", readableTextFromHtml("a&amp;b&lt;c&gt;d"))
        assertEquals("·…", readableTextFromHtml("&middot;&hellip;"))
        assertEquals("中", readableTextFromHtml("&#20013;"))
        val long = readableTextFromHtml("x".repeat(5_000), maxChars = 100)
        assertTrue(long.length <= 100)
    }

    @Test
    fun readableTextSurvivesUnclosedTags() {
        assertEquals("tail", readableTextFromHtml("<div class=\"a\">tail"))
        assertEquals("", readableTextFromHtml("<script>never closed"))
    }

    @Test
    fun charsetComesFromHeaderThenMetaThenUtf8() {
        val gbkMeta = "<html><head><meta charset=\"gb18030\">".toByteArray(Charsets.ISO_8859_1)
        assertEquals("GB18030", charsetForHtmlBody("", gbkMeta).name())
        assertEquals(
            "GBK",
            charsetForHtmlBody("text/html; charset=GBK", gbkMeta).name(),
        )
        assertEquals("UTF-8", charsetForHtmlBody("text/html", ByteArray(0)).name())
    }

    // ---------- topic grouping ----------

    @Test
    fun relatedTopicKeysMatchLabelAndQuery() {
        // The subagent's label and the query it runs normalise differently.
        assertTrue(browserTopicKeysRelated("西安美食攻略 必吃特色小吃", "西安美食 肉夹馍 凉皮"))
        assertTrue(browserTopicKeysRelated("杭州美食 特色小吃 推荐", "杭州美食"))
        assertFalse(browserTopicKeysRelated("西安美食", "上海景点"))
        assertFalse(browserTopicKeysRelated("", "西安美食"))
    }

    @Test
    fun previewForTopicNeverBorrowsAnotherGroupsPage() {
        val mine = BrowserDeskPreview(
            kind = BrowserDeskPreviewKind.Search,
            title = "西安美食",
            hits = listOf(BrowserDeskHit(title = "肉夹馍", url = "https://a.example/1")),
        )
        val theirs = BrowserDeskPreview(
            kind = BrowserDeskPreviewKind.Article,
            title = "上海景点",
            body = "外滩",
        )
        val state = BrowserDeskState(
            preview = theirs,
            previewsByTopic = mapOf("西安美食" to mine, "上海景点" to theirs),
        )
        assertEquals(mine, browserPreviewForTopic(state, "西安美食攻略"))
        assertEquals(
            BrowserDeskPreview(),
            browserPreviewForTopic(state, "成都火锅"),
        )
        // With nothing filed yet the live preview is still the best answer.
        assertEquals(
            theirs,
            browserPreviewForTopic(BrowserDeskState(preview = theirs), "成都火锅"),
        )
    }

    @Test
    fun concurrentSessionsKeepSeparateTabGroups() {
        BrowserTopicGraph.openOrGet("西安美食", sessionId = "agent-a")
        BrowserTopicGraph.bindGeckoTab("西安美食", "tab-a", asImageTab = false, sessionId = "agent-a")
        BrowserTopicGraph.openOrGet("上海景点", sessionId = "agent-b")
        BrowserTopicGraph.bindGeckoTab("上海景点", "tab-b", asImageTab = false, sessionId = "agent-b")

        assertEquals("tab-a", BrowserTopicGraph.primaryTabId("西安美食", "agent-a"))
        assertEquals("tab-b", BrowserTopicGraph.primaryTabId("上海景点", "agent-b"))
        // Agent A's group must not be visible in agent B's bucket.
        assertEquals("", BrowserTopicGraph.primaryTabId("西安美食", "agent-b"))
        assertEquals("tab-a", BrowserTopicGraph.lastPrimaryTabId("agent-a"))
        assertEquals("tab-b", BrowserTopicGraph.lastPrimaryTabId("agent-b"))
    }

    // ---------- turn boundary ----------

    @Test
    fun retireTurnDropsLastTasksTabsButKeepsTheHeldPage() {
        BrowserTopicGraph.openOrGet("西安美食", sessionId = "s1")
        BrowserTopicGraph.bindGeckoTab("西安美食", "tab-old", asImageTab = false, sessionId = "s1")
        BrowserTopicGraph.openOrGet("报名页", sessionId = "s1")
        BrowserTopicGraph.bindGeckoTab("报名页", "tab-keep", asImageTab = false, sessionId = "s1")

        val dropped = BrowserTopicGraph.retireTurn("s1", keepTabId = "tab-keep")
        assertEquals(setOf("tab-old"), dropped)
        assertEquals("tab-keep", BrowserTopicGraph.primaryTabId("报名页", "s1"))
        assertEquals("", BrowserTopicGraph.primaryTabId("西安美食", "s1"))
        // Another session is untouched.
        assertEquals(emptySet<String>(), BrowserTopicGraph.retireTurn("s2"))
    }

    @Test
    fun turnIdAdvancesWithEachUserMessage() {
        val before = BrowserDesk.turnId()
        BrowserDesk.configureLookupLoop(deepSearch = false)
        val after = BrowserDesk.turnId()
        assertTrue("turn id must advance: $before -> $after", after > before)
    }

    // ---------- takeover gate ----------

    @Test
    fun takeoverGateReleasesTheParkedCall() {
        assertFalse(BrowserDesk.isAwaitingUserTakeover())
        BrowserDesk.requestUserTakeover("https://example.com/verify", "challenge")
        assertTrue(BrowserDesk.isAwaitingUserTakeover())

        val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
        val waiter = Thread { resumed.set(BrowserDesk.awaitUserTakeover(5_000L)) }
        waiter.start()
        Thread.sleep(120)
        assertTrue("a call was parked, so the tap resumes it", BrowserDesk.completeUserTakeover())
        waiter.join(5_000L)
        assertTrue(resumed.get())
        assertFalse(BrowserDesk.isAwaitingUserTakeover())
        // With nothing parked the tap falls back to sending a message.
        assertFalse(BrowserDesk.completeUserTakeover())
    }
}
