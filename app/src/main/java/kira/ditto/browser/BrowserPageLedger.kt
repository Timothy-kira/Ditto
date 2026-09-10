package kira.ditto.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * What we know about a page, whoever found it out.
 *
 * The parent agent and a browser subagent are separate Kimi CLI sessions with separate MCP
 * session headers, and a subagent's answer only ever reaches its parent as prose. So the one
 * place they can share a page is here, inside the app: keyed by normalized URL, scoped to the
 * conversation, and readable by every tool.
 *
 * The content itself lives in [BrowserResearchGraph], which already chunks pages into ranked
 * [AgentPassage]s. This ledger is the table of contents over it: what the conversation has read,
 * where it came from, and how to ask for it again.
 */
internal data class LedgerPage(
    val url: String,
    val title: String,
    val text: String,
    /** gecko-dom | necko-fetch | snapshot | research-graph */
    val source: String,
    val sessionId: String = "",
    val readyState: String = "",
    val domTotal: Int = -1,
    val csrShell: Boolean = false,
    val capturedAtMillis: Long = System.currentTimeMillis(),
) {
    fun ageMillis(): Long = System.currentTimeMillis() - capturedAtMillis
}

internal object BrowserPageLedger {
    private const val MaxEntries = 96
    const val MinUsefulChars = 200
    private const val IndexLimit = 12
    private const val HeadingsPerPage = 4

    private val lock = Any()
    private val byUrl = LinkedHashMap<String, LedgerPage>()

    private fun keyOf(url: String): String =
        normalizeBrowsedUrl(url).ifBlank { url.trim() }

    private fun sameSession(page: LedgerPage, sessionId: String): Boolean {
        // A blank sessionId on either side means "unscoped" and still matches, so pages recorded
        // before a session was bound stay reachable inside that same conversation.
        if (sessionId.isBlank() || page.sessionId.isBlank()) return true
        return page.sessionId == sessionId
    }

    fun record(
        url: String,
        title: String,
        text: String,
        source: String,
        sessionId: String = "",
        readyState: String = "",
        domTotal: Int = -1,
        csrShell: Boolean = false,
    ): LedgerPage? {
        val key = keyOf(url)
        if (key.isBlank() || text.isBlank()) return null
        val page = LedgerPage(
            url = url.trim(),
            title = title.trim(),
            text = text,
            source = source,
            sessionId = sessionId.trim(),
            readyState = readyState,
            domTotal = domTotal,
            csrShell = csrShell,
        )
        synchronized(lock) {
            val existing = byUrl[key]
            // A richer capture wins; an equally rich but newer one refreshes the timestamp.
            if (existing != null && existing.text.length > text.length * 2) return existing
            byUrl.remove(key)
            byUrl[key] = page
            while (byUrl.size > MaxEntries) {
                val oldest = byUrl.keys.firstOrNull() ?: break
                byUrl.remove(oldest)
            }
        }
        return page
    }

    /**
     * Shared fetch pipeline: decode/dedup already happened upstream; this is the only write
     * into the research graph and this ledger. WebMcpHost and other tools only read afterwards.
     */
    fun ingest(
        url: String,
        title: String,
        text: String,
        source: String,
        sessionId: String = "",
        canonical: String = "",
        outline: String = "",
        readyState: String = "",
        domTotal: Int = -1,
        csrShell: Boolean = false,
        recordDeskRead: Boolean = false,
    ): Boolean {
        if (text.length < MinUsefulChars) return false
        val landed = url.trim()
        if (landed.isBlank()) return false
        val near = BrowserResearchGraph.findNearDup(landed, text, canonical)
        val storedTitle = near?.title?.ifBlank { title } ?: title
        val storedText = near?.text ?: text
        if (near == null) {
            BrowserResearchGraph.index(
                url = landed,
                title = title,
                text = text,
                canonical = canonical,
                outline = outline,
            )
        }
        record(
            url = landed,
            title = storedTitle,
            text = storedText,
            source = source,
            sessionId = sessionId,
            readyState = readyState,
            domTotal = domTotal,
            csrShell = csrShell,
        )
        if (recordDeskRead) BrowserDesk.recordRead(landed, storedTitle, storedText)
        return true
    }

    fun lookup(url: String, sessionId: String = ""): LedgerPage? {
        val key = keyOf(url)
        if (key.isBlank()) return null
        synchronized(lock) {
            val page = byUrl[key] ?: return null
            return if (sameSession(page, sessionId)) page else null
        }
    }

