package kira.ditto.browser

import kira.ditto.ui.hoistInlineMarkdownImages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The evermind turn: nine jobs, one capsule, a label copied off the user's first message.
 */
class BrowserActCapsuleTest {
    @After
    fun tearDown() {
        BrowserDesk.clear()
        BrowserTopicGraph.clear()
    }

    private val jobsAnswer = """
        已打开 EverMind 招聘页面，目前看到的岗位如下：

        | # | 岗位 | 地点 | 投递链接 |
        |---|------|------|---------|
        | 1 | **产品经理** | 硅谷 | [申请](https://evermind.ai/zh/careers/product-manager) |
        | 2 | **高级开发者社区运营专员** | 北京 | [申请](https://evermind.ai/zh/careers/senior-developer-community-operations-specialist) |
        | 3 | **人工智能算法工程师** | 上海 | [申请](https://evermind.ai/zh/careers/ai-algorithm-engineer) |
    """.trimIndent()

    @Test
    fun aCapsuleTheModelLabelledWithItsOwnUrlGetsARealName() {
        // session-1788835871813, verbatim: the model wrote the marker itself, with the address as
        // the label, so tapping it sent the URL twice — once inside the sentence, once on its own
        // line — and the button read like an address instead of naming the page.
        val answer = ensureBrowserActAnswer(
            markdown = """
                招聘主页：[https://evermind.ai/careers](https://evermind.ai/careers)

                你感兴趣的岗位是哪个？我可以直接帮你打开对应的投递页。

                [[browser-act:https://evermind.ai/careers|https://evermind.ai/careers]]
            """.trimIndent(),
            userText = "搜索 evermind ，并帮我打开他们的招聘投递页面，我想看看有哪些岗位在投",
            officialUrl = "",
        )
        val marker = firstBrowserActMarkerIn(answer)
        checkNotNull(marker)
        assertEquals("https://evermind.ai/careers", marker.url)
        // The line that links the page introduces it by name; that is the label.
        assertEquals("招聘主页", marker.label)
        assertEquals(
            "帮我打开并操作：招聘主页\nhttps://evermind.ai/careers",
            browserActFollowUpUserText(marker.label, marker.url),
        )
    }

    @Test
    fun aUrlLabelNeverReachesTheSentMessageTwice() {
        // Defence in depth: even if a URL label survives to the tap, the address is not repeated
        // inside the sentence. The host label stands in for the missing name.
        assertEquals(
            "帮我打开并操作：evermind.ai\nhttps://evermind.ai/careers",
            browserActFollowUpUserText(
                "https://evermind.ai/careers",
                "https://evermind.ai/careers",
            ),
        )
    }

    @Test
    fun capsuleUrlStopsAtTheClosingParenNotTwoSentencesLater() {
        // session-1788833382756, verbatim. The table listed nine jobs but carried no per-job links,
        // so the whole capsule had to be synthesised from one inline link sitting in Chinese prose.
        val answer = ensureBrowserActAnswer(
            markdown = """
                找到了，EverMind 的招聘页面在 [evermind.ai/zh/careers](https://evermind.ai/zh/careers)。让我打开看看有哪些岗位。页面已经加载完成，以下是 EverMind 目前在招的岗位：

                | 岗位 | 工作地点 | 类型 |
                |---|---|---|
                | 产品经理 | 硅谷 | 社招 |
                | 前端开发工程师 | 北京、上海、硅谷 | 社招 |

                来源：[EverMind 招聘页面](https://evermind.ai/zh/careers)
            """.trimIndent(),
            userText = "搜索 evermind ，并帮我打开他们的招聘投递页面，我想看看有哪些岗位在投",
            officialUrl = "",
        )
        val marker = firstBrowserActMarkerIn(answer)
        checkNotNull(marker)
        // The URL used to run past the closing paren and swallow "。让我打开看看有哪些岗位。页面已经…",
        // which is what the user saw in the bubble after tapping the capsule.
        assertEquals("https://evermind.ai/zh/careers", marker.url)
        // Two anchors point at this page: the bare address inline, and its real name under 来源.
        assertEquals("EverMind 招聘页面", marker.label)
        assertFalse(marker.label == "帮我打开网页并操作")
        // And the text actually sent names the page instead of restating the user's own verb.
        assertEquals(
            "帮我打开并操作：EverMind 招聘页面\nhttps://evermind.ai/zh/careers",
            browserActFollowUpUserText(marker.label, marker.url),
        )
    }

