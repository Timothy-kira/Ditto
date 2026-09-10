package kira.ditto.browser

import kira.ditto.data.markdownSourceHost
import java.net.URI

/**
 * Where a remembered fact came from, and therefore how far it can be trusted.
 *
 * This is a taint label, not a provenance note. [TaskSummary] and [OriginDossier] are distilled from
 * web pages, which means an attacker who controls a page controls their content; the whole point of
 * carrying the label is that a memory derived from a page must not be able to authorise an action
 * later just because it has been laundered through storage.
 */
enum class MemorySource {
    /** The user said it. The only source that is trusted outright. */
    UserStated,

    /** Distilled by the browser subagent from pages it read. Untrusted content, trusted shape. */
    TaskSummary,

    /** A durable fact about how a site behaves, confirmed by operating it. Untrusted content. */
    OriginDossier,

    /**
     * Synthesised by the host from citations because the model wrote no memo.
     *
     * Useful locally and never uploaded: it is a mechanical restatement of what was read, not a
     * judgement about what was worth keeping, and the upload is irreversible.
     */
    HostDerived,
}

/** Whether a row has been sent to long-term memory, and may never be sent twice. */
enum class MemoryUploadState { Pending, Uploaded, Skipped }

/**
 * What one finished piece of browser research is worth remembering as.
 *
 * The unit is the task, not the URL - a page is evidence, a task is a conclusion. The URLs are not
 * stored here on purpose: `browser_citations` already holds which pages a topic leaned on, keyed by
 * `(sessionId, topicId)`, so `citationsForTopic` *is* the working set for "pick this research back
 * up". Duplicating it here would create a second answer to the same question.
 */
data class BrowserTaskMemo(
    val summary: String,
    val tags: List<String> = emptyList(),
    val openTodos: String = "",
    val salience: Double = 0.0,
    val origins: List<OriginFact> = emptyList(),
    val source: MemorySource = MemorySource.TaskSummary,
)

/** One durable thing learned about operating a site, keyed by host. */
data class OriginFact(val origin: String, val fact: String)

/** The block the browser subagent appends to its closing report. */
const val BrowserTaskMemoHeader = "BROWSER_TASK_MEMO:"

private const val MemoHeader = BrowserTaskMemoHeader

private const val MaxSummaryChars = 100
private const val MaxTags = 3
private const val MaxOriginFacts = 4

/**
 * Pull the memo out of a subagent's closing text.
 *
 * Forgiving about shape, strict about content: a missing field degrades that field rather than
 * failing the parse, because a memo that is 80% there is still worth keeping and a model that
 * drifts one line off format should not silently cost the user their memory. Returns null only when
 * there is no memo at all, or when it carries no summary - a memo with nothing to say is not a memo.
 */
fun parseBrowserTaskMemo(text: String): BrowserTaskMemo? {
    val start = text.indexOf(MemoHeader)
    if (start < 0) return null
    val body = text.substring(start + MemoHeader.length)
    val fields = LinkedHashMap<String, String>()
    for (rawLine in body.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) {
            // A blank line ends the block only once something has been read; models like to put one
            // straight after the header.
            if (fields.isNotEmpty()) break else continue
        }
        val name = line.substringBefore(':', missingDelimiterValue = "").trim().lowercase()
        if (name !in MemoFieldNames) break
        fields[name] = line.substringAfter(':').trim()
    }
    val summary = fields["summary"].orEmpty().trim().take(MaxSummaryChars)
    if (summary.isBlank()) return null
    return BrowserTaskMemo(
        summary = summary,
        tags = parseTags(fields["tags"].orEmpty()),
        openTodos = fields["open"].orEmpty().takeUnless { it.equals("none", ignoreCase = true) }.orEmpty(),
        salience = parseSalience(fields["salience"].orEmpty()),
        origins = parseOrigins(fields["origins"].orEmpty()),
        source = MemorySource.TaskSummary,
    )
}

