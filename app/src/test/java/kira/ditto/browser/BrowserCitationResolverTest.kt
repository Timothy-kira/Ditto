package kira.ditto.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the difference between "the agent read this page" and "the answer used this page".
 *
 * Fixtures are shaped after the 时政新闻 turn the card got wrong: four pages fetched, three of them
 * named in the answer, one never mentioned. Before the read/cited split every one of the four wore
 * the 引用 badge.
 */
class BrowserCitationResolverTest {

    private val sinaUrl = "https://news.sina.cn/2026-09-07/detail-abcdef.d.html"
    private val zhihuUrl = "https://zhuanlan.zhihu.com/p/771234567"
    private val stdailyUrl = "http://www.stdaily.com/web/gjxw/2026-09/07/content_1234.html"
    private val unusedUrl = "https://www.sohu.com/a/930606912_122486226"

    private val answer = """
        2026 年 9 月的科技动态集中在三条线上。

        华为在 9 月初发布了新一代昇腾算力平台，官方称推理吞吐提升明显[[1]]。
        国内大模型厂商同期密集更新，知乎专栏的汇总列出了当月的主要版本节点[[2]]。
        国际方面，国际科技新闻栏目报道了同期的几项基础研究进展[[3]]。

        ## 来源

        [[1]]: [手机新浪网](https://news.sina.cn/2026-09-07/detail-abcdef.d.html)
        [[2]]: [知乎专栏](https://zhuanlan.zhihu.com/p/771234567)
        [[3]]: [中国科技网](http://www.stdaily.com/web/gjxw/2026-09/07/content_1234.html)
    """.trimIndent()

    private fun hit(title: String, url: String) = BrowserDeskHit(
        title = title,
        url = url,
        snippet = title,
        read = true,
        readAtMillis = 1L,
    )

    private val topics = mapOf(
        "时政新闻 2026年9月8日" to BrowserDeskPreview(
            kind = BrowserDeskPreviewKind.Search,
            title = "时政新闻 2026年9月8日",
            hits = listOf(
                hit("2026 年 9 月科技圈大事件节奏", sinaUrl),
                hit("2026年9月科技资讯汇总", zhihuUrl),
                hit("国际科技新闻", stdailyUrl),
                hit("科技要闻速递", unusedUrl),
            ),
        ),
    )

    private fun pageIndex(vararg pages: IndexedBrowserPage): (String) -> IndexedBrowserPage? {
        val byKey = pages.associateBy { normalizeBrowsedUrl(it.url).ifBlank { it.url } }
        return { key -> byKey[key] }
    }

    private fun indexed(url: String, title: String, text: String): IndexedBrowserPage {
        val passages = AgentIndex.chunkPage(title = title, text = text, url = url)
        return IndexedBrowserPage(
            url = url,
            canonical = normalizeBrowsedUrl(url),
            title = title,
            text = text,
            contentHash = AgentIndex.contentHash(text),
            simhash = AgentIndex.simhash(text),
            origin = originOf(url),
            passages = passages,
            indexedAtMillis = 1L,
        )
    }

    @Test
    fun aPageTheAgentReadButTheAnswerNeverMentionedIsNotCited() {
        val citations = resolveBrowserCitations(answer, topics) { null }
        val urls = citations.map { normalizeBrowsedUrl(it.url) }
        assertTrue(normalizeBrowsedUrl(sinaUrl) in urls)
        assertTrue(normalizeBrowsedUrl(zhihuUrl) in urls)
        assertTrue(normalizeBrowsedUrl(stdailyUrl) in urls)
        // The one the old code would still have badged.
        assertFalse(normalizeBrowsedUrl(unusedUrl) in urls)
    }

    @Test
    fun everyResolvedCitationIsAtLeastPageConfident() {
        val citations = resolveBrowserCitations(answer, topics) { null }
        assertEquals(3, citations.size)
        assertTrue(citations.all { it.confidence == CitationConfidence.Page })
        assertTrue(citations.all { it.passageOrdinal == -1 })
    }