    @Test
    fun everyListedJobGetsItsOwnCapsuleNamedAfterTheJob() {
        val answer = ensureBrowserActAnswer(
            markdown = jobsAnswer,
            userText = "搜索 evermind ，并帮我打开他们的招聘投递页面，我想看看有哪些岗位在投",
            officialUrl = "",
        )
        val markers = answer.lines().mapNotNull { parseBrowserActMarker(it.trim()) }
        assertEquals(3, markers.size)
        assertEquals(
            listOf("产品经理", "高级开发者社区运营专员", "人工智能算法工程师"),
            markers.map { it.label },
        )
        // The generic phrase is what the user objected to; no capsule should carry it now.
        assertFalse(markers.any { it.label == "帮我打开网页并操作" })
    }

    @Test
    fun anExistingGenericCapsuleIsReplacedNotKept() {
        val withMarker = jobsAnswer + "\n\n" +
            "[[browser-act:帮我打开网页并操作|https://evermind.ai/zh/careers/ai-algorithm-engineer]]"
        val answer = ensureBrowserActAnswer(
            markdown = withMarker,
            userText = "搜索 evermind ，并帮我打开他们的招聘投递页面",
            officialUrl = "",
        )
        assertFalse(answer.contains("browser-act:帮我打开网页并操作"))
        assertEquals(3, answer.lines().count { parseBrowserActMarker(it.trim()) != null })
    }

    @Test
    fun tappingACapsuleStillReadsAsAnOperateRequest() {
        val sent = browserActFollowUpUserText(
            label = "高级开发者社区运营专员",
            url = "https://evermind.ai/zh/careers/senior-developer-community-operations-specialist",
        )
        // A bare job title is not an operate intent, so the sent text has to supply the verb.
        assertTrue(looksLikeBrowserOperateFollowUp(sent))
        assertTrue(sent.contains("高级开发者社区运营专员"))
        assertEquals(
            "https://evermind.ai/zh/careers/senior-developer-community-operations-specialist",
            browserActOperateUrlFromUserText(sent),
        )
    }

    @Test
    fun aLabelThatAlreadyCarriesIntentIsNotDecorated() {
        val sent = browserActFollowUpUserText(
            label = "帮我打开官网报名",
            url = "https://scrm.nvidia.cn/lp/dgx",
        )
        assertTrue(sent.startsWith("帮我打开官网报名\n"))
    }

    // ---------- the rail is a row of agents, not of queries ----------

