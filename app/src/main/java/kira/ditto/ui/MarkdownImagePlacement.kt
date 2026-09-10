package kira.ditto.ui

import androidx.compose.runtime.staticCompositionLocalOf
import kira.ditto.browser.headingMatchesTopic

internal data class BrowserConversationChrome(
    val browserMode: Boolean = false,
    val deskVisible: Boolean = false,
)

internal val LocalBrowserConversationChrome = staticCompositionLocalOf { BrowserConversationChrome() }

internal val LocalBrowserInlineImages = staticCompositionLocalOf { emptyList<BrowserInlineImage>() }

internal fun browserModeSourceChipsVisible(browserMode: Boolean, deskVisible: Boolean): Boolean =
    !browserMode || !deskVisible

internal fun browserModeSourceChipsAtTop(browserMode: Boolean, deskVisible: Boolean): Boolean = false

internal fun browserImagesForMessage(
    messageId: String,
    currentTurnIds: Set<String>,
    liveImages: List<BrowserInlineImage>,
    imagesByMessage: Map<String, List<BrowserInlineImage>>,
    agentMode: Boolean,
): List<BrowserInlineImage> {
    if (agentMode) return emptyList()
    return if (messageId in currentTurnIds) liveImages else imagesByMessage[messageId].orEmpty()
}

data class BrowserInlineImage(
    val url: String,
    val alt: String = "",
    val topicId: String = "",
)

data class TopicImageBundle(
    val topicId: String,
    val images: List<BrowserInlineImage>,
)

private val CjkRunPattern = Regex("[\\u4e00-\\u9fff]{2,}")
private val LatinTokenPattern = Regex("[A-Za-z]{3,}")
private val MarkdownImageStopwords = setOf(
    "图片", "照片", "美图", "配图", "附图", "截图", "封面", "风景", "夜景", "旅游", "景点",
    "高清", "实拍", "摄影", "image", "photo", "picture",
)

internal fun markdownImageLabels(text: String): Set<String> {
    val labels = linkedSetOf<String>()
    CjkRunPattern.findAll(text).forEach { match ->
        val run = match.value
        if (run.length in 2..4 && run !in MarkdownImageStopwords) labels += run
        if (run.length > 4) {
            for (size in 2..4) {
                var index = 0
                while (index + size <= run.length) {
                    val piece = run.substring(index, index + size)
                    if (piece !in MarkdownImageStopwords) labels += piece
                    index++
                }
            }
        }
    }
    LatinTokenPattern.findAll(text).forEach { match ->
        val token = match.value.lowercase()
        if (token !in MarkdownImageStopwords) labels += token
    }
    return labels
}

internal fun markdownImageTopic(alt: String): String =
    markdownImageLabels(alt).maxByOrNull { it.length }.orEmpty()

internal fun markdownImagesShareTopic(
    firstAlt: String,
    secondAlt: String,
): Boolean {
    if (firstAlt.isBlank() || secondAlt.isBlank()) return true
    if (firstAlt.contains(secondAlt) || secondAlt.contains(firstAlt)) return true
    val first = markdownImageLabels(firstAlt)
    val second = markdownImageLabels(secondAlt)
    if (first.isEmpty() || second.isEmpty()) return true
    return first.any { label -> second.any { other -> label.contains(other) || other.contains(label) } }
}

internal fun diversifyInlineImages(
    images: List<BrowserInlineImage>,
    perTopic: Int = 4,
    maxTotal: Int = 16,
): List<BrowserInlineImage> {
    if (images.isEmpty()) return emptyList()
    val unique = images.filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
        .distinctBy { it.url }
    val groups = linkedMapOf<String, MutableList<BrowserInlineImage>>()
    unique.forEach { image ->
        val topic = markdownImageTopic(image.alt).ifBlank { image.url }
        groups.getOrPut(topic) { mutableListOf() }.add(image)
    }
    val capped = groups.mapValues { (_, items) -> items.take(perTopic.coerceAtLeast(1)) }
    val mixed = ArrayList<BrowserInlineImage>(maxTotal)
    var round = 0
    while (mixed.size < maxTotal) {
        var added = false
        capped.values.forEach { items ->
            if (round < items.size) {
                mixed += items[round]
                added = true
            }
        }
        if (!added) break
        round++
    }
    return mixed
}

internal fun topicBundlesFromImages(images: List<BrowserInlineImage>): List<TopicImageBundle> {
    if (images.isEmpty()) return emptyList()
    val groups = linkedMapOf<String, MutableList<BrowserInlineImage>>()
    images.filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
        .distinctBy { it.url }
        .forEach { image ->
            val topic = image.topicId.ifBlank { markdownImageTopic(image.alt) }.ifBlank { image.url }
            groups.getOrPut(topic) { mutableListOf() }.add(image)
        }
    return groups.map { (topicId, items) -> TopicImageBundle(topicId = topicId, images = items) }
}

