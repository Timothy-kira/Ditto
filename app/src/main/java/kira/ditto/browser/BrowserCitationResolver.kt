package kira.ditto.browser

import kira.ditto.data.KnowledgeCitation
import kira.ditto.data.extractMarkdownWebCitations
import kira.ditto.data.markdownReferencedUrls
import kira.ditto.data.markdownSourceHost
import kira.ditto.data.markdownSourceHostLabel
import kira.ditto.data.stripMarkdownWebSourceSection

/**
 * How precisely a citation could be pinned to the page it came from.
 *
 * The three levels are cumulative: [Passage] implies the page matched, [Quote] implies the passage
 * did. Nothing is ever reported above the level actually established, so a card badge never claims
 * more than the resolver could prove.
 */
enum class CitationConfidence { Page, Passage, Quote }

enum class CitationKind { Web, Image }

/**
 * One "this answer leaned on this source" fact, addressed finely enough to survive the session.
 *
 * The anchor is deliberately layered - topic, then normalized URL, then passage, then the shared
 * sentence - so a consumer can take it at whatever precision it needs and a later turn can still
 * check whether the anchor still holds after a page changes.
 */
data class BrowserCitation(
    val topicId: String = "",
    val pageKey: String,
    val url: String,
    val title: String = "",
    val contentHash: String = "",
    val passageOrdinal: Int = -1,
    val quote: String = "",
    val quoteHash: String = "",
    val confidence: CitationConfidence = CitationConfidence.Page,
    val kind: CitationKind = CitationKind.Web,
)

/** Shortest run of characters worth calling a quote. Below this, overlap is coincidence. */
internal const val MinQuoteChars = 12
internal const val MaxQuoteChars = 160

/**
 * Work out which of a turn's browsed pages the finished answer actually used.
 *
 * The host does this by reading the answer rather than asking the model to declare it, so it works
 * on every answer including ones already in history. Page level comes from URL matching; passage
 * and quote come from running the answer's own sentences back through the BM25 index that ranked
 * the page in the first place.
 *
 * @param topics the turn's research groups, keyed by topic id
 * @param page resolves a normalized URL to the indexed page, normally [BrowserResearchGraph.lookup]
 */
fun resolveBrowserCitations(
    answerMarkdown: String,
    topics: Map<String, BrowserDeskPreview>,
    page: (pageKey: String) -> IndexedBrowserPage? = { BrowserResearchGraph.lookup(it) },
): List<BrowserCitation> {
    if (answerMarkdown.isBlank() || topics.isEmpty()) return emptyList()
    val body = stripMarkdownWebSourceSection(answerMarkdown)
    val footnotes = extractMarkdownWebCitations(answerMarkdown)
    val referenced = referencedPageKeys(answerMarkdown, footnotes)
    if (referenced.isEmpty()) return emptyList()
    val sentences = splitSentences(body)

    val resolved = LinkedHashMap<String, BrowserCitation>()
    topics.forEach { (topicId, preview) ->
        preview.hits.forEach { hit ->
            val key = normalizeBrowsedUrl(hit.url).ifBlank { hit.url.trim() }
            if (key.isBlank() || key in resolved) return@forEach
            val reference = referenced[key] ?: return@forEach
            resolved[key] = anchorCitation(
                topicId = topicId,
                hit = hit,
                pageKey = key,
                reference = reference,
                footnotes = footnotes,
                sentences = sentences,
                page = page,
            )
        }
        preview.images.forEach { image ->
            val source = image.sourceUrl.ifBlank { return@forEach }
            val key = normalizeBrowsedUrl(source).ifBlank { source.trim() }
            if (key.isBlank() || key in resolved) return@forEach
            // An image is cited by appearing in the answer, not by its source page being linked.
            if (!answerMarkdown.contains(image.url)) return@forEach
            resolved[key] = BrowserCitation(
                topicId = topicId,
                pageKey = key,
                url = source,
                title = image.caption.ifBlank { image.alt },
                quote = image.caption,
                quoteHash = hashOf(image.caption),
                confidence = CitationConfidence.Page,
                kind = CitationKind.Image,
            )
        }
    }
    return resolved.values.toList()
}

