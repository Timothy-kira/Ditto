package kira.ditto.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestMarkupTest {
    @Test
    fun readsChipsOffOneLineInTheOrderWritten() {
        // The shape the evermind answer should have ended in: it asked "你对哪个方向感兴趣？" and only
        // one of the follow-ups has a URL, so the rest cannot be browser-act capsules.
        val answer = """
            他们正在做 EverOS。你对哪个方向感兴趣？

            [[suggest:看北京的岗位]] [[suggest:只要校招的]] [[suggest:帮我投递前端]]
        """.trimIndent()
        val chips = suggestMarkersIn(answer)
        assertEquals(listOf("看北京的岗位", "只要校招的", "帮我投递前端"), chips.map { it.label })
        // With no pipe, the chip sends exactly what it says.
        assertEquals("看北京的岗位", chips.first().text)
    }

    @Test
    fun aShortChipCanSendALongerRequest() {
        val chips = suggestMarkersIn("[[suggest:只要校招的|只列出开放校招的岗位和它们的投递链接]]")
        assertEquals("只要校招的", chips.single().label)
        assertEquals("只列出开放校招的岗位和它们的投递链接", chips.single().text)
    }

    @Test
    fun capsAndDeduplicates() {
        val chips = suggestMarkersIn(
            "[[suggest:一]] [[suggest:二]] [[suggest:三]] [[suggest:四]] [[suggest:五]]",
        )
        assertEquals(MaxSuggestChips, chips.size)

        // Two labels, one sentence: one choice as far as the user is concerned.
        val duped = suggestMarkersIn("[[suggest:看北京|只看北京]] [[suggest:北京的|只看北京]]")
        assertEquals(1, duped.size)
        assertEquals("看北京", duped.single().label)
    }

    @Test
    fun refusesTheShapesThatBelongToBrowserAct() {
        // A URL is a page to open — that is a browser-act capsule, not a message to send.
        assertNull(parseSuggestMarker("[[suggest:https://evermind.ai/zh/careers]]"))
        assertNull(parseSuggestMarker("[[suggest:]]"))
        assertNull(parseSuggestMarker("看北京的岗位"))
        assertNull(parseSuggestMarker("[[browser-act:EverMind 招聘页面|https://evermind.ai/zh/careers]]"))
    }

    @Test
    fun labelIsTrimmedToStayReadableInOneRow() {
        val long = "帮我把北京上海硅谷三地的所有算法岗位按投递截止时间排序并列出链接"
        val chip = parseSuggestMarker("[[suggest:$long]]")
        checkNotNull(chip)
        assertEquals(MaxSuggestLabelChars, chip.label.length)
        assertTrue(long.startsWith(chip.label))
    }

    @Test
    fun theAnswerBodyKeepsItsTextAndLosesTheMarkers() {
        val answer = """
            他们正在做 EverOS——AI 长期记忆操作系统。

            [[suggest:看北京的岗位]] [[suggest:只要校招的]]
        """.trimIndent()
        assertEquals("他们正在做 EverOS——AI 长期记忆操作系统。", stripSuggestMarkers(answer))
        // Text sharing a line with a chip survives; only the marker goes.
        assertEquals("还想看哪些？", stripSuggestMarkers("还想看哪些？[[suggest:看北京的岗位]]"))
        // Nothing to do is nothing done.
        val plain = "就这些了。"
        assertEquals(plain, stripSuggestMarkers(plain))
    }
}