    /**
     * Best text we hold for this URL in this conversation, from any path — including pages that
     * only ever went through [BrowserResearchGraph] (FetchURL, the Kimi CLI fetch hook, a browser
     * subagent's page_read). That fallback is what stops a subagent's read from being thrown away.
     */
    fun recover(url: String, sessionId: String = ""): LedgerPage? {
        lookup(url, sessionId)?.let { page ->
            if (page.text.length >= MinUsefulChars) return page
        }
        val indexed = BrowserResearchGraph.lookup(url) ?: return null
        if (indexed.text.length < MinUsefulChars) return null
        return LedgerPage(
            url = indexed.url,
            title = indexed.title,
            text = indexed.text,
            source = "research-graph",
            sessionId = sessionId,
            capturedAtMillis = indexed.indexedAtMillis,
        )
    }

    fun has(url: String, sessionId: String = ""): Boolean = recover(url, sessionId) != null

    /** Tool-payload shape: what we know, where it came from, and how old it is. */
    fun describe(page: LedgerPage, textLimit: Int = 6_000): JSONObject = JSONObject()
        .put("url", page.url)
        .put("title", page.title)
        .put("text", page.text.take(textLimit))
        .put("text_truncated", page.text.length > textLimit)
        .put("source", page.source)
        .put("age_ms", page.ageMillis())
        .apply {
            if (page.readyState.isNotBlank()) put("ready_state", page.readyState)
            if (page.domTotal >= 0) put("dom_total", page.domTotal)
            if (page.csrShell) put("csr_shell", true)
        }

    /**
     * The table of contents handed to the model: enough to recognise a page, never its body.
     * Newest first.
     */
    fun index(sessionId: String, limit: Int = IndexLimit): JSONArray {
        val pages = synchronized(lock) {
            byUrl.values
                .filter { sameSession(it, sessionId) && it.text.length >= MinUsefulChars }
                .toList()
        }
        val array = JSONArray()
        pages.asReversed().take(limit).forEachIndexed { position, page ->
            val headings = BrowserResearchGraph.lookup(page.url)
                ?.passages
                .orEmpty()
                .map { it.heading.trim() }
                .filter { it.isNotBlank() && it != page.title }
                .distinct()
                .take(HeadingsPerPage)
            val entry = JSONObject()
                .put("n", position + 1)
                .put("url", page.url)
                .put("title", page.title)
                .put("chars", page.text.length)
                .put("source", page.source)
            if (headings.isNotEmpty()) {
                entry.put("headings", JSONArray().also { list -> headings.forEach(list::put) })
            }
            if (page.csrShell) entry.put("csr_shell", true)
            array.put(entry)
        }
        return array
    }

    /** Resolve the `n` shown in a page_graph index back to a URL. */
    /**
     * Which pages this session has already read answer this query?
     *
     * The lookup round used to go straight to a search engine every time, even for a question the
     * conversation had already read three pages about. Ranking the graph first turns a repeat
     * question into zero navigations.
     */
    fun rankedFor(
        query: String,
        sessionId: String,
        limit: Int = 5,
    ): List<Pair<LedgerPage, String>> {
        val needle = query.trim()
        if (needle.isBlank()) return emptyList()
        val pages = synchronized(lock) {
            byUrl.values
                .filter { sameSession(it, sessionId) && it.text.length >= MinUsefulChars }
                .toList()
        }
        if (pages.isEmpty()) return emptyList()
        val scored = ArrayList<Triple<Float, LedgerPage, String>>()
        pages.forEach { page ->
            val passages = BrowserResearchGraph.lookup(page.url)?.passages.orEmpty()
            if (passages.isEmpty()) return@forEach
            val ranked = AgentIndex.rank(needle, passages)
            val best = ranked.firstOrNull() ?: return@forEach
            val snippet = ranked.take(2).joinToString(" ") { it.passage.text.trim() }.take(320)
            scored += Triple(best.score, page, snippet)
        }
        return scored
            .sortedByDescending { it.first }
            .take(limit)
            .map { it.second to it.third }
    }

    fun urlForIndex(sessionId: String, n: Int): String {
        if (n <= 0) return ""
        val entries = index(sessionId, limit = Int.MAX_VALUE)
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (entry.optInt("n") == n) return entry.optString("url")
        }
        return ""
    }

    fun clearSession(sessionId: String) {
        val id = sessionId.trim()
        synchronized(lock) {
            if (id.isBlank()) {
                byUrl.clear()
                return
            }
            byUrl.entries.removeAll { it.value.sessionId == id }
        }
    }

    fun clear() {
        synchronized(lock) { byUrl.clear() }
    }
}
