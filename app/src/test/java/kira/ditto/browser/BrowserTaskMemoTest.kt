package kira.ditto.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two things the memory track cannot get wrong.
 *
 * A memo that fails to parse costs a memory. A memo that uploads when it should not costs a memory
 * that can never be taken back - EverMe has no delete - so the gate is tested harder than the parser.
 */
class BrowserTaskMemoTest {

    private val fullMemo = """
        GUI_TASK_SUCCEEDED: read four sources on September chip announcements
        BROWSER_TASK_MEMO:
          summary: 华为昇腾新平台发布，推理吞吐提升，配套软件栈同步更新
          tags: 国产算力, 芯片发布, 华为
          open: 第三方实测数据尚未公开
          salience: 0.8
          origins: news.sina.cn: 正文在 .article 内，无需登录 | zhuanlan.zhihu.com: 长文需要展开按钮
    """.trimIndent()

    @Test
    fun aCompleteMemoParsesEveryField() {
        val memo = parseBrowserTaskMemo(fullMemo)!!
        assertTrue(memo.summary.startsWith("华为昇腾新平台发布"))
        assertEquals(listOf("国产算力", "芯片发布", "华为"), memo.tags)
        assertEquals("第三方实测数据尚未公开", memo.openTodos)
        assertEquals(0.8, memo.salience, 0.0001)
        assertEquals(2, memo.origins.size)
        assertEquals("news.sina.cn", memo.origins.first().origin)
        assertEquals(MemorySource.TaskSummary, memo.source)
    }

    @Test
    fun aMemoMissingFieldsKeepsWhatItHas() {
        val partial = """
            BROWSER_TASK_MEMO:
              summary: 只查到一条公开时间线
        """.trimIndent()
        val memo = parseBrowserTaskMemo(partial)!!
        assertEquals("只查到一条公开时间线", memo.summary)
        assertTrue(memo.tags.isEmpty())
        assertEquals(0.0, memo.salience, 0.0001)
        // Degrading a field must not fail the parse - a memo that is mostly there is still memory.
        assertEquals("", memo.openTodos)
    }

    @Test
    fun textWithoutAMemoParsesToNothing() {
        assertNull(parseBrowserTaskMemo("GUI_TASK_SUCCEEDED: opened the page and read it"))
        // A header with no summary is not a memo either.
        assertNull(parseBrowserTaskMemo("BROWSER_TASK_MEMO:\n  tags: a, b"))
    }

    @Test
    fun openNoneMeansNothingOutstanding() {
        val memo = parseBrowserTaskMemo("BROWSER_TASK_MEMO:\n  summary: done\n  open: none")!!
        assertEquals("", memo.openTodos)
    }

    @Test
    fun theMemoBlockNeverReachesTheReader() {
        val stripped = stripBrowserTaskMemo(fullMemo)
        assertFalse("BROWSER_TASK_MEMO" in stripped)
        assertFalse("salience" in stripped)
        assertTrue(stripped.startsWith("GUI_TASK_SUCCEEDED:"))
    }

    @Test
    fun aSalientRecurringTaskUploads() {
        val memo = parseBrowserTaskMemo(fullMemo)!!
        assertTrue(shouldUploadMemo(memo, mapOf("国产算力" to 3, "芯片发布" to 1)))
    }

    @Test
    fun aSalientOneOffStaysLocal() {
        val memo = parseBrowserTaskMemo(fullMemo)!!
        // Every tag seen exactly once: an observation, not yet an interest.
        assertFalse(shouldUploadMemo(memo, memo.tags.associateWith { 1 }))
    }

    @Test
    fun anUnimportantTaskStaysLocalHoweverOftenItRecurs() {
        val memo = parseBrowserTaskMemo(fullMemo)!!.copy(salience = 0.3)
        assertFalse(shouldUploadMemo(memo, memo.tags.associateWith { 9 }))
    }

    @Test
    fun theHostsOwnFallbackNeverUploadsOnItsOwn() {
        val citations = listOf(
            BrowserCitation(
                pageKey = "news.sina.cn/detail",
                url = "https://news.sina.cn/detail",
                title = "科技要闻",
            ),
        )
        val memo = synthesizeBrowserTaskMemo(citations, "今天的主要进展集中在算力平台。")!!
        assertEquals(MemorySource.HostDerived, memo.source)
        assertEquals("今天的主要进展集中在算力平台。", memo.summary)
        // Salient by inflation and recurring by tag would still not be enough.
        assertFalse(
            shouldUploadMemo(memo.copy(salience = 1.0), memo.tags.associateWith { 9 }),
        )
    }

    @Test
    fun aSiteFactWaitsForItsSecondConfirmation() {
        assertFalse(shouldUploadOriginFact(1))
        assertTrue(shouldUploadOriginFact(2))
    }

    @Test
    fun redactionDropsCredentialsAndKeepsTheAnchorIntact() {
        val url = "https://example.com/article/42?id=7&session_token=abc123&utm_source=x"
        val redacted = redactBrowsedUrl(url)
        assertFalse("abc123" in redacted)
        assertTrue("id=7" in redacted)
        // The citation anchor is a separate function and must be untouched by any of this: it still
        // carries the whole query, session token included, because that string is the key existing
        // citations are stored under. Redacting there would silently re-point every one of them.
        assertEquals(
            "https://example.com/article/42?id=7&session_token=abc123",
            normalizeBrowsedUrl(url),
        )
    }

    @Test
    fun anAccountPathIsRememberedAsAHostAndNothingElse() {
        assertEquals(
            "https://bank.example.com",
            redactBrowsedUrl("https://bank.example.com/account/settings/cards?x=1"),
        )
    }

    @Test
    fun errorPagesAreNotWorthRemembering() {
        val body = "x".repeat(400)
        assertFalse(isRememberablePage("https://a.com/x", "Fetch failed: a.com", body))
        assertFalse(isRememberablePage("https://a.com/x", "404 Not Found", body))
        assertFalse(isRememberablePage("https://a.com/x", "Real title", "too short"))
        assertTrue(isRememberablePage("https://a.com/x", "Real title", body))
    }
}
