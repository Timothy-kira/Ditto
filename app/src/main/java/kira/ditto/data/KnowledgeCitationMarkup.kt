package kira.ditto.data

import androidx.compose.runtime.Immutable

@Immutable
data class KnowledgeCitation(
    val index: Int,
    val sourceName: String,
    val text: String,
    val url: String = "",
)

internal const val KnowledgeCitationUrlScheme = "aether-citation:"

data class KnowledgeCitationMarkerMatch(
    val index: Int,
    val endExclusive: Int,
)

fun knowledgeCitationsFromSlices(slices: List<PersonaKnowledgeSlice>): List<KnowledgeCitation> =
    slices.mapIndexed { index, slice ->
        KnowledgeCitation(
            index = index + 1,
            sourceName = slice.sourceName,
            text = slice.text.trim(),
        )
    }

fun knowledgeCitationUrl(index: Int): String = "$KnowledgeCitationUrlScheme$index"

fun parseKnowledgeCitationUrl(url: String): Int? {
    val trimmed = url.trim()
    if (!trimmed.startsWith(KnowledgeCitationUrlScheme, ignoreCase = true)) return null
    return trimmed.substring(KnowledgeCitationUrlScheme.length).toIntOrNull()?.takeIf { it in 1..999 }
}

fun parseKnowledgeCitationMarker(text: String, startIndex: Int): KnowledgeCitationMarkerMatch? {
    if (startIndex !in text.indices || text[startIndex] != '[') return null
    if (startIndex + 1 < text.length && text[startIndex + 1] == '[') {
        val close = text.indexOf("]]", startIndex + 2)
        if (close <= startIndex + 2) return null
        val number = text.substring(startIndex + 2, close).trim().toIntOrNull() ?: return null
        if (number !in 1..999) return null
        return KnowledgeCitationMarkerMatch(index = number, endExclusive = close + 2)
    }
    var cursor = startIndex + 1
    if (cursor < text.length && text[cursor] == '^') cursor++
    val digitStart = cursor
    while (cursor < text.length && text[cursor].isDigit()) cursor++
    if (cursor == digitStart || cursor >= text.length || text[cursor] != ']') return null
    if (cursor + 1 < text.length && text[cursor + 1] == '(') return null
    val number = text.substring(digitStart, cursor).toIntOrNull() ?: return null
    if (number !in 1..999) return null
    return KnowledgeCitationMarkerMatch(index = number, endExclusive = cursor + 1)
}

fun stripKnowledgeSourceAttributions(text: String): String {
    if (text.isEmpty()) return text
    val attributionLine = Regex(
        """^[ \t]*(?:[-*]\s*)?(?:以上|上述)?(?:信息|内容|资料)?均?来自[:：]?.+$""",
        RegexOption.IGNORE_CASE,
    )
    val englishAttributionLine = Regex(
        """^[ \t]*(?:[-*]\s*)?(?:source|sources|according to)[:：].+\.(?:pdf|md|txt|docx?)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    var result = text.lineSequence()
        .filterNot { line ->
            attributionLine.matches(line) || englishAttributionLine.matches(line)
        }
        .joinToString("\n")
    result = result.replace(
        Regex("""(?:以上|上述)(?:信息|内容|资料)均?来自[:：]?\s*[^\n。！？.!?]{1,80}[。.]?"""),
        "",
    )
    result = result.replace(
        Regex("""[（(](?:来自|引自|来源)[:：]?[^）)]+[）)]"""),
        "",
    )
    return result.replace(Regex("\n{3,}"), "\n\n").trimEnd()
}

private val webSourceHeadingPattern = Regex(
    """^#{0,3}\s*\**\s*(?:来源|参考(?:来源|链接)?|资料来源|相关链接|Sources?|References?)\s*\**\s*[:：]?\s*$""",
    RegexOption.IGNORE_CASE,
)
private val webSourceFootnotePattern = Regex("""^\[\[(\d+)\]\]\s*:\s*(.+)$""")
private val webSourceRefPattern = Regex("""^\[(\d+)\]\s*:\s*(.+)$""")
private val webSourceListPrefix = Regex("""^(?:[-*+]|\d+[.)])\s+""")
private val indexedWebSourcePattern = Regex("""^(\d+)[.)]\s+(.+)$""")
private val markdownHttpLinkPattern = Regex(
    """\[([^\[\]]*)\]\((https?://[^)\s]+)(?:\s+(?:"[^"]*"|'[^']*'))?\)""",
)
private val bareHttpUrlPattern = Regex("""https?://[^\s<>\])]+""")

fun markdownSourceHost(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return ""
    val withScheme = when {
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        else -> return ""
    }
    val host = runCatching { java.net.URI(withScheme).host }.getOrNull().orEmpty()
    return host.removePrefix("www.")
}

fun knowledgeCitationChipLabel(citation: KnowledgeCitation): String =
    markdownSourceHostLabel(citation.url)
        .ifBlank { markdownSourceHost(citation.url) }
        .ifBlank { citation.sourceName.trim() }
        .ifBlank { citation.index.toString() }

