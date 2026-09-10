package kira.ditto.browser

import java.net.URI
import kira.ditto.data.markdownSourceHost
import org.json.JSONObject
import org.jsoup.Jsoup

internal fun normalizeHttpUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
    val scheme = uri.scheme.orEmpty().lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank()) return null
    return uri.toString()
}

internal fun classifyResource(url: String, typeHint: String = ""): String {
    val hint = typeHint.trim().lowercase()
    if (hint.isNotBlank() && hint != "other") {
        return when (hint) {
            "main_frame", "sub_frame" -> "document"
            "xmlhttprequest", "xhr", "fetch" -> "xhr"
            "imageset" -> "image"
            else -> hint
        }
    }
    val path = url.substringBefore('?').lowercase()
    return when {
        path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m3u8") ||
            path.endsWith(".mp3") || path.endsWith(".m4a") -> "media"
        path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
            path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
            path.endsWith(".ico") -> "image"
        path.endsWith(".js") || path.endsWith(".mjs") -> "script"
        path.endsWith(".css") -> "stylesheet"
        path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") -> "font"
        path.endsWith(".json") -> "xhr"
        path.endsWith(".pdf") -> "document"
        path.endsWith(".apk") || path.endsWith(".zip") || path.endsWith(".gz") ||
            path.endsWith(".7z") || path.endsWith(".rar") || path.endsWith(".exe") ||
            path.endsWith(".dmg") || path.endsWith(".csv") || path.endsWith(".xlsx") ||
            path.endsWith(".docx") -> "download"
        else -> hint.ifBlank { "other" }
    }
}

internal fun looksLikeDownload(url: String, type: String = ""): Boolean {
    if (type.equals("download", ignoreCase = true)) return true
    val path = url.substringBefore('?').lowercase()
    return path.endsWith(".pdf") || path.endsWith(".zip") || path.endsWith(".apk") ||
        path.endsWith(".gz") || path.endsWith(".7z") || path.endsWith(".rar") ||
        path.endsWith(".dmg") || path.endsWith(".exe") || path.endsWith(".csv") ||
        path.endsWith(".xlsx") || path.endsWith(".docx") || path.endsWith(".ppt") ||
        path.endsWith(".pptx")
}

internal fun extractSearchImages(html: String, baseUri: String, limit: Int = 8): List<JSONObject> {
    if (html.isBlank()) return emptyList()
    val document = runCatching { Jsoup.parse(html, baseUri) }.getOrNull() ?: return emptyList()
    val seen = LinkedHashSet<String>()
    val hits = ArrayList<JSONObject>(limit)

    fun add(imageUrl: String, title: String, pageUrl: String = "") {
        if (hits.size >= limit) return
        val image = normalizeHttpUrl(imageUrl) ?: return
        if (!isUsefulSearchImage(image)) return
        if (!seen.add(image)) return
        hits += JSONObject()
            .put("title", title.replace("\\s+".toRegex(), " ").trim().take(120))
            .put("url", pageUrl.ifBlank { image })
            .put("image", image)
    }

    document.select("meta[property=og:image], meta[name=twitter:image], meta[name=og:image]").forEach { meta ->
        if (hits.size >= limit) return@forEach
        add(meta.absUrl("content").ifBlank { meta.attr("content") }, document.title())
    }
    document.select("a.iusc[m], a[m]").forEach { anchor ->
        if (hits.size >= limit) return@forEach
        val payload = runCatching { JSONObject(anchor.attr("m")) }.getOrNull() ?: return@forEach
        val image = payload.optString("murl").ifBlank { payload.optString("turl") }
        val title = payload.optString("t").ifBlank { anchor.text() }
        add(image, title, payload.optString("purl"))
    }
    document.select("[data-objurl], [data-imgurl], [murl]").forEach { node ->
        if (hits.size >= limit) return@forEach
        val image = node.absUrl("data-objurl").ifBlank {
            node.absUrl("data-imgurl").ifBlank { node.absUrl("murl") }
        }
        add(image, node.attr("alt").ifBlank { node.attr("title") }.ifBlank { node.text() })
    }
    document.select("img[src], img[data-src], img[data-original], img[data-lazy]").forEach { img ->
        if (hits.size >= limit) return@forEach
        val src = img.absUrl("src").ifBlank {
            img.absUrl("data-src").ifBlank {
                img.absUrl("data-original").ifBlank { img.absUrl("data-lazy") }
            }
        }
        val width = img.attr("width").toIntOrNull() ?: 0
        val height = img.attr("height").toIntOrNull() ?: 0
        if ((width in 1..47) || (height in 1..47)) return@forEach
        val page = img.closest("a[href]")?.absUrl("href").orEmpty()
        add(src, img.attr("alt").ifBlank { img.attr("title") }, page)
    }
    if (hits.size < limit) {
        InlineImageUrlRegex.findAll(html).forEach { match ->
            if (hits.size >= limit) return@forEach
            add(match.value, "")
        }
    }
    return hits
}

