package kira.ditto.ui

import kira.ditto.R
import kira.ditto.browser.BrowserDeskActivity
import kira.ditto.browser.BrowserDeskPreview
import kira.ditto.browser.BrowserDeskPreviewKind
import kira.ditto.browser.BrowserDeskState
import kira.ditto.browser.BrowserDeskVerb
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPreviewCardTest {
    @Test
    fun searchQueryPrefersSearchingActivityDetail() {
        val query = browserPreviewSearchQuery(
            preview = BrowserDeskPreview(
                kind = BrowserDeskPreviewKind.Search,
                title = "page title",
            ),
            activities = listOf(
                BrowserDeskActivity(
                    id = "a1",
                    verb = BrowserDeskVerb.Searching,
                    detail = "西湖",
                ),
            ),
        )
        assertEquals("西湖", query)
    }

    @Test
    fun searchQueryFallsBackToPreviewTitle() {
        val query = browserPreviewSearchQuery(
            preview = BrowserDeskPreview(
                kind = BrowserDeskPreviewKind.Search,
                title = "西湖十景",
                hits = listOf(
                    kira.ditto.browser.BrowserDeskHit(
                        title = "West Lake",
                        url = "https://example.com/west-lake",
                    ),
                ),
            ),
            activities = emptyList(),
        )
        assertEquals("西湖十景", query)
    }

    @Test
    fun eachTabPrintsItsOwnSearchTerm() {
        val activities = listOf(
            BrowserDeskActivity(
                id = "a1",
                verb = BrowserDeskVerb.Searching,
                detail = "合肥美食特色小吃推荐",
            ),
        )
        val invocations = listOf(
            ChatToolInvocation(
                id = "s1",
                toolName = "Launching browser agent: 合肥美食特色小吃推荐",
                argumentsJson = """{"subagent_type":"browser","query":"合肥美食特色小吃推荐"}""",
                toolKind = "agent",
            ),
        )
        val duck = browserPreviewSearchQuery(
            preview = BrowserDeskPreview(
                kind = BrowserDeskPreviewKind.Images,
                title = "合肥庐州烤鸭",
            ),
            activities = activities,
            invocations = invocations,
            topicId = "合肥庐州烤鸭",
        )
        assertEquals("合肥庐州烤鸭", duck)

        // The group the turn-wide candidates actually belong to still shows them.
        val food = browserPreviewSearchQuery(
            preview = BrowserDeskPreview(
                kind = BrowserDeskPreviewKind.Search,
                title = "合肥美食",
            ),
            activities = activities,
            invocations = invocations,
            topicId = "合肥美食",
        )
        assertEquals("合肥美食特色小吃推荐", food)
    }

    @Test
    fun tabTitleUsesShortRunningAndDoneCopy() {
        assertEquals(
            R.string.browser_preview_tab_searching,
            browserPreviewTabTitleRes(
                running = true,
                failed = false,
                verb = BrowserDeskVerb.Searching,
            ),
        )
        assertEquals(
            R.string.browser_preview_tab_operating,
            browserPreviewTabTitleRes(
                running = true,
                failed = false,
                verb = BrowserDeskVerb.Operating,
            ),
        )
        assertEquals(
            R.string.browser_preview_tab_done,
            browserPreviewTabTitleRes(
                running = false,
                failed = false,
                verb = BrowserDeskVerb.Searching,
            ),
        )
        assertEquals(
            R.string.browser_preview_tab_failed,
            browserPreviewTabTitleRes(
                running = false,
                failed = true,
                verb = BrowserDeskVerb.Searching,
            ),
        )
    }

    @Test
    fun hostAttachesToPendingOrLastMessageNotBoth() {
        val pending = BrowserPreviewHost(
            visible = true,
            attachToPending = true,
            messageIds = setOf("m1"),
        )
        assertTrue(pending.shouldShowOnPending())
        assertFalse(
            pending.shouldShowOnMessages(
                listOf(ChatMessage(id = "m1", author = MessageAuthor.Agent, text = "")),
            ),
        )
        val messageHost = pending.copy(attachToPending = false)
        assertFalse(messageHost.shouldShowOnPending())
        assertTrue(
            messageHost.shouldShowOnMessages(
                listOf(ChatMessage(id = "m1", author = MessageAuthor.Agent, text = "")),
            ),
        )
        assertFalse(
            messageHost.shouldShowOnMessages(
                listOf(ChatMessage(id = "old", author = MessageAuthor.Agent, text = "")),
            ),
        )
        val sealed = ChatToolInvocation(
            id = "s1",
            toolName = "Launching browser agent: 西湖",
            argumentsJson = """{"subagent_type":"browser","description":"西湖","query":"西湖门票"}""",
            toolKind = "agent",
        )
        val hiddenHost = BrowserPreviewHost(visible = false, attachToPending = false)
        assertTrue(
            hiddenHost.shouldShowOnMessages(
                listOf(
                    ChatMessage(
                        id = "m2",
                        author = MessageAuthor.Agent,
                        text = "西湖很大",
                        toolInvocations = listOf(sealed),
                    ),
                ),
            ),
        )
        assertFalse(hiddenHost.shouldShowOnPending())
    }

    @Test
    fun historicalBrowserCardStaysWhenNextSearchIsPending() {
        val previous = ChatMessage(
            id = "old-agent",
            author = MessageAuthor.Agent,
            text = "已找到报名页",
            toolInvocations = listOf(
                ChatToolInvocation(
                    id = "t1",
                    toolName = "Launching browser agent: 第三届NVIDIA DGX Spark 黑客松",
                    argumentsJson = """{"subagent_type":"browser","query":"第三届NVIDIA DGX Spark 黑客松"}""",
                    toolKind = "agent",
                ),
            ),
        )
        val pending = BrowserPreviewHost(
            visible = true,
            attachToPending = true,
            messageIds = emptySet(),
        )
        assertTrue(pending.shouldShowOnPending())
        assertTrue(pending.shouldShowOnMessages(listOf(previous)))
        val currentTurn = pending.copy(messageIds = setOf("new-agent"))
        assertFalse(
            currentTurn.shouldShowOnMessages(
                listOf(ChatMessage(id = "new-agent", author = MessageAuthor.Agent, text = "")),
            ),
        )
        assertTrue(currentTurn.shouldShowOnMessages(listOf(previous)))
        assertEquals(
            emptySet<String>(),
            browserPreviewLiveMessageIds(setOf("old-agent"), emptySet()),
        )
        assertEquals(
            setOf("new-agent"),
            browserPreviewLiveMessageIds(setOf("old-agent", "new-agent"), setOf("new-agent")),
        )
    }

    @Test
    fun unrelatedTurnDoesNotShowBrowserCardWhenDeskIsLive() {
        // Regression: a live BrowserDesk must not leak a browser card onto messages from a
        // different turn that did no browser work. The card only belongs to the current turn
        // (by currentTurnMessageIds) or to messages that actually carry browser invocations.
        val oldMessage = ChatMessage(
            id = "old-agent",
            author = MessageAuthor.Agent,
            text = "和浏览器无关的回答",
        )
        val currentTurnMessage = ChatMessage(
            id = "new-agent",
            author = MessageAuthor.Agent,
            text = "正在用浏览器",
        )
        val host = BrowserPreviewHost(
            visible = true,
            attachToPending = true,
            messageIds = emptySet(),
            currentTurnMessageIds = setOf("new-agent"),
        )
        // The old message is not in the current turn, so it must not show the card even if the
        // desk were live.
        assertFalse(host.shouldShowOnMessages(listOf(oldMessage)))
        // The two desk predicates answer different questions, and keeping them apart is the point:
        // an open surface outlives the turn that opened it, so only the research predicate may feed
        // a message-position card.
        val researching = BrowserDeskState(
            openedUrl = "https://example.com",
            preview = BrowserDeskPreview(kind = BrowserDeskPreviewKind.Article, title = "Example"),
        )
        assertTrue(host.browserDeskSurfaceIsOpen(researching))
        assertTrue(host.browserDeskHasResearch(researching))
        val surfaceOnly = BrowserDeskState(openedUrl = "https://example.com")
        assertTrue(host.browserDeskSurfaceIsOpen(surfaceOnly))
        assertFalse(host.browserDeskHasResearch(surfaceOnly))
        assertFalse(host.browserDeskSurfaceIsOpen(BrowserDeskState()))
        assertFalse(host.browserDeskHasResearch(BrowserDeskState()))
    }

    @Test
    fun rebuildsSearchPreviewFromMoonshotResults() {
        val invocation = ChatToolInvocation(
            id = "s1",
            toolName = "Launching browser agent: 西湖",
            argumentsJson = """{"subagent_type":"browser","description":"西湖","query":"西湖"}""",
            outputJson = org.json.JSONObject()
                .put(
                    "search_results",
                    org.json.JSONArray().put(
                        org.json.JSONObject()
                            .put("title", "西湖景区")
                            .put("url", "https://travel.hangzhou.cn/west-lake")
                            .put("snippet", "杭州西湖"),
                    ),
                )
                .toString(),
            toolKind = "agent",
        )
        val preview = browserPreviewFromInvocations(listOf(invocation))
        requireNotNull(preview)
        assertEquals(BrowserDeskPreviewKind.Search, preview.kind)
        assertEquals("https://travel.hangzhou.cn/west-lake", preview.hits.single().url)
    }

    @Test
    fun rebuildsSearchPreviewFromQueryWhenOutputIsPlainText() {
        val invocation = ChatToolInvocation(
            id = "s1",
            toolName = "Launching browser agent: 西湖门票",
            argumentsJson = """{"subagent_type":"browser","description":"西湖门票","query":"西湖门票"}""",
            outputJson = "GUI_TASK_SUCCEEDED: 旺季 80 元",
            toolKind = "agent",
        )
        val preview = browserPreviewFromInvocations(listOf(invocation))
        requireNotNull(preview)
        assertEquals(BrowserDeskPreviewKind.Search, preview.kind)
        assertEquals("西湖门票", preview.title)
    }

    @Test
    fun splitBrowserPreviewTabsKeepsSelectedWhenOverflowing() {
        val tabs = (1..5).map { index ->
            BrowserPreviewAgentTab(
                id = "t$index",
                name = "Agent $index",
                topicId = "topic-$index",
                sourceToolCallId = "t$index",
                running = false,
                failed = false,
                verb = BrowserDeskVerb.Searching,
            )
        }
        val (inline, overflow) = splitBrowserPreviewTabs(tabs, selectedId = "t5", maxInline = 3)
        assertEquals(listOf("t1", "t2", "t5"), inline.map { it.id })
        assertEquals(listOf("t3", "t4"), overflow.map { it.id })
    }

    @Test
    fun foldingKeepsAtLeastTwoTabsWhenCrowded() {
        assertEquals(3, browserPreviewInlineTabCount(tabCount = 3, availableWidth = 160.dp))
        assertEquals(2, browserPreviewInlineTabCount(tabCount = 5, availableWidth = 160.dp))
        assertEquals(5, browserPreviewInlineTabCount(tabCount = 5, availableWidth = 400.dp))
        assertEquals(2, browserPreviewInlineTabCount(tabCount = 2, availableWidth = 80.dp))
    }

    @Test
    fun avatarOnlyWhenTwoLabeledTabsDoNotFit() {
        assertFalse(browserPreviewTabsUseAvatarOnly(tabCount = 1, availableWidth = 80.dp))
        assertTrue(browserPreviewTabsUseAvatarOnly(tabCount = 2, availableWidth = 160.dp))
        assertFalse(browserPreviewTabsUseAvatarOnly(tabCount = 2, availableWidth = 240.dp))
    }

    @Test
    fun searchQueryPrefersInvocationAndStripsRelatedSearchJunk() {
        assertEquals(
            "F# compiler",
            sanitizeBrowserPreviewSearchQuery(
                """F# compiler 2025 2026和|" F# JavaScript compiler""",
            ),
        )
        assertEquals("西湖门票", sanitizeBrowserPreviewSearchQuery("西湖门票 - Bing"))
        val query = browserPreviewSearchQuery(
            preview = BrowserDeskPreview(
                kind = BrowserDeskPreviewKind.Search,
                title = """F# compiler 2025 2026和|" F# JavaScript compiler""",
            ),
            activities = emptyList(),
            invocations = listOf(
                ChatToolInvocation(
                    id = "s1",
                    toolName = "Launching browser agent: F#",
                    argumentsJson = """{"subagent_type":"browser","query":"F# compiler"}""",
                    toolKind = "agent",
                ),
            ),
        )
        assertEquals("F# compiler", query)
    }

    @Test
    fun searchQueryCollapsesKeywordSalad() {
        assertEquals("西湖门票", sanitizeBrowserPreviewSearchQuery("帮我搜索西湖门票多少钱"))
        assertEquals("英伟达 dgx", sanitizeBrowserPreviewSearchQuery("英伟达 dgx 2025 最新"))
        assertEquals(
            "GPT6 Astra OpenAI September release",
            sanitizeBrowserPreviewSearchQuery("GPT6 Astra OpenAI CNBC pcmag September release"),
        )
    }

    @Test
    fun rebuildsSearchPreviewFromPersistedBrowserPreview() {
        val preview = BrowserDeskPreview(
            kind = BrowserDeskPreviewKind.Search,
            title = "西湖门票",
            hits = listOf(
                kira.ditto.browser.BrowserDeskHit(
                    title = "西湖景区",
                    url = "https://travel.hangzhou.cn/west-lake",
                    snippet = "杭州西湖",
                ),
            ),
        )
        val invocation = ChatToolInvocation(
            id = "s1",
            toolName = "Launching browser agent: 西湖门票",
            argumentsJson = """{"subagent_type":"browser","description":"西湖门票","query":"西湖门票"}""",
            outputJson = kira.ditto.browser.mergeBrowserPreviewIntoOutput(
                "GUI_TASK_SUCCEEDED: 旺季 80 元",
                preview,
            ),
            toolKind = "agent",
        )
        val rebuilt = browserPreviewFromInvocations(listOf(invocation))
        requireNotNull(rebuilt)
        assertEquals("西湖景区", rebuilt.hits.single().title)
        assertEquals("杭州西湖", rebuilt.hits.single().snippet)
    }

    @Test
    fun attachPreviewDoesNotOverwriteOlderCompletedSearch() {
        val oldPreview = BrowserDeskPreview(
            kind = BrowserDeskPreviewKind.Search,
            title = "西湖门票",
            hits = listOf(
                kira.ditto.browser.BrowserDeskHit(
                    title = "西湖景区",
                    url = "https://travel.hangzhou.cn/west-lake",
                    snippet = "杭州西湖",
                ),
            ),
        )
        val oldInvocation = ChatToolInvocation(
            id = "old",
            toolName = "Launching browser agent: 西湖门票",
            argumentsJson = """{"subagent_type":"browser","query":"西湖门票"}""",
            outputJson = kira.ditto.browser.mergeBrowserPreviewIntoOutput("done", oldPreview),
            toolKind = "agent",
        )
        val messages = listOf(
            ChatMessage(
                id = "m1",
                author = MessageAuthor.Agent,
                text = "西湖很大",
                toolInvocations = listOf(oldInvocation),
            ),
        )
        val next = attachBrowserDeskPreviewToMessages(
            messages,
            BrowserDeskState(
                preview = BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Search,
                    title = "ChatGPT 教程",
                    hits = listOf(
                        kira.ditto.browser.BrowserDeskHit(
                            title = "新手指南",
                            url = "https://example.com/chatgpt",
                            snippet = "入门",
                        ),
                    ),
                ),
            ),
        )
        assertEquals(oldInvocation.outputJson, next.single().toolInvocations.single().outputJson)
    }

    @Test
    fun attachEmptySnapshotDoesNotWipeStoredSearchHits() {
        val stored = BrowserDeskPreview(
            kind = BrowserDeskPreviewKind.Search,
            title = "第三届 NVIDIA DGX Spark 黑客松",
            hits = listOf(
                kira.ditto.browser.BrowserDeskHit(
                    title = "官方报名页",
                    url = "https://scrm.nvidia.cn/lp/dgx-spark-hackathon-agent-skills-20260920",
                    snippet = "立即报名",
                ),
            ),
        )
        val invocation = ChatToolInvocation(
            id = "n1",
            toolName = "Launching browser agent: 第三届 NVIDIA DGX Spark 黑客松",
            argumentsJson = """{"subagent_type":"browser","query":"第三届 NVIDIA DGX Spark 黑客松"}""",
            outputJson = kira.ditto.browser.mergeBrowserPreviewIntoOutput("done", stored),
            toolKind = "agent",
        )
        val messages = listOf(
            ChatMessage(
                id = "m1",
                author = MessageAuthor.Agent,
                text = "已找到报名页",
                toolInvocations = listOf(invocation),
            ),
        )
        val next = attachBrowserDeskPreviewToMessages(
            messages,
            BrowserDeskState(
                preview = BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Snapshot,
                    title = "第三届 NVIDIA DGX Spark 黑客松",
                    body = "@e1 button 立即报名",
                ),
            ),
        )
        assertEquals(invocation.outputJson, next.single().toolInvocations.single().outputJson)
        val live = effectiveBrowserDeskState(
            live = BrowserDeskState(
                preview = BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Snapshot,
                    title = "第三届 NVIDIA DGX Spark 黑客松",
                    body = "@e1 button 立即报名",
                ),
            ),
            invocations = listOf(invocation),
        )
        assertEquals("官方报名页", live.preview.hits.single().title)
        assertEquals(BrowserDeskPreviewKind.Search, live.preview.kind)
    }

    @Test
    fun coalescedBrowserSwarmShowsOnePreviewTab() {
        val first = punchThroughWebToBrowserAgent(
            toolName = "WebSearch",
            argumentsJson = """{"query":"GPT6 最新消息"}""",
            toolKind = "search",
        )
        val second = punchThroughWebToBrowserAgent(
            toolName = "WebSearch",
            argumentsJson = """{"query":"GPT-6 Astra features"}""",
            toolKind = "search",
        )
        val invocations = listOf(
            ChatToolInvocation(
                id = "s1",
                toolName = first.toolName,
                argumentsJson = first.argumentsJson,
                isRunning = true,
                toolKind = first.toolKind,
            ),
            ChatToolInvocation(
                id = "s2",
                toolName = second.toolName,
                argumentsJson = second.argumentsJson,
                isRunning = true,
                toolKind = second.toolKind,
            ),
        )
        val coalesced = coalesceParallelBrowserAgents(invocations)
        val people = browserPreviewPeople(
            identityKey = "gpt6",
            invocations = coalesced,
            names = listOf("Ada", "Bea", "Cara", "Dan"),
        )
        val tabs = browserPreviewAgentTabs(
            people = people,
            invocations = coalesced,
            deskState = BrowserDeskState(),
            sessionRunning = true,
        )
        assertEquals(1, tabs.size)
        assertEquals(1, people.size)
        assertEquals("GPT6", invocationSearchQuery(coalesced.single()))
        assertEquals("GPT6", invocationSearchQuery(invocations.first()))
        assertEquals(
            "GPT6",
            sanitizeBrowserPreviewSearchQuery(
                "GPT6 latest news 2025 · pcmag.com · GPT-6 Astra OpenAI September 2026",
            ),
        )
    }

    @Test
    fun fetchUrlIsNotUsedAsSearchQuery() {
        val fetch = ChatToolInvocation(
            id = "f1",
            toolName = "FetchURL",
            argumentsJson = """{"url":"https://www.pcmag.com/news/gpt-6"}""",
        )
        assertEquals("", invocationSearchQuery(fetch))
        assertEquals("", sanitizeBrowserPreviewSearchQuery("https://www.pcmag.com/news/gpt-6"))
    }

    @Test
    fun standalonePunchedFetchKeepsOnePreviewTab() {
        val fetch = punchThroughWebToBrowserAgent(
            toolName = "FetchURL",
            argumentsJson = """{"url":"https://www.pcmag.com/news/gpt-6"}""",
            toolKind = "",
        )
        val invocations = listOf(
            ChatToolInvocation(
                id = "f1",
                toolName = fetch.toolName,
                argumentsJson = fetch.argumentsJson,
                isRunning = true,
                toolKind = fetch.toolKind,
            ),
        )
        val people = browserPreviewPeople(
            identityKey = "fetch-only",
            invocations = invocations,
            names = listOf("Ada", "Bea"),
        )
        val tabs = browserPreviewAgentTabs(
            people = people,
            invocations = invocations,
            deskState = BrowserDeskState(),
            sessionRunning = true,
        )
        assertEquals(1, people.size)
        assertEquals(1, tabs.size)
        assertTrue(invocations.single().isPunchedWebFetch())
    }

    @Test
    fun coalescedSwarmPeopleMatchTopicTabsNotSwarmItems() {
        val queries = listOf(
            "GPT6 latest news 2026",
            "GPT6 最新消息",
            "OpenAI Astra GPT-6",
            "GPT-6 CNBC",
        )
        val invocations = queries.mapIndexed { index, query ->
            val punched = punchThroughWebToBrowserAgent(
                toolName = "WebSearch",
                argumentsJson = """{"query":"$query"}""",
                toolKind = "search",
            )
            ChatToolInvocation(
                id = "s$index",
                toolName = punched.toolName,
                argumentsJson = punched.argumentsJson,
                isRunning = true,
                toolKind = punched.toolKind,
            )
        }
        val coalesced = coalesceParallelBrowserAgents(invocations)
        val desk = BrowserDeskState(
            previewsByTopic = mapOf(
                "gpt6-en" to BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Search,
                    title = "GPT6 latest news 2026",
                ),
                "gpt6-zh" to BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Search,
                    title = "GPT6 最新消息",
                ),
            ),
        )
        val people = browserPreviewPeople(
            identityKey = "gpt6-four",
            invocations = coalesced,
            names = listOf("Ada", "Bea", "Cara", "Dan"),
            deskState = desk,
        )
        val tabs = browserPreviewAgentTabs(
            people = people,
            invocations = coalesced,
            deskState = desk,
            sessionRunning = true,
        )
        assertEquals(2, tabs.size)
        assertEquals(2, people.size)
        assertEquals(2, browserPreviewPeopleForVisibleTabs(tabs, people).size)
    }

    @Test
    fun theRunningTurnReadsTheLiveDeskBeforeItsIdsArePopulated() {
        // Mid-turn: the streamed agent message exists, but no browser invocation has been committed
        // to it yet, so liveMessageIds is still empty. This is the state the card spends most of a
        // turn in — and reading the saved state there is why hits only appeared at the end.
        assertTrue(
            browserPreviewUsesLiveDesk(
                placement = BrowserPreviewPlacement.Message,
                messageIds = listOf("agent-now"),
                liveMessageIds = emptySet(),
                currentTurnMessageIds = setOf("agent-now"),
                sessionRunning = true,
            ),
        )
        // Once they are populated it stays live, running or not.
        assertTrue(
            browserPreviewUsesLiveDesk(
                placement = BrowserPreviewPlacement.Message,
                messageIds = listOf("agent-now"),
                liveMessageIds = setOf("agent-now"),
                currentTurnMessageIds = emptySet(),
                sessionRunning = false,
            ),
        )
        // An earlier turn's card must keep showing its own saved results, not this turn's desk.
        assertFalse(
            browserPreviewUsesLiveDesk(
                placement = BrowserPreviewPlacement.Message,
                messageIds = listOf("agent-earlier"),
                liveMessageIds = emptySet(),
                currentTurnMessageIds = setOf("agent-now"),
                sessionRunning = true,
            ),
        )
        // And a finished turn stops being live even for its own messages.
        assertFalse(
            browserPreviewUsesLiveDesk(
                placement = BrowserPreviewPlacement.Message,
                messageIds = listOf("agent-now"),
                liveMessageIds = emptySet(),
                currentTurnMessageIds = setOf("agent-now"),
                sessionRunning = false,
            ),
        )
        // The pending card is always the running turn.
        assertTrue(
            browserPreviewUsesLiveDesk(
                placement = BrowserPreviewPlacement.Pending,
                messageIds = emptyList(),
                liveMessageIds = emptySet(),
                currentTurnMessageIds = emptySet(),
                sessionRunning = false,
            ),
        )
    }

    @Test
    fun theToolsThatDoTheWorkHaveAVerbAndLightTheirPages() {
        // browser_open and browser_fetch_many were in neither map, so the card said "Noting" while
        // the browser was navigating and fetching — the whole of "不会实时显示浏览器活动".
        assertEquals(
            BrowserDeskVerb.Opening,
            kira.ditto.browser.verbForTool(
                "mcp__webmcp__browser_open",
                org.json.JSONObject().put("url", "https://world.huanqiu.com/"),
                running = true,
            ),
        )
        assertEquals(
            BrowserDeskVerb.Searching,
            kira.ditto.browser.verbForTool(
                "mcp__webmcp__browser_open",
                org.json.JSONObject().put("query", "时政新闻"),
                running = true,
            ),
        )
        val batch = org.json.JSONObject()
            .put("query", "时政 要闻")
            .put(
                "urls",
                org.json.JSONArray()
                    .put("https://politics.people.com.cn/GB/461001/index1.html")
                    .put("https://www.chinanews.com.cn/china/")
                    .put("https://world.huanqiu.com/"),
            )
        assertEquals(
            BrowserDeskVerb.Reading,
            kira.ditto.browser.verbForTool("mcp__webmcp__browser_fetch_many", batch, running = true),
        )
        // And it must name a page, not fall through to a bare host guess of an empty url.
        assertEquals(
            "https://politics.people.com.cn/GB/461001/index1.html",
            kira.ditto.browser.detailForTool("mcp__webmcp__browser_fetch_many", batch),
        )
    }

    @Test
    fun aTopicThatFoundSomethingGetsItsOwnTabWhileTheTurnRuns() {
        // One punched agent researching three subjects. Capping the rail by agent count meant it
        // stayed at one tab no matter how many groups landed — the rail never split.
        val desk = BrowserDeskState(
            previewsByTopic = mapOf(
                "时政要闻" to BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Search,
                    title = "时政要闻",
                    hits = listOf(
                        kira.ditto.browser.BrowserDeskHit("人民网", "https://politics.people.com.cn/", ""),
                    ),
                ),
                "国际新闻" to BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Search,
                    title = "国际新闻",
                    hits = listOf(
                        kira.ditto.browser.BrowserDeskHit("环球网", "https://world.huanqiu.com/", ""),
                    ),
                ),
                // An image lookup the same agent ran in passing: not a research group of its own.
                "时政 配图" to BrowserDeskPreview(
                    kind = BrowserDeskPreviewKind.Images,
                    title = "时政 配图",
                ),
            ),
        )
        val rail = kira.ditto.browser.browserTopicRailItems(desk, emptyList(), agentCount = 1)
        assertEquals(listOf("时政要闻", "国际新闻"), rail.map { it.topicId })
        assertTrue(rail.all { it.preview.hits.isNotEmpty() })
    }

    @Test
    fun searchBoxShowsWhatWasSearchedNotTheLaunchLabel() {
        // session-1788835327015, verbatim. The punch minted the agent from the model's first
        // WebSearch, so the launch label — and the topic id — froze as "时政新闻 2025年9月", while the
        // agent went on to search something else entirely.
        val invocations = listOf(
            ChatToolInvocation(
                id = "0:launch",
                toolName = "Launching browser agent: 时政新闻 2025年9月",
                argumentsJson = """
                    {"query":"时政新闻 2025年9月","subagent_type":"browser",
                     "topic_id":"时政新闻 2025年9月","aether_punched_web":"search"}
                """.trimIndent(),
                toolKind = "agent",
            ),
            ChatToolInvocation(
                id = "0:s1",
                toolName = "Searching: 国内国际时政要闻 最新",
                argumentsJson = """{"query":"国内国际时政要闻 最新"}""",
                toolKind = "fetch",
            ),
            ChatToolInvocation(
                id = "0:s2",
                toolName = "Searching: 时政新闻 2026年9月8日",
                argumentsJson = """{"query":"时政新闻 2026年9月8日"}""",
                toolKind = "fetch",
            ),
        )
        val query = browserPreviewSearchQuery(
            preview = BrowserDeskPreview(
                kind = BrowserDeskPreviewKind.Search,
                title = "时政新闻 2026年9月8日",
                hits = listOf(
                    kira.ditto.browser.BrowserDeskHit(
                        title = "时政要闻",
                        url = "https://politics.people.com.cn/GB/461001/index1.html",
                        snippet = "",
                    ),
                ),
            ),
            activities = emptyList(),
            invocations = invocations,
            topicId = "时政新闻 2025年9月",
        )
        // The box must name the search whose hits are on screen, not the label the agent was
        // launched under — the user reads them side by side and they disagreed.
        assertEquals("时政新闻 2026年9月8日", query)

        // And those rendered names are the agent's own web calls: the card is showing this work,
        // so the trace must not repeat it as chips.
        assertTrue(invocations[1].isSilentBrowserHostTool(invocations))
        assertTrue(invocations[2].isSilentBrowserHostTool(invocations))
        // The launch itself stays visible — that is a subagent starting.
        assertFalse(
            invocations[0].isSilentBrowserHostTool(invocations) &&
                invocations[0].toolKind != "agent",
        )
    }

    @Test
    fun replaysTheRealTurnThatShowedOnlyASearchBox() {
        // session-1788833145054, "搜下 最新科技新闻". The exact four calls the turn made, with the
        // exact strings, so the card is exercised the way the phone exercises it rather than the
        // way a hand-written fixture flatters it.
        val invocations = listOf(
            ChatToolInvocation(
                id = "0:launch",
                toolName = "Launching browser agent: 最新科技新闻",
                argumentsJson = """
                    {"query":"最新科技新闻","subagent_type":"browser","description":"最新科技新闻",
                     "topic_id":"最新科技新闻","aether_punched_web":"search"}
                """.trimIndent(),
                toolKind = "agent",
                isRunning = true,
            ),
            ChatToolInvocation(
                id = "0:search1",
                toolName = "Searching: latest technology news September 2026",
                argumentsJson = """{"query":"latest technology news September 2026"}""",
                toolKind = "fetch",
            ),
            ChatToolInvocation(
                id = "0:fetch1",
                toolName = "Fetching: https://techstartups.com/2026/09/04/top-tech-news-",
                argumentsJson =
                    """{"url":"https://techstartups.com/2026/09/04/top-tech-news-today-september-4-2026"}""",
                toolKind = "other",
            ),
            ChatToolInvocation(
                id = "0:search2",
                toolName = "Searching: 科技新闻 2026年9月 AI 芯片",
                argumentsJson = """{"query":"科技新闻 2026年9月 AI 芯片"}""",
                toolKind = "fetch",
            ),
        )
        // The live desk is gone — the turn has ended, or this is the message-placed card. All the
        // card has left is what the tool outputs carry, and those are the agent's rendered text.
        val searchOutput = """
            Title: TechStartups https://techstartups.com › top-tech-news-today-september...
            Site: techstartups.com
            URL: https://techstartups.com/2026/09/04/top-tech-news-today-september-4-2026
            Snippet: 4 days ago · It's Friday, September 4, 2026, and the AI boom stopped looking theoretical.

            ---

            Title: 10times https://10times.com › usa › technology
            Site: 10times.com
            URL: https://10times.com/usa/technology?month=september
            Snippet: Sep 1, 2026 · The Abilities Expo Phoenix, taking place from September 4 to 6.
        """.trimIndent()
        val withOutputs = invocations.map { invocation ->
            if (invocation.toolName.startsWith("Searching")) {
                invocation.copy(outputJson = searchOutput)
            } else {
                invocation
            }
        }
        val desk = effectiveBrowserDeskState(live = BrowserDeskState(), invocations = withOutputs)
        val people = browserPreviewPeople(
            identityKey = "tech-news",
            invocations = withOutputs,
            names = listOf("Ada", "Bea", "Cara", "Dan"),
            deskState = desk,
        )
        val tabs = browserPreviewAgentTabs(
            people = people,
            invocations = withOutputs,
            deskState = desk,
            sessionRunning = true,
        )
        // One browser agent was launched. Two searches and a fetch are that one agent working.
        assertEquals(1, tabs.size)
        // And the card must have real hits to draw, not just the query.
        val preview = desk.preview
        assertEquals(BrowserDeskPreviewKind.Search, preview.kind)
        assertEquals(2, preview.hits.size)
        assertEquals("TechStartups", preview.hits.first().title)
        assertTrue(
            browserPreviewRevealResults(
                searching = true,
                hitCount = preview.hits.size,
                viewingPage = false,
                kind = preview.kind,
            ),
        )
    }

    @Test
    fun searchOnlyRevealsResultsAfterHitsOrArticle() {
        // A running search shows its own placeholder in the body; hiding the body left the card
        // as a lone search box for the whole run.
        assertTrue(
            browserPreviewRevealResults(
                searching = true,
                hitCount = 0,
                viewingPage = false,
                kind = BrowserDeskPreviewKind.Search,
            ),
        )
        assertFalse(
            browserPreviewRevealResults(
                searching = true,
                hitCount = 0,
                viewingPage = false,
                kind = BrowserDeskPreviewKind.Empty,
            ),
        )
        assertTrue(
            browserPreviewRevealResults(
                searching = true,
                hitCount = 2,
                viewingPage = false,
                kind = BrowserDeskPreviewKind.Search,
            ),
        )
        assertTrue(
            browserPreviewRevealResults(
                searching = false,
                hitCount = 0,
                viewingPage = false,
                kind = BrowserDeskPreviewKind.Article,
            ),
        )
        assertTrue(
            browserPreviewRevealResults(
                searching = false,
                hitCount = 0,
                viewingPage = true,
                kind = BrowserDeskPreviewKind.Search,
            ),
        )
        assertFalse(
            browserPreviewRevealResults(
                searching = false,
                hitCount = 0,
                viewingPage = false,
                kind = BrowserDeskPreviewKind.Search,
            ),
        )
    }
}
