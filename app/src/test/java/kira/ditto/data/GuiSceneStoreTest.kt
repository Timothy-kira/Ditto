package kira.ditto.data

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiSceneStoreTest {
    @Test
    fun teachingCardHasGuiTagsSceneAndApp() {
        val distilled = TeachingTraceDistiller.distill(
            listOf(
                AgentModeSopStep(
                    action = "tap",
                    source = AgentModeSopProvenance.UserTeaching.storageValue,
                    guiLabel = "搜索",
                    uiFingerprint = "home",
                    toFingerprint = "search",
                    pageTargetCount = 4,
                ),
                AgentModeSopStep(
                    action = "tap",
                    source = AgentModeSopProvenance.UserTeaching.storageValue,
                    guiLabel = "确定",
                    uiFingerprint = "search",
                    toFingerprint = "done",
                    pageTargetCount = 5,
                ),
            ),
        )
        val card = GuiSceneComposer.fromTeaching(
            goal = "打开小红书搜 everme",
            appName = "小红书",
            packageName = "com.xingin.xhs",
            distilled = distilled,
            sopId = "sop-1",
        )
        assertTrue(card.tags.contains("#gui"))
        assertTrue(card.tags.any { it.startsWith("#app:") })
        assertTrue(card.tags.any { it.startsWith("#scene:") })
        assertEquals("小红书", card.appName)
        assertEquals("小红书 / 搜索", card.scene)
        val fact = card.toEverMeFact()
        assertTrue(fact.contains("#gui"))
        assertTrue(fact.contains("App: 小红书"))
        assertTrue(fact.contains("Scene: 小红书 / 搜索"))
        assertTrue(fact.contains("搜索"))
        assertFalse(fact.contains("everme"))
    }

    @Test
    fun sameAppAndSceneUpdatesInsteadOfDuplicating() {
        val root = Files.createTempDirectory("aether-gui-scenes").toFile()
        val store = GuiSceneStore(root)
        val first = store.upsert(
            GuiSceneCard(
                id = "",
                appName = "小红书",
                packageName = "com.xingin.xhs",
                scene = "搜索笔记",
                tags = listOf("#gui", "#app:小红书", "#scene:搜索笔记"),
                guiLabels = listOf("搜索"),
                method = listOf("点击「搜索」"),
                body = "方法：搜索",
            ),
        )
        val second = store.upsert(
            GuiSceneCard(
                id = "",
                appName = "小红书",
                packageName = "com.xingin.xhs",
                scene = "搜索笔记",
                tags = listOf("#gui", "#app:小红书", "#scene:搜索笔记", "#确定"),
                guiLabels = listOf("搜索", "确定"),
                method = listOf("点击「搜索」", "点击确认控件「确定」"),
                body = "方法：搜索 → 确定",
            ),
        )
        assertEquals(first.id, second.id)
        assertEquals(2, second.version)
        assertEquals(1, store.loadAll().size)
        val hits = store.recall("小红书 搜索笔记")
        assertEquals(1, hits.size)
        assertEquals(listOf("搜索", "确定"), hits.single().guiLabels)
        val hintWithSop = GuiSceneCard(
            id = "scene-abc",
            appName = "小红书",
            packageName = "com.xingin.xhs",
            scene = "搜索",
            tags = emptyList(),
            guiLabels = listOf("搜索"),
            method = emptyList(),
            body = "",
            sopId = "sop-ready",
        ).toLeadHint()
        assertTrue(hintWithSop.contains("sop_id=sop-ready"))
        assertFalse(hintWithSop.contains("scene-abc"))
        val emptySop = GuiSceneCard(
            id = "scene-xyz",
            appName = "小红书",
            packageName = "com.xingin.xhs",
            scene = "搜索",
            tags = emptyList(),
            guiLabels = listOf("搜索"),
            method = emptyList(),
            body = "",
        ).toLeadHint()
        assertFalse(emptySop.contains("sop_id="))
        assertFalse(emptySop.contains("scene-xyz"))
    }
}
