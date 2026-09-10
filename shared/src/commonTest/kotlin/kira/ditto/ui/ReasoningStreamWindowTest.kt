package kira.ditto.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReasoningStreamWindowTest {
    @Test
    fun rollingWindowKeepsNewestCompleteLines() {
        val text = (1..12).joinToString("\n") { "line-$it" }
        val window = rollingLineWindow(text, maxChars = 40)
        assertTrue(window.startsWith("line-"))
        assertTrue(window.endsWith("line-12"))
        assertTrue('\n' in window)
        assertTrue("line-1\n" !in window || !window.startsWith("line-1\n"))
    }

    @Test
    fun rollingWindowNeverReturnsBlankWhenSourceHasText() {
        val text = "only-one-very-long-line-without-breaks-" + "x".repeat(200)
        val window = rollingLineWindow(text, maxChars = 48)
        assertTrue(window.isNotBlank())
        assertTrue(window.endsWith("x"))
        assertEquals(48, window.length)
    }

    @Test
    fun overlapKeepsSharedTailWhenOldestLineDrops() {
        val old = "alpha\nbeta\ngamma"
        val new = "beta\ngamma\ndelta"
        assertEquals("beta\ngamma".length, longestSuffixPrefixOverlap(old, new))
    }

    @Test
    fun overlapIsZeroWhenTextIsUnrelated() {
        assertEquals(0, longestSuffixPrefixOverlap("thinking about A", "completely different"))
    }
}

private fun rollingLineWindow(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val slice = text.takeLast(maxChars)
    val newline = slice.indexOf('\n')
    val window = if (newline >= 0) slice.substring(newline + 1).ifBlank { slice } else slice
    return window.ifBlank { text.takeLast(maxChars) }
}

private fun longestSuffixPrefixOverlap(left: String, right: String): Int {
    val max = minOf(left.length, right.length)
    for (len in max downTo 0) {
        if (left.endsWith(right.take(len))) return len
    }
    return 0
}
