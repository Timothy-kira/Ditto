package kira.ditto.data

import java.io.File
import java.security.MessageDigest

/**
 * Overflow from a tool result, kept on disk with a pointer left in the context.
 *
 * The folder already does this at freeze time — a long tool result becomes a head/tail view plus
 * `[node:… blob:…]`. The gap this closes is *when*: folding happens after the oversized result has
 * already been sent once, at full, uncached input price. Spilling on arrival means the model never
 * pays for the middle of a page it did not ask to read, and can still fetch it deliberately.
 *
 * Content-addressed, so a page read twice spills once. Nothing is ever deleted here on a single
 * page's behalf: two results can hash to the same sha, and the store is cleared wholesale with the
 * rest of the cache.
 */
object ToolResultSpill {
    /**
     * How much of an oversized result stays inline.
     *
     * A head-only view rather than head-and-tail: unlike a shell command, whose exit line is the
     * point, a truncated web page's tail is rarely more informative than its beginning — and the
     * model that wants more has a tool to ask with.
     */
    const val MinSpillChars = 512

    @Volatile
    private var root: File? = null

    /** Called once at startup. Until it is, spilling degrades to plain truncation. */
    fun attach(directory: File) {
        runCatching {
            directory.mkdirs()
            root = directory
        }
    }

    fun detach() {
        root = null
    }

    private fun shaOf(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Trim [text] to [max] characters, keeping the remainder retrievable.
     *
     * Returns the text unchanged when it fits, and falls back to the previous truncation when no
     * store is attached or the write fails: a tool result that is merely shortened is a degraded
     * answer, while one that throws is a broken turn.
     */
    fun capWithSpill(text: String, max: Int): String {
        if (text.length <= max) return text
        val store = root
        val marker = { sha: String, omitted: Int ->
            "\n[spill:$sha $omitted chars omitted — read with tool_recall sha=$sha offset=$max]"
        }
        // Reserve room for the pointer, and never shrink the visible part below something readable:
        // a pointer that crowds out the content it points at helps nobody.
        val head = (max - 160).coerceAtLeast(MinSpillChars).coerceAtMost(max)
        if (store == null) return text.take(max - 1) + "…"
        val sha = runCatching { shaOf(text) }.getOrNull() ?: return text.take(max - 1) + "…"
        val file = File(store, "$sha.txt")
        val written = runCatching {
            if (!file.isFile) file.writeText(text)
            true
        }.getOrDefault(false)
        if (!written) return text.take(max - 1) + "…"
        return text.take(head) + marker(sha, text.length - head)
    }

    /** Read a window back out of a spill. Returns null when the sha is unknown. */
    fun read(sha: String, offset: Int, limit: Int): String? {
        val store = root ?: return null
        val safe = sha.trim()
        if (!safe.matches(Regex("[0-9a-f]{64}"))) return null
        val file = File(store, "$safe.txt")
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val from = offset.coerceIn(0, text.length)
        val to = (from + limit.coerceAtLeast(1)).coerceAtMost(text.length)
        return text.substring(from, to)
    }

    /** Total size of the spill directory, for the cache screen. */
    fun sizeBytes(): Long {
        val store = root ?: return 0L
        return runCatching {
            store.listFiles()?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
    }

    fun clear(): Boolean = runCatching {
        val store = root ?: return@runCatching true
        store.listFiles()?.forEach { it.delete() }
        true
    }.getOrDefault(false)
}
