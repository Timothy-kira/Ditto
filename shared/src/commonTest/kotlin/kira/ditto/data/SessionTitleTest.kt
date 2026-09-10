package kira.ditto.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionTitleTest {
    @Test
    fun sanitizesMarkdownWithoutTruncating() {
        assertEquals("华为健康匹配", "**华为健康匹配**".sanitizeGeneratedSessionTitle())
        assertEquals("附近商家查询结果总结过长", "# 附近商家查询结果总结过长".sanitizeGeneratedSessionTitle())
        assertEquals("天气卡片", "[天气卡片](https://example.com)".sanitizeGeneratedSessionTitle())
        assertEquals("待办事项", "- 待办事项".sanitizeGeneratedSessionTitle())
        assertEquals("计划", "<b>计划</b>".sanitizeGeneratedSessionTitle())
        assertEquals("截图说明", "![截图说明](https://example.com/a.png)".sanitizeGeneratedSessionTitle())
        assertEquals("代码标题", "`代码标题`".sanitizeGeneratedSessionTitle())
        val longTitle = "这是一段明显超过十二个汉字的标题内容"
        assertEquals(longTitle, longTitle.sanitizeGeneratedSessionTitle())
        assertFalse(longTitle.sanitizeGeneratedSessionTitle().sessionTitleFits())
        assertTrue("华为健康步数".sessionTitleFits())
    }

    @Test
    fun visibleUserMessageTextStripsEverMeInjections() {
        val injected = "什么是 yolo 模式<system-reminder>\n" +
            "<plugin_session_start plugin=\"everme\" skill=\"memory-recall\">\n" +
            "# EverMe Memory Recall\nThis session is backed by EverMe.\n" +
            "</plugin_session_start>\n</system-reminder>"
        assertEquals("什么是 yolo 模式", injected.visibleUserMessageText())
        assertEquals("hello", "hello".visibleUserMessageText())
        assertTrue("EverMe Memory Recall".looksLikeHiddenPromptTitle())
        assertFalse("附近奶茶".looksLikeHiddenPromptTitle())
        assertTrue("Agent Mode is on. You are t..".looksLikeHiddenPromptTitle())
        val withLead = "Agent Mode is on. You are the desk lead. Use Kimi Code CLI's native Agent.\n\n打开微信"
        assertEquals("打开微信", withLead.visibleUserMessageText())
        assertFalse("打开微信".looksLikeHiddenPromptTitle())
        assertTrue("Swarm Mode You are now i...".looksLikeHiddenPromptTitle())
        assertTrue("## Swarm Mode\n\nYou are now in \"agent swarm\" mode.".looksLikeHiddenPromptTitle())
        assertTrue("## Tower Mode\n\nYou are now in tower mode.".looksLikeHiddenPromptTitle())
        assertTrue("This is a host goal-intake prompt.".looksLikeHiddenPromptTitle())
        assertFalse("整理这些文件".looksLikeHiddenPromptTitle())
        assertTrue(
            "Web, search, and fetch use Aether's built-in Gecko browser.".looksLikeHiddenPromptTitle(),
        )
        assertTrue(
            "WebSearch and FetchURL are the browser group: the host runs them as Agent.".looksLikeHiddenPromptTitle(),
        )
    }

    @Test
    fun environmentDateLineNeverReachesTheTitle() {
        val dated = "$EnvironmentLeadMarker 今天是 2026-09-08（星期二）。凡是\"最新/近期/今年\"之类的问题都以这个日期为准；" +
            "不要凭记忆假设年份，也不要把年份或\"最新\"塞进搜索词——直接搜用户说的主题。\n\n搜下 最新科技新闻"
        assertEquals("搜下 最新科技新闻", dated.visibleUserMessageText())

        // A replay that collapses the blank line must still leave the question standing.
        val collapsed = "$EnvironmentLeadMarker 今天是 2026-09-08（星期二）。\n搜下 最新科技新闻"
        assertEquals("搜下 最新科技新闻", collapsed.visibleUserMessageText())

        // The date line sits ahead of the desk lead, so both have to come off together.
        val withLead = "$EnvironmentLeadMarker 今天是 2026-09-08（星期二）。\n\n" +
            "Agent Mode is on. You are the desk lead. Use Kimi Code CLI's native Agent.\n\n打开微信"
        assertEquals("打开微信", withLead.visibleUserMessageText())

        // A question that merely mentions the date is not an environment line.
        assertEquals("今天是几号", "今天是几号".visibleUserMessageText())
    }

    @Test
    fun picksFastestSupportedReasoningEffort() {
        assertEquals("off", fastestSupportedReasoningEffort(listOf("high", "off", "low")))
        assertEquals("minimal", fastestSupportedReasoningEffort(listOf("high", "minimal")))
        assertEquals("low", fastestSupportedReasoningEffort(listOf("max", "low")))
        assertEquals("minimal", fastestSupportedReasoningEffort(emptyList()))
    }
}
