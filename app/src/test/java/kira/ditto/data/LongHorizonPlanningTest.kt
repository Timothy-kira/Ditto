package kira.ditto.data

import kira.ditto.data.kimi.PendingPermissionOption
import kira.ditto.data.kimi.PendingPermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongHorizonPlanningTest {
    @Test
    fun deskLeadKindKeepsQuickGuiHotPath() {
        assertEquals(
            DeskLeadKind.QuickGui,
            resolveDeskLeadKind(
                agentModeEnabled = true,
                planMode = false,
                longHorizonApproved = false,
                hasOpenTodos = false,
            ),
        )
    }

    @Test
    fun planModeWithoutAgentIsResearch() {
        assertEquals(
            DeskLeadKind.Research,
            resolveDeskLeadKind(
                agentModeEnabled = false,
                planMode = true,
                longHorizonApproved = false,
                hasOpenTodos = false,
            ),
        )
    }

    @Test
    fun approvedOrOpenTodosUseLongHorizonRelay() {
        assertEquals(
            DeskLeadKind.LongHorizon,
            resolveDeskLeadKind(
                agentModeEnabled = true,
                planMode = false,
                longHorizonApproved = true,
                hasOpenTodos = false,
            ),
        )
        assertEquals(
            DeskLeadKind.LongHorizon,
            resolveDeskLeadKind(
                agentModeEnabled = true,
                planMode = false,
                longHorizonApproved = false,
                hasOpenTodos = true,
            ),
        )
    }

    @Test
    fun planApprovalDetectsExitPlanOptions() {
        val request = PendingPermissionRequest(
            requestId = "r1",
            sessionId = "s1",
            kimiSessionId = "k1",
            toolCallId = "t1",
            toolCallTitle = "ExitPlanMode",
            toolCallKind = "other",
            options = listOf(
                PendingPermissionOption("plan_opt_0", "Go", "allow_once"),
                PendingPermissionOption("plan_revise", "Revise", "allow_once"),
                PendingPermissionOption("plan_reject_and_exit", "Exit", "reject_once"),
            ),
            requestedAtMillis = 0L,
        )
        assertTrue(isPlanApprovalAnswer(request, "plan_opt_0"))
        assertFalse(isPlanApprovalAnswer(request, "plan_revise"))
        assertFalse(isPlanApprovalAnswer(request, "plan_reject_and_exit"))
        assertFalse(isPlanApprovalAnswer(request.copy(toolCallTitle = "Bash"), "plan_opt_0"))
    }

    @Test
    fun evidencePathsStayUnderReports() {
        assertEquals("reports/yunnan/shots/1.jpg", sanitizeEvidenceRelativePath("reports/yunnan/shots/1.jpg"))
        assertEquals("reports/yunnan/shots/1.jpg", sanitizeEvidenceRelativePath("/workspace/reports/yunnan/shots/1.jpg"))
        assertEquals("", sanitizeEvidenceRelativePath("../etc/passwd"))
        assertEquals("", sanitizeEvidenceRelativePath("tmp/shot.jpg"))
        assertEquals(
            "reports/evidence/shots/9.jpg",
            resolveEvidencePersistPath(persist = true, persistPath = "", nowMillis = 9L),
        )
        assertEquals(
            "reports/a/b.jpg",
            resolveEvidencePersistPath(persist = false, persistPath = "reports/a/b.jpg", nowMillis = 1L),
        )
    }

    @Test
    fun remindersMentionRelayAndCatalog() {
        assertTrue(DeviceCatalogResearchReminder.contains("mcp__device_catalog__list_apps"))
        assertTrue(DeviceCatalogResearchReminder.contains("ExitPlanMode"))
        assertFalse(DeviceCatalogResearchReminder.contains("first action must be Agent"))
        assertTrue(LongHorizonLeadReminder.contains("subagent_type=\"explore\""))
        assertTrue(LongHorizonLeadReminder.contains("subagent_type=\"phone\""))
        assertTrue(LongHorizonLeadReminder.contains("AgentSwarm"))
        assertTrue(LongHorizonLeadReminder.contains("{{item}}"))
        assertTrue(LongHorizonLeadReminder.contains("completed_this_turn"))
        assertTrue(LongHorizonLeadReminder.contains("README.md"))
        assertTrue(AgentModeLeadReminder.contains("first action"))
        assertTrue(AgentModeLeadReminder.contains("AgentSwarm"))
        assertTrue(
            agentModeLeadReminderText(preopen = null, kind = DeskLeadKind.Research)
                .contains("mcp__device_catalog__list_apps"),
        )
        assertTrue(
            agentModeLeadReminderText(preopen = null, kind = DeskLeadKind.LongHorizon)
                .contains("explore"),
        )
        assertTrue(
            agentModeLeadReminderText(preopen = null, kind = DeskLeadKind.QuickGui)
                .contains("AgentSwarm"),
        )
        assertFalse(
            agentModeLeadReminderText(
                preopen = null,
                kind = DeskLeadKind.QuickGui,
                includeStaticRules = false,
            ).contains("AgentSwarm"),
        )
        assertTrue(
            kimiPromptHasDeskLead(AgentModeLeadReminder),
        )
        assertTrue(
            kimiPromptHasDeskLead(LongHorizonLeadReminder),
        )
        assertTrue(
            kimiPromptHasDeskLead(DeviceCatalogResearchReminder),
        )
        assertTrue(
            kimiPromptHasDeskLead(BrowserLeadReminder),
        )
        assertFalse(BrowserLeadReminder.contains("[[N]]"))
        assertFalse(BrowserLeadReminder.contains("Do not call WebSearch"))
        assertTrue(BrowserLeadReminder.contains("WebSearch and FetchURL are the browser group"))
        assertTrue(BrowserLeadReminder.contains("Lookup loop"))
        assertTrue(BrowserLeadReminder.contains("user's subject kept intact"))
        assertTrue(BrowserLeadReminder.contains("at most 3 items"))
        assertTrue(BrowserLeadReminder.contains("深度搜索"))
        assertTrue(BrowserLeadReminder.contains("topicId"))
        assertTrue(BrowserLeadReminder.contains("GUI_TASK_SUCCEEDED"))
        assertTrue(BrowserLeadReminder.contains("AgentSwarm"))
        assertTrue(BrowserLeadReminder.contains("Never say web access is limited"))
        assertTrue(BrowserLeadReminder.contains("subagent_type=\"browser\""))
        assertTrue(BrowserLeadReminder.contains("subagent_type=\"image\""))
        assertTrue(AgentModeLeadReminder.contains("[[N]]"))
        assertTrue(AgentModeLeadReminder.contains("native Android app"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("Scale effort to the task"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("search_images"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("topicId"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("GUI_TASK_SUCCEEDED"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("tabs_navigate"))
        assertFalse(KimiBrowserSubagentProfileMarkdown.contains("[[N]]"))
        assertFalse(KimiBrowserSubagentProfileMarkdown.contains("http_fetch"))
        assertFalse(KimiBrowserSubagentProfileMarkdown.contains("search_web"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("Always pass `topic_id`"))
        assertFalse(KimiBrowserSubagentProfileMarkdown.contains("serialized on the selected tab"))
        assertTrue(BrowserLeadReminder.contains("prompt_template"))
        assertTrue(BrowserLeadReminder.contains("{{item}}"))
        assertTrue(BrowserLeadReminder.contains("topic_id={{item}}"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("This is a loop, not a keyword spray"))
        assertTrue(looksLikeDeepWebSearch("帮我深度搜索西湖门票"))
        assertTrue(looksLikeDeepWebSearch("做一次深度研究"))
        assertTrue(looksLikeDeepWebSearch("Do a deep research pass"))
        assertFalse(looksLikeDeepWebSearch("西湖门票多少钱"))
        assertFalse(looksLikeDeepWebSearch("帮我搜索西湖门票"))
        assertTrue(BrowserLeadReminder.contains("distinct angles"))
        assertTrue(BrowserLeadReminder.contains("Plain 搜索"))
        assertTrue(BrowserLeadReminder.contains("[[browser-act:"))
        assertTrue(BrowserLeadReminder.contains("do not page_form"))
        assertTrue(BrowserLeadReminder.contains("1) search"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("or guess site paths"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("If the parent asked only to search or read"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("Never invent name, email, phone"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("user_takeover"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("extension_not_ready"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("page_not_ready"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("usually one more `page_snapshot`"))
        assertFalse(KimiBrowserSubagentProfileMarkdown.contains("Retry `tabs_navigate`"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("Do not ask the user to open a browser card"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("Never say web access is limited"))
        assertTrue(KimiBrowserSubagentProfileMarkdown.contains("阅读原文"))
        assertTrue(BrowserLeadReminder.contains("in-chat browser card"))
        assertEquals(
            "Keep replies short.",
            kimiAgentsMdContents("  Keep replies short.  "),
        )
        assertEquals(null, kimiAgentsMdContents("   "))
        val preopen = AgentModePreopenResult(packageName = "com.tencent.mm", appName = "微信")
        val dynamicOnly = agentModeLeadReminderText(
            preopen = preopen,
            kind = DeskLeadKind.QuickGui,
            includeStaticRules = false,
        )
        assertTrue(dynamicOnly.contains("com.tencent.mm"))
        assertFalse(dynamicOnly.contains("AgentSwarm"))
        val withFact = agentModeLeadReminderText(
            preopen = preopen,
            everMeCommitFact = "#gui #app:日历 App: 日历 (com.kira.ditto)",
            kind = DeskLeadKind.QuickGui,
            includeStaticRules = false,
        )
        assertTrue(withFact.contains("BOOKKEEPING from the previous turn"))
        assertTrue(withFact.contains("#gui #app:日历"))
        // Wrapped so the memory sidecar can strip it whole. The lead is prepended as blocks of the
        // user prompt, so an unwrapped imperative here was extracted as the turn intent and surfaced
        // as a memory entry claiming the user had asked for bookkeeping.
        assertTrue(withFact.contains("<plugin_session_end>"))
        assertTrue(withFact.trimEnd().endsWith("</plugin_session_end>"))
        assertTrue(withFact.contains("dump_tree package_name is a different app"))
    }
}
