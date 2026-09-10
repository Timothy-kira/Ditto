package kira.ditto.data

/**
 * One local conversation note, as the settings screen shows it.
 *
 * Long-term recall lives in EverMe. This page is only the Codex-style Markdown file the model
 * rewrites in place. Forgetting it deletes the file, not the cloud entry.
 */
data class MemoryPage(
    val id: String,
    val origin: String,
    val title: String,
    val summary: String,
    val intent: String = "",
    val outcome: String = "",
    val status: String = "",
    val entities: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val turnCount: Int = 0,
    val topic: String = "",
    val sealed: Boolean = false,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {
    val isSessionNote: Boolean get() = origin == "note"

    val displayTitle: String get() = title.ifBlank { topic }.ifBlank { id }
}

fun parseMemoryNoteFile(id: String, markdown: String, updatedAtMillis: Long): MemoryPage? {
    val safe = id.trim()
    if (safe.isEmpty()) return null
    val body = markdown.trim()
    val title = body.lineSequence()
        .firstOrNull { it.startsWith("### Current task") }
        ?.let { "Session note" }
        ?: "Session note"
    return MemoryPage(
        id = safe,
        origin = "note",
        title = title,
        summary = body,
        createdAtMillis = updatedAtMillis,
        updatedAtMillis = updatedAtMillis,
    )
}

fun sortMemoryPages(pages: List<MemoryPage>): List<MemoryPage> =
    pages.sortedWith(
        compareByDescending<MemoryPage> { it.updatedAtMillis }
            .thenBy { it.id },
    )
