package kira.ditto.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3-2 deterministic pruning rules (viewport / wrapper / penetration / merge). */
class AgentModeUiTreePruneTest {
    private fun node(
        index: Int,
        klass: String = "TextView",
        label: String = "",
        clickable: Boolean = false,
        editable: Boolean = false,
        left: Int = 0,
        top: Int = 0,
        right: Int = 100,
        bottom: Int = 100,
        parent: Int = -1,
        depth: Int = 0,
        windowType: Int = 0,
        hint: String = "",
    ) = AgentModeUiTree.RichNode(
        index = index, label = label, text = label, desc = "", hint = hint,
        klass = klass, id = "", clickable = clickable, longClickable = false,
        checkable = false, checked = false, selected = false, enabled = true,
        scrollable = false, focused = false, editable = editable, left = left,
        top = top, right = right, bottom = bottom, parent = parent, depth = depth,
        drawingOrder = 0, password = false, packageName = "com.example.app",
        windowType = windowType,
    )

    @Test
    fun viewportDropsFullyOffscreenSubtree() {
        val offscreen = node(index = 0, label = "drawer", left = 1200, top = 0, right = 1400, bottom = 100)
        val onscreen = node(index = 1, label = "搜索", clickable = true)
        val pruned = AgentModeUiTree.pruneRich(listOf(offscreen, onscreen))
        assertEquals(1, pruned.size)
        assertEquals("搜索", pruned.single().label)
    }

    @Test
    fun overlaySurvivesViewportCrop() {
        val overlay = node(
            index = 0, label = "确认支付", clickable = true,
            left = 1200, top = 0, right = 1400, bottom = 100,
            windowType = 2038, // TYPE_APPLICATION_OVERLAY-ish
        )
        val pruned = AgentModeUiTree.pruneRich(listOf(overlay))
        assertEquals(1, pruned.size)
    }

    @Test
    fun wrapperLayoutCollapsesAndKeepsChildren() {
        val wrapper = node(index = 0, klass = "LinearLayout")
        val child = node(index = 1, label = "标题", parent = 0, depth = 1)
        val pruned = AgentModeUiTree.pruneRich(listOf(wrapper, child))
        assertEquals(1, pruned.size)
        assertEquals("标题", pruned.single().label)
    }

    @Test
    fun clickableParentAbsorbsPlainTextChild() {
        val card = node(index = 0, klass = "LinearLayout", label = "卡片", clickable = true)
        val crumb = node(index = 1, label = "副标题", parent = 0, depth = 1)
        val pruned = AgentModeUiTree.pruneRich(listOf(card, crumb))
        assertEquals(1, pruned.size)
        assertTrue(pruned.single().label.contains("副标题"))
        assertTrue(pruned.single().clickable)
    }

    @Test
    fun editTextAbsorbsAdjacentLabelAsHint() {
        val label = node(index = 0, label = "用户名", top = 10, bottom = 60)
        val input = node(index = 1, klass = "EditText", editable = true, top = 80, bottom = 140)
        val pruned = AgentModeUiTree.pruneRich(listOf(label, input))
        assertEquals(1, pruned.size)
        assertEquals("用户名", pruned.single().hint)
        assertTrue(pruned.single().editable)
    }

    @Test
    fun stableTopLeftOrderAndDenseReindex() {
        val bottom = node(index = 0, label = "底部", top = 500, bottom = 600)
        val top = node(index = 1, label = "顶部", top = 10, bottom = 60)
        val pruned = AgentModeUiTree.pruneRich(listOf(bottom, top))
        assertEquals("顶部", pruned[0].label)
        assertEquals(0, pruned[0].index)
        assertEquals(1, pruned[1].index)
    }

    @Test
    fun compactLineCarriesRoleAndBand() {
        val btn = node(index = 0, label = "搜索", clickable = true, top = 10, bottom = 60)
        val line = AgentModeUiTree.compactLine(btn)
        assertTrue(line.contains("[btn]"))
        assertTrue(line.contains("[top]"))
        val input = node(index = 1, klass = "EditText", editable = true, top = 400, bottom = 460)
        assertTrue(AgentModeUiTree.compactLine(input).contains("[input]"))
        assertTrue(AgentModeUiTree.compactLine(input).contains("[mid]"))
    }
}
