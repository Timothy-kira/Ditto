package kira.ditto.data

private val HiddenPromptTags = listOf(
    "system-reminder",
    "plugin_session_start",
    "plugin_session_end",
    "everme_profile",
    "everme_recall",
)

private val HiddenPromptOpeners = HiddenPromptTags.map { "<$it" }

private val HiddenPromptTitleMarkers = listOf(
    "everme",
    "memory recall",
    "system-reminder",
    "plugin_session",
    "this session is backed",
    "everme_profile",
    "everme_recall",
    "agent mode is on",
    "you are the desk lead",
    "a long-horizon plan was approved",
    "you are researching a multi-app",
    "kimi code cli",
    "swarm mode",
    "agent swarm",
    "tower mode",
    "control tower",
    "goal-intake",
    "the user's task",
    "web, search, and fetch use",
    "web, search, fetch",
    "websearch and fetchurl are the browser group",
    "aether's built-in gecko",
    "the previous live session",
    "memory ledger",
)

/**
 * User-visible chat text must not include host/plugin prompt injections that
 * kimi or EverMe append after the real question. Those blocks also poison
 * session titles (every chat looks the same).
 */
fun String.visibleUserMessageText(): String {
    var text = this
    HiddenPromptTags.forEach { tag ->
        val block = Regex(
            """<$tag\b[^>]*>[\s\S]*?</$tag\s*>""",
            setOf(RegexOption.IGNORE_CASE),
        )
        text = block.replace(text, "")
    }
    val lower = text.lowercase()
    val cutAt = HiddenPromptOpeners.mapNotNull { opener ->
        lower.indexOf(opener).takeIf { it >= 0 }
    }.minOrNull()
    if (cutAt != null) {
        text = text.substring(0, cutAt)
    }
    return stripLeadingDeskLead(stripEnvironmentLead(text))
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}

fun String.looksLikeHiddenPromptTitle(): Boolean {
    val visible = visibleUserMessageText()
    if (visible.isBlank()) return true
    val n = visible.lowercase()
    return HiddenPromptTitleMarkers.any { n.contains(it) }
}

/** The prefix the host stamps on the outgoing turn so the model knows what day it is. */
const val EnvironmentLeadMarker = "[环境]"

/**
 * Drop the host's environment line.
 *
 * The date line is added to the request, and the ACP replay hands that same text back as the user's
 * own message — so without this it becomes the bubble and the session title, and every chat reads
 * "[环境] 今天是 2026-09-08...". Removed line by line rather than paragraph by paragraph: if a replay
 * ever collapses the blank line after it, dropping the whole paragraph would take the question too.
 */
private fun stripEnvironmentLead(text: String): String {
    val lines = text.lines()
    val first = lines.indexOfFirst { it.isNotBlank() }
    if (first < 0) return text
    if (!lines[first].trimStart().startsWith(EnvironmentLeadMarker)) return text
    return lines.drop(first + 1).joinToString("\n")
}

private fun stripLeadingDeskLead(text: String): String {
    val parts = text.split(Regex("""\n{2,}""")).map { it.trim() }.filter { it.isNotBlank() }
    if (parts.isEmpty()) return text.trim()
    val kept = parts.dropWhile(::looksLikeDeskLeadParagraph)
    return kept.joinToString("\n\n").trim()
}

private fun looksLikeDeskLeadParagraph(part: String): Boolean {
    val n = part.trim().lowercase()
    if (n.isEmpty()) return false
    return n.startsWith("agent mode is on") ||
        n.contains("you are the desk lead") ||
        n.startsWith("a long-horizon plan was approved") ||
        n.contains("you are researching a multi-app") ||
        n.contains("kimi code cli's native agent") ||
        n.contains("swarm mode") ||
        n.contains("agent swarm") ||
        n.contains("tower mode") ||
        n.contains("goal-intake") ||
        n.startsWith("the user's task") ||
        n.startsWith("web, search, and fetch use") ||
        n.startsWith("web, search, fetch") ||
        n.startsWith("websearch and fetchurl are the browser group") ||
        n.contains("aether's built-in gecko browser")
}
