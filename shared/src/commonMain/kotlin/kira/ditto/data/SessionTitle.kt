package kira.ditto.data

const val SessionTitleMaxChars = 12

const val SessionTitleSystemPrompt =
    "Generate a concise chat title in the user's language. Return only plain text. " +
        "Hard limit: at most 12 characters (each CJK glyph counts as 1). " +
        "If a draft would exceed 12 characters, invent a shorter complete title. " +
        "Never truncate mid-word. No markdown, no quotes, no emoji, no wrapping punctuation."

fun fastestSupportedReasoningEffort(supportedLevels: Collection<String>): String {
    val normalized = supportedLevels
        .map { it.trim().lowercase() }
        .map { if (it == "none") "off" else it }
        .filter { it.isNotBlank() }
        .toSet()
    if (normalized.isEmpty()) return "minimal"
    return SupportedReasoningEfforts.firstOrNull { it in normalized } ?: "minimal"
}

fun String.sanitizeGeneratedSessionTitle(maxChars: Int = SessionTitleMaxChars): String {
    var text = lineSequence()
        .map { line ->
            line.trim()
                .removePrefix("Title:")
                .removePrefix("title:")
                .trim()
                .trim('"', '\'', '`')
        }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    text = text
        .replace(MarkdownImage, "$1")
        .replace(MarkdownLink, "$1")
        .replace(HtmlTag, "")
        .replace(MarkdownHeadingPrefix, "")
        .replace(MarkdownListPrefix, "")
        .replace(MarkdownMarkers, "")
        .replace(Whitespace, " ")
        .trim()
        .trimEnd('.', '!', '?', '。', '！', '？', ':', '：')
        .trim()
    return text
}

fun String.sessionTitleFits(maxChars: Int = SessionTitleMaxChars): Boolean =
    isNotBlank() && length <= maxChars

fun sessionTitleRewritePrompt(previous: String, originalInput: String, maxChars: Int = SessionTitleMaxChars): String =
    buildString {
        appendLine(originalInput.trim())
        appendLine()
        appendLine("Previous title was ${previous.length} characters: $previous")
        append("Write a different complete title of at most $maxChars characters. Return only the new title.")
    }

private val MarkdownImage = Regex("""!\[([^\]\n]*)]\([^)]*\)""")
private val MarkdownLink = Regex("""\[([^\]\n]+)\]\([^)]*\)""")
private val HtmlTag = Regex("""</?[a-zA-Z][^>]*>""")
private val MarkdownHeadingPrefix = Regex("""^#{1,6}\s+""")
private val MarkdownListPrefix = Regex("""^\s*(?:[-*+]|\d+[.)])\s+""")
private val MarkdownMarkers = Regex("""[*_~`#>]+""")
private val Whitespace = Regex("""\s+""")
