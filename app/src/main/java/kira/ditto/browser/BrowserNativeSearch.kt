package kira.ditto.browser

import kira.ditto.data.BrowserPreferences
import kira.ditto.runtime.DittoWebSearchHit
import kira.ditto.runtime.DittoWebSearchMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class BrowserImageHit(
    val imageUrl: String,
    val pageUrl: String,
    val title: String,
)

/**
 * Search without handing the tab over to the engine's own results page.
 *
 * Bing is the index, not the destination: the SERP is fetched through Necko (which never touches
 * the visible tab), parsed here, and drawn by our own results surface. Navigation only happens
 * when you pick a result.
 */
internal object BrowserNativeSearch {
    private const val TimeoutMs = 12_000L

    suspend fun links(
        components: AetherBrowserRuntime.Components,
        query: String,
        prefs: BrowserPreferences,
    ): List<DittoWebSearchHit> = withContext(Dispatchers.IO) {
        val url = normalizeBrowserAddress(query, prefs)
        val document = components.fetchDocument(url, TimeoutMs) ?: return@withContext emptyList()
        DittoWebSearchMapper.fromHtml(document.html)
    }

    suspend fun images(
        components: AetherBrowserRuntime.Components,
        query: String,
        prefs: BrowserPreferences,
    ): List<BrowserImageHit> = withContext(Dispatchers.IO) {
        val url = normalizeBrowserImageSearch(query, prefs)
        val document = components.fetchDocument(url, TimeoutMs) ?: return@withContext emptyList()
        parseImageResults(document.html)
    }

    /**
     * Every Bing image tile carries its own metadata as an HTML-escaped JSON blob in `m="…"`:
     * `murl` is the full-size image, `purl` the page it came from, `t` its title. Reading the
     * attribute is far steadier than trying to follow the tile markup, which Bing reshuffles.
     */
    internal fun parseImageResults(html: String, limit: Int = 60): List<BrowserImageHit> {
        val blobs = Regex("""m="(\{[^"]*\})"""").findAll(html)
        val seen = LinkedHashSet<String>()
        val hits = mutableListOf<BrowserImageHit>()
        for (match in blobs) {
            val blob = match.groupValues[1]
            val image = jsonField(blob, "murl") ?: continue
            if (!image.startsWith("http", ignoreCase = true)) continue
            if (!seen.add(image)) continue
            hits += BrowserImageHit(
                imageUrl = image,
                pageUrl = jsonField(blob, "purl").orEmpty(),
                title = jsonField(blob, "t").orEmpty(),
            )
            if (hits.size >= limit) break
        }
        return hits
    }

    private fun jsonField(blob: String, name: String): String? =
        Regex("""&quot;$name&quot;:&quot;(.*?)&quot;""")
            .find(blob)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::unescape)
            ?.takeIf { it.isNotBlank() }

    private fun unescape(value: String): String = value
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