/** What the answer said about a page, gathered before any per-page work. */
private data class PageReference(
    val url: String,
    val anchorText: String,
    val footnoteIndex: Int,
)

private fun referencedPageKeys(
    answerMarkdown: String,
    footnotes: List<KnowledgeCitation>,
): Map<String, PageReference> {
    val byKey = LinkedHashMap<String, PageReference>()
    footnotes.forEach { citation ->
        val key = normalizeBrowsedUrl(citation.url).ifBlank { citation.url.trim() }
        if (key.isBlank()) return@forEach
        byKey[key] = PageReference(citation.url, citation.sourceName, citation.index)
    }
    markdownReferencedUrls(answerMarkdown).forEach { (url, anchor) ->
        val key = normalizeBrowsedUrl(url).ifBlank { url.trim() }
        if (key.isBlank() || key in byKey) return@forEach
        byKey[key] = PageReference(url, anchor, -1)
    }
    return byKey
}

private fun anchorCitation(
    topicId: String,
    hit: BrowserDeskHit,
    pageKey: String,
    reference: PageReference,
    footnotes: List<KnowledgeCitation>,
    sentences: List<String>,
    page: (String) -> IndexedBrowserPage?,
): BrowserCitation {
    val base = BrowserCitation(
        topicId = topicId,
        pageKey = pageKey,
        url = reference.url.ifBlank { hit.url },
        title = hit.title.ifBlank { reference.anchorText },
        confidence = CitationConfidence.Page,
    )
    val indexed = page(pageKey) ?: return base
    val carrying = sentencesCarrying(sentences, reference, hit, footnotes)
    if (carrying.isEmpty()) return base.copy(contentHash = indexed.contentHash)
    val best = AgentIndex.rank(carrying.joinToString(" "), indexed.passages, limit = 1)
        .firstOrNull()
        ?.passage
        ?: return base.copy(contentHash = indexed.contentHash)
    val quote = carrying.asSequence()
        .map { sentence -> longestSharedQuote(sentence, best.text) }
        .maxByOrNull { it.length }
        .orEmpty()
    return base.copy(
        contentHash = indexed.contentHash,
        passageOrdinal = best.ordinal,
        quote = quote,
        quoteHash = hashOf(quote),
        confidence = if (quote.length >= MinQuoteChars) {
            CitationConfidence.Quote
        } else {
            CitationConfidence.Passage
        },
    )
}

/**
 * The answer's own sentences that point at this page.
 *
 * A sentence qualifies by carrying the page's footnote marker, by naming the site, or by containing
 * the link itself. These become the query that locates the passage, so precision here is what
 * separates "somewhere on this page" from "this paragraph".
 */
private fun sentencesCarrying(
    sentences: List<String>,
    reference: PageReference,
    hit: BrowserDeskHit,
    footnotes: List<KnowledgeCitation>,
): List<String> {
    val marker = footnotes.firstOrNull { it.index == reference.footnoteIndex }
        ?.let { "[[${it.index}]]" }
        .orEmpty()
    val altMarker = if (reference.footnoteIndex > 0) "[${reference.footnoteIndex}]" else ""
    val needles = listOfNotNull(
        reference.url.takeIf { it.isNotBlank() },
        reference.anchorText.takeIf { it.length >= 2 },
        hit.title.takeIf { it.length >= 2 },
        markdownSourceHostLabel(reference.url).takeIf { it.length >= 2 },
        markdownSourceHost(reference.url).takeIf { it.length >= 4 },
    ).distinct()
    val matched = sentences.filter { sentence ->
        (marker.isNotEmpty() && sentence.contains(marker)) ||
            (altMarker.isNotEmpty() && sentence.contains(altMarker)) ||
            needles.any { sentence.contains(it, ignoreCase = true) }
    }
    if (matched.isNotEmpty()) return matched
    // A single-source answer often names the site once and then just writes prose. When the answer
    // cites exactly this page and nothing else, the whole body is the sentence set.
    return if (footnotes.size <= 1) sentences else emptyList()
}

private val SentenceBreak = Regex("""(?<=[。！？；.!?;])\s*|\n+""")

internal fun splitSentences(text: String): List<String> =
    text.split(SentenceBreak)
        .map { it.trim() }
        .filter { it.length >= 4 }

