package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeUiTreeTest {
    @Test
    fun compactXmlKeepsTapTargetsAndNormalizedBounds() {
        val xml = """
            <hierarchy rotation="0">
              <node text="" class="android.widget.FrameLayout" bounds="[0,0][1000,2000]" clickable="false">
                <node text="搜索" content-desc="" class="android.widget.TextView" resource-id="com.xingin.xhs:id/search_bar" clickable="true" bounds="[100,200][900,360]" />
                <node text="" content-desc="返回" class="android.widget.ImageView" clickable="true" bounds="[0,80][120,200]" />
                <node text="" class="android.view.View" clickable="false" bounds="[0,0][10,10]" />
              </node>
            </hierarchy>
        """.trimIndent()
        val compact = AgentModeUiTree.compactFromUiautomatorXml(xml, width = 1000, height = 2000)
        assertTrue(compact.contains("搜索 tap (100,100)-(900,180)"))
        assertTrue(compact.contains("#search_bar"))
        assertTrue(compact.contains("返回 tap (0,40)-(120,100)"))
        assertFalse(compact.contains("FrameLayout"))
        assertFalse(AgentModeUiTree.isSparse(compact))
    }

    @Test
    fun compactKeepsClickableImageViewAndListTargetsCanSimplifyClutter() {
        val xml = """
            <hierarchy rotation="0">
              <node text="搜索" class="android.widget.TextView" clickable="true" bounds="[100,200][900,360]" />
              <node text="" content-desc="返回" class="android.widget.ImageView" clickable="true" bounds="[0,80][120,200]" />
              <node text="" class="android.widget.ImageView" clickable="true" bounds="[0,800][1000,1600]" />
              <node text="" class="android.widget.FrameLayout" clickable="true" bounds="[40,400][960,560]">
                <node text="帖子标题" class="android.widget.TextView" clickable="false" bounds="[50,420][900,540]" />
              </node>
            </hierarchy>
        """.trimIndent()
        val compact = AgentModeUiTree.compactFromUiautomatorXml(xml, width = 1000, height = 2000)
        assertTrue(compact.contains("搜索 tap"))
        assertTrue(compact.contains("返回 tap"))
        assertTrue(compact.contains("ImageView tap"))
        val all = AgentModeUiTree.clickTargetsFromCompact(compact)
        assertTrue(all.any { it.label == "搜索" })
        assertTrue(all.any { it.label == "返回" })
        assertTrue(all.any { it.label == "ImageView" })
        assertTrue(all.any { it.label == "帖子标题" })
        val simplified = AgentModeUiTree.clickTargetsFromCompact(compact, simplify = true)
        assertTrue(simplified.any { it.label == "搜索" })
        assertTrue(simplified.any { it.label == "返回" })
        assertTrue(simplified.any { it.label == "帖子标题" })
        assertFalse(simplified.any { it.label == "ImageView" })
        assertFalse(AgentModeUiTree.isCluttered(3))
        assertTrue(AgentModeUiTree.isCluttered(24))
    }

    @Test
    fun dumpTreePagesDenseNodesByRegionAndQuery() {
        val nodes = (0 until 30).map { index ->
            AgentModeUiTree.RichNode(
                index = index,
                label = if (index == 12) "3号田东块" else "田$index",
                text = if (index == 12) "3号田东块" else "田$index",
                desc = "",
                hint = "",
                klass = "TextView",
                id = "item_$index",
                clickable = true,
                longClickable = false,
                checkable = false,
                checked = false,
                selected = false,
                enabled = true,
                scrollable = false,
                focused = false,
                editable = false,
                left = 80,
                top = 40 + index * 30,
                right = 900,
                bottom = 70 + index * 30,
                parent = 0,
                depth = 2,
            )
        }
        val page = AgentModeUiTree.pageRichNodes(nodes, query = "3号田", region = "", offset = 0, limit = 5)
        assertEquals(1, page.total)
        assertEquals("3号田东块", page.nodes.single().label)
        val bottom = AgentModeUiTree.pageRichNodes(nodes, region = "bottom", offset = 0, limit = 10)
        assertTrue(bottom.nodes.all { it.centerY > 666 })
        assertTrue(nodes[12].matches("item_12"))
        val swipe = AgentModeUiTree.scrollSwipe(
            nodes + AgentModeUiTree.RichNode(
                index = 99,
                label = "列表",
                text = "列表",
                desc = "",
                hint = "",
                klass = "RecyclerView",
                id = "list",
                clickable = false,
                longClickable = false,
                checkable = false,
                checked = false,
                selected = false,
                enabled = true,
                scrollable = true,
                focused = false,
                editable = false,
                left = 0,
                top = 200,
                right = 1000,
                bottom = 900,
                parent = 0,
                depth = 1,
            ),
        )
        assertEquals(500, swipe.first.first)
        assertTrue(swipe.first.second > swipe.second.second)
    }

    @Test
    fun blankXmlIsSparse() {
        assertEquals("", AgentModeUiTree.compactFromUiautomatorXml("not xml", 1080, 2400))
        assertTrue(AgentModeUiTree.isSparse(""))
    }

    @Test
    fun findsRelocatedGuiLabelFromCompactTree() {
        val compact = "搜索 tap (420,80)-(900,180)\n帖子 tap (40,260)-(940,700)"
        val target = AgentModeUiTree.findTarget(compact, "搜索")
        requireNotNull(target)
        assertEquals(660, target.centerX)
        assertEquals(130, target.centerY)
    }

    @Test
    fun layoutFingerprintIgnoresTextAndVideoFrames() {
        val chrome = listOf(
            AgentModeUiTree.LayoutNode("FrameLayout", "root", 0, 0, 1080, 2400, false, 2),
            AgentModeUiTree.LayoutNode("TextView", "clock", 20, 80, 200, 140, false, 0),
        )
        val first = AgentModeUiTree.layoutFingerprint(chrome, windowKey = 1)
        val withVideo = chrome + AgentModeUiTree.LayoutNode("SurfaceView", "player", 0, 200, 1080, 800, false, 0)
        val withGif = chrome + AgentModeUiTree.LayoutNode("ImageView", "banner", 0, 200, 1080, 800, false, 0, "gif")
        assertEquals(first, AgentModeUiTree.layoutFingerprint(withVideo, windowKey = 1))
        assertEquals(first, AgentModeUiTree.layoutFingerprint(withGif, windowKey = 1))
        val layoutShifted = chrome + AgentModeUiTree.LayoutNode("Button", "send", 40, 2000, 1040, 2200, true, 0)
        assertTrue(first != AgentModeUiTree.layoutFingerprint(layoutShifted, windowKey = 1))
    }

    @Test
    fun clickIndexIsOneBasedAndMatchesHashQuery() {
        val node = sampleNode(index = 3, label = "搜索")
        assertEquals(4, node.clickIndex)
        assertTrue(node.matches("#4"))
        assertTrue(node.matches("#3"))
        assertEquals("#4 [btn] 搜索 tap (10,20)-(90,80) #id_3 [top]", AgentModeUiTree.compactLine(node))
        assertEquals(4, AgentModeUiTree.parseIndexQuery("#4"))
    }

    @Test
    fun treeDiffReportsAddedRemovedAndText() {
        val before = listOf(sampleNode(index = 0, label = "搜索", text = "搜索"))
        val after = listOf(
            sampleNode(index = 0, label = "搜索", text = "护肤"),
            sampleNode(index = 1, label = "帖子", text = "帖子", left = 10, top = 100, right = 90, bottom = 180),
        )
        val diff = AgentModeUiTree.treeDiff(before, after)
        assertTrue(diff.changed)
        assertTrue(diff.textChanged.contains("搜索"))
        assertTrue(diff.added.contains("帖子"))
        val json = diff.toJson()
        assertTrue(json.getBoolean("changed"))
        assertTrue(json.getBoolean("fingerprint_changed"))
    }

    @Test
    fun treeDiffIgnoresMediaAndScopesToTarget() {
        val search = sampleNode(index = 0, label = "搜索", text = "搜索", id = "search_bar")
        val before = listOf(
            search,
            sampleNode(index = 1, label = "封面", text = "", klass = "ImageView", id = "cover", left = 0, top = 200, right = 1000, bottom = 700),
            sampleNode(index = 2, label = "播放器", text = "", klass = "SurfaceView", id = "player", left = 0, top = 200, right = 1000, bottom = 700),
        )
        val after = listOf(
            search,
            sampleNode(index = 1, label = "封面", text = "", klass = "ImageView", id = "cover", left = 10, top = 210, right = 990, bottom = 710),
            sampleNode(index = 2, label = "播放器", text = "", klass = "SurfaceView", id = "player", left = 0, top = 180, right = 1000, bottom = 720),
        )
        val diff = AgentModeUiTree.treeDiff(before, after, target = search)
        assertFalse(diff.changed)
        assertFalse(diff.fingerprintChanged)
        assertTrue(diff.targetPresent)
        assertFalse(diff.targetChanged)
        assertFalse(diff.blockingOverlay)
        assertEquals("yes", AgentModePortal.attachReceipt(
            result = org.json.JSONObject().put("ok", true),
            snapshot = AgentModePortal.buildSnapshot(1, 1080, 2400, "com.example.app", after),
            previous = AgentModePortal.buildSnapshot(1, 1080, 2400, "com.example.app", before),
            target = search,
        ).getString("consistent"))
    }

    @Test
    fun treeDiffFlagsDialogCoveringTarget() {
        val search = sampleNode(index = 0, label = "搜索", text = "搜索", id = "search_bar", left = 100, top = 40, right = 900, bottom = 120)
        val dialog = sampleNode(
            index = 3,
            label = "关闭广告",
            text = "关闭广告",
            klass = "Dialog",
            id = "ad_close",
            left = 0,
            top = 0,
            right = 1000,
            bottom = 1000,
            windowIndex = 1,
        )
        val before = listOf(search)
        val after = listOf(search, dialog)
        val diff = AgentModeUiTree.treeDiff(before, after, target = search)
        assertTrue(diff.changed)
        assertTrue(diff.blockingOverlay)
        assertEquals("关闭广告", diff.blockingLabel)
        assertEquals(4, diff.blockingIndex)
        val receipt = AgentModePortal.attachReceipt(
            result = org.json.JSONObject().put("ok", true),
            snapshot = AgentModePortal.buildSnapshot(1, 1080, 2400, "com.example.app", after),
            previous = AgentModePortal.buildSnapshot(1, 1080, 2400, "com.example.app", before),
            target = search,
        )
        assertEquals("no", receipt.getString("consistent"))
        assertTrue(receipt.getJSONObject("tree_diff").getBoolean("blocking_overlay"))
    }

    @Test
    fun wrapUntrustedGuiContentIsIdempotentEnoughForReceipts() {
        val wrapped = AgentModeUiTree.wrapUntrustedGuiContent("搜索 tap (1,1)-(2,2)")
        assertTrue(wrapped.startsWith(AgentModeUiTree.UntrustedGuiOpen))
        assertTrue(wrapped.endsWith(AgentModeUiTree.UntrustedGuiClose))
    }

    @Test
    fun deltaLinesReturnOnlyChangedNodes() {
        val before = listOf(
            sampleNode(index = 0, label = "搜索", text = "搜索"),
            sampleNode(index = 1, label = "帖子", text = "帖子", top = 100, bottom = 180),
        )
        val after = listOf(
            sampleNode(index = 0, label = "搜索", text = "护肤"),
            sampleNode(index = 2, label = "下一页", text = "下一页", top = 220, bottom = 280),
        )
        val lines = AgentModeUiTree.deltaLines(before, after)
        assertTrue(lines.any { it.startsWith("~ ") && it.contains("搜索") })
        assertTrue(lines.any { it.startsWith("- ") && it.contains("帖子") })
        assertTrue(lines.any { it.startsWith("+ ") && it.contains("下一页") })
    }

    @Test
    fun directionalSwipeMovesFingerLeftAndRight() {
        val list = AgentModeUiTree.RichNode(
            index = 0,
            label = "列表",
            text = "列表",
            desc = "",
            hint = "",
            klass = "ViewPager",
            id = "pager",
            clickable = false,
            longClickable = false,
            checkable = false,
            checked = false,
            selected = false,
            enabled = true,
            scrollable = true,
            focused = false,
            editable = false,
            left = 40,
            top = 200,
            right = 960,
            bottom = 800,
            parent = -1,
            depth = 0,
        )
        val left = AgentModeUiTree.directionalSwipe(listOf(list), "swipe_left")
        assertEquals("left", AgentModeUiTree.parseSwipeDirection("left_swipe"))
        assertTrue(left.first.first > left.second.first)
        assertEquals(left.first.second, left.second.second)
        val right = AgentModeUiTree.directionalSwipe(listOf(list), "right")
        assertTrue(right.second.first > right.first.first)
        assertEquals(right.first.second, right.second.second)
        val up = AgentModeUiTree.directionalSwipe(listOf(list), "up")
        assertEquals(up.first.first, up.second.first)
        assertTrue(up.first.second > up.second.second)
    }

    @Test
    fun findSearchFieldPrefersHintOverUnrelatedFocusedChat() {
        val chat = sampleNode(index = 0, label = "说点什么", klass = "EditText").copy(
            focused = true,
            editable = true,
            clickable = true,
            top = 800,
            bottom = 900,
        )
        val search = sampleNode(index = 1, label = "", klass = "EditText", id = "search_bar").copy(
            editable = true,
            clickable = true,
            hint = "搜索",
            top = 40,
            bottom = 120,
        )
        val found = AgentModeUiTree.findSearchField(listOf(chat, search))
        requireNotNull(found)
        assertEquals("search_bar", found.id)
        val focusedSearch = search.copy(focused = true, hint = "查找")
        assertEquals(
            "search_bar",
            AgentModeUiTree.findSearchField(listOf(chat, focusedSearch))?.id,
        )
    }

    private fun sampleNode(
        index: Int,
        label: String,
        text: String = label,
        left: Int = 10,
        top: Int = 20,
        right: Int = 90,
        bottom: Int = 80,
        klass: String = "TextView",
        id: String = "id_$index",
        windowIndex: Int = 0,
    ) = AgentModeUiTree.RichNode(
        index = index,
        label = label,
        text = text,
        desc = "",
        hint = "",
        klass = klass,
        id = id,
        clickable = true,
        longClickable = false,
        checkable = false,
        checked = false,
        selected = false,
        enabled = true,
        scrollable = false,
        focused = false,
        editable = false,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        parent = -1,
        depth = 1,
        windowIndex = windowIndex,
    )
}