private val MemoFieldNames = setOf("summary", "tags", "open", "salience", "origins")

/**
 * Remove the memo block from text the user will read.
 *
 * The memo is a channel between the subagent and the host. Leaving it in the answer would show the
 * reader the machinery, the same way the source-attribution block is stripped before display.
 */
fun stripBrowserTaskMemo(text: String): String {
    val start = text.indexOf(MemoHeader)
    if (start < 0) return text
    val before = text.substring(0, start)
    val after = text.substring(start + MemoHeader.length)
    val consumed = after.lineSequence()
        .takeWhile { line ->
            val trimmed = line.trim()
            trimmed.isEmpty() ||
                trimmed.substringBefore(':', missingDelimiterValue = "").trim().lowercase() in MemoFieldNames
        }
        .sumOf { it.length + 1 }
    return (before + after.drop(consumed)).trim()
}

private fun parseTags(raw: String): List<String> =
    raw.split(',', '，', '、')
        .map { it.trim().trim('#').lowercase() }
        .filter { it.isNotBlank() && it.length <= 24 }
        .distinct()
        .take(MaxTags)

private fun parseSalience(raw: String): Double {
    val value = raw.trim().removeSuffix("%").toDoubleOrNull() ?: return 0.0
    val scaled = if (value > 1.0) value / 100.0 else value
    return scaled.coerceIn(0.0, 1.0)
}

private fun parseOrigins(raw: String): List<OriginFact> =
    raw.split('|')
        .mapNotNull { entry ->
            val host = entry.substringBefore(':', missingDelimiterValue = "").trim().lowercase()
            val fact = entry.substringAfter(':', missingDelimiterValue = "").trim()
            if (host.isBlank() || fact.isBlank()) null else OriginFact(host, fact)
        }
        .take(MaxOriginFacts)

/**
 * The memo to keep when the model wrote none.
 *
 * Built from what the turn can prove it did - the pages the answer actually cited, and the quote it
 * leaned on. It is [MemorySource.HostDerived] and carries a low salience by construction, because it
 * records that research happened, not that it mattered. That distinction is what keeps it out of the
 * upload path in [shouldUploadMemo].
 */
fun synthesizeBrowserTaskMemo(
    citations: List<BrowserCitation>,
    answerOpening: String,
): BrowserTaskMemo? {
    if (citations.isEmpty()) return null
    val opening = answerOpening.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
        .orEmpty()
    val titles = citations.mapNotNull { it.title.takeIf(String::isNotBlank) }.distinct().take(3)
    val summary = opening.ifBlank { titles.joinToString("; ") }.take(MaxSummaryChars)
    if (summary.isBlank()) return null
    return BrowserTaskMemo(
        summary = summary,
        tags = citations.mapNotNull { markdownSourceHost(it.url).takeIf(String::isNotBlank) }
            .distinct()
            .take(MaxTags),
        salience = 0.2,
        source = MemorySource.HostDerived,
    )
}

/** The salience at or above which a memo is worth putting into long-term memory at all. */
const val MemoSalienceThreshold = 0.6

/** How many tasks sharing a tag make it a line of interest rather than a one-off. */
const val MemoClusterRecurrenceThreshold = 2

/**
 * Whether this memo may be sent to long-term memory.
 *
 * Three conditions, all required, and the reason they are this conservative is that the upload has
 * no inverse: EverMe takes content and extracts from it, with no client-side entry id, no upsert and
 * no delete. Anything sent is sent. So deduplication has to happen here, before the wire, and the
 * bar for "worth keeping forever" has to sit above the bar for "worth keeping this week".
 *
 * 1. Salient enough to still matter later - a one-off lookup is not.
 * 2. Not [MemorySource.HostDerived] - the host's fallback records that work happened, which is not
 *    the same as judging it worth keeping, and a judgement is what is being uploaded.
 * 3. Recurrent - some tag here has come up in at least [MemoClusterRecurrenceThreshold] tasks. A
 *    single-instance cluster is an observation; a repeated one is an interest. This is the condition
 *    that makes the whole gate self-correcting, because it costs nothing to wait: a topic that
 *    matters comes back, and the memo is still sitting locally when it does.
 *
 * @param tagOccurrences how many recent tasks carry each tag, this memo's own task included
 */