    @Test
    fun aCitationLandsOnThePassageThatCarriesTheClaim() {
        val page = indexed(
            url = sinaUrl,
            title = "2026 年 9 月科技圈大事件节奏",
            text = buildString {
                append("本月开篇是几场发布会的密集排期，厂商各自准备了新的终端产品线。\n\n")
                append("华为在 9 月初发布了新一代昇腾算力平台，官方称推理吞吐提升明显，配套的软件栈同步更新。\n\n")
                append("此外，若干车企在同期公布了智能驾驶的版本迭代计划，与本文主题关系不大。\n\n")
            },
        )
        val citations = resolveBrowserCitations(answer, topics, pageIndex(page))
        val sina = citations.first { browserHitUrlMatches(it.url, sinaUrl) }
        assertTrue("expected a passage anchor, got ${sina.passageOrdinal}", sina.passageOrdinal >= 0)
        assertEquals(page.contentHash, sina.contentHash)
        assertTrue(sina.confidence != CitationConfidence.Page)
    }

    @Test
    fun anImageIsCitedByItsSourcePageWhenItAppearsInTheAnswer() {
        val imageUrl = "https://img.example.com/chip.jpg"
        val withImage = "$answer\n\n![昇腾平台]($imageUrl)"
        val topicsWithImage = mapOf(
            "时政新闻 2026年9月8日" to topics.values.first().copy(
                images = listOf(
                    BrowserDeskImage(
                        url = imageUrl,
                        alt = "昇腾平台",
                        sourceUrl = sinaUrl,
                        caption = "昇腾算力平台发布现场",
                    ),
                ),
            ),
        )
        val citations = resolveBrowserCitations(withImage, topicsWithImage) { null }
        // The page is already cited as a web source, so the image must not duplicate it.
        assertEquals(3, citations.size)
        assertTrue(citations.none { it.kind == CitationKind.Image })
    }

    @Test
    fun anImageFromAPageTheAnswerNeverLinkedStillCarriesItsProvenance() {
        val imageUrl = "https://img.example.com/lab.jpg"
        val onlyImage = "看看这张图。\n\n![实验室]($imageUrl)"
        val topicsWithImage = mapOf(
            "topic" to BrowserDeskPreview(
                kind = BrowserDeskPreviewKind.Images,
                images = listOf(
                    BrowserDeskImage(
                        url = imageUrl,
                        alt = "实验室",
                        sourceUrl = unusedUrl,
                        caption = "国家重点实验室",
                    ),
                ),
            ),
        )
        val citations = resolveBrowserCitations(onlyImage, topicsWithImage) { null }
        assertEquals(1, citations.size)
        assertEquals(CitationKind.Image, citations.first().kind)
        assertEquals(normalizeBrowsedUrl(unusedUrl), citations.first().pageKey)
        assertEquals("国家重点实验室", citations.first().quote)
    }

    @Test
    fun anInlineLinkCountsAsACitationWithoutASourceList() {
        val inline = "据[手机新浪网]($sinaUrl)报道，昇腾平台的推理吞吐提升明显。"
        val citations = resolveBrowserCitations(inline, topics) { null }
        assertEquals(1, citations.size)
        assertEquals(normalizeBrowsedUrl(sinaUrl), citations.first().pageKey)
    }

    @Test
    fun anAnswerThatCitesNothingResolvesToNothing() {
        val citations = resolveBrowserCitations("我没有查到相关内容。", topics) { null }
        assertTrue(citations.isEmpty())
    }

    @Test
    fun applyingCitationsClearsBadgesTheNewAnswerNoLongerEarns() {
        val hits = listOf(
            hit("stale", unusedUrl).copy(cited = true, citedAtMillis = 5L, citedQuote = "旧引文"),
            hit("fresh", sinaUrl),
        )
        val applied = applyCitationsToHits(
            hits,
            listOf(BrowserCitation(pageKey = normalizeBrowsedUrl(sinaUrl), url = sinaUrl)),
        )
        assertFalse(applied.first { browserHitUrlMatches(it.url, unusedUrl) }.cited)
        assertEquals("", applied.first { browserHitUrlMatches(it.url, unusedUrl) }.citedQuote)
        assertTrue(applied.first { browserHitUrlMatches(it.url, sinaUrl) }.cited)
        // Clearing a citation must not un-read the page.
        assertTrue(applied.all { it.read })
    }
}
