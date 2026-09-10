package kira.ditto.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentIndexTest {
    @Test
    fun cjkNgramsRankWestLakeOverUnrelated() {
        val passages = listOf(
            AgentPassage(text = "杭州西湖是著名景点，湖光山色。"),
            AgentPassage(text = "The stock market closed mixed after the Fed announcement."),
        )
        val ranked = AgentIndex.rank("西湖 杭州", passages)
        assertTrue(ranked.isNotEmpty())
        assertTrue(ranked.first().passage.text.contains("西湖"))
    }

    @Test
    fun minScoreReturnsEmptyWhenNothingMatches() {
        val ranked = AgentIndex.rank(
            "quantum chromodynamics lattice",
            listOf(AgentPassage(text = "今日天气预报多云。")),
        )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun chunkPageSplitsLongArticle() {
        val chunks = AgentIndex.chunkPage(
            title = "西湖",
            text = "第一段介绍。\n\n" + "湖".repeat(800),
            passageChars = 200,
        )
        assertTrue(chunks.size >= 2)
        assertEquals("西湖", chunks.first().heading)
    }

    @Test
    fun contentHashIgnoresWhitespace() {
        assertEquals(
            AgentIndex.contentHash("hello   world"),
            AgentIndex.contentHash("hello world"),
        )
    }
}
