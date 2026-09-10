package kira.ditto.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerAsrTextTest {
    @Test
    fun emptyPrefixUsesPartial() {
        assertEquals("你好", joinComposerAsrText("", "你好"))
        assertEquals("你好", joinComposerAsrText("", "  你好  "))
    }

    @Test
    fun appendsAfterChineseDraftWithASpace() {
        assertEquals("已有草稿 下一句", joinComposerAsrText("已有草稿", "下一句"))
    }

    @Test
    fun keepsExistingTrailingSpace() {
        assertEquals("hello world", joinComposerAsrText("hello ", "world"))
    }

    @Test
    fun emptyPartialRestoresPrefix() {
        assertEquals("原稿", joinComposerAsrText("原稿", ""))
        assertEquals("原稿", joinComposerAsrText("原稿", "   "))
        assertEquals("", joinComposerAsrText("", ""))
    }

    @Test
    fun mergesOverlappingPartialInsteadOfDuplicating() {
        assertEquals("请帮我打开微信", joinComposerAsrText("请帮我打开", "打开微信"))
        assertEquals("去西湖", joinComposerAsrText("去西湖", "去西湖"))
    }
}
