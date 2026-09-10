package kira.ditto.ui

import android.util.LruCache

/**
 * Caches parsed markdown block lists keyed by the source text.
 *
 * A transcript re-parses the same message text constantly: on every recomposition of an
 * item that scrolled back into view, on every token batch during streaming, and again
 * whenever the list is rebuilt. Parsing is pure, so the result can be shared across all
 * of those as long as the source is identical.
 */
internal object MarkdownBlockCache {
    private const val MaxCachedCharacters = 2_000_000

    private val cache = object : LruCache<String, List<MarkdownBlock>>(MaxCachedCharacters) {
        override fun sizeOf(
            key: String,
            value: List<MarkdownBlock>,
        ): Int = key.length.coerceAtLeast(1)
    }

    fun peek(markdown: String): List<MarkdownBlock>? = cache.get(markdown)

    fun getOrParse(markdown: String): List<MarkdownBlock> =
        cache.get(markdown) ?: parseMarkdownBlocks(markdown).also { cache.put(markdown, it) }

    fun put(
        markdown: String,
        blocks: List<MarkdownBlock>,
    ) {
        cache.put(markdown, blocks)
    }

    fun clear() {
        cache.evictAll()
    }
}
