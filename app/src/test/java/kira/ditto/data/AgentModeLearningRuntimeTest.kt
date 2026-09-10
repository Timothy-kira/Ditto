package kira.ditto.data

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeLearningRuntimeTest {
    @Test
    fun nativeDisplayTapRecordsGuiLabelWithoutPromotingUnverifiedTrace() {
        val env = Env()
        env.runtime.beginTurn("session-1", "turn-1", "打开小红书搜 everme")
        val tagged = JSONObject(
            env.runtime.recordAgentDisplayCall(
                sessionId = "session-1",
                arguments = JSONObject().put("action", "tap").put("x", 120).put("y", 80),
                rawOutput = JSONObject()
                    .put("ok", true)
                    .put("package_name", "com.xingin.xhs")
                    .put("app_name", "小红书")
                    .put("ui_tree", "搜索 tap (100,60)-(200,120)\n帖子 tap (40,200)-(900,400)")
                    .toString(),
            ),
        )
        val learning = tagged.getJSONObject("gui_learning")
        assertEquals("搜索", learning.getString("gui_label"))
        assertEquals("kimi_agent", learning.getString("source"))

        val completion = env.runtime.completeTurn(
            sessionId = "session-1",
            outerTurnSucceeded = true,
            summary = "opened the app",
        )
        assertEquals(GuiTrajectoryVerdict.Unverified, completion?.attempt?.verdict)
        assertNull(completion?.sop)
        assertTrue(env.sops.loadAll().isEmpty())
        val scene = env.runtime.lastCommittedScene
        requireNotNull(scene)
        assertTrue(scene.tags.contains("#gui"))
        assertTrue(env.runtime.everMeCommitPrompt().contains("#gui"))
        assertEquals("点击搜索", env.runtime.liveGuiSteps.value.single().label)
    }

    @Test
    fun liveGuiStepsClearOnTheNextTurn() {
        val env = Env()
        env.runtime.beginTurn("session-live", "turn-1", "打开美团")
        env.runtime.recordAgentDisplayCall(
            sessionId = "session-live",
            arguments = JSONObject().put("action", "launch").put("target", "美团"),
            rawOutput = JSONObject()
                .put("ok", true)
                .put("package_name", "com.sankuai.meituan")
                .put("app_name", "美团")
                .toString(),
        )
        assertEquals("打开美团", env.runtime.liveGuiSteps.value.single().label)
        val toolCallKey = env.runtime.liveGuiStepsByToolCall.value.keys.single()
        env.runtime.completeTurn("session-live", true, "opened")
        assertEquals(1, env.runtime.liveGuiSteps.value.size)
        env.runtime.beginTurn("session-live", "turn-2", "再查一次")
        assertTrue(env.runtime.liveGuiSteps.value.isEmpty())
        assertEquals("打开美团", env.runtime.guiStepsForToolCall(toolCallKey).single().label)
    }

    @Test
    fun laterPhoneAgentKeepsEarlierCompletedThisTurnDigest() {
        val env = Env()
        env.runtime.beginTurn("session-digest", "turn-1", "查美团和携程门票")
        env.runtime.observeNativeAgentEvent(
            sessionId = "session-digest",
            event = AgentToolEvent(
                id = "agent-meituan",
                name = "Agent",
                argumentsJson = """{"subagent_type":"phone","prompt":"打开美团"}""",
                outputJson = null,
                isRunning = true,
            ),
        )
        env.runtime.recordAgentDisplayCall(
            sessionId = "session-digest",
            arguments = JSONObject().put("action", "launch").put("target", "美团"),
            rawOutput = JSONObject().put("ok", true).put("app_name", "美团").toString(),
        )
        val first = env.runtime.observeNativeAgentEvent(
            sessionId = "session-digest",
            event = AgentToolEvent(
                id = "agent-meituan",
                name = "Agent",
                argumentsJson = """{"subagent_type":"phone","prompt":"打开美团"}""",
                outputJson = "GUI_TASK_SUCCEEDED: 颐和园门票免费",
                isRunning = false,
            ),
        )
        requireNotNull(first)
        assertTrue(first.contains("completed_this_turn:"))
        assertTrue(first.contains("美团"))
        env.runtime.observeNativeAgentEvent(
            sessionId = "session-digest",
            event = AgentToolEvent(
                id = "agent-ctrip",
                name = "Agent",
                argumentsJson = """{"subagent_type":"phone","prompt":"打开携程"}""",
                outputJson = null,
                isRunning = true,
            ),
        )
        env.runtime.recordAgentDisplayCall(
            sessionId = "session-digest",
            arguments = JSONObject().put("action", "launch").put("target", "携程"),
            rawOutput = JSONObject().put("ok", true).put("app_name", "携程").toString(),
        )
        val second = env.runtime.observeNativeAgentEvent(
            sessionId = "session-digest",
            event = AgentToolEvent(
                id = "agent-ctrip",
                name = "Agent",
                argumentsJson = """{"subagent_type":"phone","prompt":"打开携程"}""",
                outputJson = "GUI_TASK_SUCCEEDED: 门票 20 元",
                isRunning = false,
            ),
        )
        requireNotNull(second)
        assertTrue(second.contains("美团"))
        assertTrue(second.contains("携程"))
        assertTrue(env.runtime.guiStepsForToolCall("agent-meituan").any { it.label.contains("美团") })
        assertTrue(env.runtime.guiStepsForToolCall("agent-ctrip").any { it.label.contains("携程") })
    }

    @Test
    fun failedTicketLookupStillPersistsAGuiScene() {
        val env = Env()
        env.runtime.beginTurn("session-fail", "turn-fail", "打开美团查颐和园门票")
        env.runtime.recordAgentDisplayCall(
            sessionId = "session-fail",
            arguments = JSONObject().put("action", "launch").put("target", "美团"),
            rawOutput = JSONObject()
                .put("ok", true)
                .put("package_name", "com.sankuai.meituan")
                .put("app_name", "美团")
                .toString(),
        )
        env.runtime.recordAgentDisplayCall(
            sessionId = "session-fail",
            arguments = JSONObject().put("action", "tap").put("x", 120).put("y", 80),
            rawOutput = JSONObject()
                .put("ok", true)
                .put("package_name", "com.sankuai.meituan")
                .put("app_name", "美团")
                .put("ui_tree", "搜索 tap (100,60)-(200,120)")
                .toString(),
        )
        env.runtime.observeNativeAgentEvent(
            sessionId = "session-fail",
            event = AgentToolEvent(
                id = "agent-fail",
                name = "Agent",
                argumentsJson = """{"subagent_type":"phone"}""",
                outputJson = "GUI_TASK_FAILED: 停在首页，没有看到门票价格",
                isRunning = false,
            ),
        )
        val completion = env.runtime.completeTurn("session-fail", false, "failed")
        assertEquals(GuiTrajectoryVerdict.Failure, completion?.attempt?.verdict)
        assertNull(completion?.sop)
        val scene = env.runtime.lastCommittedScene
        requireNotNull(scene)
        assertTrue(scene.tags.contains("#gui"))
        assertTrue(env.runtime.everMeCommitPrompt().contains("#gui"))
        assertTrue(env.runtime.everMeCommitPrompt().contains("美团"))
    }

    @Test
    fun phoneGuiStepLabelsDescribeLaunchAndTap() {
        assertEquals("打开美团", formatPhoneGuiStepLabel("launch", "美团", "美团"))
        assertEquals("点击购票", formatPhoneGuiStepLabel("tap", "购票", "美团"))
        assertEquals("输入", formatPhoneGuiStepLabel("text", "", ""))
        assertEquals("读取界面", formatPhoneGuiStepLabel("dump_tree", "", ""))
        assertEquals("读取上半屏", formatPhoneGuiStepLabel("dump_tree", "top", ""))
        assertEquals("查找控件", formatPhoneGuiStepLabel("list_targets", "", ""))
        assertEquals("开始听课", formatPhoneGuiStepLabel("listen_start", "", ""))
        assertEquals("听课进度", formatPhoneGuiStepLabel("listen_status", "", ""))
        assertEquals("结束听课", formatPhoneGuiStepLabel("listen_stop", "", ""))
        assertEquals("搜索故宫门票", formatPhoneGuiStepLabel("search", "故宫门票", ""))
        assertEquals("左滑", formatPhoneGuiStepLabel("swipe_left", "", ""))
        assertEquals("右滑", formatPhoneGuiStepLabel("swipe_right", "", ""))
        assertTrue(hasEnoughGuiStepsForSkill(emptyList()).not())
        val encoded = encodePhoneGuiSteps(
            listOf(PhoneGuiStepUi("1-1", 1, "launch", "打开美团", "打开美团", true)),
        )
        val parsed = parsePhoneGuiSteps(encoded)
        assertEquals(1, parsed.size)
        assertEquals("打开美团", parsed.single().label)
        assertEquals("launch", parsed.single().action)
    }

    @Test
    fun phoneAgentSuccessMarkerPromotesCandidateSop() {
        val env = Env()
        env.runtime.beginTurn("session-1", "turn-1", "打开小红书")
        env.runtime.recordAgentDisplayCall(
            sessionId = "",
            arguments = JSONObject().put("action", "launch").put("target", "小红书"),
            rawOutput = JSONObject()
                .put("ok", true)
                .put("package_name", "com.xingin.xhs")
                .put("app_name", "小红书")
                .toString(),
        )
        env.runtime.observeNativeAgentEvent(
            sessionId = "session-1",
            event = AgentToolEvent(
                id = "agent-1",
                name = "Agent",
                argumentsJson = """{"subagent_type":"phone","prompt":"打开小红书"}""",
                outputJson = "GUI_TASK_SUCCEEDED: 已打开小红书首页",
                isRunning = false,
            ),
        )
        val completion = env.runtime.completeTurn(
            sessionId = "session-1",
            outerTurnSucceeded = true,
            summary = "done",
        )
        assertEquals(GuiTrajectoryVerdict.Success, completion?.attempt?.verdict)
        assertEquals(AgentModeSopProvenance.KimiAgent, completion?.sop?.provenance)
        assertTrue(completion?.sop?.promoted == true)
        assertEquals(AgentModeSopMaturity.Ready, completion?.sop?.maturity)
        assertEquals("com.xingin.xhs", completion?.sop?.packageName)
        val scene = env.runtime.lastCommittedScene
        requireNotNull(scene)
        assertTrue(scene.tags.contains("#gui"))
        assertTrue(scene.tags.any { it.startsWith("#app:") })
        assertTrue(scene.tags.any { it.startsWith("#scene:") })
        val fact = scene.toEverMeFact()
        assertTrue(fact.contains("#gui"))
        assertFalse(fact.contains("原神"))
        assertFalse(fact.contains("今天热搜"))
    }

    @Test
    fun userTeachingRaisesConfidenceAndPromotesImmediately() {
        val env = Env()
        env.runtime.beginTurn("session-1", "turn-1", "点搜索")
        env.runtime.recordAgentDisplayCall(
            sessionId = "session-1",
            arguments = JSONObject().put("action", "launch").put("target", "小红书"),
            rawOutput = JSONObject()
                .put("ok", true)
                .put("package_name", "com.xingin.xhs")
                .put("ui_tree", "搜索 tap (100,60)-(200,120)")
                .toString(),
        )
        env.runtime.onUserTap(120, 80)
        val completion = env.runtime.completeTurn(
            sessionId = "session-1",
            outerTurnSucceeded = true,
            summary = "user finished the search",
        )
        assertEquals(GuiTrajectoryVerdict.Success, completion?.attempt?.verdict)
        assertTrue(completion?.attempt?.userTaught == true)
        assertEquals(AgentModeSopProvenance.UserTeaching, completion?.sop?.provenance)
        assertTrue(completion?.sop?.promoted == true)
        assertEquals(AgentModeSopMaturity.Ready, completion?.sop?.maturity)
        assertEquals("搜索", completion?.sop?.steps?.last()?.guiLabel)
    }

    @Test
    fun fieldPickIsASlotWithoutCoordinatesAndCommitPublishesEverMe() {
        val env = Env()
        env.runtime.beginTurn("session-field", "turn-field", "打开我的田块")
        env.runtime.recordAgentDisplayCall(
            sessionId = "session-field",
            arguments = JSONObject().put("action", "launch").put("target", "田块"),
            rawOutput = JSONObject()
                .put("ok", true)
                .put("package_name", "com.example.farm")
                .put("app_name", "田块")
                .put(
                    "ui_tree",
                    """
                    返回 tap (20,40)-(120,100)
                    1号田 tap (80,200)-(900,320)
                    2号田 tap (80,340)-(900,460)
                    3号田东块 tap (80,480)-(900,600)
                    4号田 tap (80,620)-(900,740)
                    5号田 tap (80,760)-(900,880)
                    6号田 tap (80,900)-(900,1020)
                    """.trimIndent(),
                )
                .toString(),
        )
        env.runtime.onUserTap(120, 70)
        env.runtime.onUserTap(400, 540)
        env.runtime.onTeachingPageSettled(
            """
            确定 tap (400,900)-(900,980)
            """.trimIndent(),
        )
        val committed = env.runtime.commitTeaching("session-field")
            ?: error("expected teaching commit")
        val committedSop = committed.sop ?: error("expected taught sop")
        val replay = env.sops.loadAll().flatMap { it.steps }
        val pick = replay.last { it.inputSlot == TeachingTraceDistiller.PickSlot }
        assertEquals(TeachingTraceDistiller.KindInstance, pick.kind)
        assertNull(pick.x)
        assertNull(pick.y)
        assertEquals("", pick.guiLabel)
        assertTrue(replay.any { it.guiLabel == "返回" }.not())
        val scene = env.runtime.lastCommittedScene
        requireNotNull(scene)
        assertTrue(scene.tags.contains("#gui"))
        assertTrue(scene.toEverMeFact().contains("App:"))
        assertTrue(env.runtime.everMeCommitPrompt().contains("#gui"))
        val completion = env.runtime.completeTurn("session-field", true, "taught")
        assertEquals(committedSop.id, completion?.sop?.id)
    }

    @Test
    fun secondTeachingUpdatesTheSameGuiScene() {
        val env = Env()
        env.runtime.beginTurn("session-scene", "turn-1", "打开小红书搜 everme")
        env.runtime.recordAgentDisplayCall(
            sessionId = "session-scene",
            arguments = JSONObject().put("action", "launch").put("target", "小红书"),
            rawOutput = JSONObject()
                .put("ok", true)
                .put("package_name", "com.xingin.xhs")
                .put("app_name", "小红书")
                .put("ui_tree", "搜索 tap (100,60)-(200,120)")
                .toString(),
        )
        env.runtime.onUserTap(120, 80)
        val first = env.runtime.commitTeaching("session-scene")
            ?: error("expected first teaching commit")
        requireNotNull(first.sop)
        val firstScene = env.runtime.lastCommittedScene
        requireNotNull(firstScene)
        assertEquals(1, firstScene.version)
        env.runtime.beginTurn("session-scene-2", "turn-2", "打开小红书搜 everme")
        env.runtime.recordAgentDisplayCall(
            sessionId = "session-scene-2",
            arguments = JSONObject().put("action", "launch").put("target", "小红书"),
            rawOutput = JSONObject()
                .put("ok", true)
                .put("package_name", "com.xingin.xhs")
                .put("app_name", "小红书")
                .put("ui_tree", "搜索 tap (100,60)-(200,120)\n确定 tap (400,900)-(900,980)")
                .toString(),
        )
        env.runtime.onUserTap(120, 80)
        env.runtime.onUserTap(500, 940)
        val second = env.runtime.commitTeaching("session-scene-2")
        requireNotNull(second)
        val scene = env.runtime.lastCommittedScene
        requireNotNull(scene)
        assertEquals(firstScene.id, scene.id)
        assertEquals(2, scene.version)
        assertEquals(1, env.scenes.loadAll().size)
        assertTrue(scene.toEverMeFact().contains("#gui"))
    }

    @Test
    fun automaticSuccessStartsAsValidatingBeforePromotion() {
        val env = Env()
        env.runtime.beginTurn("session-auto", "turn-auto", "打开小红书并搜索护肤")
        env.runtime.recordAgentDisplayCall(
            sessionId = "session-auto",
            arguments = JSONObject().put("action", "tap").put("x", 120).put("y", 80),
            rawOutput = JSONObject()
                .put("ok", true)
                .put("package_name", "com.xingin.xhs")
                .put("app_name", "小红书")
                .put("ui_tree", "搜索 tap (100,60)-(200,120) #search_bar")
                .toString(),
        )
        env.runtime.observeNativeAgentEvent(
            sessionId = "session-auto",
            event = AgentToolEvent(
                id = "agent-auto",
                name = "Agent",
                argumentsJson = """{"subagent_type":"phone"}""",
                outputJson = "GUI_TASK_SUCCEEDED: searched",
                isRunning = false,
            ),
        )
        val completion = env.runtime.completeTurn("session-auto", true, "ok")
        val sop = completion?.sop
        requireNotNull(sop)
        assertEquals(AgentModeSopMaturity.Validating, sop.maturity)
        assertEquals("pending_replay", sop.validationState)
        assertTrue(!sop.promoted)
    }

    @Test
    fun reusedSopRecordsFeedbackWithoutMintingADuplicate() {
        val env = Env()
        env.runtime.beginTurn("session-1", "turn-1", "打开小红书")
        env.runtime.recordAgentDisplayCall(
            sessionId = "session-1",
            arguments = JSONObject().put("action", "launch").put("target", "小红书"),
            rawOutput = JSONObject().put("ok", true).put("package_name", "com.xingin.xhs").toString(),
        )
        env.runtime.observeNativeAgentEvent(
            sessionId = "session-1",
            event = AgentToolEvent(
                id = "agent-1",
                name = "Agent",
                argumentsJson = """{"subagent_type":"phone"}""",
                outputJson = "GUI_TASK_SUCCEEDED: opened",
                isRunning = false,
            ),
        )
        val first = env.runtime.completeTurn("session-1", true, "ok")?.sop
        requireNotNull(first)

        env.runtime.beginTurn("session-2", "turn-2", "打开小红书")
        env.runtime.recordPhoneAppFlowCall(
            sessionId = "session-2",
            toolName = "xhs_open",
            arguments = JSONObject(),
            rawOutput = JSONObject()
                .put("ok", true)
                .put("sop_id", first.id)
                .put("package_name", "com.xingin.xhs")
                .toString(),
        )
        env.runtime.observeNativeAgentEvent(
            sessionId = "session-2",
            event = AgentToolEvent(
                id = "agent-2",
                name = "Agent",
                argumentsJson = """{"subagent_type":"phone"}""",
                outputJson = "GUI_TASK_SUCCEEDED: replayed",
                isRunning = false,
            ),
        )
        val second = env.runtime.completeTurn("session-2", true, "ok")
        assertNull(second?.sop)
        assertEquals(2, env.sops.findById(first.id)?.successCount)
        assertEquals(1, env.sops.loadAll().size)
    }

    @Test
    fun typedValueIsReplacedByReusableSlotAndRedactedFromTrajectory() {
        val env = Env()
        env.runtime.beginTurn("session-secret", "turn-secret", "搜索账号")
        env.runtime.recordAgentDisplayCall(
            sessionId = "session-secret",
            arguments = JSONObject().put("action", "text").put("text", "private@example.com"),
            rawOutput = JSONObject()
                .put("ok", true)
                .put("ui_tree", "账号 focus (100,200)-(900,300)")
                .toString(),
        )
        env.runtime.observeNativeAgentEvent(
            sessionId = "session-secret",
            event = AgentToolEvent(
                id = "agent-secret",
                name = "Agent",
                argumentsJson = """{"subagent_type":"phone"}""",
                outputJson = "GUI_TASK_SUCCEEDED: search complete",
                isRunning = false,
            ),
        )
        val completion = env.runtime.completeTurn("session-secret", true, "done")
        val step = completion?.sop?.steps?.single()
        assertEquals(TeachingTraceDistiller.QuerySlot, step?.inputSlot)
        assertEquals("", step?.text)
        val stored = completion?.attempt?.events?.single()?.argumentsJson.orEmpty()
        assertFalse(stored.contains("private@example.com"))
        assertTrue(stored.contains("text_length"))
    }

    @Test
    fun watchingMarkerDoesNotPersistAScene() {
        val env = Env()
        env.runtime.beginTurn("session-watch", "turn-watch", "刷课")
        env.runtime.recordAgentDisplayCall(
            sessionId = "session-watch",
            arguments = JSONObject().put("action", "launch").put("target", "学习通"),
            rawOutput = JSONObject().put("ok", true).put("app_name", "学习通").toString(),
        )
        env.runtime.observeNativeAgentEvent(
            sessionId = "session-watch",
            event = AgentToolEvent(
                id = "agent-watch",
                name = "Agent",
                argumentsJson = """{"subagent_type":"phone"}""",
                outputJson = "GUI_TASK_WATCHING: 第一节课 interval=10m",
                isRunning = false,
            ),
        )
        val completion = env.runtime.completeTurn("session-watch", true, "watching")
        assertEquals(GuiTrajectoryVerdict.Unverified, completion?.attempt?.verdict)
        assertNull(env.runtime.lastCommittedScene)
        assertTrue(env.runtime.everMeCommitPrompt().isBlank())
        val watch = env.runtime.consumeWatchSchedule()
        requireNotNull(watch)
        assertEquals("session-watch", watch.sessionId)
        assertEquals(10L * 60L * 1000L, watch.intervalMillis)
    }

    @Test
    fun succeededMarkerCancelsHostWatchSchedule() {
        val env = Env()
        env.runtime.beginTurn("session-end", "turn-end", "刷课")
        env.runtime.observeNativeAgentEvent(
            sessionId = "session-end",
            event = AgentToolEvent(
                id = "agent-end",
                name = "Agent",
                argumentsJson = """{"subagent_type":"phone"}""",
                outputJson = "GUI_TASK_WATCHING: 课 interval=5m",
                isRunning = false,
            ),
        )
        env.runtime.consumeWatchSchedule()
        env.runtime.observeNativeAgentEvent(
            sessionId = "session-end",
            event = AgentToolEvent(
                id = "agent-end",
                name = "Agent",
                argumentsJson = """{"subagent_type":"phone"}""",
                outputJson = "GUI_TASK_SUCCEEDED: 课上完了",
                isRunning = false,
            ),
        )
        val cancel = env.runtime.consumeWatchSchedule()
        requireNotNull(cancel)
        assertEquals("session-end", cancel.sessionId)
        assertNull(cancel.intervalMillis)
    }

    @Test
    fun secondAutomaticSuccessUpsertsTheSameAbstractScene() {
        val env = Env()
        fun runOnce(sessionId: String, goal: String) {
            env.runtime.beginTurn(sessionId, "turn", goal)
            env.runtime.recordAgentDisplayCall(
                sessionId = sessionId,
                arguments = JSONObject().put("action", "tap").put("x", 120).put("y", 80),
                rawOutput = JSONObject()
                    .put("ok", true)
                    .put("package_name", "tv.danmaku.bili")
                    .put("app_name", "哔哩哔哩")
                    .put("ui_tree", "搜索 tap (100,60)-(200,120) #search_bar")
                    .toString(),
            )
            env.runtime.observeNativeAgentEvent(
                sessionId = sessionId,
                event = AgentToolEvent(
                    id = "agent-$sessionId",
                    name = "Agent",
                    argumentsJson = """{"subagent_type":"phone"}""",
                    outputJson = "GUI_TASK_SUCCEEDED: 已搜索",
                    isRunning = false,
                ),
            )
            env.runtime.completeTurn(sessionId, true, "ok")
        }
        runOnce("session-a", "打开哔哩哔哩搜原神并点赞")
        val first = env.runtime.lastCommittedScene
        requireNotNull(first)
        runOnce("session-b", "打开哔哩哔哩搜星穹铁道点赞")
        val second = env.runtime.lastCommittedScene
        requireNotNull(second)
        assertEquals(first.id, second.id)
        assertTrue(second.version >= 2)
        assertEquals(1, env.scenes.loadAll().size)
        assertFalse(second.toEverMeFact().contains("原神"))
        assertFalse(second.toEverMeFact().contains("星穹铁道"))
    }

    @Test
    fun guiStepsReloadFromDiskAfterNewRuntime() {
        val root = Files.createTempDirectory("aether-gui-step-files").toFile()
        val stepsDir = root.resolve("steps").apply { mkdirs() }
        val first = AgentModeLearningRuntime(
            trajectoryStore = GuiTrajectoryStore(root.resolve("trajectories").apply { mkdirs() }),
            sopStore = AgentModeSopStore(root.resolve("sops").apply { mkdirs() }),
            settlementRuntime = null,
            guiStepStoreDir = stepsDir,
        )
        first.beginTurn("session-disk", "turn-1", "打开美团")
        first.observeNativeAgentEvent(
            sessionId = "session-disk",
            event = AgentToolEvent(
                id = "call-meituan",
                name = "Agent",
                argumentsJson = """{"subagent_type":"phone","prompt":"打开美团"}""",
                outputJson = null,
                isRunning = true,
            ),
        )
        first.recordAgentDisplayCall(
            sessionId = "session-disk",
            arguments = JSONObject().put("action", "launch").put("target", "美团"),
            rawOutput = JSONObject().put("ok", true).put("app_name", "美团").toString(),
        )
        assertEquals("打开美团", first.guiStepsForToolCall("call-meituan").single().label)

        val restored = AgentModeLearningRuntime(
            trajectoryStore = GuiTrajectoryStore(root.resolve("trajectories-2").apply { mkdirs() }),
            sopStore = AgentModeSopStore(root.resolve("sops-2").apply { mkdirs() }),
            settlementRuntime = null,
            guiStepStoreDir = stepsDir,
        )
        assertEquals("打开美团", restored.guiStepsForToolCall("call-meituan").single().label)
        assertTrue(restored.guiStepsJsonForToolCall("call-meituan").contains("打开美团"))
    }

    private class Env {
        private val root = Files.createTempDirectory("aether-gui-learning").toFile()
        val trajectories = GuiTrajectoryStore(root.resolve("trajectories").apply { mkdirs() })
        val sops = AgentModeSopStore(root.resolve("sops").apply { mkdirs() })
        val scenes = GuiSceneStore(root.resolve("scenes").apply { mkdirs() })
        val runtime = AgentModeLearningRuntime(
            trajectoryStore = trajectories,
            sopStore = sops,
            settlementRuntime = null,
            sceneStore = scenes,
        )
    }
}
