package kira.ditto.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The gate for making the event log the desk's only writer.
 *
 * The plan for this change said the read path does not switch until `replayBrowserDesk(events)`
 * produces, field for field, the state the live desk produces. That is the first test here, and it
 * runs against real call sequences rather than synthetic events - a replay that only agrees with
 * hand-written events proves nothing about the code that actually writes them.
 */
class BrowserEventLogTest {

    @Before
    fun reset() {
        BrowserDesk.clear()
        BrowserDesk.clearReadLedger()
    }

    private val articleUrl = "https://news.sina.cn/2026-09-08/x.html"

    private fun searchPayload() = JSONObject()
        .put("ok", true)
        .put(
            "queries",
            JSONArray().put(
                JSONObject()
                    .put("query", "时政新闻")
                    .put(
                        "hits",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("title", "手机新浪网")
                                    .put("url", articleUrl)
                                    .put("snippet", "科技要闻"),
                            )
                            .put(
                                JSONObject()
                                    .put("title", "知乎专栏")
                                    .put("url", "https://zhuanlan.zhihu.com/p/771234567")
                                    .put("snippet", "汇总"),
                            ),
                    ),
            ),
        )

    /**
     * A real turn driven through the real API: search, read, cite.
     *
     * Driven with `begin`/`complete` rather than by hand-building events, because a replay that only
     * agrees with hand-written events proves nothing about the code that writes them.
     */
    private fun runResearchTurn() {
        val searchId = BrowserDesk.begin("search_web", JSONObject().put("query", "时政新闻"))
        BrowserDesk.complete(searchId, "search_web", searchPayload().toString())
        val readId = BrowserDesk.begin("page_read", JSONObject().put("url", articleUrl))
        BrowserDesk.complete(
            readId,
            "page_read",
            JSONObject()
                .put("ok", true)
                .put("url", articleUrl)
                .put("title", "手机新浪网")
                .put("text", "华为在 9 月初发布了新一代昇腾算力平台。")
                .toString(),
        )
        BrowserDesk.applyCitations(
            listOf(
                BrowserCitation(
                    pageKey = normalizeBrowsedUrl(articleUrl),
                    url = articleUrl,
                    title = "手机新浪网",
                ),
            ),
        )
    }

    @Test
    fun replayReproducesTheLiveDeskFieldForField() {
        runResearchTurn()
        val live = BrowserDesk.state.value
        val replayed = replayBrowserDesk(BrowserDesk.eventLog())

        // Compared field by field rather than by equals() so a failure names the field.
        assertEquals("preview", live.preview, replayed.preview)
        assertEquals("previewsByTopic", live.previewsByTopic, replayed.previewsByTopic)
        assertEquals("pages", live.pages, replayed.pages)
        assertEquals("activities", live.activities, replayed.activities)
        assertEquals("sources", live.sources, replayed.sources)
        assertEquals("uiPreviewTopicId", live.uiPreviewTopicId, replayed.uiPreviewTopicId)
        assertEquals("readingUrl", live.readingUrl, replayed.readingUrl)
        assertEquals("readingUrls", live.readingUrls, replayed.readingUrls)
        assertEquals("readingExcerpt", live.readingExcerpt, replayed.readingExcerpt)
        assertEquals("openedUrl", live.openedUrl, replayed.openedUrl)
        assertEquals("userTakeover", live.userTakeover, replayed.userTakeover)
        assertEquals("userTakeoverReason", live.userTakeoverReason, replayed.userTakeoverReason)
        assertEquals("keepLiveSurface", live.keepLiveSurface, replayed.keepLiveSurface)
        assertEquals("lastUpdatedMillis", live.lastUpdatedMillis, replayed.lastUpdatedMillis)
        // And the whole thing, so a field added later without being replayed fails here.
        assertEquals(live, replayed)
    }

    @Test
    fun replayHoldsAcrossASessionSwitch() {
        runResearchTurn()
        val log = BrowserDesk.eventLog()
        BrowserDesk.clear()
        assertEquals(BrowserDeskState(), BrowserDesk.state.value)

        // Switching back rebuilds from the log rather than from a parked copy - the parked copy was
        // a second writer to state the log is supposed to own.
        val restored = replayBrowserDesk(log)
        assertTrue(restored.previewsByTopic.isNotEmpty())
    }

    @Test
    fun theCitedVerdictSurvivesReplay() {
        runResearchTurn()
        val replayed = replayBrowserDesk(BrowserDesk.eventLog())
        val cited = replayed.previewsByTopic.values.flatMap { it.hits }.filter { it.cited }
        assertEquals(1, cited.size)
        assertTrue(browserHitUrlMatches(cited.single().url, articleUrl))
    }

    /**
     * Order is no longer something to get right.
     *
     * The citation-settlement bug existed because two writable states had to be updated in the right
     * order. With one log there is no second state to be stale: replaying a prefix gives the state
     * as of that prefix, and replaying all of it gives the state now.
     */
    @Test
    fun everyPrefixOfTheLogIsAValidState() {
        runResearchTurn()
        val log = BrowserDesk.eventLog()
        assertTrue("expected a non-trivial log", log.size >= 3)
        for (length in 1..log.size) {
            val state = replayBrowserDesk(log.take(length))
            // Not asserting a value - asserting that no prefix throws and none produces a desk that
            // claims to be reading a page it has no record of.
            if (state.readingUrl.isNotBlank()) {
                assertTrue(state.readingUrls.isEmpty() || state.readingUrl in state.readingUrls)
            }
        }
        assertEquals(BrowserDesk.state.value, replayBrowserDesk(log))
    }

    @Test
    fun clearingResetsTheProjectionToNothing() {
        runResearchTurn()
        assertNotEquals(BrowserDeskState(), BrowserDesk.state.value)
        BrowserDesk.clear()
        assertEquals(BrowserDeskState(), BrowserDesk.state.value)
        assertEquals(BrowserDeskState(), replayBrowserDesk(BrowserDesk.eventLog()))
    }

    @Test
    fun compactionCutsAtAResetAndKeepsTheResult() {
        runResearchTurn()
        BrowserDesk.clear()
        runResearchTurn()
        val log = BrowserDesk.eventLog()
        val compacted = compactBrowserDeskLog(log, softLimit = 1)
        assertTrue("compaction should shorten the log", compacted.size < log.size)
        assertEquals(
            "compaction must not change what the log means",
            replayBrowserDesk(log),
            replayBrowserDesk(compacted),
        )
    }

    @Test
    fun anEmptyLogIsTheEmptyDesk() {
        assertEquals(BrowserDeskState(), replayBrowserDesk(emptyList()))
    }
}
