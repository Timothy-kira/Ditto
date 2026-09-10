package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModePortalHarnessTest {
    @Test
    fun parseNodesUsesOneBasedIAndZeroBasedIndex() {
        val array = JSONArray().put(
            JSONObject()
                .put("i", 1)
                .put("index", 0)
                .put("label", "搜索")
                .put("clickable", true)
                .put("left", 10)
                .put("top", 20)
                .put("right", 90)
                .put("bottom", 80),
        )
        val nodes = AgentModePortal.parseNodes(array)
        assertEquals(0, nodes.single().index)
        assertEquals(1, nodes.single().clickIndex)
        assertEquals("搜索", AgentModePortal.findByIndexQuery(nodes, "#1")?.label)
    }

    @Test
    fun attachReceiptAddsTreeDiffAndDoesNotForceReplan() {
        val previous = AgentModePortal.buildSnapshot(
            displayId = 1,
            width = 1080,
            height = 2400,
            packageName = "com.example.app",
            nodes = listOf(node(0, "搜索")),
        )
        val current = AgentModePortal.buildSnapshot(
            displayId = 1,
            width = 1080,
            height = 2400,
            packageName = "com.example.app",
            nodes = listOf(node(0, "搜索"), node(1, "帖子", top = 120, bottom = 200)),
        )
        val result = AgentModePortal.attachReceipt(
            result = JSONObject().put("ok", true),
            snapshot = current,
            previous = previous,
            usedBackend = "a11y",
        )
        assertEquals("yes", result.getString("consistent"))
        assertTrue(result.getJSONObject("tree_diff").getBoolean("changed"))
        assertEquals("a11y", result.getString("used_backend"))
        assertTrue(result.getString("planning").contains("CAPTURE_TIMEOUT"))
        assertTrue(result.getString("click_targets_text").contains(AgentModeUiTree.UntrustedGuiOpen))
        assertEquals(current.id, result.getString("snapshot_id"))
        assertEquals("full", result.getString("receipt"))
    }

    @Test
    fun compactReceiptFlagStripsTreeForTheModel() {
        val snapshot = AgentModePortal.buildSnapshot(
            displayId = 1,
            width = 1080,
            height = 2400,
            packageName = "com.example.app",
            nodes = listOf(node(0, "搜索")),
        )
        val host = AgentModePortal.attachReceipt(
            result = JSONObject().put("ok", true),
            snapshot = snapshot,
            previous = null,
            compact = true,
        )
        assertEquals("compact", host.getString("receipt"))
        assertTrue(host.has("interactive"))
        val model = AgentModePortal.stripBulkyFieldsForModel(JSONObject(host.toString()))
        assertFalse(model.has("interactive"))
        assertFalse(model.has("click_targets_text"))
        assertFalse(model.has("ui_tree"))
        assertEquals(snapshot.id, model.getString("snapshot_id"))
        assertTrue(model.has("tree_diff"))
        assertTrue(AgentModePortal.shouldCompactReceipt("swipe", skipCapture = true))
        assertFalse(AgentModePortal.shouldCompactReceipt("dump_tree", skipCapture = true))
        assertFalse(AgentModePortal.shouldCompactReceipt("screenshot", skipCapture = false))
    }

    @Test
    fun interruptCodesMarkReceiptForReplan() {
        val snapshot = AgentModePortal.buildSnapshot(
            displayId = 1,
            width = 1080,
            height = 2400,
            packageName = "com.example.app",
            nodes = listOf(node(0, "搜索")),
        )
        val result = AgentModePortal.attachReceipt(
            result = JSONObject().put("ok", false).put("code", AgentModeSafety.CaptureTimeoutCode),
            snapshot = snapshot,
            previous = null,
        )
        assertEquals("no", result.getString("consistent"))
        assertEquals(AgentModeSafety.ReplanDegradedTo, result.getString("degraded_to"))
        assertTrue(AgentModeSafety.CaptureTimeoutCode in AgentModeSafety.InterruptCodes)
        assertTrue(AgentModeDisplayGate.DisplayBusyCode in AgentModeSafety.InterruptCodes)
        assertTrue(AgentModeSafety.SceneNotSopCode in AgentModeSafety.InterruptCodes)
        val timeout = org.json.JSONObject(AgentModeSafety.captureTimeoutJson())
        assertEquals(AgentModeSafety.CaptureTimeoutCode, timeout.getString("code"))
        assertEquals(AgentModeSafety.ReplanDegradedTo, timeout.getString("degraded_to"))
    }

    @Test
    fun pipelineSlotsInterpolateLastPick() {
        assertEquals("护肤帖", GuiPipelineSlots.interpolate("{{last.pick}}", "护肤帖"))
        assertEquals("keep", GuiPipelineSlots.interpolate("keep", "护肤帖"))
    }

    @Test
    fun observeModesDistinguishJpegFromTree() {
        assertEquals("full", AgentModeCapture.observeMode(JSONObject().put("observe", true)))
        assertEquals("ax", AgentModeCapture.observeMode(JSONObject().put("observe", "ax")))
        assertEquals("delta", AgentModeCapture.observeMode(JSONObject().put("observe", "delta")))
        assertTrue(AgentModeCapture.wantsObserve(JSONObject().put("observe", "ax")))
        assertFalse(AgentModeCapture.wantsJpeg(JSONObject().put("observe", "ax")))
        assertTrue(AgentModeCapture.wantsJpeg(JSONObject().put("observe", true)))
        assertTrue(AgentModeCapture.wantsSom(JSONObject().put("observe", "som")))
        assertTrue(AgentModeCapture.wantsTextTree(JSONObject().put("observe", "text")))
        assertTrue(AgentModeCapture.wantsDeltaTree(JSONObject().put("observe", "delta")))
    }

    @Test
    fun blockedReceiptsCarryTypedFailureSignals() {
        val snapshot = AgentModePortal.buildSnapshot(
            displayId = 1,
            width = 1080,
            height = 2400,
            packageName = "com.example.app",
            nodes = listOf(node(0, "搜索")),
        )
        val result = AgentModePortal.attachReceipt(
            result = JSONObject()
                .put("ok", false)
                .put("code", AgentModeSafety.StaleSnapshotCode),
            snapshot = snapshot,
            previous = null,
        )
        val failure = result.getJSONObject("failure")
        assertEquals(AgentModeSafety.StaleSnapshotCode, failure.getString("code"))
        assertEquals("redump_snapshot", failure.getString("repair_strategy"))
        assertEquals("no", result.getString("consistent"))
    }

    @Test
    fun safetyBlocksPasswordManagersAndSensitiveLabels() {
        assertTrue(AgentModeSafety.shouldBlockLaunch("com.x8bit.bitwarden"))
        assertFalse(AgentModeSafety.shouldBlockLaunch("com.xingin.xhs"))
        assertTrue(AgentModeSafety.looksSensitiveLabel("立即支付"))
        assertTrue(AgentModeSafety.focusedPassword(listOf(node(0, "密码", password = true, focused = true))))
    }

    @Test
    fun skipRelaunchWhenForegroundAlreadyMatches() {
        assertTrue(AgentModePortal.shouldSkipRelaunch("com.xingin.xhs", "com.xingin.xhs"))
        assertTrue(AgentModePortal.shouldSkipRelaunch("com.xingin.xhs", "COM.XINGIN.XHS"))
        assertFalse(AgentModePortal.shouldSkipRelaunch("com.xingin.xhs", "com.ss.android.ugc.aweme"))
        assertFalse(AgentModePortal.shouldSkipRelaunch("com.xingin.xhs", ""))
        assertFalse(AgentModePortal.shouldSkipRelaunch("", "com.xingin.xhs"))
    }

    @Test
    fun snapshotDumpSpecSkipsWaitOnGesturesAndDumpOnWaitIdle() {
        val waitIdle = AgentModePortal.snapshotDumpSpec("wait_idle", skipCapture = false)
        assertFalse(waitIdle.dumpTree)
        assertTrue(waitIdle.waitStable)
        val swipe = AgentModePortal.snapshotDumpSpec("swipe", skipCapture = true)
        assertTrue(swipe.dumpTree)
        assertTrue(swipe.cheap)
        assertFalse(swipe.waitStable)
        assertEquals(AgentModeUiTree.CheapDumpQuery, swipe.query)
        assertEquals(AgentModeUiTree.DefaultTargetLimit, swipe.limit)
        val dumpTree = AgentModePortal.snapshotDumpSpec("dump_tree", skipCapture = true)
        assertFalse(dumpTree.cheap)
        assertFalse(dumpTree.waitStable)
        assertEquals(AgentModeUiTree.MaxDumpLimit, dumpTree.limit)
        assertEquals("id_0", AgentModePortal.clickQueryForNode("#1", node(0, "搜索")))
    }

    @Test
    fun flowReplayDumpsOnceOnlyWhenLocatorNeedsNodes() {
        assertTrue(PhoneAppFlowMcp.shouldDumpBeforeStep(emptyList(), needsLocator = true))
        assertFalse(PhoneAppFlowMcp.shouldDumpBeforeStep(listOf(node(0, "搜索")), needsLocator = true))
        assertFalse(PhoneAppFlowMcp.shouldDumpBeforeStep(emptyList(), needsLocator = false))
        val receipt = org.json.JSONObject().put(
            "interactive",
            org.json.JSONArray().put(
                org.json.JSONObject()
                    .put("i", 1)
                    .put("index", 0)
                    .put("label", "搜索")
                    .put("id", "search_bar")
                    .put("clickable", true),
            ),
        )
        assertEquals("搜索", AgentModePortal.nodesFromReceipt(receipt).single().label)
    }

    private fun node(
        index: Int,
        label: String,
        top: Int = 20,
        bottom: Int = 80,
        password: Boolean = false,
        focused: Boolean = false,
    ) = AgentModeUiTree.RichNode(
        index = index,
        label = label,
        text = label,
        desc = "",
        hint = "",
        klass = "TextView",
        id = "id_$index",
        clickable = true,
        longClickable = false,
        checkable = false,
        checked = false,
        selected = false,
        enabled = true,
        scrollable = false,
        focused = focused,
        editable = password,
        left = 10,
        top = top,
        right = 90,
        bottom = bottom,
        parent = -1,
        depth = 1,
        password = password,
        packageName = "com.example.app",
    )
}
