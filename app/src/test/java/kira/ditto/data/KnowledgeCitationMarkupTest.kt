package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeCitationMarkupTest {
    @Test
    fun parseDoubleBracketMarkers() {
        val text = "He lives in Hangzhou[[1]] now."
        val match = parseKnowledgeCitationMarker(text, text.indexOf("[["))
        requireNotNull(match)
        assertEquals(1, match.index)
        assertEquals(text.indexOf(" now."), match.endExclusive)
    }

    @Test
    fun parseBareAndFootnoteMarkersButIgnoreMarkdownLinks() {
        val bare = parseKnowledgeCitationMarker("claim [2] next", "claim ".length)
        requireNotNull(bare)
        assertEquals(2, bare.index)

        val footnote = parseKnowledgeCitationMarker("claim [^3] next", "claim ".length)
        requireNotNull(footnote)
        assertEquals(3, footnote.index)

        assertNull(parseKnowledgeCitationMarker("[4](https://example.com)", 0))
        assertNull(parseKnowledgeCitationMarker("still streaming [[1", 16))
    }

    @Test
    fun stripSourceFilenameAttributions() {
        val stripped = stripKnowledgeSourceAttributions(
            """
            凤翕晟今年 28 岁。

            以上信息均来自 凤翕晟_简.pdf。
            """.trimIndent(),
        )
        assertTrue(stripped.contains("28 岁"))
        assertTrue("pdf" !in stripped.lowercase())
        assertTrue("以上信息均来自" !in stripped)
        assertEquals(
            "He is 28.",
            stripKnowledgeSourceAttributions("He is 28.（来自 凤翕晟_简.pdf）").trim(),
        )
    }

    @Test
    fun citationUrlRoundTrip() {
        assertEquals(7, parseKnowledgeCitationUrl(knowledgeCitationUrl(7)))
        assertNull(parseKnowledgeCitationUrl("https://example.com"))
    }

    @Test
    fun extractAndStripWebSourceSection() {
        val markdown = """
            西湖很大[[1]]。

            来源
            1. [西湖](https://example.com/west-lake)
            [[2]]: https://example.org/notes
        """.trimIndent()
        val citations = extractMarkdownWebCitations(markdown)
        assertEquals(2, citations.size)
        assertEquals("https://example.com/west-lake", citations[0].url)
        assertEquals("https://example.org/notes", citations[1].url)
        val stripped = stripMarkdownWebSourceSection(markdown)
        assertTrue(stripped.contains("[[1]]"))
        assertTrue("west-lake" !in stripped)
        assertTrue("example.org" !in stripped)
    }

    @Test
    fun extractTrailingLinkListWithoutHeading() {
        val markdown = """
            结论写在这里。

            - https://a.example/one
            - https://b.example/two
        """.trimIndent()
        val citations = extractMarkdownWebCitations(markdown)
        assertEquals(2, citations.size)
        assertEquals("https://a.example/one", citations[0].url)
        assertEquals("https://b.example/two", citations[1].url)
    }

    @Test
    fun markdownSourceHostStripsWww() {
        assertEquals("example.com", markdownSourceHost("https://www.example.com/path"))
        assertEquals("", markdownSourceHost("/local.png"))
        assertEquals("zhihu", markdownSourceHostLabel("https://www.zhihu.com/question/1"))
        assertEquals("bbc", markdownSourceHostLabel("https://www.bbc.co.uk/news"))
        assertEquals(
            "zhihu",
            knowledgeCitationChipLabel(
                KnowledgeCitation(
                    index = 1,
                    sourceName = "西湖",
                    text = "西湖",
                    url = "https://www.zhihu.com/question/1",
                ),
            ),
        )
    }

    @Test
    fun injectBrowserCitationMarkersAddsInlineSuperscripts() {
        val sources = listOf(
            KnowledgeCitation(
                index = 1,
                sourceName = "西湖",
                text = "西湖",
                url = "https://www.zhihu.com/question/1",
            ),
        )
        val injected = injectBrowserCitationMarkers("杭州西湖很大。", sources)
        assertTrue(injected.contains("西湖[[1]]"))
        assertFalse(injected.contains("来源"))
    }

    @Test
    fun injectSkipsLastLineWhenUnmatchedDisabled() {
        val sources = listOf(
            KnowledgeCitation(
                index = 1,
                sourceName = "西湖",
                text = "西湖",
                url = "https://www.zhihu.com/question/1",
            ),
        )
        val injected = injectBrowserCitationMarkers(
            markdown = "今天天气很好。",
            sources = sources,
            appendUnmatchedToLastLine = false,
        )
        assertFalse(injected.contains("[[1]]"))
    }

    @Test
    fun imageListIsNotTreatedAsWebSources() {
        val markdown = """
            这些是现场图。

            - ![a](https://cdn.example/a.jpg)
            - ![b](https://cdn.example/b.jpg)
        """.trimIndent()
        assertTrue(extractMarkdownWebCitations(markdown).isEmpty())
        val stripped = stripMarkdownWebSourceSection(markdown)
        assertTrue(stripped.contains("![a](https://cdn.example/a.jpg)"))
        assertTrue(stripped.contains("![b](https://cdn.example/b.jpg)"))
    }
}