fun markdownSourceHostLabel(url: String): String {
    val host = markdownSourceHost(url)
    if (host.isBlank()) return ""
    val parts = host.split('.').filter { it.isNotBlank() }
    if (parts.size < 2) return host
    val last = parts.last().lowercase()
    val secondLast = parts[parts.size - 2]
    val ccTld = last.length == 2 && parts.size >= 3 &&
        parts[parts.size - 2].lowercase() in setOf("com", "co", "net", "org", "ac", "edu", "gov")
    return if (ccTld) {
        parts[parts.size - 3]
    } else if (last in setOf("com", "cn", "net", "org", "io", "app", "dev", "ai")) {
        secondLast
    } else {
        secondLast
    }
}

fun mergeKnowledgeCitations(
    primary: List<KnowledgeCitation>,
    fallback: List<KnowledgeCitation>,
): List<KnowledgeCitation> {
    if (primary.isEmpty()) return fallback
    if (fallback.isEmpty()) return primary
    val merged = fallback.associateBy { it.index }.toMutableMap()
    primary.forEach { merged[it.index] = it }
    return merged.values.sortedBy { it.index }
}

internal fun browserModeTrailingCitations(
    agentMode: Boolean,
    deskSources: List<KnowledgeCitation>,
    preceding: List<KnowledgeCitation>,
): List<KnowledgeCitation> =
    if (agentMode) preceding else mergeKnowledgeCitations(deskSources, preceding)

fun extractMarkdownWebCitations(markdown: String): List<KnowledgeCitation> {
    if (markdown.isBlank()) return emptyList()
    val byIndex = linkedMapOf<Int, KnowledgeCitation>()
    val lines = markdown.replace("\r\n", "\n").lines()
    var index = 0
    var inSourceSection = false
    var nextIndex = 1
    while (index < lines.size) {
        val trimmed = lines[index].trim()
        if (webSourceHeadingPattern.matches(trimmed)) {
            inSourceSection = true
            index++
            continue
        }
        parseWebSourceFootnote(trimmed)?.let { citation ->
            byIndex[citation.index] = citation
            nextIndex = maxOf(nextIndex, citation.index + 1)
            index++
            continue
        }
        if (inSourceSection) {
            if (trimmed.isBlank()) {
                val peek = lines.getOrNull(index + 1)?.trim().orEmpty()
                if (peek.isBlank() ||
                    webSourceHeadingPattern.matches(peek) ||
                    (!looksLikeWebSourceItem(peek) && parseWebSourceFootnote(peek) == null)
                ) {
                    inSourceSection = false
                }
                index++
                continue
            }
            if (!looksLikeWebSourceItem(trimmed)) {
                inSourceSection = false
                continue
            }
            parseWebSourceItem(trimmed, nextIndex)?.let { citation ->
                byIndex[citation.index] = citation
                nextIndex = maxOf(nextIndex, citation.index + 1)
            }
            index++
            continue
        }
        index++
    }
    if (byIndex.isEmpty()) {
        extractTrailingWebSourceList(lines).forEach { citation ->
            byIndex[citation.index] = citation
        }
    }
    return byIndex.values.sortedBy { it.index }
}

/**
 * Every http(s) URL the answer points at, in reading order, with the text that introduced it.
 *
 * [extractMarkdownWebCitations] only sees a formal source list. An answer that links a page inline
 * - "据[新浪科技](https://…)报道" - cites it just as surely, so the citation resolver needs both.
 * The link and bare-URL patterns already live in this file, so the harvesting does too.
 */
fun markdownReferencedUrls(markdown: String): List<Pair<String, String>> {
    if (markdown.isBlank()) return emptyList()
    val found = LinkedHashMap<String, String>()
    markdownHttpLinkPattern.findAll(markdown).forEach { match ->
        val url = match.groupValues[2].trim().trimEnd('.', ',', ';', '。', '，')
        if (url.isBlank()) return@forEach
        val anchor = match.groupValues[1].trim()
        val existing = found[url]
        if (existing.isNullOrBlank()) found[url] = anchor
    }
    bareHttpUrlPattern.findAll(markdown).forEach { match ->
        val url = match.value.trimEnd('.', ',', ';', '。', '，')
        if (url.isBlank()) return@forEach
        found.putIfAbsent(url, "")
    }
    return found.entries.map { it.key to it.value }
}

fun stripMarkdownWebSourceSection(markdown: String): String {
    if (markdown.isBlank()) return markdown
    val lines = markdown.replace("\r\n", "\n").lines()
    val keep = ArrayList<String>(lines.size)
    var index = 0
    var inSourceSection = false
    while (index < lines.size) {
        val trimmed = lines[index].trim()
        if (webSourceHeadingPattern.matches(trimmed)) {
            inSourceSection = true
            index++
            continue
        }
        if (parseWebSourceFootnote(trimmed) != null) {
            index++
            continue
        }
        if (inSourceSection) {
            if (trimmed.isBlank()) {
                val peek = lines.getOrNull(index + 1)?.trim().orEmpty()
                if (!looksLikeWebSourceItem(peek) && parseWebSourceFootnote(peek) == null) {
                    inSourceSection = false
                }
                index++
                continue
            }
            if (looksLikeWebSourceItem(trimmed)) {
                index++
                continue
            }
            inSourceSection = false
        }
        keep += lines[index]
        index++
    }
    return keep.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trimEnd()
}

