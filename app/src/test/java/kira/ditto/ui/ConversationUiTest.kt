package kira.ditto.ui

import kira.ditto.R
import kira.ditto.browser.browserDeskCaption
import kira.ditto.data.looksLikeHiddenPromptTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationUiTest {
    @Test
    fun restoredHistoryLeadIsHiddenFromSessionTitle() {
        // Regression: the memory-ledger restore prompt was leaking into the
        // session title because HiddenPromptTitleMarkers didn't cover it.
        assertTrue(
            "The previous live session was not available. Continue this chat using the memory ledger."
                .looksLikeHiddenPromptTitle(),
        )
    }

    @Test
    fun reasoningOffKeepsToolsAndOnlyTheFinalTextBlock() {
        val tools = listOf(
            ChatToolInvocation(
                id = "call-1",
                toolName = "bash",
                argumentsJson = "{}",
            ),
        )
        val blocks = listOf(
            AssistantResponseBlock.Text("draft", "Intermediate answer"),
            AssistantResponseBlock.Reasoning(
                "reasoning",
                ReasoningTrace(id = "reasoning", rawText = "Hidden", toolInvocations = tools),
            ),
            AssistantResponseBlock.Text("final", "Final answer"),
        )

        assertEquals(
            listOf(
                AssistantResponseBlock.ToolGroup("reasoning", tools),
                AssistantResponseBlock.Text("final", "Final answer"),
            ),
            blocks.sanitizedForReasoningOff(),
        )
    }

    @Test
    fun contextUsageFractionUsesCatalogWindow() {
        assertEquals(0f, modelContextUsageFraction(0, 128_000), 0.0001f)
        assertEquals(0.5f, modelContextUsageFraction(64_000, 128_000), 0.0001f)
        assertEquals(1f, modelContextUsageFraction(200_000, 128_000), 0.0001f)
        assertEquals(128_000L, resolveModelContextWindow(null))
        assertEquals(262_144L, resolveModelContextWindow(262_144))
        assertEquals(100L, conversationUsedContextTokens(100, ""))
        assertEquals(3L, conversationUsedContextTokens(null, "abcdefghijkl"))
        assertEquals(
            0.19f,
            capsuleContextUsageFraction(
                acpUsedTokens = 24_200L,
                acpWindowTokens = 128_000L,
                catalogWindow = 200_000L,
                localUsedTokens = 1_000L,
            ),
            0.005f,
        )
        assertEquals(
            0.5f,
            capsuleContextUsageFraction(
                acpUsedTokens = 64_000L,
                acpWindowTokens = 0L,
                catalogWindow = 128_000L,
                localUsedTokens = 10L,
            ),
            0.0001f,
        )
        assertEquals(
            0.25f,
            capsuleContextUsageFraction(
                acpUsedTokens = null,
                acpWindowTokens = null,
                catalogWindow = 128_000L,
                localUsedTokens = 32_000L,
            ),
            0.0001f,
        )
    }

    @Test
    fun capsuleUsageGrowsByWhatThisTurnStreamed() {
        // Mid-turn the capsule still has to move, but it moves by *adding* the streamed text to the
        // last ACP snapshot rather than by taking the larger of two different quantities.
        val acp = 24_000L
        val streamedThisTurn = 24_000L
        assertEquals(
            0.375f,
            capsuleContextUsageFraction(
                acpUsedTokens = acp,
                acpWindowTokens = 128_000L,
                catalogWindow = null,
                localUsedTokens = 0L,
                sessionRunning = true,
                pendingTokens = streamedThisTurn,
            ),
            0.001f,
        )
        // The case the old `maxOf(acp, localUsedTokens)` got wrong. `localUsedTokens` carries the
        // previous turn's *billing* total, which counts cacheRead - so after a turn with a large
        // cache hit it exceeded true occupancy and won the max, and the capsule read high for
        // reasons unrelated to how full the window was. Billing must not move occupancy at all.
        assertEquals(
            0.1875f,
            capsuleContextUsageFraction(
                acpUsedTokens = acp,
                acpWindowTokens = 128_000L,
                catalogWindow = null,
                localUsedTokens = 120_000L,
                sessionRunning = true,
                pendingTokens = 0L,
            ),
            0.001f,
        )
        // Between turns, ACP alone.
        assertEquals(
            0.1875f,
            capsuleContextUsageFraction(
                acpUsedTokens = acp,
                acpWindowTokens = 128_000L,
                catalogWindow = null,
                localUsedTokens = 48_000L,
                sessionRunning = false,
            ),
            0.001f,
        )
        // With no usage_update at all the local estimate is all there is, and it is still used.
        assertEquals(
            0.375f,
            capsuleContextUsageFraction(
                acpUsedTokens = null,
                acpWindowTokens = 128_000L,
                catalogWindow = null,
                localUsedTokens = 48_000L,
                sessionRunning = true,
            ),
            0.001f,
        )
    }

    @Test
    fun wideLayoutFollowsMaterialMediumWidthBreakpoint() {
        assertFalse(shouldUseWideAppLayout(599f))
        assertTrue(shouldUseWideAppLayout(600f))
        assertTrue(shouldUseWideAppLayout(840f))
        assertEquals(320f, supportingPaneWidthDp(600f), 0.01f)
        assertEquals(400f, supportingPaneWidthDp(800f), 0.01f)
        assertEquals(320f, supportingPaneWidthDp(840f), 0.01f)
        assertEquals(360f, supportingPaneWidthDp(1_200f), 0.01f)
    }

    @Test
    fun selectedModelDisplaySplitsFamilyAndVariant() {
        assertEquals(
            SelectedModelDisplayName("GPT", "5.5"),
            formatSelectedModelDisplayName("gpt-5.5"),
        )
        assertEquals(
            SelectedModelDisplayName("Gemini", "3.1 Flash Lite"),
            formatSelectedModelDisplayName("Gemini 3.1 Flash Lite Preview"),
        )
        assertEquals(
            SelectedModelDisplayName("Qwen", "3.6 Max"),
            formatSelectedModelDisplayName("qwen3.6-max-preview"),
        )
    }

    @Test
    fun selectedModelDisplayUsesCapabilityIcons() {
        assertEquals(
            SelectedModelDisplayName("GPT", "5.3 Codex", SelectedModelDisplayIcon.Fast),
            formatSelectedModelDisplayName("gpt-5.3-codex-spark"),
        )
        assertEquals(
            SelectedModelDisplayName("Mimo", "v2.5 Pro", SelectedModelDisplayIcon.Fast),
            formatSelectedModelDisplayName("mimo-v2.5-pro-ultraspeed"),
        )
        assertEquals(
            SelectedModelDisplayName("Nemotron", "3 Nano Omni", SelectedModelDisplayIcon.Reasoning),
            formatSelectedModelDisplayName("nemotron-3-nano-omni-30b-a3b-reasoning"),
        )
    }

    @Test
    fun selectedModelDisplayKeepsSingleFamilyWhenNoVariantExists() {
        assertEquals(
            SelectedModelDisplayName("Fugu", ""),
            formatSelectedModelDisplayName("fugu"),
        )
        assertEquals(
            SelectedModelDisplayName("Fugu", "Ultra"),
            formatSelectedModelDisplayName("fugu-ultra"),
        )
        assertEquals(
            SelectedModelDisplayName("GLM", "5.1"),
            formatSelectedModelDisplayName("GLM-5.1"),
        )
    }

    @Test
    fun pendingIndicatorShowsThinkingAfterBodyTextResetsForToolCall() {
        val previousBlocks = listOf(
            AssistantResponseBlock.Text(
                id = "text-1",
                text = "I will inspect the file first.",
            ),
            AssistantResponseBlock.ToolGroup(
                id = "tools-1",
                toolInvocations = listOf(
                    ChatToolInvocation(
                        id = "call-1",
                        toolName = "read",
                        argumentsJson = """{"path":"README.md"}""",
                        isRunning = true,
                    )
                ),
            ),
        )

        assertTrue(previousBlocks.any { it is AssistantResponseBlock.Text && it.text.isNotBlank() })
        assertEquals(
            PendingGenerationIndicator.Thinking,
            pendingGenerationIndicator(
                isSending = true,
                pendingAssistantText = "",
                pendingStatusText = "",
                lastVisibleMessageAuthor = MessageAuthor.User,
            ),
        )
    }

    @Test
    fun pendingIndicatorHidesThinkingWhileBodyTextIsActive() {
        assertEquals(
            PendingGenerationIndicator.None,
            pendingGenerationIndicator(
                isSending = true,
                pendingAssistantText = "Streaming body text",
                pendingStatusText = "",
                lastVisibleMessageAuthor = MessageAuthor.User,
            ),
        )
    }

    @Test
    fun pendingIndicatorHidesThinkingAfterAgentMessageAppears() {
        assertEquals(
            PendingGenerationIndicator.None,
            pendingGenerationIndicator(
                isSending = true,
                pendingAssistantText = "",
                pendingStatusText = "",
                lastVisibleMessageAuthor = MessageAuthor.Agent,
            ),
        )
    }

    @Test
    fun pendingIndicatorHidesThinkingWhilePendingWorkIsVisible() {
        assertEquals(
            PendingGenerationIndicator.None,
            pendingGenerationIndicator(
                isSending = true,
                pendingAssistantText = "",
                pendingStatusText = "",
                hasVisiblePendingWork = true,
                lastVisibleMessageAuthor = MessageAuthor.User,
            ),
        )
    }

    @Test
    fun pendingIndicatorShowsThinkingForEmptyReasoningPlaceholder() {
        assertEquals(
            PendingGenerationIndicator.Thinking,
            pendingGenerationIndicator(
                isSending = true,
                pendingAssistantText = "",
                pendingStatusText = "",
                hasVisiblePendingReasoning = hasVisibleReasoningStatus(ReasoningTrace(id = "empty")),
                lastVisibleMessageAuthor = MessageAuthor.User,
            ),
        )
    }

    @Test
    fun pendingIndicatorHidesThinkingForVisibleReasoningStatus() {
        assertEquals(
            PendingGenerationIndicator.None,
            pendingGenerationIndicator(
                isSending = true,
                pendingAssistantText = "",
                pendingStatusText = "",
                hasVisiblePendingReasoning = hasVisibleReasoningStatus(
                    ReasoningTrace(id = "reasoning", latestStatusText = "Checking"),
                ),
                lastVisibleMessageAuthor = MessageAuthor.User,
            ),
        )
    }

    @Test
    fun pendingIndicatorShowsWorkspaceSetupBeforeThinking() {
        assertEquals(
            PendingGenerationIndicator.WorkspaceSetup,
            pendingGenerationIndicator(
                isSending = true,
                pendingAssistantText = "",
                pendingStatusText = "",
                lastVisibleMessageAuthor = MessageAuthor.User,
                isPreparingWorkspace = true,
            ),
        )
    }

    @Test
    fun pendingIndicatorHidesSetupAndThinkingForAgentModeDesk() {
        assertEquals(
            PendingGenerationIndicator.None,
            pendingGenerationIndicator(
                isSending = true,
                pendingAssistantText = "",
                pendingStatusText = "",
                lastVisibleMessageAuthor = MessageAuthor.User,
                isPreparingWorkspace = true,
                hideSetupAndThinking = true,
            ),
        )
    }

    @Test
    fun pendingIndicatorIgnoresToolUsingStatusText() {
        assertTrue(isToolProgressStatusText("Using bash"))
        assertTrue(isToolProgressStatusText("Used grep"))
        assertFalse(isToolProgressStatusText("Reconnecting..."))
        assertEquals(
            PendingGenerationIndicator.Thinking,
            pendingGenerationIndicator(
                isSending = true,
                pendingAssistantText = "",
                pendingStatusText = "Using bash",
                lastVisibleMessageAuthor = MessageAuthor.User,
            ),
        )
    }

    @Test
    fun pendingIndicatorShowsStatusWhenStatusTextExists() {
        assertEquals(
            PendingGenerationIndicator.Status,
            pendingGenerationIndicator(
                isSending = true,
                pendingAssistantText = "Streaming body text",
                pendingStatusText = "Reconnecting...",
                lastVisibleMessageAuthor = MessageAuthor.User,
            ),
        )
    }

    @Test
    fun pendingIndicatorHidesAfterTurnEnds() {
        assertEquals(
            PendingGenerationIndicator.None,
            pendingGenerationIndicator(
                isSending = false,
                pendingAssistantText = "",
                pendingStatusText = "",
                lastVisibleMessageAuthor = MessageAuthor.Agent,
            ),
        )
    }

    @Test
    fun pendingGenerationBlockHidesCommittedTextEcho() {
        val reply = "I'm doing great, thank you for asking!"

        assertEquals(
            false,
            shouldRenderPendingGenerationBlock(
                isSending = true,
                pendingResponseBlocks = listOf(
                    AssistantResponseBlock.Text(id = "pending-text", text = reply),
                ),
                pendingToolInvocations = emptyList(),
                pendingStatusText = "",
                lastVisibleAgentText = reply,
            ),
        )
    }

    @Test
    fun pendingGenerationBlockKeepsDistinctPendingText() {
        assertEquals(
            true,
            shouldRenderPendingGenerationBlock(
                isSending = true,
                pendingResponseBlocks = listOf(
                    AssistantResponseBlock.Text(id = "pending-text", text = "Still streaming"),
                ),
                pendingToolInvocations = emptyList(),
                pendingStatusText = "",
                lastVisibleAgentText = "Previous reply",
            ),
        )
    }

    @Test
    fun runningWorkDurationAdvancesFromRecordedStartTime() {
        assertEquals(
            5_000L,
            runningWorkDurationMillis(
                startedAtMillis = 10_000L,
                fallbackStartedRealtimeMillis = 1_000L,
                nowMillis = 15_000L,
                nowRealtimeMillis = 1_000L,
            ),
        )
    }

    @Test
    fun runningWorkDurationUsesStableFallbackStartTime() {
        assertEquals(
            4_000L,
            runningWorkDurationMillis(
                startedAtMillis = null,
                fallbackStartedRealtimeMillis = 2_000L,
                nowMillis = 20_000L,
                nowRealtimeMillis = 6_000L,
            ),
        )
    }

    @Test
    fun completedWorkDurationUsesTurnStatisticsInsteadOfOlderTraceTimestamps() {
        val turnStartedAt = 1_700_000_090_000L
        val turnCompletedAt = 1_700_000_100_000L
        val messages = listOf(
            ChatMessage(
                id = "reasoning",
                author = MessageAuthor.Agent,
                text = "",
                createdAtMillis = turnCompletedAt,
                reasoningTrace = ReasoningTrace(
                    id = "trace",
                    startedAtMillis = 1_700_000_000_000L,
                    completedAtMillis = turnCompletedAt,
                ),
            ),
            ChatMessage(
                id = "answer",
                author = MessageAuthor.Agent,
                text = "Done",
                createdAtMillis = turnCompletedAt + 1L,
                thoughtDurationMillis = 90_000L,
                usageStatistics = ChatUsageStatistics(
                    startedAtMillis = turnStartedAt,
                    completedAtMillis = turnCompletedAt,
                ),
            ),
        )

        assertEquals(
            10_000L,
            workDurationMillisForMessages(messages, endAtMillis = turnCompletedAt + 1L),
        )
        assertEquals(
            7_000L,
            workDurationMillisForMessages(
                messages = listOf(
                    ChatMessage(
                        id = "legacy-answer",
                        author = MessageAuthor.Agent,
                        text = "Done",
                        createdAtMillis = turnCompletedAt,
                        thoughtDurationMillis = 7_000L,
                    ),
                ),
                endAtMillis = turnCompletedAt,
            ),
        )
    }

    @Test
    fun reasoningTimelineKeepsSummaryAndToolsInRecordedOrder() {
        val trace = ReasoningTrace(
            id = "reasoning-1",
            chunks = listOf(
                ReasoningSummaryChunk(
                    id = "summary-1",
                    title = "Planning",
                    detail = "I am checking the input first.",
                    timelineOrder = 1,
                ),
                ReasoningSummaryChunk(
                    id = "summary-2",
                    title = "Reviewing output",
                    detail = "I should inspect the command result.",
                    timelineOrder = 3,
                ),
            ),
            toolInvocations = listOf(
                ChatToolInvocation(
                    id = "tool-1",
                    toolName = "bash",
                    argumentsJson = """{"command":"pwd"}""",
                    timelineOrder = 2,
                ),
            ),
        )

        val items = reasoningTimelineItems(trace)

        assertEquals(
            listOf("summary-1", "tool-1", "summary-2"),
            items.map { item ->
                when (item) {
                    is ReasoningTimelineItem.Summary -> item.chunk.id
                    is ReasoningTimelineItem.Tool -> item.toolInvocation.id
                }
            },
        )
    }

    @Test
    fun steerCompletionFreezesThinkingAndRunningTools() {
        val completed = completeAssistantBlocksForSteer(
            blocks = listOf(
                AssistantResponseBlock.Reasoning(
                    id = "reasoning",
                    trace = ReasoningTrace(
                        id = "reasoning",
                        latestStatusText = "",
                        startedAtMillis = 1_000L,
                        toolInvocations = listOf(
                            ChatToolInvocation(
                                id = "tool-1",
                                toolName = "bash",
                                argumentsJson = "{}",
                                isRunning = true,
                                startedAtMillis = 1_100L,
                            ),
                        ),
                    ),
                ),
            ),
            nowMillis = 2_000L,
        )

        val reasoning = completed.single() as AssistantResponseBlock.Reasoning
        assertEquals(2_000L, reasoning.trace.completedAtMillis)
        assertFalse(reasoning.trace.toolInvocations.single().isRunning)
        assertEquals(2_000L, reasoning.trace.toolInvocations.single().completedAtMillis)
    }

    @Test
    fun conversationPromptPreviewStripsHiddenPromptInjections() {
        val injected = "什么是 yolo 模式<system-reminder><plugin_session_start>EverMe</plugin_session_start></system-reminder>"
        assertEquals("什么是 yo", conversationPromptPreview(injected))
    }

    @Test
    fun conversationPromptPreviewKeepsAboutSixCharacters() {
        assertEquals("你好世界啊哈", conversationPromptPreview("你好世界啊哈这是后面"))
        assertEquals("Hello", conversationPromptPreview("Hello"))
        assertEquals("Hello ", conversationPromptPreview("Hello world"))
        assertEquals("…", conversationPromptPreview("   "))
    }

    @Test
    fun conversationLazyPrefixCountMatchesChatAnchors() {
        assertEquals(2, conversationLazyPrefixCount(0, false, false))
        assertEquals(5, conversationLazyPrefixCount(
            pendingInputCount = 1,
            showPendingGeneration = true,
            isCompacting = true,
        ))
        assertEquals(5, conversationLazyPrefixCount(1, true, true))
    }

    @Test
    fun conversationLazyIndexForItemUsesReverseLayout() {
        assertEquals(5, conversationLazyIndexForItem(prefixCount = 3, conversationItemCount = 4, itemIndex = 1))
        assertEquals(3, conversationLazyIndexForItem(prefixCount = 3, conversationItemCount = 4, itemIndex = 3))
    }

    @Test
    fun fisheyeBarIsLongestAtFocus() {
        val max = fisheyeBarWidth(0f, 6f, 22f)
        val near = fisheyeBarWidth(1f, 6f, 22f)
        val far = fisheyeBarWidth(6f, 6f, 22f)
        assertTrue(max > near)
        assertTrue(near > far)
        assertEquals(22f, max, 0.01f)
        assertTrue(far >= 6f)
    }

    @Test
    fun conversationPromptFocusPicksNearestVisibleUserTick() {
        val focus = conversationPromptFocusFromVisibleItems(
            visibleCenters = listOf(0 to 10, 4 to 100, 5 to 180, 6 to 260),
            viewportCenter = 190,
            prefixCount = 4,
            conversationItemCount = 4,
            promptItemIndices = listOf(0, 2),
        )
        assertEquals(1, focus)
    }

    @Test
    fun conversationIndexRailHiddenUntilTwoPrompts() {
        assertFalse(shouldShowConversationIndexRail(emptyList()))
        assertFalse(
            shouldShowConversationIndexRail(
                listOf(ConversationPromptTick(messageId = "m1", preview = "问", itemIndex = 0)),
            ),
        )
        assertTrue(
            shouldShowConversationIndexRail(
                listOf(
                    ConversationPromptTick(messageId = "m1", preview = "问", itemIndex = 0),
                    ConversationPromptTick(messageId = "m2", preview = "再问", itemIndex = 2),
                ),
            ),
        )
    }

    @Test
    fun conversationIndexRailCapsVisibleTicks() {
        val ticks = (0 until 40).map { index ->
            ConversationPromptTick(
                messageId = "m$index",
                preview = "问$index",
                itemIndex = index * 2,
            )
        }
        val visible = conversationIndexRailVisibleTicks(ticks, maxCount = 24)
        assertEquals(24, visible.size)
        assertEquals(ticks.first().messageId, visible.first().messageId)
        assertEquals(ticks.last().messageId, visible.last().messageId)
        val shortTicks = ticks.take(10)
        assertEquals(shortTicks, conversationIndexRailVisibleTicks(shortTicks, maxCount = 24))
    }

    @Test
    fun elicitationOtherUsesComposerFreeText() {
        assertTrue(isElicitationOtherChoice("其他"))
        assertTrue(isElicitationOtherChoice("Other"))
        val options = listOf(
            kira.ditto.data.kimi.PendingElicitationOption("上海", "上海"),
            kira.ditto.data.kimi.PendingElicitationOption("北京", "北京"),
        )
        val withOther = elicitationOptionsWithOther(options, "其他")
        assertEquals(3, withOther.size)
        assertEquals(ElicitationOtherSentinel, withOther.last().value)

        val request = kira.ditto.data.kimi.PendingElicitationRequest(
            requestId = "r1",
            sessionId = "s",
            kimiSessionId = "k",
            message = "请告诉我你的城市或具体地址",
            questions = listOf(
                kira.ditto.data.kimi.PendingElicitationQuestion(
                    id = "q0",
                    title = "位置",
                    body = "请告诉我你的城市或具体地址",
                    multiSelect = false,
                    required = true,
                    options = options,
                ),
            ),
            requestedAtMillis = 0L,
        )
        val answers = buildElicitationAnswers(
            request,
            mapOf("q0" to setOf(ElicitationOtherSentinel)),
            otherText = "杭州西湖",
        )
        assertEquals("杭州西湖", answers.getString("q0"))
    }

    @Test
    fun agentModeDeskDoesNotAppearJustBecauseATurnIsSending() {
        assertFalse(
            agentModeConversationDockVisible(
                agentModeSelected = false,
                hasPinnedSubagents = false,
            ),
        )
        assertFalse(agentModeVirtualDeskVisible(agentModeSelected = false))
        assertTrue(agentModeVirtualDeskVisible(agentModeSelected = true))
        assertTrue(
            agentModeConversationDockVisible(
                agentModeSelected = true,
                hasPinnedSubagents = false,
            ),
        )
        assertTrue(
            agentModeConversationDockVisible(
                agentModeSelected = false,
                hasPinnedSubagents = true,
            ),
        )
    }

    @Test
    fun defaultChromeStatusDoesNotOpenBrowserDock() {
        assertFalse(
            browserDeskDockVisible(
                invocations = emptyList(),
                deskState = kira.ditto.browser.BrowserDeskState(),
            ),
        )
        assertFalse(
            agentModeConversationDockVisible(
                agentModeSelected = false,
                hasPinnedSubagents = browserDeskDockVisible(
                    invocations = emptyList(),
                    deskState = kira.ditto.browser.BrowserDeskState(
                        preview = kira.ditto.browser.BrowserDeskPreview(),
                    ),
                ),
            ),
        )
        assertFalse(
            browserDeskDockVisible(
                invocations = emptyList(),
                deskState = kira.ditto.browser.BrowserDeskState(
                    preview = kira.ditto.browser.BrowserDeskPreview(
                        kind = kira.ditto.browser.BrowserDeskPreviewKind.Search,
                        title = "西湖",
                    ),
                ),
            ),
        )
        assertTrue(
            browserDeskDockVisible(
                invocations = emptyList(),
                deskState = kira.ditto.browser.BrowserDeskState(
                    preview = kira.ditto.browser.BrowserDeskPreview(
                        kind = kira.ditto.browser.BrowserDeskPreviewKind.Search,
                        title = "西湖",
                        hits = listOf(
                            kira.ditto.browser.BrowserDeskHit(
                                title = "西湖",
                                url = "https://travel.hangzhou.cn/west-lake",
                            ),
                        ),
                    ),
                ),
                keepCompletedPreview = true,
            ),
        )
        assertFalse(
            browserModeSourceChipsVisible(
                browserMode = true,
                deskVisible = browserDeskDockVisible(
                    invocations = emptyList(),
                    deskState = kira.ditto.browser.BrowserDeskState(
                        preview = kira.ditto.browser.BrowserDeskPreview(
                            kind = kira.ditto.browser.BrowserDeskPreviewKind.Search,
                            title = "西湖",
                        ),
                    ),
                    keepCompletedPreview = true,
                ),
            ),
        )
        assertTrue(
            browserDeskDockVisible(
                invocations = emptyList(),
                deskState = kira.ditto.browser.BrowserDeskState(
                    activities = listOf(
                        kira.ditto.browser.BrowserDeskActivity(
                            id = "search-1",
                            verb = kira.ditto.browser.BrowserDeskVerb.Searching,
                            detail = "西湖",
                        ),
                    ),
                ),
            ),
        )
        assertFalse(browserModeSourceChipsVisible(browserMode = true, deskVisible = true))
        assertTrue(browserModeSourceChipsVisible(browserMode = true, deskVisible = false))
        assertFalse(browserModeSourceChipsAtTop(browserMode = true, deskVisible = false))
        assertFalse(browserModeSourceChipsAtTop(browserMode = true, deskVisible = true))
        assertTrue(browserModeSourceChipsVisible(browserMode = false, deskVisible = true))
        assertFalse(browserModeSourceChipsAtTop(browserMode = false, deskVisible = false))
        val deskSources = listOf(
            kira.ditto.data.KnowledgeCitation(
                index = 1,
                sourceName = "西湖",
                text = "西湖",
                url = "https://example.com/west-lake",
            ),
        )
        val persona = listOf(
            kira.ditto.data.KnowledgeCitation(
                index = 1,
                sourceName = "笔记",
                text = "笔记",
            ),
        )
        assertEquals(
            persona,
            kira.ditto.data.browserModeTrailingCitations(
                agentMode = true,
                deskSources = deskSources,
                preceding = persona,
            ),
        )
        assertEquals(
            "https://example.com/west-lake",
            kira.ditto.data.browserModeTrailingCitations(
                agentMode = false,
                deskSources = deskSources,
                preceding = persona,
            ).single().url,
        )
        assertTrue(
            browserDeskHeaderBusy(
                sessionRunning = true,
                invocations = emptyList(),
                activities = emptyList(),
            ),
        )
        assertFalse(
            browserDeskHeaderBusy(
                sessionRunning = false,
                invocations = emptyList(),
                activities = emptyList(),
            ),
        )
        assertFalse(
            browserDeskDockVisible(
                invocations = emptyList(),
                deskState = kira.ditto.browser.BrowserDeskState(),
                sessionRunning = true,
            ),
        )
        assertEquals(true, browserDeskForcedExpanded(running = true, collapsePreview = true))
        assertEquals(false, browserDeskForcedExpanded(running = false, collapsePreview = true))
        assertEquals(null, browserDeskForcedExpanded(running = false, collapsePreview = false))
        val searchHits = kira.ditto.browser.BrowserDeskState(
            preview = kira.ditto.browser.BrowserDeskPreview(
                kind = kira.ditto.browser.BrowserDeskPreviewKind.Search,
                title = "西湖",
                hits = listOf(
                    kira.ditto.browser.BrowserDeskHit(
                        title = "西湖",
                        url = "https://travel.hangzhou.cn/west-lake",
                    ),
                ),
            ),
        )
        assertTrue(
            browserDeskDockVisible(
                invocations = emptyList(),
                deskState = searchHits,
                keepCompletedPreview = browserDeskRetainDuringTurn(
                    isSending = true,
                    pendingAssistantText = "",
                    hasBrowserWork = true,
                ),
            ),
        )
        assertTrue(
            browserDeskDockVisible(
                invocations = emptyList(),
                deskState = searchHits,
                keepCompletedPreview = browserDeskRetainDuringTurn(
                    isSending = true,
                    pendingAssistantText = "西湖很大。",
                    hasBrowserWork = true,
                ),
            ),
        )
        // A turn that never touches the browser must not inherit the last task's card.
        assertFalse(
            browserDeskDockVisible(
                invocations = emptyList(),
                deskState = searchHits,
                keepCompletedPreview = browserDeskRetainDuringTurn(
                    isSending = true,
                    pendingAssistantText = "邮件草稿已创建。",
                    hasBrowserWork = false,
                ),
            ),
        )
        assertFalse(
            agentModeConversationDockVisible(
                agentModeSelected = false,
                hasPinnedSubagents = false,
            ),
        )
        assertFalse(
            browserDeskDockVisible(
                invocations = emptyList(),
                deskState = searchHits,
                keepCompletedPreview = browserDeskRetainDuringTurn(
                    isSending = false,
                    pendingAssistantText = "",
                ),
            ),
        )
        assertEquals(
            R.string.composer_asr_empty_no_voice,
            composerAsrEmptyMessageRes(hadVisualVoice = false, listenedForMillis = 3_000L),
        )
        assertEquals(
            R.string.composer_asr_empty_too_short,
            composerAsrEmptyMessageRes(hadVisualVoice = true, listenedForMillis = 1_200L),
        )
    }

    @Test
    fun agentModePhonePreviewWaitsForARealDisplayOrFrame() {
        assertFalse(
            agentModePhonePreviewVisible(
                deskBusy = true,
                displayActive = false,
                hasPreviewBitmap = false,
            ),
        )
        assertTrue(
            agentModePhonePreviewVisible(
                deskBusy = false,
                displayActive = true,
                hasPreviewBitmap = false,
            ),
        )
        assertFalse(
            agentModePhonePreviewVisible(
                deskBusy = false,
                displayActive = false,
                hasPreviewBitmap = false,
            ),
        )
    }

    @Test
    fun agentModeComputerExpandsOnlyAfterCurrentTurnPhoneSubagentStarts() {
        assertFalse(agentModeComputerShouldAutoExpand(phoneSubagentStarted = false))
        assertTrue(agentModeComputerShouldAutoExpand(phoneSubagentStarted = true))
        assertTrue(
            agentModeShouldIsolateIme(
                displayActive = true,
                previewExpanded = true,
                composerFocused = false,
            ),
        )
        assertTrue(
            agentModeShouldIsolateIme(
                displayActive = true,
                previewExpanded = true,
                composerFocused = true,
            ),
        )
        assertFalse(
            agentModeShouldIsolateIme(
                displayActive = true,
                previewExpanded = false,
                composerFocused = true,
            ),
        )

        val user = ChatMessage(id = "u1", author = MessageAuthor.User, text = "查门票")
        val phone = ChatToolInvocation(
            id = "phone-1",
            toolName = "Agent",
            argumentsJson = """{"subagent_type":"phone","prompt":"查门票"}""",
            isRunning = true,
        )
        assertFalse(
            currentTurnPhoneSubagentStarted(
                pendingBlocks = emptyList(),
                pendingTools = emptyList(),
                messages = listOf(user),
            ),
        )
        assertTrue(
            currentTurnPhoneSubagentStarted(
                pendingBlocks = emptyList(),
                pendingTools = listOf(phone),
                messages = listOf(user),
            ),
        )
        val previousAgent = ChatMessage(
            id = "a0",
            author = MessageAuthor.Agent,
            text = "done",
            toolInvocations = listOf(phone.copy(id = "old", isRunning = false)),
        )
        val nextUser = ChatMessage(id = "u2", author = MessageAuthor.User, text = "再查一次")
        assertFalse(
            currentTurnPhoneSubagentStarted(
                pendingBlocks = emptyList(),
                pendingTools = emptyList(),
                messages = listOf(user, previousAgent, nextUser),
            ),
        )
        assertTrue(
            currentTurnPhoneSubagentStarted(
                pendingBlocks = emptyList(),
                pendingTools = emptyList(),
                messages = listOf(
                    user,
                    previousAgent.copy(
                        id = "a1",
                        toolInvocations = listOf(phone.copy(id = "current", isRunning = false)),
                    ),
                ),
            ),
        )
    }

    @Test
    fun composerAgentModeChipWaitsForPreviewBeforeTakeover() {
        assertEquals(
            ComposerAgentModeChipState.AgentMode,
            composerAgentModeChipState(teachingActive = false, previewHasContent = false),
        )
        assertEquals(
            ComposerAgentModeChipState.Takeover,
            composerAgentModeChipState(teachingActive = false, previewHasContent = true),
        )
        assertEquals(
            ComposerAgentModeChipState.EndTeaching,
            composerAgentModeChipState(teachingActive = true, previewHasContent = true),
        )
        assertEquals(
            ComposerAgentModeChipState.EndTeaching,
            composerAgentModeChipState(teachingActive = true, previewHasContent = false),
        )
        assertTrue(agentModePreviewHasContent(displayActive = true, previewPath = ""))
        assertTrue(agentModePreviewHasContent(displayActive = false, previewPath = "/tmp/frame.jpg"))
        assertFalse(agentModePreviewHasContent(displayActive = false, previewPath = ""))
        assertFalse(agentModePreviewUserInputEnabled(teachingActive = false))
        assertTrue(agentModePreviewUserInputEnabled(teachingActive = true))
    }

    @Test
    fun browserDeskPeopleMatchBodyCapsuleIdentity() {
        val invocation = ChatToolInvocation(
            id = "browser-1",
            toolName = "Launching browser agent: search",
            argumentsJson = """{"subagent_type":"browser","description":"西湖","prompt":"搜西湖"}""",
            isRunning = true,
        )
        val names = listOf("苏晚", "林深", "顾言")
        val identityKey = "conv:u1"
        val deskPeople = browserDeskPeople(identityKey, listOf(invocation), names)
        val bodyPeople = subagentPeopleForLaunch(
            identityKey = identityKey,
            toolCallId = invocation.id,
            info = parseSubagentLaunch(invocation.toolName, invocation.argumentsJson, invocation.toolKind)!!,
            members = emptyList(),
            names = names,
        )
        assertEquals(bodyPeople.single().name, deskPeople.single().name)
        assertEquals(bodyPeople.single().avatar.seed, deskPeople.single().avatar.seed)
        assertEquals("西湖", browserDeskCaption("Web, search, fetch, and ima…", "prompt", deskDetail = "西湖"))
    }
}
