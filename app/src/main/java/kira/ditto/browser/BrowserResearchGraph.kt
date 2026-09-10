package kira.ditto.browser

import java.net.URI

data class IndexedBrowserPage(
    val url: String,
    val canonical: String,
    val title: String,
    val text: String,
    val contentHash: String,
    val simhash: Long,
    val origin: String,
    val passages: List<AgentPassage>,
    val indexedAtMillis: Long,
)

/**
 * Task-scoped lexical page graph. Same-origin NearDup is simhash; cross-origin
 * merge requires a rel=canonical (or equivalent) URL.
 */
object BrowserResearchGraph {
    private const val MaxPages = 64
    private val lock = Any()
    private val pages = LinkedHashMap<String, IndexedBrowserPage>()

    fun clear() {
        synchronized(lock) { pages.clear() }
    }

    fun index(
        url: String,
        title: String,
        text: String,
        canonical: String = "",
        outline: String = "",
    ): IndexedBrowserPage? {
        val key = normalizeBrowsedUrl(url).ifBlank { url.trim() }
        if (key.isBlank() || text.isBlank()) return null
        val canon = normalizeBrowsedUrl(canonical).ifBlank { key }
        val hashed = AgentIndex.contentHash(text)
        // Chunk once per version, not once per touch. Chunking plus the BM25 build is the expensive
        // half of indexing, and a turn revisits the same page repeatedly - a recall, a re-read after
        // a click, a second subagent landing on the same URL. The content hash is exactly the key
        // that says "this is the same bytes", so hitting it means the previous work is still valid;
        // only indexedAtMillis moves. Same trade Continuity makes when it compacts centrally: pay
        // once for a version, then hand the result out.
        val alreadyChunked = synchronized(lock) {
            pages[key]?.takeIf { it.contentHash == hashed }
                ?: pages.values.firstOrNull { it.contentHash == hashed }
        }
        val page = IndexedBrowserPage(
            url = url.trim(),
            canonical = canon,
            title = title.trim(),
            text = text,
            contentHash = hashed,
            simhash = alreadyChunked?.simhash ?: AgentIndex.simhash(text),
            origin = originOf(url),
            passages = alreadyChunked?.passages ?: AgentIndex.chunkPage(
                title = title,
                text = text,
                url = url,
                outline = outline,
            ),
            indexedAtMillis = System.currentTimeMillis(),
        )
        synchronized(lock) {
            pages.remove(key)
            pages[key] = page
            while (pages.size > MaxPages) {
                val oldest = pages.keys.firstOrNull() ?: break
                pages.remove(oldest)
            }
        }
        return page
    }

    fun lookup(url: String): IndexedBrowserPage? {
        val key = normalizeBrowsedUrl(url).ifBlank { url.trim() }
        if (key.isBlank()) return null
        synchronized(lock) {
            pages[key]?.let { return it }
            return pages.values.firstOrNull { page ->
                page.canonical.isNotBlank() && page.canonical == key
            }
        }
    }

    fun findByContentHash(hash: String): IndexedBrowserPage? {
        if (hash.isBlank()) return null
        synchronized(lock) {
            return pages.values.firstOrNull { it.contentHash == hash }
        }
    }

    fun findNearDup(url: String, text: String, canonical: String = ""): IndexedBrowserPage? {
        if (text.isBlank()) return null
        val hash = AgentIndex.contentHash(text)
        findByContentHash(hash)?.let { return it }
        val origin = originOf(url)
        val fingerprint = AgentIndex.simhash(text)
        val canon = normalizeBrowsedUrl(canonical)
        synchronized(lock) {
            pages.values.forEach { page ->
                val sameOrigin = page.origin.isNotBlank() && page.origin == origin
                val canonicalMatch = canon.isNotBlank() &&
                    page.canonical.isNotBlank() &&
                    page.canonical == canon
                if (!sameOrigin && !canonicalMatch) return@forEach
                if (canonicalMatch && !sameOrigin) return page
                if (sameOrigin && AgentIndex.hamming(page.simhash, fingerprint) <= 3) return page
            }
        }
        return null
    }

    fun markdownFor(url: String, query: String = ""): String {
        val page = lookup(url) ?: return ""
        val passages = if (query.isBlank()) {
            page.passages
        } else {
            AgentIndex.rank(query, page.passages).map { it.passage }.ifEmpty { page.passages }
        }
        if (passages.isEmpty()) {
            return buildPageMarkdown(page.title, page.url, page.text)
        }
        return buildString {
            if (page.title.isNotBlank()) {
                append("# ")
                append(page.title)
                append("\n\n")
            }
            append("url: ")
            append(page.url)
            append("\n\n")
            passages.forEach { passage ->
                if (passage.heading.isNotBlank() && passage.heading != page.title) {
                    append("## ")
                    append(passage.heading)
                    append("\n\n")
                }
                append(passageMarker(passage.ordinal))
                append(passage.text.trim())
                append("\n\n")
            }
        }.trim()
    }

    fun passagesSnippet(url: String, query: String, limitChars: Int = 900): String {
        val page = lookup(url) ?: return ""
        val ranked = AgentIndex.rank(query, page.passages).map { it.passage }.ifEmpty { page.passages }
        return ranked.joinToString("\n\n") { passageMarker(it.ordinal) + it.text }.trim().take(limitChars)
    }

    /** The passage a citation anchored to, or null when the page is gone or the page was re-chunked shorter. */
    fun passageAt(url: String, ordinal: Int): AgentPassage? {
        if (ordinal < 0) return null
        val page = lookup(url) ?: return null
        return page.passages.firstOrNull { it.ordinal == ordinal }
    }
}

/**
 * Prefix that makes a passage addressable in rendered page text.
 *
 * Both the model (so it can say which part of a page it used) and the host citation resolver read
 * these back, so the format lives in one place.
 */
internal fun passageMarker(ordinal: Int): String = if (ordinal < 0) "" else "[p$ordinal] "

private val PassageMarkerRegex = Regex("""^\s*\[p(\d{1,4})]\s*""")

internal fun parsePassageMarker(text: String): Int =
    PassageMarkerRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1

internal fun stripPassageMarker(text: String): String = text.replaceFirst(PassageMarkerRegex, "")

internal fun originOf(url: String): String {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return ""
    val host = uri.host.orEmpty().lowercase().removePrefix("www.")
    if (host.isBlank()) return ""
    val scheme = uri.scheme.orEmpty().lowercase().ifBlank { "https" }
    return "$scheme://$host"
}

internal fun buildPageMarkdown(title: String, url: String, text: String): String = buildString {
    if (title.isNotBlank()) {
        append("# ")
        append(title)
        append("\n\n")
    }
    if (url.isNotBlank()) {
        append("url: ")
        append(url)
        append("\n\n")
    }
    append(text.trim())
}
