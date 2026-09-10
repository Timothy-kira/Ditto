package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class PhoneAppCatalogTest {
    @Test
    fun classifiesVisibleContentExtractionAsReusablePrimitive() {
        val kinds = PhoneAppPrimitiveClassifier.kindsFrom(
            goal = "点击小红书帖子并抓取内容",
            steps = emptyList(),
        )

        assertTrue(PhoneAppPrimitiveKind.OpenItem in kinds)
        assertTrue(PhoneAppPrimitiveKind.ExtractContent in kinds)
    }

    @Test
    fun classifiesXiaohongshuSearchAndOpenPost() {
        val searchLocator = GuiLocator(role = "search", resourceId = "search_bar", staticDesc = "搜索")
        val steps = listOf(
            AgentModeSopStep(
                action = "launch",
                target = "com.xingin.xhs",
                packageName = "com.xingin.xhs",
                appName = "小红书",
                uiFingerprint = "home",
                toFingerprint = "home",
            ),
            AgentModeSopStep(
                action = "tap",
                kind = TeachingTraceDistiller.KindMethod,
                uiFingerprint = "home",
                toFingerprint = "search",
                locatorSpec = searchLocator,
                guiLabel = "搜索",
                packageName = "com.xingin.xhs",
                appName = "小红书",
            ),
            AgentModeSopStep(
                action = "text",
                text = "护肤",
                inputSlot = TeachingTraceDistiller.QuerySlot,
                uiFingerprint = "search",
                toFingerprint = "search",
                packageName = "com.xingin.xhs",
                appName = "小红书",
            ),
            AgentModeSopStep(
                action = "tap",
                kind = TeachingTraceDistiller.KindInstance,
                inputSlot = TeachingTraceDistiller.PickSlot,
                uiFingerprint = "results",
                toFingerprint = "detail",
                guiLabel = "护肤帖",
                packageName = "com.xingin.xhs",
                appName = "小红书",
            ),
        )
        val segments = GuiFlowSegmenter.segment(steps)
        assertEquals(listOf("search", "open_item"), segments.map { it.sceneKey })
        val primitives = segments.flatMap { segment ->
            PhoneAppPrimitiveClassifier.classify(
                goal = "打开小红书搜索护肤并点击帖子",
                steps = segment.steps,
                packageName = "com.xingin.xhs",
                appName = "小红书",
                sopId = "sop-${segment.sceneKey}",
            )
        }
        val tools = primitives.map { it.toolName }
        assertTrue(tools.contains("xhs_search"))
        assertTrue(tools.contains("xhs_open_item"))
        assertEquals(1, primitives.count { it.kind == PhoneAppPrimitiveKind.Search })
        assertEquals(1, primitives.count { it.kind == PhoneAppPrimitiveKind.OpenItem })
        assertTrue(primitives.first { it.kind == PhoneAppPrimitiveKind.Search }.acceptsQuery)
        assertEquals("sop-search", primitives.first { it.kind == PhoneAppPrimitiveKind.Search }.sopId)
        assertEquals("sop-open_item", primitives.first { it.kind == PhoneAppPrimitiveKind.OpenItem }.sopId)
        val skill = PhoneAppSkillMarkdown.render(
            PhoneAppCatalog(
                packageName = "com.xingin.xhs",
                appName = "小红书",
                primitives = primitives.map { it.copy(promoted = true, successCount = 3) },
            ),
        )
        assertTrue(skill.body.contains("degraded_to=replan"))
        assertTrue(skill.body.contains("Never tell the user the MCP backend is unavailable"))
        assertFalse(skill.body.contains("falls back to `agent_display`"))
    }

    @Test
    fun mergeIncrementsSuccessCountAndKeepsLatestSop() {
        val dir = createTempDirectory("agent-mode-apps").toFile()
        try {
            val store = PhoneAppCatalogStore(dir)
            val first = PhoneAppPrimitive(
                kind = PhoneAppPrimitiveKind.Search,
                toolName = "xhs_search",
                title = "搜索小红书",
                description = "search",
                sopId = "sop-1",
                goal = "搜索护肤",
                acceptsQuery = true,
            )
            store.merge("com.xingin.xhs", "小红书", listOf(first), "agent-mode-com-xingin-xhs")
            val second = first.copy(sopId = "sop-2")
            val catalog = store.merge("com.xingin.xhs", "小红书", listOf(second))
            assertEquals(1, catalog.primitives.size)
            assertEquals(2, catalog.primitives.first().successCount)
            assertEquals("sop-2", catalog.primitives.first().sopId)
            assertFalse(catalog.readyPrimitives().any { it.toolName == "xhs_search" })
            val verified = store.merge(
                "com.xingin.xhs",
                "小红书",
                listOf(second.copy(sopId = "sop-3", successCount = 3, promoted = true)),
            )
            assertTrue(verified.readyPrimitives().any { it.toolName == "xhs_search" })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun recallsOnlyPromotedFlowsRelevantToTask() {
        val dir = createTempDirectory("agent-mode-recall").toFile()
        try {
            val store = PhoneAppCatalogStore(dir)
            val search = PhoneAppPrimitive(
                kind = PhoneAppPrimitiveKind.Search,
                toolName = "xhs_search",
                title = "搜索小红书",
                description = "search",
                sopId = "sop-search",
                goal = "在小红书搜索帖子",
                successCount = 3,
                promoted = true,
                confidence = 0.8,
            )
            val draft = search.copy(
                toolName = "xhs_compose",
                sopId = "sop-draft",
                goal = "发布小红书帖子",
                promoted = false,
                successCount = 1,
            )
            store.merge("com.xingin.xhs", "小红书", listOf(search, draft))
            val hits = store.recallReady("帮我在小红书搜索护肤帖子")
            assertEquals(listOf("sop-search"), hits.map { it.second.sopId })
        } finally {
            dir.deleteRecursively()
        }
    }
}

class PhoneSettlementStoreTest {
    @Test
    fun reclaimStaleRunningMakesJobResumable() {
        val dir = createTempDirectory("agent-mode-settlements").toFile()
        try {
            val store = PhoneSettlementStore(dir)
            store.upsert(
                PhoneSettlementJob(
                    id = "settle-1",
                    stage = PhoneSettlementStage.Catalogued,
                    goal = "搜索护肤",
                    packageName = "com.xingin.xhs",
                    appName = "小红书",
                    running = true,
                    updatedAtMillis = System.currentTimeMillis() - 120_000,
                ),
                updateTimestamp = false,
            )
            store.reclaimStaleRunning(staleAfterMillis = 45_000)
            val next = store.nextResumable()
            requireNotNull(next)
            assertEquals("settle-1", next.id)
            assertFalse(next.running)
            assertEquals(PhoneSettlementStage.Catalogued, next.stage)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun doneJobsAreNotResumed() {
        val dir = createTempDirectory("agent-mode-settlements-done").toFile()
        try {
            val store = PhoneSettlementStore(dir)
            store.upsert(
                PhoneSettlementJob(
                    id = "settle-done",
                    stage = PhoneSettlementStage.Done,
                    goal = "搜索护肤",
                    running = false,
                ),
            )
            assertEquals(null, store.nextResumable())
        } finally {
            dir.deleteRecursively()
        }
    }
}

class PhoneAppFlowMcpTest {
    @Test
    fun listsOnlyReadyDistilledTools() {
        val catalog = PhoneAppCatalog(
            packageName = "com.xingin.xhs",
            appName = "小红书",
            primitives = listOf(
                PhoneAppPrimitive(
                    kind = PhoneAppPrimitiveKind.Search,
                    toolName = "xhs_search",
                    title = "搜索小红书",
                    description = "Search Xiaohongshu",
                    sopId = "sop-1",
                    goal = "搜索护肤",
                    successCount = 3,
                    promoted = true,
                    acceptsQuery = true,
                ),
            ),
        )
        val tools = PhoneAppFlowMcp.listToolsResult(listOf(catalog)).getJSONArray("tools")
        assertEquals(3, tools.length())
        assertEquals(PhoneAppFlowMcp.RecallToolName, tools.getJSONObject(0).getString("name"))
        assertEquals(PhoneAppFlowMcp.RunToolName, tools.getJSONObject(1).getString("name"))
        assertEquals(PhoneAppFlowMcp.PipelineToolName, tools.getJSONObject(2).getString("name"))
    }

    @Test
    fun wrapCallResultStripsCompactInteractiveTree() {
        val raw = org.json.JSONObject()
            .put("ok", true)
            .put("receipt", "compact")
            .put("snapshot_id", "snap-1")
            .put("interactive", org.json.JSONArray().put(org.json.JSONObject().put("label", "搜索")))
            .put("click_targets_text", "搜索 tap")
            .put("ui_tree", "搜索 tap")
            .toString()
        val wrapped = PhoneAppFlowMcp.wrapCallResult(raw)
        val structured = wrapped.getJSONObject("structuredContent")
        assertFalse(structured.has("interactive"))
        assertFalse(structured.has("click_targets_text"))
        assertFalse(structured.has("ui_tree"))
        assertEquals("snap-1", structured.getString("snapshot_id"))
        assertFalse(wrapped.getJSONArray("content").getJSONObject(0).getString("text").contains("interactive"))
    }

    @Test
    fun pipelineMismatchUsesReplanNotExplore() {
        val parsed = org.json.JSONObject()
            .put("ok", false)
            .put("code", AgentModeSafety.MacroMismatchCode)
            .put("completed_steps", org.json.JSONArray().put(org.json.JSONObject().put("index", 0)))
            .put("failed_step", 1)
            .put("degraded_to", AgentModeSafety.ReplanDegradedTo)
            .put("consistent", "no")
        assertEquals("replan", parsed.getString("degraded_to"))
        assertEquals("no", parsed.getString("consistent"))
        assertEquals(1, parsed.getInt("failed_step"))
    }

    @Test
    fun sceneIdWithoutSopReturnsSceneNotSop() {
        val dir = createTempDirectory("gui-scene-not-sop").toFile()
        try {
            val store = GuiSceneStore(dir)
            store.upsert(
                GuiSceneCard(
                    id = "scene-340dd433",
                    appName = "小红书",
                    packageName = "com.xingin.xhs",
                    scene = "搜索",
                    tags = emptyList(),
                    guiLabels = listOf("搜索"),
                    method = emptyList(),
                    body = "",
                ),
            )
            assertEquals(null, PhoneAppFlowMcp.resolveRunSopId("scene-340dd433", store))
            val parsed = org.json.JSONObject(PhoneAppFlowMcp.sceneNotSopJson("scene-340dd433"))
            assertEquals(AgentModeSafety.SceneNotSopCode, parsed.getString("code"))
            assertEquals(AgentModeSafety.ReplanDegradedTo, parsed.getString("degraded_to"))
            assertEquals("no", parsed.getString("consistent"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sceneIdWithSopResolvesToRealSop() {
        val dir = createTempDirectory("gui-scene-mapped-sop").toFile()
        try {
            val store = GuiSceneStore(dir)
            store.upsert(
                GuiSceneCard(
                    id = "scene-mapped",
                    appName = "小红书",
                    packageName = "com.xingin.xhs",
                    scene = "搜索",
                    tags = emptyList(),
                    guiLabels = listOf("搜索"),
                    method = emptyList(),
                    body = "",
                    sopId = "sop-ready",
                ),
            )
            assertEquals("sop-ready", PhoneAppFlowMcp.resolveRunSopId("scene-mapped", store))
            assertEquals("sop-plain", PhoneAppFlowMcp.resolveRunSopId("sop-plain", store))
        } finally {
            dir.deleteRecursively()
        }
    }
}