    @Test
    fun oneAgentRunningManyImageSearchesStaysOneCard() {
        val state = BrowserDeskState(
            previewsByTopic = mapOf(
                "合肥美食" to BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Search,
                    title = "合肥美食",
                    hits = listOf(BrowserDeskHit(title = "合肥", url = "https://a.example/1")),
                ),
                "合肥庐州烤鸭" to BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Images,
                    title = "合肥庐州烤鸭",
                ),
                "合肥臭鳜鱼徽菜" to BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Images,
                    title = "合肥臭鳜鱼徽菜",
                ),
                "合肥肥西老母鸡汤" to BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Images,
                    title = "合肥肥西老母鸡汤",
                ),
            ),
        )
        // One agent ran all of those searches.
        assertEquals(1, browserTopicRailItems(state, agentCount = 1).size)
        assertEquals("合肥美食", browserTopicRailItems(state, agentCount = 1).single().topicId)
        // It used to need the agent count to tell queries from agents, and without one it kept all
        // four. It no longer needs to ask: an image lookup is not a research group, so the same one
        // tab comes out either way — and a turn that really does research three subjects now splits
        // into three tabs as they land, instead of being held at one by the agent count.
        assertEquals(1, browserTopicRailItems(state).size)
        assertEquals("合肥美食", browserTopicRailItems(state).single().topicId)
    }

    @Test
    fun realSwarmMembersStillGetOneCardEach() {
        val state = BrowserDeskState(
            previewsByTopic = mapOf(
                "杭州美食" to BrowserDeskPreview(kind = BrowserDeskPreviewKind.Search, title = "杭州美食"),
                "杭州景点" to BrowserDeskPreview(kind = BrowserDeskPreviewKind.Search, title = "杭州景点"),
                "杭州美食排行" to BrowserDeskPreview(kind = BrowserDeskPreviewKind.Images, title = "排行"),
            ),
        )
        val rail = browserTopicRailItems(state, swarmItems = listOf("杭州美食 推荐", "杭州景点"))
        assertEquals(2, rail.size)
        assertEquals(listOf("杭州美食", "杭州景点"), rail.map { it.topicId })
    }

    @Test
    fun aNewTaskStartsWithABlankCard() {
        BrowserDesk.recordSearch(
            query = "杭州美食",
            url = "https://www.bing.com/search?q=x",
            hits = listOf(BrowserDeskHit(title = "西湖", url = "https://a.example/1")),
        )
        assertTrue(BrowserDesk.state.value.previewsByTopic.isNotEmpty())
        BrowserDesk.configureLookupLoop(deepSearch = false)
        val after = BrowserDesk.state.value
        assertTrue(after.previewsByTopic.isEmpty())
        assertEquals(BrowserDeskPreviewKind.Empty, after.preview.kind)
    }

    // ---------- inline images ----------

    @Test
    fun anImageInsideAParagraphIsLiftedOntoItsOwnLine() {
        val hoisted = hoistInlineMarkdownImages(
            "- **老北京炸酱面** ![老北京炸酱面](https://img.example/zjm.jpg) 手擀面配炸酱",
        )
        assertEquals(
            listOf(
                "- **老北京炸酱面**",
                "![老北京炸酱面](https://img.example/zjm.jpg)",
                "手擀面配炸酱",
            ),
            hoisted.lines(),
        )
    }

    // ---------- the live surface belongs to operate turns ----------

    @Test
    fun aLookupTurnKeepsTheResultCardsInsteadOfTheLiveSerp() {
        BrowserDesk.configureLookupLoop(deepSearch = false)
        BrowserDesk.recordSearch(
            query = "最新科技新闻",
            url = "https://www.bing.com/search?q=x",
            hits = listOf(BrowserDeskHit(title = "Reuters", url = "https://reuters.com/tech")),
        )
        // Every browser tool used to call this, which switched the card to the live Gecko tab.
        BrowserDesk.holdLivePage("https://www.bing.com/search?q=x")
        BrowserDesk.holdLivePage()
        val state = BrowserDesk.state.value
        assertFalse(state.keepLiveSurface)
        assertEquals("", state.openedUrl)
        assertTrue(state.preview.hits.isNotEmpty())
    }

    @Test
    fun anOperateTurnStillHoldsTheLivePage() {
        BrowserDesk.configureLookupLoop(
            deepSearch = false,
            operateUrl = "https://evermind.ai/zh/careers/product-manager",
            keepLivePage = true,
        )
        BrowserDesk.holdLivePage("https://evermind.ai/zh/careers/product-manager")
        val state = BrowserDesk.state.value
        assertTrue(state.keepLiveSurface)
        assertEquals("https://evermind.ai/zh/careers/product-manager", state.openedUrl)
    }

    @Test
    fun tappingAResultOpensThatPageEvenOnALookupTurn() {
        BrowserDesk.configureLookupLoop(deepSearch = false)
        BrowserDesk.requestOpenUrl("https://reuters.com/tech")
        assertTrue(BrowserDesk.state.value.keepLiveSurface)
        assertEquals("https://reuters.com/tech", BrowserDesk.state.value.openedUrl)
        // And the back control returns to the result list.
        BrowserDesk.clearOpenedUrl()
        assertFalse(BrowserDesk.state.value.keepLiveSurface)
        assertEquals("", BrowserDesk.state.value.openedUrl)
    }

    // ---------- helper tools join the current group ----------

    @Test
    fun onlySearchAndNavigationOpenAResearchGroup() {
        assertTrue(browserToolOpensTopic("search_web"))
        assertTrue(browserToolOpensTopic("mcp__webmcp__search_images"))
        assertTrue(browserToolOpensTopic("tabs_navigate"))
        assertFalse(browserToolOpensTopic("browser_fetch_many"))
        assertFalse(browserToolOpensTopic("mcp__webmcp__page_recall"))
        assertFalse(browserToolOpensTopic("browser_find_signup"))
    }

    @Test
    fun aRankingQueryDoesNotMintASecondGroup() {
        val search = BrowserTopicGraph.ensure(
            org.json.JSONObject().put("query", "technology September"),
            sessionId = "s1",
            tool = "search_web",
        )
        assertEquals("technology September", search)
        // browser_fetch_many carries a query for snippet ranking, not a new line of research.
        val fetchMany = BrowserTopicGraph.ensure(
            org.json.JSONObject().put("query", "latest technology news September 2026"),
            sessionId = "s1",
            tool = "browser_fetch_many",
        )
        assertEquals(search, fetchMany)
        assertEquals(1, BrowserTopicGraph.snapshot("s1").size)
    }

    @Test
    fun aTabWhoseLabelMatchesNothingStillGetsAGroup() {
        // The swarm label and the query the agent ran normalise differently; containment cannot
        // bridge them, and the tab used to resolve to an empty preview.
        val state = BrowserDeskState(
            previewsByTopic = mapOf(
                "tech September AI breakthroughs" to BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Search,
                    title = "tech September AI breakthroughs",
                    hits = listOf(BrowserDeskHit(title = "Reuters", url = "https://a.example/1")),
                ),
                "最新进展" to BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Search,
                    title = "最新进展",
                    hits = listOf(BrowserDeskHit(title = "Zhihu", url = "https://a.example/2")),
                ),
            ),
        )
        val rail = browserTopicRailItems(
            state,
            swarmItems = listOf(
                "latest tech news September 2025 AI breakthroughs",
                "AI 最新进展 2026年9月 人工智能 大模型",
            ),
        )
        assertEquals(2, rail.size)
        assertTrue("every tab must carry hits", rail.all { it.preview.hits.isNotEmpty() })
        assertEquals(
            listOf("https://a.example/1", "https://a.example/2").sorted(),
            rail.flatMap { item -> item.preview.hits.map { it.url } }.sorted(),
        )
    }

    // ---------- search words ----------

    @Test
    fun theBrowserProfileForbidsInventedDatesAndParaphrases() {
        val profile = kira.ditto.data.KimiBrowserSubagentProfileMarkdown
        assertTrue(profile.contains("Do not append a year"))
        assertTrue(profile.contains("One search is the normal number"))
    }

    @Test
    fun cleanImageLinesTablesAndCodeAreLeftAlone() {
        val imageOnly = "![庐州烤鸭](https://img.example/kaoya.jpg)"
        assertEquals(imageOnly, hoistInlineMarkdownImages(imageOnly))

        val row = "| 1 | 炸酱面 | ![炸酱面](https://img.example/a.jpg) |"
        assertEquals(row, hoistInlineMarkdownImages(row))

        val fenced = "```\ntext ![x](https://img.example/b.jpg) more\n```"
        assertEquals(fenced, hoistInlineMarkdownImages(fenced))
    }
}
