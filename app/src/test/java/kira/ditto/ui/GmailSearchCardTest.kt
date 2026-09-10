package kira.ditto.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailSearchCardTest {
    private fun search(
        id: String,
        query: String = "from:ariel",
        output: String = "",
        isRunning: Boolean = false,
    ) = ChatToolInvocation(
        id = id,
        toolName = "search_threads",
        argumentsJson = JSONObject().put("query", query).toString(),
        outputJson = output,
        isRunning = isRunning,
    )

    @Test
    fun matchesSearchThreadsAliases() {
        assertTrue(
            ChatToolInvocation(
                id = "a",
                toolName = "mcp__gmail__search_threads",
                argumentsJson = "{}",
            ).isGmailSearchCard(),
        )
        assertFalse(
            ChatToolInvocation(
                id = "b",
                toolName = "get_thread",
                argumentsJson = "{}",
            ).isGmailSearchCard(),
        )
        assertFalse(
            ChatToolInvocation(
                id = "c",
                toolName = "list_drafts",
                argumentsJson = "{}",
            ).isGmailSearchCard(),
        )
    }

    @Test
    fun collectsOnlySearchThreads() {
        val collected = collectGmailSearchInvocations(
            listOf(
                search("one"),
                ChatToolInvocation(id = "thread", toolName = "get_thread", argumentsJson = "{}"),
                search("one"),
                search("two"),
            ),
        )
        assertEquals(listOf("one", "two"), collected.map { it.id })
    }

    @Test
    fun liveCollectionKeepsSearchesAndLatestDraft() {
        val collected = collectGmailLiveInvocations(
            listOf(
                search("s1"),
                ChatToolInvocation(id = "d1", toolName = "create_draft", argumentsJson = "{}"),
                search("s2"),
                ChatToolInvocation(id = "d2", toolName = "mcp__gmail__create_draft", argumentsJson = "{}"),
                ChatToolInvocation(id = "thread", toolName = "get_thread", argumentsJson = "{}"),
            ),
        )
        assertEquals(listOf("s1", "s2", "d2"), collected.map { it.id })
    }

    @Test
    fun composeUrlPrefersMessageIdThenDraftId() {
        assertTrue(gmailWebUrl(messageId = "msg-9", draftId = "r-1").contains("#drafts?compose=msg-9"))
        assertTrue(gmailWebUrl(draftId = "r-1").contains("#drafts?compose=r-1"))
        assertEquals(
            listOf(
                "https://mail.google.com/mail/u/0/#drafts?compose=msg-9",
                "https://mail.google.com/mail/u/0/#drafts?compose=r-1",
            ),
            gmailOpenUrls(messageId = "msg-9", draftId = "r-1"),
        )
    }

    @Test
    fun readsQueryFromArguments() {
        assertEquals("from:sakamoto newer_than:7d", gmailSearchQuery("""{"query":"from:sakamoto newer_than:7d"}"""))
        assertEquals("", gmailSearchQuery("{}"))
    }

    @Test
    fun senderDisplayPrefersName() {
        assertEquals("Ariel", gmailSenderDisplay("Ariel <ariel@example.com>"))
        assertEquals("ariel@example.com", gmailSenderDisplay("<ariel@example.com>"))
        assertEquals("solo@example.com", gmailSenderDisplay("solo@example.com"))
    }

    @Test
    fun senderEmailFromAngleBrackets() {
        assertEquals("ariel@example.com", gmailSenderEmail("Ariel <ariel@example.com>"))
        assertEquals("solo@example.com", gmailSenderEmail("solo@example.com"))
        assertEquals("", gmailSenderEmail("Ariel"))
    }

    @Test
    fun webUrlOpensThreadSearchOrInbox() {
        assertEquals("https://mail.google.com/mail/u/0/#all/abc", gmailWebUrl(threadId = "abc"))
        assertEquals(
            "https://mail.google.com/mail/u/0/#search/from%3Aariel",
            gmailWebUrl(query = "from:ariel"),
        )
        assertEquals("https://mail.google.com/mail/u/0/#inbox", gmailWebUrl())
        assertEquals(
            "https://mail.google.com/mail/u/0/#drafts?compose=r-1",
            gmailWebUrl(draftId = "r-1"),
        )
        assertEquals(GmailAppPackage, "com.google.android.gm")
        assertTrue(
            gmailAppOpenUrls(threadId = "abc").first().contains("view=cv") &&
                gmailAppOpenUrls(threadId = "abc").first().contains("th=abc"),
        )
        assertTrue(gmailAppOpenUrls(threadId = "abc").any { it.startsWith("googlegmail://") })
        val draftApp = gmailAppOpenUrls(threadId = "t-1", draftId = "r-1", messageId = "msg-9")
        assertEquals("https://mail.google.com/mail/u/0/?view=cv&search=all&th=t-1", draftApp.first())
        assertTrue(draftApp.any { it.contains("th=msg-9") })
        assertTrue(gmailAppOpenUrls(query = "from:ariel").isNotEmpty())
        assertTrue(gmailAppOpenUrls().isEmpty())
    }

    @Test
    fun collectsWarmSearchAndThreadBodies() {
        val collected = collectGmailWarmInvocations(
            listOf(
                search("one", output = """{"ok":true,"threads":[]}"""),
                ChatToolInvocation(
                    id = "thread",
                    toolName = "get_thread",
                    argumentsJson = """{"threadId":"abc"}""",
                    outputJson = """{"ok":true,"id":"abc","messages":[]}""",
                ),
                search("two"),
                ChatToolInvocation(
                    id = "draft",
                    toolName = "list_drafts",
                    argumentsJson = "{}",
                    outputJson = "{}",
                ),
            ),
        )
        assertEquals(listOf("one", "thread"), collected.map { it.id })
    }

    @Test
    fun cardCacheReusesSnapshotAndHtml() {
        val output = JSONObject()
            .put("ok", true)
            .put(
                "threads",
                JSONArray().put(
                    JSONObject()
                        .put("id", "abc")
                        .put(
                            "messages",
                            JSONArray().put(
                                JSONObject()
                                    .put("id", "m1")
                                    .put("sender", "Ariel <ariel@example.com>")
                                    .put("subject", "Plan")
                                    .put("snippet", "Ready"),
                            ),
                        ),
                ),
            )
            .toString()
        val first = GmailCardCache.getOrParseSnapshot("""{"query":"plan"}""", output)
        val second = GmailCardCache.getOrParseSnapshot("""{"query":"plan"}""", output)
        assertTrue(first === second)
        assertTrue(GmailCardCache.peekSnapshot("""{"query":"plan"}""", output) === first)
        val messages = listOf(
            GmailThreadMessage(
                id = "m1",
                from = "Ariel",
                email = "ariel@example.com",
                date = "2026-09-01",
                subject = "Plan",
                body = "Ready",
                htmlBody = "<p>Hello <b>world</b></p>",
            ),
        )
        val html = GmailCardCache.getOrBuildHtml(messages)
        assertTrue(html.contains("<p>Hello <b>world</b></p>"))
        assertTrue(html === GmailCardCache.getOrBuildHtml(messages))
    }

    @Test
    fun renderableHtmlKeepsMarkup() {
        val html = gmailRenderableHtml("<p>Hello <b>world</b></p>", "")
        assertTrue(html.contains("<p>Hello <b>world</b></p>"))
        assertFalse(html.contains("<script"))
    }

    @Test
    fun previewTakesFirstN() {
        val hits = (1..10).map { index ->
            GmailSearchHit(
                threadId = "t$index",
                subject = "S$index",
                from = "A",
                snippet = "body",
                date = "2026-09-03",
                unread = false,
            )
        }
        assertEquals(6, gmailSearchPreview(hits).size)
        assertEquals("t1", gmailSearchPreview(hits).first().threadId)
        assertEquals(3, gmailSearchPreview(hits, limit = 3).size)
    }

    @Test
    fun parsesWrappedSearchThreads() {
        val thread = JSONObject()
            .put("id", "abc")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("id", "m1")
                        .put("sender", "Ariel <ariel@example.com>")
                        .put("subject", "Plan")
                        .put("snippet", "The marketing plan is ready.")
                        .put("date", "2026-09-01")
                        .put("labelIds", JSONArray().put("UNREAD")),
                ),
            )
        val inner = JSONObject()
            .put("ok", true)
            .put("threads", JSONArray().put(thread))
            .put("resultCountEstimate", 12)
        val wrapped = JSONObject()
            .put(
                "content",
                JSONArray().put(JSONObject().put("type", "text").put("text", inner.toString())),
            )
            .put("structuredContent", inner)
        val snapshot = parseGmailSearchSnapshot(
            argumentsJson = """{"query":"plan"}""",
            outputJson = wrapped.toString(),
        )
        assertEquals("plan", snapshot.query)
        assertTrue(snapshot.ok)
        assertEquals(12, snapshot.resultCountEstimate)
        assertEquals(1, snapshot.threads.size)
        assertEquals("abc", snapshot.threads[0].threadId)
        assertEquals("Plan", snapshot.threads[0].subject)
        assertEquals("Ariel", snapshot.threads[0].from)
        assertEquals("ariel@example.com", snapshot.threads[0].email)
        assertTrue(snapshot.threads[0].unread)
    }

    @Test
    fun parsesThreadBodyAndStripsHtml() {
        val inner = JSONObject()
            .put("ok", true)
            .put("id", "abc")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("id", "m1")
                        .put("sender", "Ariel <ariel@example.com>")
                        .put("subject", "Plan")
                        .put("plaintextBody", "")
                        .put("htmlBody", "<p>Hello<br/>world</p>"),
                ),
            )
        val detail = parseGmailThreadDetail(inner.toString(), fallbackId = "abc")
        assertEquals("abc", detail?.threadId)
        assertEquals("Plan", detail?.subject)
        assertEquals("Hello\nworld", detail?.messages?.single()?.body)
        assertEquals("<p>Hello<br/>world</p>", detail?.messages?.single()?.htmlBody)
        assertEquals("ariel@example.com", detail?.messages?.single()?.email)
    }

    @Test
    fun findsGetThreadAmongRelatedInvocations() {
        val detail = gmailThreadFromInvocations(
            listOf(
                search("s1"),
                ChatToolInvocation(
                    id = "g1",
                    toolName = "mcp__gmail__get_thread",
                    argumentsJson = """{"threadId":"abc"}""",
                    outputJson = JSONObject()
                        .put("ok", true)
                        .put("id", "abc")
                        .put(
                            "messages",
                            JSONArray().put(
                                JSONObject()
                                    .put("id", "m1")
                                    .put("sender", "Ariel")
                                    .put("subject", "Plan")
                                    .put("plaintextBody", "Ready to go."),
                            ),
                        )
                        .toString(),
                ),
            ),
            threadId = "abc",
        )
        assertEquals("Ready to go.", detail?.messages?.single()?.body)
    }

    @Test
    fun parsesCreateDraftPreview() {
        val preview = parseGmailDraftPreview(
            argumentsJson = JSONObject()
                .put("to", JSONArray().put("kaggle-noreply@google.com"))
                .put("subject", "Re: Competition deadline approaching")
                .put("body", "收到，谢谢提醒。")
                .toString(),
            outputJson = JSONObject()
                .put("ok", true)
                .put("id", "r-1")
                .put("messageId", "msg-9")
                .put("threadId", "t-1")
                .toString(),
        )
        assertEquals("r-1", preview.draftId)
        assertEquals("msg-9", preview.messageId)
        assertEquals(listOf("kaggle-noreply@google.com"), preview.to)
        assertEquals("Re: Competition deadline approaching", preview.subject)
        assertEquals("收到，谢谢提醒。", preview.body)
        assertTrue(gmailWebUrl(draftId = "r-1").contains("#drafts?compose=r-1"))
        val nested = parseGmailDraftPreview(
            argumentsJson = "{}",
            outputJson = JSONObject()
                .put("id", "r-2")
                .put("message", JSONObject().put("id", "msg-nested").put("threadId", "t-2"))
                .toString(),
        )
        assertEquals("msg-nested", nested.messageId)
        assertTrue(
            gmailWebUrl(messageId = nested.messageId, draftId = nested.draftId)
                .contains("compose=msg-nested"),
        )
    }

    @Test
    fun stripsDraftRecapFromAssistantReply() {
        val draft = parseGmailDraftPreview(
            argumentsJson = JSONObject()
                .put("to", JSONArray().put("kaggle-noreply@google.com"))
                .put("subject", "Re: Competition deadline approaching")
                .put("body", "收到，谢谢提醒。我会在截止前关注 Hyperspectral Object Tracking Challenge 2026 的进展。")
                .toString(),
            outputJson = "{}",
        )
        val markdown = """
            已保存为草稿：
            > **收件人:** kaggle-noreply@google.com
            > **主题:** Re: Competition deadline approaching
            > **内容:** 收到，谢谢提醒。我会在截止前关注 Hyperspectral Object Tracking Challenge 2026 的进展。
            不过提醒一下，这是 Kaggle 的自动通知地址，回复可能不会被收到。需要我修改内容或者直接发送吗？
        """.trimIndent()
        val visible = stripGmailDraftRecap(markdown, listOf(draft))
        assertFalse(visible.contains("收件人"))
        assertFalse(visible.contains("kaggle-noreply@google.com"))
        assertTrue(visible.contains("自动通知地址"))
        assertTrue(
            ChatToolInvocation(
                id = "d1",
                toolName = "create_draft",
                argumentsJson = "{}",
            ).isGmailLiveCard(),
        )
    }

    @Test
    fun renderableHtmlDarkUsesDarkScheme() {
        val html = gmailRenderableHtml("<p>Hello</p>", "", dark = true)
        assertTrue(html.contains("color-scheme:dark") || html.contains("1c1c1e"))
        assertTrue(html.contains("<p>Hello</p>"))
    }
}
