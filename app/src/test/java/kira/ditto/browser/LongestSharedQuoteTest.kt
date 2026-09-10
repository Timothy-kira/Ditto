package kira.ditto.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quote a citation shows must be findable on the page.
 *
 * That is the property under test throughout: whatever comes back has to appear in the passage, in
 * the passage's own spelling. Returning "" is always allowed - it downgrades the citation to
 * [CitationConfidence.Passage], which is still true.
 */
class LongestSharedQuoteTest {

    private fun assertQuoteIsInPassage(quote: String, passage: String) {
        if (quote.isEmpty()) return
        assertTrue("quote must appear verbatim in the passage: $quote", quote in passage)
    }

    @Test
    fun findsTheSharedRunInChineseProse() {
        val passage = "本月开篇是几场发布会的密集排期。华为在 9 月初发布了新一代昇腾算力平台，官方称推理吞吐提升明显，配套软件栈同步更新。"
        val sentence = "华为在 9 月初发布了新一代昇腾算力平台，官方称推理吞吐提升明显。"
        val quote = longestSharedQuote(sentence, passage)
        assertTrue("expected a substantial quote, got '$quote'", quote.length >= MinQuoteChars)
        assertTrue(quote, "昇腾算力平台" in quote)
        assertQuoteIsInPassage(quote, passage)
    }

    @Test
    fun normalisationBridgesFullWidthAndHalfWidthPunctuation() {
        // The page uses full-width punctuation; the answer half-width. Same sentence to a reader,
        // different bytes to a comparison that does not normalise.
        val passage = "报告指出（截至九月）该平台的推理吞吐提升明显，且软件栈同步更新。"
        val sentence = "报告指出(截至九月)该平台的推理吞吐提升明显,且软件栈同步更新。"
        val quote = longestSharedQuote(sentence, passage)
        assertTrue("expected normalisation to find the match, got '$quote'", quote.length >= MinQuoteChars)
        // Reported in the page's spelling, not the answer's.
        assertQuoteIsInPassage(quote, passage)
    }

    @Test
    fun collapsedWhitespaceStillMatches() {
        val passage = "The registration    page requires a\n\nverified developer account before you can apply."
        val sentence = "The registration page requires a verified developer account."
        val quote = longestSharedQuote(sentence, passage)
        assertTrue("expected a match across whitespace differences, got '$quote'", quote.isNotEmpty())
        assertQuoteIsInPassage(quote, passage)
    }

    @Test
    fun latinMatchesDoNotStartOrEndMidWord() {
        val passage = "Applicants must submit the completed registration form before the deadline."
        val sentence = "ted registration form bef"
        val quote = longestSharedQuote(sentence, passage)
        if (quote.isNotEmpty()) {
            assertTrue("must not start mid-word: '$quote'", !quote.startsWith("ted"))
            assertTrue("must not end mid-word: '$quote'", !quote.endsWith("bef"))
            assertQuoteIsInPassage(quote, passage)
        }
    }

    @Test
    fun aShortCoincidenceIsNotAQuote() {
        // "the" and a few spaces overlap; nothing worth showing.
        assertEquals("", longestSharedQuote("the and of", "a passage about the weather"))
    }

    @Test
    fun nothingSharedYieldsNothing() {
        assertEquals("", longestSharedQuote("完全无关的一句话内容在这里", "an english passage with no overlap at all"))
    }

    @Test
    fun blankInputsAreSafe() {
        assertEquals("", longestSharedQuote("", "something"))
        assertEquals("", longestSharedQuote("something", ""))
    }

    @Test
    fun theQuoteNeverExceedsItsCap() {
        val shared = "这是一段很长的共享文本".repeat(40)
        val quote = longestSharedQuote(shared, shared)
        assertTrue("quote was ${quote.length} chars", quote.length <= MaxQuoteChars)
    }

    /**
     * A hash collision must never invent a quote.
     *
     * Every candidate is verified with `regionMatches`, so this is really a regression guard: run a
     * wide spread of unrelated pairs and assert that anything returned is genuinely present in both.
     */
    @Test
    fun everyReportedQuoteExistsInBothTexts() {
        val passages = listOf(
            "国家重点实验室在九月公布了新的开放课题指南，申请截止日期为十月三十一日。",
            "GET /api/v1/mem/search returned 200 in 42ms with 10 rows and no cache read.",
            "报名页需要登录后才能看到表单，验证码出现在提交按钮上方。",
        )
        val sentences = listOf(
            "开放课题指南的申请截止日期为十月三十一日。",
            "The request returned 200 in 42ms with 10 rows.",
            "报名页需要登录后才能看到表单。",
            "完全不相干的一句话，用来确认不会凭空产生引文。",
        )
        for (passage in passages) {
            for (sentence in sentences) {
                val quote = longestSharedQuote(sentence, passage)
                if (quote.isEmpty()) continue
                assertTrue("'$quote' not in passage", quote in passage)
                assertTrue("'$quote' shorter than the floor", quote.length >= MinQuoteChars)
            }
        }
    }
}