/**
 * The longest stretch the answer sentence and the source passage literally share.
 *
 * The quote a citation shows has to be text the reader can actually find on the page, so this looks
 * for a genuine shared substring rather than a paraphrase. Three decisions shape it:
 *
 * **Normalise to compare, report the original.** The answer and the page differ in ways that carry
 * no meaning - full-width against half-width punctuation, a space the model inserted between CJK and
 * Latin, collapsed whitespace. Comparing raw characters misses matches for those reasons alone. So
 * the search runs over a normalised projection and an index map carries the result back to the
 * passage's own characters: a quote spelled differently from the page is not a quote.
 *
 * **A rolling hash, not a DP table.** Passages run ~600 characters and sentences ~100, so an O(n*m)
 * table is only about 60k cells - fine once, but this runs per cited page per turn on a phone.
 * Binary searching the length with a Rabin-Karp set is O((n+m)·log n) and allocates no table. Every
 * hash hit is verified against the real substring, so a collision can never fabricate a quote the
 * page does not contain.
 *
 * **Trim to word boundaries, where words have boundaries.** A match starting mid-word reads as
 * damage even when it is technically correct. CJK is written without delimiters, so a CJK run is
 * left alone - trimming it to punctuation would usually discard everything. If trimming drops the
 * result below [MinQuoteChars] the answer is "", and the caller reports [CitationConfidence.Passage]
 * instead, which is true either way.
 */
