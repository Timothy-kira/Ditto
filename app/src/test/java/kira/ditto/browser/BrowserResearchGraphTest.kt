package kira.ditto.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrowserResearchGraphTest {
    @Before
    fun reset() {
        BrowserResearchGraph.clear()
    }

    @Test
    fun sameOriginNearDupIsO1Fetch() {
        BrowserResearchGraph.index(
            url = "https://example.com/west-lake",
            title = "西湖",
            text = "杭州西湖是中国著名的湖泊，湖光山色，游客众多。".repeat(4),
        )
        val dup = BrowserResearchGraph.findNearDup(
            url = "https://example.com/west-lake?ref=1",
            text = "杭州西湖是中国著名的湖泊，湖光山色，游客众多。".repeat(4),
        )
        assertNotNull(dup)
        val markdown = BrowserResearchGraph.markdownFor("https://www.example.com/west-lake")
        assertTrue(markdown.contains("西湖"))
        assertTrue(markdown.contains("url:"))
    }

    @Test
    fun crossOriginMergeRequiresCanonical() {
        val body = "同一篇文章的正文，用于检测跨站合并是否需要 canonical。".repeat(6)
        val near = "同一篇文章的正文，用于检测跨站合并是否需要 规范化标记。".repeat(6)
        BrowserResearchGraph.index(
            url = "https://news.example.com/a",
            title = "A",
            text = body,
            canonical = "https://canonical.example/a",
        )
        assertNull(
            BrowserResearchGraph.findNearDup(
                url = "https://mirror.other.com/a",
                text = near,
            ),
        )
        val merged = BrowserResearchGraph.findNearDup(
            url = "https://mirror.other.com/a",
            text = near,
            canonical = "https://canonical.example/a",
        )
        assertEquals("https://news.example.com/a", merged?.url)
        val hashed = BrowserResearchGraph.findNearDup(
            url = "https://mirror.other.com/a",
            text = body,
        )
        assertEquals("https://news.example.com/a", hashed?.url)
    }

    @Test
    fun contentHashLookupIsExact() {
        val text = "抽取后的正文哈希，而不是 HTML。"
        val page = BrowserResearchGraph.index(
            url = "https://docs.example.com/hash",
            title = "Hash",
            text = text,
        )
        assertNotNull(page)
        assertEquals(
            page,
            BrowserResearchGraph.findByContentHash(AgentIndex.contentHash(text)),
        )
    }
}