private val InlineMarkdownImagePattern =
    Regex("""!\[([^\]\n]*)]\(([^)\s]+)(?:\s+"[^"]*")?\)""")

/**
 * Move an image that is embedded in a line of prose onto a line of its own.
 *
 * The block renderer draws a line that is nothing but images; the inline renderer never learned
 * about images at all. So `![老北京炸酱面](url)` written inside a paragraph or a list item hit the
 * inline path, where the `!` fell through as a literal character and the `[...](...)` behind it
 * parsed as an ordinary link — printing `!老北京炸酱面` with no picture. Hoisting the image out of
 * the line hands it back to the renderer that can actually draw it.
 *
 * Table rows and code fences are left alone: a picture cannot be lifted out of a table cell
 * without breaking the table, and those cases are handled in the inline renderer instead.
 */
internal fun hoistInlineMarkdownImages(markdown: String): String {
    if (!markdown.contains("![")) return markdown
    val lines = markdown.replace("\r\n", "\n").lines()
    val out = ArrayList<String>(lines.size + 8)
    var inFence = false
    lines.forEach { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            inFence = !inFence
            out += line
            return@forEach
        }
        if (inFence || trimmed.startsWith("|") || !trimmed.contains("![")) {
            out += line
            return@forEach
        }
        val matches = InlineMarkdownImagePattern.findAll(line).toList()
        if (matches.isEmpty()) {
            out += line
            return@forEach
        }
        val head = line.substring(0, matches.first().range.first).trimEnd()
        val tail = line.substring(matches.last().range.last + 1).trim()
        if (head.isBlank() && tail.isBlank()) {
            out += line
            return@forEach
        }
        if (head.isNotBlank()) out += head
        matches.forEach { out += it.value }
        if (tail.isNotBlank()) out += tail
    }
    return out.joinToString("\n")
}

internal fun attachTopicImages(
    markdown: String,
    bundles: List<TopicImageBundle>,
    streaming: Boolean = false,
    perTopic: Int = 4,
): String {
    if (bundles.isEmpty() || markdown.isBlank()) return markdown
    val remaining = bundles
        .map { bundle ->
            bundle.copy(images = bundle.images.take(perTopic.coerceAtLeast(1)))
        }
        .filter { it.images.isNotEmpty() }
        .toMutableList()
    if (remaining.isEmpty()) return markdown
    val lines = markdown.replace("\r\n", "\n").lines()
    val headingPattern = Regex("^(#{1,6})\\s+(.+?)\\s*$")
    val headingStarts = lines.indices.filter { index -> headingPattern.matches(lines[index].trim()) }
    if (headingStarts.isEmpty()) return markdown
    val insertAfter = HashMap<Int, TopicImageBundle>()
    headingStarts.forEachIndexed { index, start ->
        val nextStart = headingStarts.getOrNull(index + 1)
        val endExclusive = nextStart ?: lines.size
        val lastLine = (endExclusive - 1).coerceAtLeast(start)
        val sealed = nextStart != null ||
            !streaming ||
            lines.lastOrNull().orEmpty().isBlank()
        if (!sealed) return@forEachIndexed
        val heading = headingPattern.matchEntire(lines[start].trim())?.groupValues?.getOrNull(2).orEmpty()
        val matchIndex = remaining.indexOfFirst { bundle -> headingMatchesTopic(heading, bundle.topicId) }
        if (matchIndex < 0) return@forEachIndexed
        val bundle = remaining.removeAt(matchIndex)
        val section = lines.subList(start, endExclusive).joinToString("\n")
        val unused = bundle.images.filter { image -> !section.contains(image.url) }
        if (unused.isEmpty()) return@forEachIndexed
        insertAfter[lastLine] = bundle.copy(images = unused)
    }
    if (insertAfter.isEmpty()) return markdown
    val rebuilt = ArrayList<String>(lines.size + insertAfter.size * 6)
    lines.forEachIndexed { index, line ->
        rebuilt += line
        val placed = insertAfter[index] ?: return@forEachIndexed
        if (line.isNotBlank()) rebuilt += ""
        rebuilt += renderInlineImageLines(placed.images)
    }
    return rebuilt.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trimEnd()
}

