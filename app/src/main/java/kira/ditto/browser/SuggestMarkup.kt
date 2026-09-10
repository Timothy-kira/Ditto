package kira.ditto.browser

/**
 * Suggested next messages, written by the model, tapped by the user.
 *
 * `[[browser-act:…|url]]` can only ever mean one thing: hand this URL to the browser. That covers
 * the case where the next step is a page, and nothing else — an answer that ends "你对哪个方向感兴趣？
 * 我可以帮你打开具体岗位的投递链接。" is offering three or four follow-ups, and only one of them has an
 * address. The rest ("看北京的岗位", "帮我投递前端") are plain sentences the user would otherwise type
 * out. So this marker carries no URL at all: it is the text to send.
 *
 * Only the model can write these, because only the model has read the conversation. The host does
 * not guess them from keywords — that is how "帮我打开网页并操作" got stamped on every answer.
 */
internal const val SuggestPrefix = "[[suggest:"

/** How many suggestions a single answer may offer before it stops being a suggestion. */
internal const val MaxSuggestChips = 4

/** The longest a chip can be and still be readable at a glance in one row. */
internal const val MaxSuggestLabelChars = 24

internal data class SuggestMarker(
    /** What the chip says, and — unless [send] differs — what tapping it sends. */
    val label: String,
    /** The message to send. Equal to [label] unless the model spelled out a longer request. */
    val send: String,
) {
    val text: String get() = send.ifBlank { label }
}

/**
 * `[[suggest:看北京的岗位]]`, or `[[suggest:看北京的岗位|只列出北京的岗位和链接]]` when the chip should
 * read shorter than the message it sends.
 */
internal fun parseSuggestMarker(text: String): SuggestMarker? {
    val trimmed = text.trim()
    if (!trimmed.startsWith(SuggestPrefix) || !trimmed.endsWith("]]")) return null
    val inner = trimmed.substring(SuggestPrefix.length, trimmed.length - 2).trim()
    if (inner.isBlank()) return null
    val pipe = inner.lastIndexOf('|')
    val label: String
    val send: String
    if (pipe <= 0 || pipe >= inner.lastIndex) {
        label = inner
        send = inner
    } else {
        label = inner.substring(0, pipe).trim()
        send = inner.substring(pipe + 1).trim()
    }
    if (label.isBlank() || send.isBlank()) return null
    // A suggestion that is a URL is a browser-act capsule wearing the wrong marker.
    if (label.startsWith("http", ignoreCase = true)) return null
    return SuggestMarker(label = label.take(MaxSuggestLabelChars), send = send.take(200))
}

/**
 * Every suggestion in this answer, in the order written, deduplicated and capped.
 *
 * Duplicates are dropped by the text they send, not by their label: two chips that read differently
 * but send the same sentence are one choice as far as the user is concerned.
 */
internal fun suggestMarkersIn(markdown: String): List<SuggestMarker> {
    if (markdown.isBlank() || !markdown.contains(SuggestPrefix)) return emptyList()
    val seen = LinkedHashMap<String, SuggestMarker>()
    markdown.replace("\r\n", "\n").lineSequence().forEach { line ->
        val trimmed = line.trim()
        // A line may hold several chips, so scan it rather than requiring one per line.
        SuggestAnywhere.findAll(trimmed).forEach { match ->
            val marker = parseSuggestMarker(match.value) ?: return@forEach
            seen.putIfAbsent(marker.text.lowercase(), marker)
        }
    }
    return seen.values.take(MaxSuggestChips)
}

/** The answer without its suggestion markers, for the message body and for any copy of it. */
internal fun stripSuggestMarkers(markdown: String): String {
    if (markdown.isBlank() || !markdown.contains(SuggestPrefix)) return markdown
    return markdown.replace("\r\n", "\n").lineSequence()
        .map { line -> SuggestAnywhere.replace(line, "").trimEnd() }
        .filterNot { line -> line.isBlank() && line != line.trimEnd() }
        .joinToString("\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}

private val SuggestAnywhere = Regex("""\[\[suggest:[^\]]{1,240}]]""", RegexOption.IGNORE_CASE)
