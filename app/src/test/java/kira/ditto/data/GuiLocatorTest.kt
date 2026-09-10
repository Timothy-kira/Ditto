package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiLocatorTest {
    @Test
    fun searchBoxMatchesAfterHotWordChanges() {
        val locator = GuiLocatorCodec.capture(
            label = "今天热搜：原神",
            resourceId = "search_bar",
            klass = "EditText",
            editable = true,
            focused = true,
            left = 80,
            top = 40,
            right = 900,
            bottom = 120,
        )
        assertEquals("search", locator.role)
        assertEquals("search_bar", locator.resourceId)
        assertFalse(locator.hint.contains("原神"))
        val compact = """
            今天热搜：星穹铁道 focus (80,40)-(900,120) #search_bar
            推荐 tap (40,200)-(900,400)
        """.trimIndent()
        val hit = GuiLocatorCodec.resolveFromCompact(compact, locator)
        assertNotNull(hit)
        assertEquals("search_bar", hit?.id)
        assertTrue(hit?.label.orEmpty().contains("星穹铁道"))
    }

    @Test
    fun likeStepDoesNotUseLikeCountAsIdentity() {
        val locator = GuiLocatorCodec.capture(
            label = "点赞",
            resourceId = "like_btn",
            left = 40,
            top = 880,
            right = 200,
            bottom = 980,
        )
        assertEquals("like", locator.role)
        assertFalse(locator.staticDesc.any { it.isDigit() })
        val compact = """
            12840 tap (40,880)-(200,980) #like_btn
            评论 tap (220,880)-(360,980)
        """.trimIndent()
        val hit = GuiLocatorCodec.resolveFromCompact(compact, locator)
        assertNotNull(hit)
        assertEquals("like_btn", hit?.id)
    }

    @Test
    fun themeColorIsNotPartOfLocator() {
        val locator = GuiLocatorCodec.capture(
            label = "搜索",
            resourceId = "search",
            klass = "EditText",
            left = 80,
            top = 40,
            right = 900,
            bottom = 120,
        )
        val json = locator.toJson()
        assertFalse(json.has("color"))
        assertFalse(json.toString().contains("night"))
        assertFalse(json.toString().contains("#FF"))
    }

    @Test
    fun abstractSceneDropsThisQuery() {
        assertEquals("搜索并点赞", GuiSceneAbstractor.abstractScene("打开哔哩哔哩搜原神并点赞"))
        assertEquals("搜索", GuiSceneAbstractor.abstractScene("打开小红书搜 everme"))
        assertEquals("选择田块", GuiSceneAbstractor.abstractScene("打开我的田块点3号田东块"))
        assertEquals("哔哩哔哩 / 搜索并点赞", GuiSceneAbstractor.sceneGoal("哔哩哔哩", "搜这个视频点赞"))
    }
}