internal fun injectBrowserImages(
    markdown: String,
    images: List<BrowserInlineImage>,
    requireTopicMatch: Boolean = false,
    holdUnmatched: Boolean = false,
    streaming: Boolean = false,
): String {
    val lines = markdown.replace("\r\n", "\n").lines()
    val existing = ArrayList<BrowserInlineImage>()
    val body = ArrayList<String>()
    lines.forEach { line ->
        val parsed = parseMarkdownImageSequence(line.trim())
            ?: parseMarkdownImageOnlyListLineForPlacement(line)
        if (parsed != null) {
            parsed.forEach { image ->
                existing += BrowserInlineImage(url = image.url, alt = image.altText)
            }
        } else {
            body += line
        }
    }
    val merged = diversifyInlineImages(existing + images)
    if (merged.isEmpty()) return markdown
    if (body.all { it.isBlank() }) {
        return if (holdUnmatched || requireTopicMatch) markdown else renderInlineImageLines(merged)
    }
    val matchable = matchableBodyIndices(body, streaming)
    val assignments = Array(body.size) { ArrayList<BrowserInlineImage>() }
    val unmatched = ArrayList<BrowserInlineImage>()
    merged.forEach { image ->
        val index = bestMatchingBodyLine(
            body = body,
            alt = image.alt,
            requirePositiveScore = requireTopicMatch,
            matchable = matchable,
        )
        if (index != null) assignments[index].add(image) else unmatched += image
    }
    if (!holdUnmatched && !requireTopicMatch) {
        distributeUnmatchedImages(body, assignments, unmatched)
    }
    val rebuilt = ArrayList<String>(body.size + merged.size)
    body.forEachIndexed { index, line ->
        rebuilt += line
        val placed = assignments[index]
        if (placed.isNotEmpty()) {
            if (line.isNotBlank()) rebuilt += ""
            appendTopicImageRows(rebuilt, placed)
        }
    }
    return rebuilt.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trimEnd()
}

internal fun matchableBodyIndices(
    body: List<String>,
    streaming: Boolean,
): Set<Int> {
    val anchors = body.indices.filter { index ->
        val trimmed = body[index].trim()
        trimmed.isNotBlank() && !trimmed.startsWith("```") && !trimmed.startsWith("![")
    }
    if (anchors.isEmpty()) return emptySet()
    if (!streaming) return anchors.toSet()
    val lastNonBlank = anchors.last()
    val lastSealed = lastNonBlank < body.lastIndex
    return if (lastSealed) anchors.toSet() else anchors.dropLast(1).toSet()
}

private fun parseMarkdownImageOnlyListLineForPlacement(line: String): List<MarkdownImageSpec>? {
    val trimmed = line.trim()
    val content = trimmed.replaceFirst(Regex("""^(?:[-*+]|\d+[.)])\s+"""), "")
    if (content == trimmed) return null
    return parseMarkdownImageSequence(content.trim())
}

private fun bestMatchingBodyLine(
    body: List<String>,
    alt: String,
    requirePositiveScore: Boolean = false,
    matchable: Set<Int>? = null,
): Int? {
    val labels = markdownImageLabels(alt)
    if (labels.isEmpty()) return null
    var bestIndex: Int? = null
    var bestScore = 0
    body.forEachIndexed { index, line ->
        if (matchable != null && index !in matchable) return@forEachIndexed
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("```") || trimmed.startsWith("![")) return@forEachIndexed
        val score = labels.sumOf { label -> if (trimmed.contains(label)) label.length else 0 }
        if (requirePositiveScore) {
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        } else if (score >= bestScore) {
            bestScore = score
            bestIndex = index
        }
    }
    if (requirePositiveScore && bestScore <= 0) return null
    return bestIndex
}

private fun distributeUnmatchedImages(
    body: List<String>,
    assignments: Array<ArrayList<BrowserInlineImage>>,
    unmatched: List<BrowserInlineImage>,
) {
    if (unmatched.isEmpty()) return
    val anchors = body.indices.filter { index ->
        val trimmed = body[index].trim()
        trimmed.isNotBlank() && !trimmed.startsWith("```")
    }
    if (anchors.isEmpty()) {
        assignments[assignments.lastIndex].addAll(unmatched)
        return
    }
    unmatched.forEachIndexed { offset, image ->
        assignments[anchors[offset % anchors.size]].add(image)
    }
}

private fun appendTopicImageRows(
    out: MutableList<String>,
    images: List<BrowserInlineImage>,
) {
    val rows = ArrayList<MutableList<BrowserInlineImage>>()
    images.forEach { image ->
        val current = rows.lastOrNull()
        if (current == null || !markdownImagesShareTopic(current.first().alt, image.alt)) {
            rows += mutableListOf(image)
        } else {
            current += image
        }
    }
    rows.forEachIndexed { index, row ->
        if (index > 0) out += ""
        out += renderInlineImageLines(row)
    }
}

private fun renderInlineImageLines(images: List<BrowserInlineImage>): String =
    images.joinToString("\n") { image ->
        val alt = image.alt.replace("]", "").trim()
        "![$alt](${image.url})"
    }