private fun looksLikeWebSourceItem(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.startsWith("![") || trimmed.startsWith("[![")) return false
    val content = trimmed.replaceFirst(webSourceListPrefix, "")
    if (content.startsWith("![") || content.startsWith("[![")) return false
    return markdownHttpLinkPattern.containsMatchIn(content) ||
        content.startsWith("http://") ||
        content.startsWith("https://")
}

private fun parseWebSourceFootnote(line: String): KnowledgeCitation? {
    val match = webSourceFootnotePattern.matchEntire(line.trim())
        ?: webSourceRefPattern.matchEntire(line.trim())
        ?: return null
    val index = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..999 } ?: return null
    val rest = match.groupValues[2].trim()
    return parseWebSourceItem(rest, index)?.copy(index = index)
}

private fun parseWebSourceItem(line: String, defaultIndex: Int): KnowledgeCitation? {
    val trimmed = line.trim()
    if (trimmed.startsWith("![") || trimmed.startsWith("[![")) return null
    val indexed = indexedWebSourcePattern.matchEntire(trimmed)
    val index = indexed?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..999 } ?: defaultIndex
    val content = (indexed?.groupValues?.get(2) ?: trimmed.replaceFirst(webSourceListPrefix, "")).trim()
    if (content.startsWith("![") || content.startsWith("[![")) return null
    val link = markdownHttpLinkPattern.find(content)
    val title: String
    val url: String
    if (link != null) {
        title = link.groupValues[1].trim()
        url = link.groupValues[2].trim().trimEnd('.', ',', ';')
    } else {
        url = bareHttpUrlPattern.find(content)?.value?.trimEnd('.', ',', ';') ?: return null
        title = content.replace(url, "").trim().ifBlank { markdownSourceHost(url) }
    }
    if (!url.startsWith("http://") && !url.startsWith("https://")) return null
    val host = markdownSourceHost(url)
    val name = title.ifBlank { host }.ifBlank { url }
    return KnowledgeCitation(
        index = index,
        sourceName = name,
        text = title.ifBlank { host },
        url = url,
    )
}

private fun extractTrailingWebSourceList(lines: List<String>): List<KnowledgeCitation> {
    var end = lines.indexOfLast { it.trim().isNotBlank() }
    if (end < 0) return emptyList()
    val items = ArrayList<String>()
    var cursor = end
    while (cursor >= 0) {
        val trimmed = lines[cursor].trim()
        if (trimmed.isBlank()) {
            if (items.isNotEmpty()) break
            cursor--
            continue
        }
        if (!looksLikeWebSourceItem(trimmed)) break
        items.add(0, trimmed)
        cursor--
    }
    if (items.size < 2) return emptyList()
    return items.mapIndexedNotNull { offset, line -> parseWebSourceItem(line, offset + 1) }
}

fun injectBrowserCitationMarkers(
    markdown: String,
    sources: List<KnowledgeCitation>,
    appendUnmatchedToLastLine: Boolean = true,
): String {
    if (markdown.isBlank() || sources.isEmpty()) return markdown
    var result = markdown
    sources.sortedBy { it.index }.forEach { source ->
        val marker = "[[${source.index}]]"
        if (result.contains(marker)) return@forEach
        val hostLabel = markdownSourceHostLabel(source.url)
        val host = markdownSourceHost(source.url)
        val needles = listOf(source.sourceName, hostLabel, host)
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
        var inserted = false
        for (needle in needles) {
            val idx = result.indexOf(needle, ignoreCase = true)
            if (idx >= 0 && !citationNeedleIsInsideUrlOrImage(result, idx)) {
                result = result.substring(0, idx + needle.length) + marker + result.substring(idx + needle.length)
                inserted = true
                break
            }
        }
        if (!inserted && appendUnmatchedToLastLine) {
            val lines = result.lines().toMutableList()
            val last = lines.indexOfLast { line ->
                val trimmed = line.trim()
                trimmed.isNotBlank() &&
                    !trimmed.startsWith("![") &&
                    !trimmed.startsWith("[![") &&
                    !trimmed.startsWith("#") &&
                    !trimmed.startsWith("```")
            }
            if (last >= 0) {
                lines[last] = lines[last].trimEnd() + marker
                result = lines.joinToString("\n")
            }
        }
    }
    return result
}

private fun citationNeedleIsInsideUrlOrImage(text: String, index: Int): Boolean {
    val lineStart = text.lastIndexOf('\n', index).let { if (it < 0) 0 else it + 1 }
    val line = text.substring(lineStart, index)
    return line.contains("](") || line.trimStart().startsWith("![") || line.contains("http://") ||
        line.contains("https://")
}
