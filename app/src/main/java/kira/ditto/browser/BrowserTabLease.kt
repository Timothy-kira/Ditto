package kira.ditto.browser

/**
 * Exclusive lease on a tab for mutating page tools.
 * Observe tools (snapshot/read/grep/wait/inspect/screenshot) never acquire it.
 */
object BrowserTabLease {
    private val lock = Any()
    private val owners = HashMap<String, String>()

    private val actTools = setOf(
        "browser_execute",
        "tabs_navigate",
        "page_click",
        "page_fill",
        "page_select",
        "page_keys",
        "page_js",
        "page_batch",
        "page_hover",
        "page_file",
        "page_form",
        "page_scroll",
        "page_dialog",
        "search_images",
    )

    fun isActTool(tool: String): Boolean = tool.trim().lowercase() in actTools

    fun tryAcquire(tabId: String, owner: String): Boolean {
        if (tabId.isBlank() || owner.isBlank()) return true
        synchronized(lock) {
            val current = owners[tabId]
            if (current != null && current != owner) return false
            owners[tabId] = owner
            return true
        }
    }

    fun release(owner: String) {
        if (owner.isBlank()) return
        synchronized(lock) {
            owners.entries.removeAll { it.value == owner }
        }
    }

    fun holder(tabId: String): String = synchronized(lock) { owners[tabId].orEmpty() }

    fun clear() {
        synchronized(lock) { owners.clear() }
    }
}
