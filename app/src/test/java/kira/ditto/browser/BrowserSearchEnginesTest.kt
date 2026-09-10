package kira.ditto.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSearchEnginesTest {
    @Test
    fun searchEngineRedirectHopsAreNotSerpChrome() {
        assertTrue(isSearchEngineRedirectHop("https://www.bing.com/ck/a?!&&p=1&ig=deadbeef"))
        assertTrue(isSearchEngineRedirectHop("https://www.google.com/url?q=https%3A%2F%2Fdianping.com%2Fshop%2F1"))
        assertTrue(isSearchEngineRedirectHop("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fopenai.com"))
        assertTrue(isSearchEngineRedirectHop("https://www.baidu.com/link?url=abcdef"))
        assertFalse(isSearchEngineRedirectHop("https://www.bing.com/search?q=%E4%B8%8A%E6%B5%B7%E7%BE%8E%E9%A3%9F"))
        assertFalse(isSearchEngineRedirectHop("https://www.bing.com/images/search?q=xiaolongbao"))
        assertFalse(isSearchEngineRedirectHop("https://www.dianping.com/shop/1"))
    }

    @Test
    fun prefetchableUrlsAllowHopsAndDropSerp() {
        val urls = prefetchableSearchUrls(
            listOf(
                "https://www.bing.com/search?q=x",
                "https://www.bing.com/ck/a?!&&p=1",
                "https://www.dianping.com/shop/1",
                "https://example.com/docs",
                "https://www.dianping.com/shop/1?utm_source=bing",
            ),
        )
        assertEquals(
            listOf(
                "https://www.bing.com/ck/a?!&&p=1",
                "https://www.dianping.com/shop/1",
            ),
            urls,
        )
        assertTrue(isPrefetchableBrowserUrl("https://www.bing.com/ck/a?!&&p=1"))
        assertFalse(isPrefetchableBrowserUrl("https://www.bing.com/search?q=x"))
        assertFalse(isPrefetchableBrowserUrl("https://example.org/x"))
    }

    @Test
    fun seedAboutBlankIsNotASettledPageLoad() {
        assertTrue(isSeedBrowserUrl("about:blank"))
        assertTrue(isSeedBrowserUrl("about:blank#aether-12"))
        assertTrue(isSeedBrowserUrl("about:newtab"))
        assertFalse(isSeedBrowserUrl("https://www.bing.com/search?q=x"))
        assertFalse(isSeedBrowserUrl("about:neterror?e=dnsNotFound"))
        assertFalse(
            browserPageLoadSettled(
                url = "about:blank#aether-1",
                title = "",
                loading = false,
            ),
        )
        assertTrue(
            browserPageLoadSettled(
                url = "https://www.bing.com/search?q=%E8%A5%BF%E6%B9%96",
                title = "西湖 - 搜索",
                loading = false,
            ),
        )
        assertFalse(
            browserPageLoadSettled(
                url = "https://www.bing.com/search?q=x",
                title = "x",
                loading = true,
            ),
        )
        assertTrue(
            browserPageLoadSettled(
                url = "about:neterror?e=dnsNotFound",
                title = "Server not found",
                loading = false,
            ),
        )
        assertFalse(
            isMissingBrowserPage(
                url = "https://www.google.com/search?q=nvidia",
                title = "Page Not Found | NVIDIA",
                requestedUrl = "https://www.google.com/search?q=nvidia",
            ),
        )
        assertFalse(
            isMissingBrowserPage(
                url = "https://www.x-techcon.com/article/183222.html",
                title = "Page Not Found | NVIDIA",
                requestedUrl = "https://www.x-techcon.com/article/183222.html",
            ),
        )
        assertTrue(
            isMissingBrowserPage(
                url = "https://www.nvidia.cn/object/deep-learning-hackathon.html",
                title = "Page Not Found | NVIDIA",
                requestedUrl = "https://www.nvidia.cn/object/deep-learning-hackathon.html",
            ),
        )
        assertFalse(
            isMissingBrowserPage(
                url = "https://www.nvidia.cn/object/deep-learning-hackathon.html",
                title = "Page Not Found | NVIDIA",
                requestedUrl = "https://www.x-techcon.com/article/183222.html",
            ),
        )
        assertTrue(
            isStaleMissingPageTitle(
                "https://www.google.com/search?q=x",
                "Page Not Found | NVIDIA",
            ),
        )
        assertFalse(
            browserPageLoadSettled(
                url = "https://www.google.com/search?q=x",
                title = "Page Not Found | NVIDIA",
                loading = true,
                requestedUrl = "https://www.google.com/search?q=x",
            ),
        )
    }

    @Test
    fun captchaAndCloudflareAreChallengesNotMissingPages() {
        assertTrue(
            detectBrowserChallenge(
                url = "https://example.com/apply",
                title = "Just a moment...",
                text = "Checking your browser before accessing example.com",
            ),
        )
        assertTrue(
            detectBrowserChallenge(
                url = "https://scrm.nvidia.cn/lp/x",
                title = "安全验证",
                text = "请完成人机验证后继续",
            ),
        )
        assertFalse(
            detectBrowserChallenge(
                url = "https://www.nvidia.cn/missing",
                title = "Page Not Found | NVIDIA",
                text = "404",
            ),
        )
        assertFalse(
            detectBrowserChallenge(
                url = "https://www.x-techcon.com/article/1",
                title = "第三届 NVIDIA DGX Spark 黑客松",
                text = "扫描文末二维码报名。点击阅读原文了解详情。".repeat(20),
            ),
        )
    }

    @Test
    fun bingSearchUsesInternationalEngine() {
        val url = normalizeBrowserAddress("西湖门票", kira.ditto.data.BrowserPreferences(searchEngineId = "bing"))
        assertTrue(url.startsWith("https://www.bing.com/search?q="))
        assertTrue(url.contains("ensearch=1"))
        assertTrue(url.contains("cc=US"))
        assertTrue(url.contains("setlang=en"))
        val china = internationalizeBingUrl("https://cn.bing.com/search?q=xihu&mkt=zh-CN")
        assertTrue(china.startsWith("https://www.bing.com/search?"))
        assertTrue(china.contains("ensearch=1"))
        assertFalse(china.contains("cn.bing.com"))
        assertFalse(china.contains("mkt="))
        assertTrue(isBrowserSerpUrl("https://www.bing.com/search?q=xihu&ensearch=1"))
        assertEquals("xihu", searchQueryFromBrowserUrl("https://www.bing.com/search?q=xihu&ensearch=1"))
        val once = internationalizeBingUrl("https://www.bing.com/search?q=xihu")
        assertEquals(once, internationalizeBingUrl(once))
        val image = normalizeBrowserImageSearch("西湖", kira.ditto.data.BrowserPreferences(searchEngineId = "bing"))
        assertTrue(image.contains("ensearch=1"))
        assertTrue(image.startsWith("https://www.bing.com/images/search?q="))
    }

    @Test
    fun humanizeSearchQueryKeepsShortHumanPhrases() {
        assertEquals("西湖门票", humanizeBrowserSearchQuery("帮我搜索西湖门票多少钱"))
        assertEquals("英伟达 dgx", humanizeBrowserSearchQuery("英伟达 dgx 2025 最新"))
        assertEquals("英伟达 dgx", humanizeBrowserSearchQuery("英伟达 dgx 2026 最新"))
        assertEquals("F# compiler", humanizeBrowserSearchQuery("F# compiler 2025 2026"))
        assertEquals("WWDC", humanizeBrowserSearchQuery("WWDC 2026"))
        assertEquals("2026", humanizeBrowserSearchQuery("2026"))
        assertEquals(
            "GPT6",
            humanizeBrowserSearchQuery(
                "GPT6 latest news 2025 · pcmag.com · GPT-6 Astra OpenAI September 2026",
            ),
        )
        assertEquals(
            "GPT6 Astra OpenAI September release",
            humanizeBrowserSearchQuery("GPT6 Astra OpenAI CNBC pcmag September release"),
        )
        assertEquals(
            "第三届 NVIDIA DGX Spark 黑客松",
            humanizeBrowserSearchQuery(
                "帮我搜索第三届 NVIDIA DGX Spark 黑客松 的信息，并打开官网帮我报名",
            ),
        )
        assertEquals(
            "第三届NVIDIA DGX Spark 黑客松",
            humanizeBrowserSearchQuery(
                "帮我搜索第三届NVIDIA DGX Spark 黑客松的信息，并打开官网帮我报名",
            ),
        )
        assertEquals(
            "https://www.nvidia.com/dgx-spark-hackathon",
            humanizeBrowserSearchQuery(
                "帮我打开官网报名\nhttps://www.nvidia.com/dgx-spark-hackathon",
            ),
        )
    }

    @Test
    fun htmlExtractDropsScriptsAndReadsTitle() {
        val html = """
            <html><head><title> 南翔馒头店 </title>
            <script>var x = "ignore"</script></head>
            <body><p>小笼包是上海的经典点心，汤汁鲜美。</p></body></html>
        """.trimIndent()
        assertEquals("南翔馒头店", titleFromHtml(html))
        val text = readableTextFromHtml(html)
        assertTrue(text.contains("小笼包是上海的经典点心"))
        assertFalse(text.contains("ignore"))
    }

    @Test
    fun wrongHostDocumentIsNotTheRequestedSite() {
        assertTrue(
            isWrongHostDocument(
                "https://www.toutiao.com/article/1",
                "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
            ),
        )
        assertFalse(
            isWrongHostDocument(
                "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
                "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
            ),
        )
        assertFalse(
            isWrongHostDocument(
                "https://www.nvidia.cn/dgx-spark",
                "https://scrm.nvidia.cn/lp/x",
            ),
        )
        assertFalse(
            browserPageLoadSettled(
                url = "https://www.toutiao.com/article/1",
                title = "头条",
                loading = false,
                requestedUrl = "https://scrm.nvidia.cn/lp/x",
            ),
        )
    }

    @Test
    fun disconnectedSnapshotIsNotSuccess() {
        val disconnected = org.json.JSONObject()
            .put("ok", true)
            .put("errmsg", "Could not establish connection. Receiving end does not exist.")
            .put("url", "about:blank#aether-0")
            .put("tree", "")
        assertTrue(isDisconnectedBrowserResult(disconnected))
        assertTrue(
            isDisconnectedBrowserResult(
                org.json.JSONObject()
                    .put("ok", false)
                    .put("code", "extension_not_ready")
                    .put("errmsg", "WebMCP extension is not connected yet."),
            ),
        )
        assertFalse(
            isDisconnectedBrowserResult(
                org.json.JSONObject()
                    .put("ok", true)
                    .put("url", "https://scrm.nvidia.cn/lp/x")
                    .put("tree", "@e1 button 立即报名"),
            ),
        )
        assertTrue(
            isDisconnectedBrowserResult(
                org.json.JSONObject()
                    .put("ok", false)
                    .put("code", "page_not_ready")
                    .put("errmsg", "Page tools are not ready yet."),
            ),
        )
        assertFalse(isUsableBrowserScreenshot(4, 4))
        assertFalse(isUsableBrowserScreenshot(16, 900))
        assertTrue(isUsableBrowserScreenshot(360, 640))
    }

    @Test
    fun addressMarkupStarsAreStripped() {
        val prefs = kira.ditto.data.BrowserPreferences()
        assertEquals(
            "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
            normalizeBrowserAddress(
                "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920**",
                prefs,
            ),
        )
        assertEquals(
            "https://scrm.nvidia.cn/lp/x",
            stripBrowserAddressMarkup("**https://scrm.nvidia.cn/lp/x**"),
        )
    }
}