internal fun longestSharedQuote(sentence: String, passage: String): String {
    if (sentence.isBlank() || passage.isBlank()) return ""
    val (needle, _) = normalizeForQuote(sentence)
    val (hay, hayIndex) = normalizeForQuote(passage)
    if (needle.length < MinQuoteChars || hay.length < MinQuoteChars) return ""

    val maxLength = minOf(needle.length, hay.length, MaxQuoteChars)
    if (maxLength < MinQuoteChars) return ""

    var low = MinQuoteChars
    var high = maxLength
    var bestStart = -1
    var bestLength = 0
    while (low <= high) {
        val mid = (low + high) / 2
        val match = sharedSubstringOfLength(needle, hay, mid)
        if (match != null) {
            bestStart = match
            bestLength = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    if (bestLength < MinQuoteChars || bestStart < 0) return ""

    val from = hayIndex[bestStart]
    val to = hayIndex[bestStart + bestLength - 1] + 1
    val trimmed = trimToWordBoundaries(passage.substring(from, to))
    return if (trimmed.length >= MinQuoteChars) trimmed else ""
}

/**
 * Collapse the differences that are not differences, keeping a map back to the original.
 *
 * The map is the whole point. Without it a match found on normalised text could only be reported in
 * normalised form, which is not what the page says.
 */
private fun normalizeForQuote(text: String): Pair<String, IntArray> {
    val builder = StringBuilder(text.length)
    val indices = IntArray(text.length)
    var lastWasSpace = true
    for (index in text.indices) {
        val folded = foldQuoteChar(text[index])
        if (folded == ' ') {
            if (lastWasSpace) continue
            lastWasSpace = true
        } else {
            lastWasSpace = false
        }
        indices[builder.length] = index
        builder.append(folded)
    }
    while (builder.isNotEmpty() && builder.last() == ' ') builder.setLength(builder.length - 1)
    return builder.toString() to indices
}

/** Full-width punctuation, curly quotes and every kind of space fold onto one representative. */
private fun foldQuoteChar(raw: Char): Char = when (raw) {
    '　', '\t', '\n', '\r', ' ' -> ' '
    '，' -> ','
    '。' -> '.'
    '：' -> ':'
    '；' -> ';'
    '（' -> '('
    '）' -> ')'
    '！' -> '!'
    '？' -> '?'
    '“', '”', '‘', '’' -> '"'
    else -> raw.lowercaseChar()
}

private const val QuoteHashBase = 131L
private const val QuoteHashMod = 1_000_000_007L

/** Start index in [hay] of some length-[length] substring shared with [needle], or null. */
private fun sharedSubstringOfLength(needle: String, hay: String, length: Int): Int? {
    if (length <= 0 || length > needle.length || length > hay.length) return null
    // B^length, not B^(length-1): the oldest character is dropped *after* the newest is folded in,
    // so at that moment the window holds length+1 characters and the one leaving carries B^length.
    var power = 1L
    repeat(length) { power = power * QuoteHashBase % QuoteHashMod }

    val needleHashes = HashMap<Long, MutableList<Int>>()
    var hash = 0L
    for (index in needle.indices) {
        hash = (hash * QuoteHashBase + needle[index].code) % QuoteHashMod
        if (index >= length) {
            hash = (hash - needle[index - length].code * power % QuoteHashMod + QuoteHashMod) % QuoteHashMod
        }
        if (index >= length - 1) {
            needleHashes.getOrPut(hash) { mutableListOf() }.add(index - length + 1)
        }
    }

    hash = 0L
    for (index in hay.indices) {
        hash = (hash * QuoteHashBase + hay[index].code) % QuoteHashMod
        if (index >= length) {
            hash = (hash - hay[index - length].code * power % QuoteHashMod + QuoteHashMod) % QuoteHashMod
        }
        if (index < length - 1) continue
        val start = index - length + 1
        val candidates = needleHashes[hash] ?: continue
        for (candidate in candidates) {
            if (hay.regionMatches(start, needle, candidate, length)) return start
        }
    }
    return null
}

/**
 * Pull the ends back to word boundaries, but only where words have boundaries.
 *
 * CJK is written without delimiters, so a run of Han characters is returned untouched - trimming it
 * to the nearest punctuation would usually leave nothing at all.
 */
private fun trimToWordBoundaries(raw: String): String {
    var start = 0
    var end = raw.length
    while (start < end && raw[start].isWhitespace()) start += 1
    while (end > start && raw[end - 1].isWhitespace()) end -= 1
    if (start >= end) return ""
    if (!isCjkChar(raw[start])) {
        var cursor = start
        while (cursor < end && !raw[cursor].isWhitespace() && !isCjkChar(raw[cursor])) cursor += 1
        if (cursor < end) {
            start = cursor
            while (start < end && raw[start].isWhitespace()) start += 1
        }
    }
    if (end > start && !isCjkChar(raw[end - 1])) {
        var cursor = end
        while (cursor > start && !raw[cursor - 1].isWhitespace() && !isCjkChar(raw[cursor - 1])) cursor -= 1
        if (cursor > start) {
            end = cursor
            while (end > start && raw[end - 1].isWhitespace()) end -= 1
        }
    }
    return raw.substring(start, end).trim()
}

private fun isCjkChar(value: Char): Boolean {
    val code = value.code
    return (code in 0x3040..0x30ff) ||
        (code in 0x3400..0x9fff) ||
        (code in 0xac00..0xd7af) ||
        (code in 0xf900..0xfaff)
}

/**
 * Whether the page has moved on since this citation was made.
 *
 * "Stale" is a claim, so it is only made when both hashes are known and they differ. A missing hash
 * on either side means the question cannot be answered, and an unanswerable question reports `false`
 * rather than guessing - the same discipline as the confidence levels above, where the resolver
 * never reports more than it can prove.
 *
 * What this earns is the cheap half of freshness checking: comparing two hashes says whether a
 * source changed without downloading the source. That is what makes revisiting a few dozen
 * citations weeks later a table scan instead of a few dozen page fetches.
 *
 * Being stale is not being wrong. The stored version is still exactly what the answer read, so the
 * citation stays resolvable; what the caller shows is "the source has changed since", and the reader
 * decides. Nothing here silently re-points a citation at newer text.
 */
fun citationIsStale(citationContentHash: String, latestContentHash: String): Boolean =
    citationContentHash.isNotBlank() &&
        latestContentHash.isNotBlank() &&
        citationContentHash != latestContentHash

/** Convenience over [citationIsStale] for the in-memory graph, where the page may not be indexed. */
fun citationIsStale(citation: BrowserCitation, latest: IndexedBrowserPage?): Boolean =
    citationIsStale(citation.contentHash, latest?.contentHash.orEmpty())

internal fun hashOf(text: String): String =
    if (text.isBlank()) "" else AgentIndex.contentHash(text)
