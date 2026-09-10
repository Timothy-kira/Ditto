package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachingTraceDistillerTest {
    @Test
    fun wrongPageBackThenFieldPickBecomesRepairPlusMethodAndInstanceSlot() {
        val distilled = TeachingTraceDistiller.distill(
            listOf(
                step(action = "tap", label = "返回", from = "wrong", to = "list", count = 4),
                step(action = "tap", label = "搜索", from = "list", to = "list", count = 4),
                step(
                    action = "tap",
                    label = "3号田东块",
                    from = "list",
                    to = "detail",
                    count = 12,
                    x = 410,
                    y = 620,
                ),
                step(action = "tap", label = "确定", from = "detail", to = "done", count = 5),
            ),
        )
        assertEquals(listOf("返回"), distilled.repair.map { it.guiLabel })
        assertEquals(
            listOf("method", "instance", "method"),
            distilled.replay.map { it.kind },
        )
        val pick = distilled.replay[1]
        assertEquals(TeachingTraceDistiller.PickSlot, pick.inputSlot)
        assertEquals("", pick.guiLabel)
        assertNull(pick.x)
        assertNull(pick.y)
        assertEquals("搜索", distilled.replay[0].guiLabel)
        assertEquals("确定", distilled.replay[2].guiLabel)
        assertEquals(TeachingTraceDistiller.ConfirmSlot, distilled.replay[2].inputSlot)
        assertTrue(distilled.replay[0].semantic.contains("搜索"))
        assertTrue(
            distilled.replay[0].locator.contains("搜索") ||
                distilled.replay[0].locatorSpec.role == "search",
        )
        assertTrue(distilled.everMeSummary.contains("纠正"))
        assertTrue(distilled.everMeSummary.contains("不要复用"))
        assertNull(distilled.replay[0].x)
    }

    @Test
    fun chromeOnABusyPageStaysAMethodNotAnInstance() {
        val kind = TeachingTraceDistiller.classify(
            action = "tap",
            label = "搜索",
            fromFingerprint = "home",
            toFingerprint = "home",
            previouslySeen = emptySet(),
            pageTargetCount = 30,
        )
        assertEquals(TeachingTraceDistiller.KindMethod, kind)
    }

    @Test
    fun returningToASeenPageCountsAsRepairEvenWithoutBackLabel() {
        val kind = TeachingTraceDistiller.classify(
            action = "tap",
            label = "空白处",
            fromFingerprint = "dialog",
            toFingerprint = "home",
            previouslySeen = setOf("home"),
            pageTargetCount = 8,
        )
        assertEquals(TeachingTraceDistiller.KindRepair, kind)
    }

    @Test
    fun kimiAgentSamePageDuplicateTapsAreDropped() {
        val distilled = TeachingTraceDistiller.distill(
            listOf(
                AgentModeSopStep(
                    action = "tap",
                    source = AgentModeSopProvenance.KimiAgent.storageValue,
                    guiLabel = "今天热搜：原神",
                    resourceId = "search_bar",
                    locatorSpec = GuiLocatorCodec.capture(
                        label = "今天热搜：原神",
                        resourceId = "search_bar",
                        klass = "EditText",
                        editable = true,
                        left = 80,
                        top = 40,
                        right = 900,
                        bottom = 120,
                    ),
                    uiFingerprint = "home",
                    toFingerprint = "home",
                    pageTargetCount = 20,
                ),
                AgentModeSopStep(
                    action = "tap",
                    source = AgentModeSopProvenance.KimiAgent.storageValue,
                    guiLabel = "今天热搜：原神",
                    resourceId = "search_bar",
                    locatorSpec = GuiLocatorCodec.capture(
                        label = "今天热搜：原神",
                        resourceId = "search_bar",
                        klass = "EditText",
                        editable = true,
                        left = 80,
                        top = 40,
                        right = 900,
                        bottom = 120,
                    ),
                    uiFingerprint = "home",
                    toFingerprint = "home",
                    pageTargetCount = 20,
                ),
                AgentModeSopStep(
                    action = "tap",
                    source = AgentModeSopProvenance.KimiAgent.storageValue,
                    guiLabel = "空白广告",
                    uiFingerprint = "home",
                    toFingerprint = "home",
                    pageTargetCount = 20,
                    x = 500,
                    y = 500,
                ),
            ),
        )
        assertEquals(1, distilled.replay.size)
        assertEquals("search", distilled.replay.single().locatorSpec.role)
        assertTrue(distilled.replay.single().locatorSpec.resourceId.contains("search"))
    }

    @Test
    fun likeCountIsNotALocator() {
        val distilled = TeachingTraceDistiller.distill(
            listOf(
                AgentModeSopStep(
                    action = "tap",
                    source = AgentModeSopProvenance.KimiAgent.storageValue,
                    guiLabel = "点赞",
                    resourceId = "like_btn",
                    locatorSpec = GuiLocatorCodec.capture(
                        label = "点赞",
                        resourceId = "like_btn",
                        left = 40,
                        top = 880,
                        right = 200,
                        bottom = 980,
                    ),
                    uiFingerprint = "video",
                    toFingerprint = "video",
                    pageTargetCount = 12,
                ),
            ),
        )
        val like = distilled.replay.single()
        assertEquals(TeachingTraceDistiller.KindMethod, like.kind)
        assertFalse(like.locator.any { it.isDigit() })
        assertFalse(like.semantic.contains("12840"))
        assertEquals("like", like.locatorSpec.role)
    }

    private fun step(
        action: String,
        label: String,
        from: String,
        to: String,
        count: Int,
        x: Int? = 100,
        y: Int? = 200,
    ): AgentModeSopStep = AgentModeSopStep(
        action = action,
        source = AgentModeSopProvenance.UserTeaching.storageValue,
        x = x,
        y = y,
        guiLabel = label,
        uiFingerprint = from,
        toFingerprint = to,
        pageTargetCount = count,
    )
}