internal fun extractPageImages(html: String, baseUri: String, limit: Int = 12): List<JSONObject> =
    extractSearchImages(html, baseUri, limit)

private fun isUsefulSearchImage(url: String): Boolean {
    val lower = url.lowercase()
    if (lower.startsWith("data:")) return false
    if (SkipImagePath.any { lower.contains(it) }) return false
    val path = lower.substringBefore('?')
    return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
        path.endsWith(".webp") || path.endsWith(".gif") ||
        lower.contains("/images/") || lower.contains("isimage") ||
        lower.contains("bing.net") || lower.contains("googleusercontent") ||
        lower.contains("gstatic.com") || lower.contains("ggpht.com") ||
        lower.contains("baidu.com") || lower.contains("img") || lower.contains("media")
}

internal fun extractSearchHits(html: String, baseUri: String, limit: Int = 8): List<JSONObject> {
    if (html.isBlank()) return emptyList()
    val document = runCatching { Jsoup.parse(html, baseUri) }.getOrNull() ?: return emptyList()
    val host = runCatching { URI(baseUri).host }.getOrNull().orEmpty().lowercase()
    val preferred = document.select(
        "h2 a[href], h3 a[href], a.result__a, a[data-testid=result-title-a], .b_algo h2 a, #b_results h2 a, .g h3 a, .result h3 a",
    )
    val fallback = document.select("a[href]")
    val fromPreferred = collectSearchHits(preferred, host, limit)
    return fromPreferred.ifEmpty { collectSearchHits(fallback, host, limit) }
}

private fun collectSearchHits(
    anchors: org.jsoup.select.Elements,
    pageHost: String,
    limit: Int,
): List<JSONObject> {
    val seen = HashSet<String>()
    val hits = ArrayList<JSONObject>(limit)
    for (anchor in anchors.distinct()) {
        if (hits.size >= limit) break
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        if (!href.startsWith("http")) continue
        val hrefHost = runCatching { URI(href).host }.getOrNull().orEmpty().lowercase()
        if (hrefHost.isBlank()) continue
        if (pageHost.isNotBlank() && (hrefHost == pageHost || hrefHost.endsWith(".$pageHost"))) continue
        if (!seen.add(href)) continue
        val title = anchor.text().replace("\\s+".toRegex(), " ").trim()
        if (title.length < 4) continue
        if (looksLikeDomainLabel(title, href)) continue
        hits += JSONObject()
            .put("title", title.take(120))
            .put("url", href)
            .put("snippet", searchHitSnippet(anchor, title, href))
    }
    return hits
}

private fun searchHitSnippet(
    anchor: org.jsoup.nodes.Element,
    title: String,
    href: String,
): String {
    val root = anchor.parents().firstOrNull { parent ->
        val tag = parent.tagName()
        val cls = parent.className()
        tag == "li" || tag == "article" ||
            cls.contains("b_algo") ||
            cls.contains("result") ||
            cls.contains("c-container")
    } ?: anchor.parent()
    val caption = root?.selectFirst(
        ".b_caption p, .b_lineclamp, .result__snippet, [data-result=snippet], .VwiC3b, .st, .c-abstract",
    )?.text().orEmpty()
    return cleanBrowserSearchSnippet(caption, title, markdownSourceHost(href), href)
}

internal fun readableText(html: String): String {
    val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return html.take(MaxText)
    document.select("script,style,nav,footer,noscript,iframe,svg").remove()
    return document.text().replace("\\s+".toRegex(), " ").trim().take(MaxText)
}

internal fun collectSearchFetchUrls(result: JSONObject, limit: Int): List<String> {
    val urls = ArrayList<String>()
    val seen = HashSet<String>()
    val queries = result.optJSONArray("queries") ?: return emptyList()
    for (index in 0 until queries.length()) {
        val hits = queries.optJSONObject(index)?.optJSONArray("hits") ?: continue
        for (hitIndex in 0 until hits.length()) {
            if (urls.size >= limit) return urls
            val url = hits.optJSONObject(hitIndex)?.optString("url").orEmpty().trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            if (!seen.add(url)) continue
            urls += url
        }
    }
    return urls
}

internal fun looksLikeHtml(raw: String): Boolean {
    val start = raw.trimStart().take(64).lowercase()
    return start.startsWith("<!doctype") || start.startsWith("<html") || start.contains("<body")
}

private const val UserAgent =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36"
private const val MaxBytes = 80_000
private const val ImageSearchMaxBytes = 250_000
private const val MaxText = 48_000
private const val MaxParallelUrls = 5
private val InlineImageUrlRegex = Regex(
    """https?://[^"'\\\s>]+\.(?:jpg|jpeg|png|webp|gif)(?:\?[^"'\\\s]*)?""",
    RegexOption.IGNORE_CASE,
)
private val SkipImagePath = listOf(
    "/favicon",
    "1x1",
    "pixel",
    "spacer",
    "tracking",
    "sprite",
    "/logo.",
    "data:image",
    "favicon",
    "avatar",
    "/icon",
    "sprite",
)
