package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class PhoneOperatorRuntimeTest {
    @Test
    fun chatCompletionsUrlAddsKimiCodingV1() {
        assertEquals(
            "https://api.kimi.com/coding/v1/chat/completions",
            PhoneOperatorRuntime.chatCompletionsUrl("https://api.kimi.com/coding"),
        )
        assertEquals(
            "https://api.kimi.com/coding/v1/chat/completions",
            PhoneOperatorRuntime.chatCompletionsUrl("https://api.kimi.com/coding/chat/completions"),
        )
        assertEquals(
            "https://api.moonshot.cn/v1/chat/completions",
            PhoneOperatorRuntime.chatCompletionsUrl("https://api.moonshot.cn/v1"),
        )
    }

    @Test
    fun extractAssistantTextPrefersContentThenReasoning() {
        val reasoningOnly = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject()
                            .put("content", "")
                            .put("reasoning_content", "{\"action\":\"tap\",\"x\":500,\"y\":500}"),
                    ),
                ),
            )
        assertEquals(
            "{\"action\":\"tap\",\"x\":500,\"y\":500}",
            PhoneOperatorRuntime.extractAssistantText(reasoningOnly),
        )
    }

    @Test
    fun extractAssistantTextReportsTruncation() {
        val truncated = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("finish_reason", "length")
                        .put("message", JSONObject().put("content", "")),
                ),
            )
        val thrown = runCatching { PhoneOperatorRuntime.extractAssistantText(truncated) }.exceptionOrNull()
        assertTrue(thrown?.message.orEmpty().contains("truncated"))
    }

    @Test
    fun nonJsonOperatorReplyWaitsInsteadOfReporting() {
        val parsed = PhoneOperatorRuntime.parseOperatorCommandJson("Opened Xiaohongshu, next I will search.")
        assertEquals("wait", parsed.getString("action"))
    }

    @Test
    fun searchTaskIsPrematureAfterOnlyLaunch() {
        assertTrue(PhoneOperatorRuntime.taskNeedsMoreThanLaunch("打开小红书搜everme", ""))
        assertTrue(PhoneOperatorRuntime.taskNeedsMoreThanLaunch("open Xiaohongshu and search everme", ""))
        assertFalse(PhoneOperatorRuntime.taskNeedsMoreThanLaunch("打开小红书", ""))
        assertTrue(
            PhoneOperatorRuntime.isPrematureReport(
                task = "打开小红书搜everme",
                successCriteria = "看到最新帖",
                stepIndex = 2,
                traceActions = listOf("launch"),
            ),
        )
        assertFalse(
            PhoneOperatorRuntime.isPrematureReport(
                task = "打开小红书搜everme",
                successCriteria = "看到最新帖",
                stepIndex = 4,
                traceActions = listOf("launch", "tap", "type_text"),
            ),
        )
    }

    @Test
    fun launcherPackagesAreNotTreatedAsOpenedApps() {
        assertTrue(PhoneOperatorRuntime.isHomeOrSystemUiPackage(""))
        assertTrue(PhoneOperatorRuntime.isHomeOrSystemUiPackage("com.huawei.android.launcher"))
        assertTrue(PhoneOperatorRuntime.isHomeOrSystemUiPackage("com.android.systemui"))
        assertTrue(PhoneOperatorRuntime.isHomeOrSystemUiPackage("com.miui.home"))
        assertFalse(PhoneOperatorRuntime.isHomeOrSystemUiPackage("com.xingin.xhs"))
        assertFalse(PhoneOperatorRuntime.isHomeOrSystemUiPackage("com.tencent.mm"))
    }
}

class AgentModeSopStoreTest {
    @Test
    fun promotesAfterThreeSuccesses() {
        val dir = createTempDirectory("agent-mode-sops").toFile()
        try {
            val store = AgentModeSopStore(dir)
            val step = AgentModeSopStep(
                action = "tap",
                kind = TeachingTraceDistiller.KindInstance,
                inputSlot = TeachingTraceDistiller.PickSlot,
                guiLabel = "帖子",
                appName = "小红书",
            )
            val second = store.recordSuccess("打开小红书点赞", listOf(step)).let {
                store.recordSuccess("打开小红书点赞", listOf(step))
            }
            assertEquals(2, second.successCount)
            assertFalse(second.promoted)
            val third = store.recordSuccess("打开小红书点赞", listOf(step))
            assertEquals(3, third.successCount)
            assertTrue(third.promoted)
            assertEquals(third.id, store.findByGoal("打开小红书点赞")?.id)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun methodSegmentPromotesAfterOneSuccess() {
        val dir = createTempDirectory("agent-mode-sops-method").toFile()
        try {
            val store = AgentModeSopStore(dir)
            val step = AgentModeSopStep(
                action = "tap",
                kind = TeachingTraceDistiller.KindMethod,
                guiLabel = "搜索",
                locatorSpec = GuiLocator(role = "search", resourceId = "search_bar"),
                appName = "小红书",
            )
            val first = store.recordVerifiedSuccess("小红书 · search", listOf(step))
            assertEquals(1, first.successCount)
            assertTrue(first.promoted)
            assertEquals(AgentModeSopMaturity.Ready, first.maturity)
        } finally {
            dir.deleteRecursively()
        }
    }
}
