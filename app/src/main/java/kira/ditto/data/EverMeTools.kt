package kira.ditto.data

/**
 * EverMe recall/write tools as the chat UI should describe them.
 *
 * The host talks to EverMe through a plugin, but the user-facing copy is memory
 * in and memory out — never an MCP server name.
 */
internal object EverMeTools {
    fun matches(name: String): Boolean {
        val n = normalize(name)
        if (n.contains("everme")) return true
        return isRead(name) || isWrite(name)
    }

    fun isWrite(name: String): Boolean {
        val n = normalize(name)
        return n.contains("mem_save") ||
            n.contains("save_personal_memory") ||
            n.contains("savepersonalmemory")
    }

    fun isRead(name: String): Boolean {
        val n = normalize(name)
        return n.contains("mem_search") ||
            n.contains("mem_context") ||
            n.contains("memory_recall")
    }

    private fun normalize(name: String): String =
        name.trim().lowercase().replace('-', '_')
}

/** Local session-note sidecar. Same rule: never show the MCP prefix. */
internal object SessionNoteTools {
    private val Canonical = listOf("notes", "new_context", "read_original", "update_session_note")

    fun matches(name: String): Boolean {
        val n = normalize(name)
        if (n.contains("session_memory") || n.contains("aether_session_memory")) return true
        return canonical(name).isNotEmpty()
    }

    fun canonical(name: String): String {
        val n = normalize(name)
        return Canonical.firstOrNull { tool ->
            n == tool || n.endsWith("_$tool") || n.endsWith("__$tool")
        }.orEmpty()
    }

    private fun normalize(name: String): String =
        name.trim().lowercase().replace('-', '_')
}