fun shouldUploadMemo(
    memo: BrowserTaskMemo,
    tagOccurrences: Map<String, Int>,
    salienceThreshold: Double = MemoSalienceThreshold,
): Boolean {
    if (memo.salience < salienceThreshold) return false
    if (memo.source == MemorySource.HostDerived) return false
    return memo.tags.any { (tagOccurrences[it] ?: 0) >= MemoClusterRecurrenceThreshold }
}

/**
 * Whether a site fact may be uploaded: on the second confirmation, never the first.
 *
 * One observation of "this site needs a login" can just as easily be one session that happened to be
 * signed out. Waiting for a second costs a session and prevents fossilising a misread into permanent
 * memory - and again, permanent means permanent.
 */
fun shouldUploadOriginFact(confirmCount: Int): Boolean = confirmCount >= 2

/**
 * Strip credentials and identifiers out of a URL before it is remembered.
 *
 * Separate from [normalizeBrowsedUrl] on purpose, and it must stay that way: that function produces
 * the key citations are anchored on, so changing what it emits would silently re-point every stored
 * citation. This one is applied at the memory boundary only.
 *
 * Two levels. A URL on a payment or account-management path keeps its host and nothing else, because
 * on those paths the path itself is the identifier. Everywhere else, sensitive query parameters are
 * dropped and the rest is kept.
 */
fun redactBrowsedUrl(raw: String): String {
    val normalized = normalizeBrowsedUrl(raw)
    if (normalized.isBlank()) return ""
    val uri = runCatching { URI(normalized) } .getOrNull() ?: return ""
    val host = uri.host.orEmpty()
    val path = uri.path.orEmpty()
    if (SensitivePathPattern.containsMatchIn(path)) {
        return uri.scheme + "://" + host
    }
    val query = uri.rawQuery.orEmpty()
        .split("&")
        .filter { part ->
            val name = part.substringBefore("=").lowercase()
            name.isNotBlank() && SensitiveQueryParams.none { it in name }
        }
        .joinToString("&")
    return buildString {
        append(uri.scheme).append("://").append(host).append(path)
        if (query.isNotBlank()) append("?").append(query)
    }
}

/**
 * Substrings, not exact names: the parameter carrying a session is called `sessionid` on one site,
 * `PHPSESSID` on the next and `x-access-token` on the third, and a memory that keeps one of them is
 * a memory that leaks an account.
 */
private val SensitiveQueryParams = listOf(
    "token", "session", "sid", "auth", "passwd", "password", "secret", "signature",
    "sign", "key", "code", "otp", "ticket", "credential", "access", "refresh",
)

private val SensitivePathPattern = Regex(
    """(?i)/(pay|payment|checkout|order|wallet|bank|billing|account|profile|settings|admin|console|dashboard)(/|$)""",
)

/**
 * Pages worth refusing to remember at all.
 *
 * A failed load says something about the network, not about the subject. Keeping it as a task memory
 * would teach the agent that a site has nothing on a topic when all that happened was a timeout;
 * the same fact is worth keeping as an origin fact, where it reads as "this site often fails", which
 * is true.
 */
fun isRememberablePage(url: String, title: String, text: String): Boolean {
    if (redactBrowsedUrl(url).isBlank()) return false
    if (title.startsWith("Fetch failed", ignoreCase = true)) return false
    if (text.length < 200) return false
    return ErrorPageMarkers.none { it in title.lowercase() }
}

private val ErrorPageMarkers = listOf(
    "neterror", "404", "not found", "403", "forbidden", "500", "服务器错误", "页面不存在",
)
