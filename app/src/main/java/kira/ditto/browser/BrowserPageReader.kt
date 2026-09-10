package kira.ditto.browser

import android.content.Context
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kira.ditto.data.BrowserPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gecko-backed reader used by Kimi FetchURL.
 * Search warmup uses Necko speculativeConnect + fetch, not a background tab.
 */
object BrowserPageReader {
    private const val ReadableTimeoutMs = 8_000L
    private const val FetchWaitMs = 6_000L
    private const val MinReadableChars = 280
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val prefetchExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gecko-prefetch").apply { isDaemon = true }
    }

    fun prefetch(
        context: Context,
        prefs: BrowserPreferences,
        vault: BrowserLoginVault,
        history: BrowserHistoryStore,
        urls: List<String>,
        query: String = "",
    ) {
        val ready = prefetchableSearchUrls(urls)
        if (ready.isEmpty()) return
        prefetchExecutor.execute {
            runCatching {
                AetherBrowserRuntime.updatePreferences(prefs)
                AetherBrowserRuntime.loginVault = vault
                AetherBrowserRuntime.historyStore = history
                val components = AetherBrowserRuntime.getBlocking(context)
                ready.forEach { url ->
                    runCatching { components.speculativeConnect(url) }
                }
                ready.forEach { url ->
                    runCatching { indexFromNecko(components, url, query) }
                }
            }
        }
    }

    private fun indexFromNecko(
        components: AetherBrowserRuntime.Components,
        url: String,
        query: String,
    ) {
        if (BrowserResearchGraph.markdownFor(url, query).isNotBlank()) return
        val fetched = components.fetchDocument(url) ?: return
        val text = readableTextFromHtml(fetched.html)
        if (text.length < MinReadableChars) return
        val title = titleFromHtml(fetched.html).ifBlank {
            runCatching { java.net.URI(fetched.url).host }.getOrDefault("")
                .orEmpty()
                .removePrefix("www.")
        }
        val canonical = extractCanonical(fetched.html, fetched.url)
        BrowserResearchGraph.findNearDup(fetched.url, text, canonical)?.let { return }
        BrowserPageLedger.ingest(
            url = fetched.url,
            title = title,
            text = text,
            source = "necko-fetch",
            canonical = canonical,
            recordDeskRead = true,
        )
    }

    fun readMarkdown(
        context: Context,
        prefs: BrowserPreferences,
        vault: BrowserLoginVault,
        history: BrowserHistoryStore,
        url: String,
        query: String = "",
    ): String {
        if (isPlaceholderExampleHost(url)) {
            return browserFetchFailedMarkdown(
                url,
                "placeholder",
                "This is an IANA documentation placeholder, not a real source. Pick a different URL.",
            )
        }
        BrowserDesk.lookupFailed(url)?.let { failed ->
            if (failed.code == "timeout") {
                // Timeouts must not poison this URL for the rest of the turn.
            } else {
                return browserFetchFailedMarkdown(
                    url,
                    failed.code,
                    failed.errmsg.ifBlank { "This URL already failed. Do not fetch it again." },
                )
            }
        }
        BrowserResearchGraph.markdownFor(url, query).takeIf { it.isNotBlank() }?.let { cached ->
            BrowserDesk.noteReadingTarget(url)
            BrowserDesk.recordRead(url, BrowserDesk.lookupRead(url)?.title.orEmpty(), cached)
            return cached
        }
        BrowserDesk.noteReadingTarget(url)
        val key = normalizeBrowsedUrl(url).ifBlank { url.trim() }
        inFlight[key]?.let { pending ->
            val ready = runCatching { pending.get(FetchWaitMs, TimeUnit.MILLISECONDS) }.getOrNull()
            if (!ready.isNullOrBlank()) {
                BrowserDesk.recordRead(url, BrowserDesk.lookupRead(url)?.title.orEmpty(), ready)
                return ready
            }
        }
        return loadAndIndex(context, prefs, vault, history, url, query, key)
    }

    private fun loadAndIndex(
        context: Context,
        prefs: BrowserPreferences,
        vault: BrowserLoginVault,
        history: BrowserHistoryStore,
        url: String,
        query: String,
        key: String,
    ): String {
        val created = CompletableFuture<String>()
        val existing = inFlight.putIfAbsent(key, created)
        // readMarkdown already waited on any in-flight future for this key; waiting again here only
        // doubled the latency before doing the duplicate work anyway.
        val future = existing ?: created
        return try {
            val markdown = actuallyLoad(context, prefs, vault, history, url, query)
            if (existing == null) future.complete(markdown)
            markdown
        } catch (error: Throwable) {
            if (existing == null) future.completeExceptionally(error)
            browserFetchFailedMarkdown(url, "error", error.message ?: "fetch failed")
        } finally {
            if (existing == null) inFlight.remove(key, future)
        }
    }

    private fun actuallyLoad(
        context: Context,
        prefs: BrowserPreferences,
        vault: BrowserLoginVault,
        history: BrowserHistoryStore,
        url: String,
        query: String,
    ): String {
        AetherBrowserRuntime.updatePreferences(prefs)
        AetherBrowserRuntime.loginVault = vault
        AetherBrowserRuntime.historyStore = history
        val components = AetherBrowserRuntime.getBlocking(context)
        val existingTab = components.findTabIdByUrl(url)
        val topicArgs = JSONObject().put("url", url)
        val lastTopic = BrowserTopicGraph.lastTopicId()
        if (lastTopic.isNotBlank()) topicArgs.put("topic_id", lastTopic)
        val target = resolveBrowserTopicTarget(topicArgs, forImages = false)
        val tabId = existingTab.ifBlank {
            AetherBrowserRuntime.ensureTopicTab(context, target)
        }.ifBlank {
            waitForTabId(components, url)
        }
        if (tabId.isNotBlank()) {
            components.selectTab(tabId)
            val currentUrl = components.tabUrl(tabId)
            if (normalizeBrowsedUrl(currentUrl) != normalizeBrowsedUrl(url)) {
                components.navigate(url, tabId)
            }
            BrowserTopicGraph.attachGeckoTab(tabId, lastTopic.ifBlank { url })
        }
        if (tabId.isBlank()) {
            BrowserDesk.finishReading()
            return browserFetchFailedMarkdown(url, "error", "could not open tab")
        }
        val page = runBlocking {
            val waited = waitUntilReadable(components, tabId, url)
            if (isMissingBrowserPage(
                    components.tabUrl(tabId),
                    components.tabTitle(tabId),
                    waited,
                    requestedUrl = url,
                )
            ) {
                return@runBlocking LoadedPage(
                    title = components.tabTitle(tabId),
                    text = "",
                    missing = true,
                )
            }
            val read = components.pageCommand(
                "read",
                JSONObject().put("limit", 8_000),
                timeoutMs = 6_000,
                tabId = tabId,
            )
            val title = read.optString("title").ifBlank { components.tabTitle(tabId) }
            val text = read.optString("text")
                .ifBlank { read.optString("body") }
                .ifBlank { read.optString("content") }
                .ifBlank { firstPageText(read.optJSONArray("pages")) }
                .ifBlank { waited }
            val canonical = read.optString("canonical").ifBlank {
                extractCanonical(read.optString("html"), url)
            }
            val outline = read.optString("outline")
            LoadedPage(
                title = title,
                text = text,
                canonical = canonical,
                outline = outline,
                missing = isMissingBrowserPage(url, title, text, requestedUrl = url),
            )
        }
        if (page.missing) {
            BrowserDesk.recordFailed(url, "not_found", page.title)
            BrowserDesk.finishReading()
            return browserFetchFailedMarkdown(
                url,
                "not_found",
                page.title.ifBlank { "page does not exist" },
            )
        }
        if (page.text.isBlank()) {
            indexFromNecko(components, url, query)
            val fromNecko = BrowserResearchGraph.markdownFor(url, query)
            if (fromNecko.isNotBlank()) return fromNecko
            BrowserDesk.finishReading()
            return browserFetchFailedMarkdown(url, "empty", "page had no readable text")
        }
        BrowserResearchGraph.findNearDup(url, page.text, page.canonical)?.let { dup ->
            BrowserDesk.recordRead(url, dup.title.ifBlank { page.title }, dup.text)
            return BrowserResearchGraph.markdownFor(dup.url, query).ifBlank {
                buildPageMarkdown(dup.title, dup.url, dup.text)
            }
        }
        BrowserPageLedger.ingest(
            url = url,
            title = page.title,
            text = page.text,
            source = "gecko-dom",
            canonical = page.canonical,
            outline = page.outline,
            recordDeskRead = true,
        )
        return BrowserResearchGraph.markdownFor(url, query).ifBlank {
            buildPageMarkdown(page.title, url, page.text)
        }
    }

    private suspend fun waitUntilReadable(
        components: AetherBrowserRuntime.Components,
        tabId: String,
        targetUrl: String,
    ): String {
        val deadline = System.currentTimeMillis() + ReadableTimeoutMs
        val startUrl = normalizeBrowsedUrl(components.tabUrl(tabId))
        val want = normalizeBrowsedUrl(targetUrl)
        var lastText = ""
        while (System.currentTimeMillis() < deadline) {
            val url = components.tabUrl(tabId)
            val title = components.tabTitle(tabId)
            if (isMissingBrowserPage(url, title, requestedUrl = targetUrl)) return ""
            if (isSeedBrowserUrl(url) || url.isBlank()) {
                delay(80)
                continue
            }
            // The old guard skipped the read only while `tabLoading` was true. In the window after
            // navigate() is dispatched and before Gecko flips that flag, the tab is still showing
            // the page we came from — and reading it there is how a Bing results page came to be
            // indexed, titled and cited under an article's URL. Being on the previous page is not
            // "arrived", whatever the loading flag says. A redirect that lands somewhere else is.
            val now = normalizeBrowsedUrl(url)
            val arrived = !looksHttp(targetUrl) ||
                now == want ||
                (now != startUrl && !isBrowserSerpUrl(url))
            if (!arrived) {
                delay(80)
                continue
            }
            if (!components.tabLoading(tabId) && looksHttp(url)) {
                val read = components.pageCommand(
                    "read",
                    JSONObject().put("limit", 1_200),
                    timeoutMs = 3_000,
                    tabId = tabId,
                )
                lastText = read.optString("text")
                    .ifBlank { read.optString("body") }
                    .ifBlank { read.optString("content") }
                    .ifBlank { firstPageText(read.optJSONArray("pages")) }
                if (lastText.isNotBlank() || isMissingBrowserPage(url, title, lastText, requestedUrl = targetUrl)) {
                    return lastText
                }
            }
            delay(120)
        }
        return lastText
    }

    private fun waitForTabId(
        components: AetherBrowserRuntime.Components,
        url: String,
    ): String {
        repeat(20) {
            val id = components.findTabIdByUrl(url)
            if (id.isNotBlank()) return id
            runBlocking { delay(50) }
        }
        return components.findTabIdByUrl(url)
    }

    private fun extractCanonical(html: String, fallback: String): String {
        if (html.isBlank()) return fallback
        val match = Regex(
            """rel\s*=\s*["']canonical["'][^>]*href\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)
            ?: Regex(
                """href\s*=\s*["']([^"']+)["'][^>]*rel\s*=\s*["']canonical["']""",
                RegexOption.IGNORE_CASE,
            ).find(html)
        return match?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { fallback }
    }

    private fun firstPageText(pages: JSONArray?): String {
        if (pages == null || pages.length() == 0) return ""
        return pages.optJSONObject(0)?.optString("text").orEmpty()
    }

    private fun looksHttp(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    private data class LoadedPage(
        val title: String,
        val text: String,
        val canonical: String = "",
        val outline: String = "",
        val missing: Boolean = false,
    )
}

internal fun prefetchableSearchUrls(urls: List<String>, limit: Int = 3): List<String> =
    urls
        .filter(::isPrefetchableBrowserUrl)
        .distinctBy(::normalizeBrowsedUrl)
        .take(limit)

/** Tags whose contents are code or graphics, never prose. Skipped wholesale. */
private val HtmlSkipTags = setOf(
    "script", "style", "noscript", "svg", "template", "iframe", "canvas", "select", "datalist",
)

/** Tags that end a line of prose. Emitting a break here is what gives the text paragraphs. */
private val HtmlBlockTags = setOf(
    "p", "div", "section", "article", "header", "footer", "nav", "aside", "main", "br", "hr",
    "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "tr", "td", "th", "table", "thead",
    "tbody", "blockquote", "pre", "figure", "figcaption", "form", "fieldset", "label", "dl",
    "dt", "dd", "address", "details", "summary", "body", "title", "option",
)

/**
 * HTML to readable text in one pass.
 *
 * The previous implementation ran eight regexes over the whole document, each allocating another
 * copy of it — on a 1 MB page that is eight megabyte-scale string builds before a single character
 * is used, and the result was capped nowhere. It also collapsed every newline, so the text arrived
 * downstream as one unbroken paragraph: `chunkPersonaKnowledgeText` splits on blank lines, found
 * none, and fell back to blind 900-character slices, which is why page-graph headings read like
 * fragments of a sentence.
 *
 * This walks the document once, skips script/style/svg bodies by jumping to their closing tag,
 * decodes entities inline, collapses whitespace as it goes, emits a paragraph break at block
 * boundaries, and stops as soon as [maxChars] of text exist.
 */
internal fun readableTextFromHtml(html: String, maxChars: Int = 60_000): String {
    if (html.isBlank()) return ""
    val length = html.length
    val out = StringBuilder(minOf(length / 2 + 32, maxChars + 64))
    var index = 0
    var pendingBreak = false
    var pendingSpace = false

    fun separate() {
        if (out.isEmpty()) {
            pendingBreak = false
            pendingSpace = false
            return
        }
        when {
            pendingBreak -> out.append("\n\n")
            pendingSpace -> out.append(' ')
        }
        pendingBreak = false
        pendingSpace = false
    }

    while (index < length && out.length < maxChars) {
        val ch = html[index]
        if (ch == '<') {
            val close = html.indexOf('>', index + 1)
            if (close < 0) break
            var cursor = index + 1
            val closing = cursor < length && html[cursor] == '/'
            if (closing) cursor++
            val nameStart = cursor
            while (cursor < close && !html[cursor].isWhitespace() &&
                html[cursor] != '/' && html[cursor] != '>'
            ) {
                cursor++
            }
            val name = html.substring(nameStart, cursor).lowercase()
            if (!closing && name in HtmlSkipTags) {
                val endTag = "</" + name
                val endIndex = html.indexOf(endTag, close, ignoreCase = true)
                index = if (endIndex < 0) {
                    length
                } else {
                    val endClose = html.indexOf('>', endIndex)
                    if (endClose < 0) length else endClose + 1
                }
                pendingBreak = true
                continue
            }
            if (name in HtmlBlockTags) pendingBreak = true
            index = close + 1
            continue
        }
        if (ch == '&') {
            val semi = html.indexOf(';', index + 1)
            if (semi in (index + 2)..(index + 10)) {
                val decoded = decodeHtmlEntity(html.substring(index + 1, semi))
                if (decoded != null) {
                    if (decoded.isBlank()) {
                        pendingSpace = true
                    } else {
                        separate()
                        out.append(decoded)
                    }
                    index = semi + 1
                    continue
                }
            }
        }
        if (ch.isWhitespace()) {
            pendingSpace = true
            index++
            continue
        }
        separate()
        out.append(ch)
        index++
    }
    return out.toString().trim()
}

private fun decodeHtmlEntity(body: String): String? {
    if (body.isEmpty() || body.length > 9) return null
    if (body[0] == '#') {
        val code = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
            body.substring(2).toIntOrNull(16)
        } else {
            body.substring(1).toIntOrNull()
        }
        if (code == null || code <= 0 || code > 0x10FFFF) return null
        return runCatching { String(Character.toChars(code)) }.getOrNull()
    }
    return when (body.lowercase()) {
        "nbsp", "ensp", "emsp", "thinsp" -> " "
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        "hellip" -> "\u2026"
        "mdash" -> "\u2014"
        "ndash" -> "\u2013"
        "middot" -> "\u00b7"
        "lsquo" -> "\u2018"
        "rsquo" -> "\u2019"
        "ldquo" -> "\u201c"
        "rdquo" -> "\u201d"
        "laquo" -> "\u00ab"
        "raquo" -> "\u00bb"
        "times" -> "\u00d7"
        "copy" -> "\u00a9"
        "reg" -> "\u00ae"
        "trade" -> "\u2122"
        "yen" -> "\u00a5"
        "euro" -> "\u20ac"
        "pound" -> "\u00a3"
        "deg" -> "\u00b0"
        "bull" -> "\u2022"
        else -> null
    }
}

/** The title lives in <head>; scanning a megabyte of body for it is wasted work. */
internal fun titleFromHtml(html: String): String {
    val head = if (html.length > 120_000) html.substring(0, 120_000) else html
    val match = Regex("(?is)<title[^>]*>(.*?)</title>").find(head) ?: return ""
    val raw = match.groupValues.getOrNull(1).orEmpty()
    return readableTextFromHtml(raw, maxChars = 400)
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(180)
}
