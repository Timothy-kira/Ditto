package kira.ditto.browser

import kira.ditto.data.BrowserPreferences
import kira.ditto.data.DefaultBrowserSearchEngineId
import java.net.URLEncoder

data class BrowserSearchEngine(
    val id: String,
    val label: String,
    val template: String,
    val imageTemplate: String = "",
)

val BuiltInBrowserSearchEngines: List<BrowserSearchEngine> = listOf(
    BrowserSearchEngine(
        id = "bing",
        label = "Bing",
        template = "https://www.bing.com/search?q={query}&cc=US&setlang=en&ensearch=1",
        imageTemplate = "https://www.bing.com/images/search?q={query}&cc=US&setlang=en&ensearch=1",
    ),
    BrowserSearchEngine(
        id = "google",
        label = "Google",
        template = "https://www.google.com/search?q={query}",
        imageTemplate = "https://www.google.com/search?tbm=isch&q={query}",
    ),
    BrowserSearchEngine(
        id = "ddg",
        label = "DuckDuckGo",
        template = "https://duckduckgo.com/?q={query}",
        imageTemplate = "https://duckduckgo.com/?q={query}&iax=images&ia=images",
    ),
    BrowserSearchEngine(
        id = "baidu",
        label = "百度",
        template = "https://www.baidu.com/s?wd={query}",
        imageTemplate = "https://image.baidu.com/search/index?tn=baiduimage&word={query}",
    ),
    BrowserSearchEngine("custom", "自定义", ""),
)

fun resolveSearchEngine(prefs: BrowserPreferences): BrowserSearchEngine {
    val match = BuiltInBrowserSearchEngines.firstOrNull { it.id == prefs.searchEngineId }
        ?: BuiltInBrowserSearchEngines.first { it.id == DefaultBrowserSearchEngineId }
    return if (match.id == "custom") {
        match.copy(template = prefs.customSearchTemplate.trim())
    } else {
        match
    }
}

private val PlaceholderExampleHosts = setOf("example.com", "example.org", "example.net")

/** IANA reserved documentation hosts — never treat as a real search hit or article. */
fun isPlaceholderExampleHost(url: String): Boolean {
    val host = placeholderExampleHostOf(url)
    if (host.isBlank()) return false
    return host in PlaceholderExampleHosts ||
        PlaceholderExampleHosts.any { reserved -> host.endsWith(".$reserved") }
}

/** Address-bar search engines and their SERP hosts — never cite or treat as a source page. */
fun isBrowserSearchEngineHost(url: String): Boolean {
    val host = placeholderExampleHostOf(url)
    if (host.isBlank()) return false
    return host.endsWith("duckduckgo.com") ||
        host.endsWith("bing.com") ||
        host.endsWith("microsoft.com") ||
        host.endsWith("google.com") ||
        host.endsWith("googleusercontent.com") ||
        host.endsWith("baidu.com")
}

/** Placeholders, SERP pages, and loopback — not real content. */
fun isNonContentBrowserHost(url: String): Boolean {
    if (isPlaceholderExampleHost(url) || isBrowserSearchEngineHost(url)) return true
    val host = placeholderExampleHostOf(url)
    return host.isBlank() || host == "127.0.0.1" || host == "localhost"
}

/**
 * Click-tracking hops on a search engine host (`/ck/a`, `/url?q=`, `/l/?uddg=`).
 * These are not SERP chrome; Gecko can follow them to the real article.
 */
fun isSearchEngineRedirectHop(url: String): Boolean {
    if (!isBrowserSearchEngineHost(url)) return false
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
    val path = uri.path.orEmpty().lowercase()
    if (isSearchEngineResultsPath(path)) return false
    val query = uri.rawQuery.orEmpty().lowercase()
    if (
        path.contains("/ck") ||
        path.contains("/aclick") ||
        path == "/url" ||
        path.startsWith("/url/") ||
        path == "/link" ||
        path.startsWith("/link/") ||
        path == "/l" ||
        path.startsWith("/l/")
    ) {
        return true
    }
    return query.contains("uddg=") ||
        query.contains("u=a1") ||
        query.contains("imgurl=") ||
        query.contains("murl=")
}

/** Origins Gecko may preconnect or Necko-fetch after a SERP. */
fun isPrefetchableBrowserUrl(url: String): Boolean {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return false
    if (isPlaceholderExampleHost(url)) return false
    if (isSearchEngineRedirectHop(url)) return true
    return !isNonContentBrowserHost(url)
}

internal fun isSearchEngineResultsPath(path: String): Boolean {
    val p = path.lowercase()
    return p == "/search" ||
        p.startsWith("/search/") ||
        p.contains("/images/search") ||
        p == "/s" ||
        p.startsWith("/s/") ||
        p.contains("/search/index")
}

internal fun isBrowserSerpUrl(url: String): Boolean {
    if (!isBrowserSearchEngineHost(url)) return false
    val path = runCatching { java.net.URI(url.trim()) }.getOrNull()?.path.orEmpty()
    return isSearchEngineResultsPath(path)
}

internal fun searchQueryFromBrowserUrl(url: String): String {
    val raw = runCatching { java.net.URI(url.trim()) }.getOrNull()?.rawQuery.orEmpty()
    if (raw.isBlank()) return ""
    listOf("q", "wd", "text", "word", "query").forEach { key ->
        val match = Regex("(?:^|&)$key=([^&]*)", RegexOption.IGNORE_CASE).find(raw) ?: return@forEach
        val decoded = runCatching {
            java.net.URLDecoder.decode(match.groupValues[1], Charsets.UTF_8.name())
        }.getOrDefault(match.groupValues[1])
        if (decoded.isNotBlank()) return decoded
    }
    return ""
}

private val HumanSearchRelatedSplit =
    Regex("""\s*(?:和\s*[|｜]\s*["“]?|[|｜]\s*["“]|和\s*["“])\s*""")
private val HumanSearchSerpSuffix =
    Regex("""(?i)\s*[-–—|｜]\s*(bing|google|duckduckgo|baidu|yahoo|search|搜索)\b.*$""")
private val HumanSearchInstructionPrefix =
    Regex("""^(?:请(?:问|帮我?)?|麻烦|帮(?:我)?)?(?:帮忙)?(?:再)?(?:搜(?:索|一下)?|查(?:询|一下)?|找(?:一下)?|了解(?:一下)?|看看)\s*""")
private val HumanSearchQuestionSuffix =
    Regex("""(?:多少钱|怎么卖|是什么|怎么样|如何|吗|呢)+$""")
private val HumanSearchOperateTail =
    Regex("""[，,、。]?\s*(?:并)?打开(?:官网)?(?:帮我)?(?:报名|填表|填写|预约|注册).*$""")
private val HumanSearchInfoTail =
    Regex("""(?:的)?(?:相关)?信息$""")
private val HumanSearchStuffing = setOf(
    "official", "官网", "官方", "latest", "newest", "news", "最新", "消息",
    "review", "reviews", "评测", "对比", "vs", "comparison",
    "price", "价格", "hours", "开放时间", "site", "pcmag", "cnbc",
    "最新消息", "最新资讯",
)

/**
 * Strip instruction chrome and model stuffing from a search query.
 * Keep the user's subject intact — do not truncate by token or character count.
 */
internal fun humanizeBrowserSearchQuery(raw: String): String {
    parseBrowserActFollowUpFromUserText(raw)?.url?.let { return it }
    var query = raw.trim().replace(Regex("\\s+"), " ")
    if (query.isBlank()) return ""
    if (looksLikeUrl(query)) {
        query = if (isBrowserSerpUrl(query) || query.contains("/search?", ignoreCase = true)) {
            searchQueryFromBrowserUrl(query).ifBlank { return "" }
        } else {
            return query
        }
    }
    query = query.replace(HumanSearchSerpSuffix, "").trim()
    query = query.split(HumanSearchRelatedSplit).firstOrNull().orEmpty().trim()
    if (query.contains(" · ")) query = query.substringBefore(" · ").trim()
    if (query.contains(" | ")) query = query.substringBefore(" | ").trim()
    query = query.trim('"', '“', '”', '|', '｜', ' ', '·', '、')
    val quoted = Regex("""["“]([^"”]{2,})["”]""").find(query)
    if (quoted != null && query.count { it == '"' || it == '“' || it == '”' } >= 2) {
        query = quoted.groupValues[1].trim()
    }
    val withoutPrefix = query.replace(HumanSearchInstructionPrefix, "").trim()
    if (withoutPrefix.length >= 2) query = withoutPrefix
    val withoutOperate = query.replace(HumanSearchOperateTail, "").trim()
    if (withoutOperate.length >= 2) query = withoutOperate
    val withoutInfo = query.replace(HumanSearchInfoTail, "").trim()
    if (withoutInfo.length >= 2) query = withoutInfo
    val withoutSuffix = query.replace(HumanSearchQuestionSuffix, "").trim()
    if (withoutSuffix.length >= 2) query = withoutSuffix
    val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
    val lean = tokens.filter { token ->
        token.lowercase() !in HumanSearchStuffing && !token.matches(Regex("""20\d{2}"""))
    }
    query = when {
        lean.size >= 2 -> lean.joinToString(" ")
        lean.size == 1 && lean.single().length >= 2 -> lean.single()
        else -> tokens.joinToString(" ")
    }
    return query.trim()
}

/**
 * Built-in Bing is the international engine. Rewrite `cn.bing.com` and inject
 * `ensearch=1` so a Chinese locale/IP does not land on the China SERP.
 */
internal fun internationalizeBingUrl(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return trimmed
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return trimmed
    val host = uri.host.orEmpty().lowercase()
    if (!host.endsWith("bing.com")) return trimmed
    val intlHost = if (host == "cn.bing.com" || host.endsWith(".cn.bing.com")) {
        "www.bing.com"
    } else {
        uri.host
    }
    val path = uri.path.orEmpty().ifBlank { "/" }
    val serp = isSearchEngineResultsPath(path) || path == "/"
    val query = if (serp) {
        upsertQueryParams(
            rawQuery = uri.rawQuery,
            updates = mapOf(
                "cc" to "US",
                "setlang" to "en",
                "ensearch" to "1",
            ),
            remove = setOf("mkt"),
        )
    } else {
        uri.rawQuery.orEmpty()
    }
    val rebuilt = buildString {
        append(uri.scheme ?: "https")
        append("://")
        append(intlHost)
        if (uri.port > 0) {
            append(':')
            append(uri.port)
        }
        append(path)
        if (query.isNotBlank()) {
            append('?')
            append(query)
        }
        if (!uri.rawFragment.isNullOrBlank()) {
            append('#')
            append(uri.rawFragment)
        }
    }
    return rebuilt
}

private fun upsertQueryParams(
    rawQuery: String?,
    updates: Map<String, String>,
    remove: Set<String> = emptySet(),
): String {
    val map = LinkedHashMap<String, String>()
    rawQuery.orEmpty().split('&').forEach { part ->
        if (part.isBlank()) return@forEach
        val key = part.substringBefore('=').lowercase()
        if (key.isBlank() || key in remove) return@forEach
        map[key] = part.substringAfter('=', missingDelimiterValue = "")
    }
    updates.forEach { (key, value) ->
        map[key.lowercase()] = value
    }
    remove.forEach(map::remove)
    return map.entries.joinToString("&") { (key, value) ->
        if (value.isEmpty()) key else "$key=$value"
    }
}

internal fun isStalePlaceholderDocument(observedUrl: String, targetUrl: String): Boolean {
    if (!isPlaceholderExampleHost(observedUrl)) return false
    return !isPlaceholderExampleHost(targetUrl)
}

/** Topic seed tabs and empty new-tab pages — never treat as a finished navigation. */
internal fun isSeedBrowserUrl(url: String): Boolean {
    val lower = url.trim().lowercase()
    if (lower.isBlank()) return false
    val withoutHash = lower.substringBefore('#').substringBefore('?')
    return withoutHash == "about:blank" ||
        withoutHash == "about:newtab" ||
        withoutHash == "about:home"
}

/**
 * When [waitForPageLoad] may stop: an error page, or a real http(s) document
 * that is no longer loading. Seed about:blank pages are never settled.
 * A leftover 404 title from another host must not settle the new navigation.
 */
internal fun browserPageLoadSettled(
    url: String,
    title: String,
    loading: Boolean,
    requestedUrl: String = "",
): Boolean {
    if (isBrowserErrorUrl(url)) return true
    if (loading) return false
    if (isMissingBrowserPage(url, title, requestedUrl = requestedUrl)) return true
    if (isSeedBrowserUrl(url) || url.isBlank()) return false
    if (requestedUrl.isNotBlank() && isWrongHostDocument(url, requestedUrl)) return false
    return url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true)
}

internal fun isBrowserErrorUrl(url: String): Boolean {
    val lower = url.trim().lowercase()
    return lower.startsWith("about:neterror") ||
        lower.startsWith("about:certerror") ||
        lower.startsWith("about:blocked") ||
        lower.startsWith("about:crash")
}

internal fun browserPageHost(url: String): String = placeholderExampleHostOf(url)

internal fun browserHostsRelated(left: String, right: String): Boolean {
    val a = browserPageHost(left)
    val b = browserPageHost(right)
    if (a.isBlank() || b.isBlank()) return false
    return a == b || a.endsWith(".$b") || b.endsWith(".$a")
}

/**
 * Gecko can keep the previous tab title ("Page Not Found | NVIDIA") after the
 * URL bar already moved. That title must not mark the new URL as dead.
 */
internal fun isStaleMissingPageTitle(url: String, title: String): Boolean {
    val heading = title.trim()
    if (!missingPageHeading(heading.lowercase())) return false
    val host = browserPageHost(url)
    if (host.isBlank()) return false
    val pipe = heading.lastIndexOf('|')
    if (pipe < 0 || pipe >= heading.lastIndex) return false
    val brand = heading.substring(pipe + 1).trim().lowercase().removePrefix("www.")
    if (brand.length < 3) return false
    val hostHead = host.substringBefore('.')
    val brandHead = brand.replace(" ", "").substringBefore('.')
    if (host.contains(brandHead.take(4)) || brandHead.contains(hostHead.take(4))) return false
    return true
}

internal fun isStaleMissingPageDocument(
    observedUrl: String,
    title: String,
    requestedUrl: String,
): Boolean {
    if (requestedUrl.isBlank() || isBrowserErrorUrl(observedUrl)) return false
    val heading = title.trim().lowercase()
    if (!missingPageHeading(heading) && !isStaleMissingPageTitle(observedUrl, title)) return false
    if (!browserHostsRelated(observedUrl, requestedUrl)) return true
    return isStaleMissingPageTitle(observedUrl, title)
}

/** Gecko stayed on a previous site after tabs_navigate to [requestedUrl]. */
internal fun isWrongHostDocument(observedUrl: String, requestedUrl: String): Boolean {
    if (requestedUrl.isBlank() || observedUrl.isBlank()) return false
    if (isBrowserErrorUrl(observedUrl) || isSeedBrowserUrl(observedUrl)) return false
    if (isSearchEngineRedirectHop(observedUrl)) return false
    if (isBrowserSerpUrl(requestedUrl) && isBrowserSearchEngineHost(observedUrl)) return false
    return !browserHostsRelated(observedUrl, requestedUrl)
}

internal fun isDisconnectedBrowserResult(result: org.json.JSONObject): Boolean {
    val err = result.optString("errmsg").lowercase()
    val code = result.optString("code").lowercase()
    if (code == "extension_not_ready" || code == "page_not_ready") return true
    if (
        err.contains("no tab") ||
        err.contains("receiving end does not exist") ||
        err.contains("could not establish connection") ||
        err.contains("webmcp extension is not connected")
    ) {
        return true
    }
    val url = result.optString("url")
    return url.startsWith("about:blank") && err.isNotBlank() && result.optString("tree").isBlank()
}

internal fun isMissingBrowserPage(
    url: String,
    title: String = "",
    text: String = "",
    requestedUrl: String = "",
): Boolean {
    val observed = url.trim()
    if (observed.isBlank() && title.isBlank() && text.isBlank()) return false
    if (isBrowserErrorUrl(observed)) return true
    if (requestedUrl.isNotBlank() && isStaleMissingPageDocument(observed, title, requestedUrl)) {
        return false
    }
    if (isStaleMissingPageTitle(observed, title)) return false
    val heading = title.trim().lowercase()
    if (missingPageHeading(heading)) return true
    val body = text.replace(Regex("\\s+"), " ").trim().lowercase()
    if (body.length in 1..1600 && missingPageHeading(body.take(180))) return true
    if (body.contains("http error 404") || body.contains("error code: 404")) return true
    if (body.contains("dns_probe") || body.contains("err_name_not_resolved") ||
        body.contains("err_connection_refused") || body.contains("err_connection_timed_out")
    ) {
        return true
    }
    return false
}

internal fun missingPageRequestedUrl(observedUrl: String, requestedUrl: String): String {
    val observed = observedUrl.trim()
    val lower = observed.lowercase()
    if (lower.startsWith("about:neterror") || lower.startsWith("about:certerror")) {
        val marker = observed.indexOf("u=", ignoreCase = true)
        if (marker >= 0) {
            val raw = observed.substring(marker + 2).substringBefore("&")
            val decoded = runCatching {
                java.net.URLDecoder.decode(raw, Charsets.UTF_8.name())
            }.getOrDefault(raw)
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) return decoded
        }
    }
    return requestedUrl.trim().ifBlank { observed }
}

private fun missingPageHeading(value: String): Boolean {
    if (value.isBlank()) return false
    if (value.contains("404") && (
            value.contains("not found") ||
                value.contains("error") ||
                value.contains("找不到") ||
                value.contains("不存在")
            )
    ) {
        return true
    }
    if (value.contains("page not found") || value.contains("file not found")) return true
    if (value.contains("problem loading page") || value.contains("server not found")) return true
    if (value.contains("unable to connect") || value.contains("site can't be reached")) return true
    if (value.contains("this site can’t be reached") || value.contains("this site can't be reached")) return true
    if (value.contains("页面不存在") || value.contains("找不到页面") || value.contains("站点无法访问")) return true
    if (value.contains("无法访问此网站") || value.contains("网页无法打开")) return true
    return false
}

private fun placeholderExampleHostOf(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    val candidate = when {
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.contains("://") -> trimmed
        else -> "https://$trimmed"
    }
    return runCatching { java.net.URI(candidate).host }.getOrNull().orEmpty()
        .lowercase()
        .removePrefix("www.")
}

fun looksLikeUrl(input: String): Boolean {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.contains(' ')) return false
    val lower = trimmed.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:")) {
        return true
    }
    val host = trimmed.substringBefore('/').substringBefore('?')
    return host.contains('.') && !host.startsWith('.')
}

internal fun isUsableBrowserScreenshot(width: Int, height: Int): Boolean =
    width >= 32 && height >= 32

internal fun stripBrowserAddressMarkup(raw: String): String {
    var text = raw.trim()
    while (text.startsWith("*")) text = text.drop(1).trimStart()
    while (text.endsWith("*")) text = text.dropLast(1).trimEnd()
    return text.trim()
}

fun normalizeBrowserAddress(raw: String, prefs: BrowserPreferences): String {
    val trimmed = stripBrowserAddressMarkup(raw)
    if (trimmed.isEmpty()) {
        return internationalizeBingUrl(prefs.homepage.ifBlank { "https://www.bing.com" })
    }
    if (looksLikeUrl(trimmed)) {
        val resolved = if (trimmed.contains("://") || trimmed.startsWith("about:")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return internationalizeBingUrl(resolved)
    }
    return internationalizeBingUrl(applySearchTemplate(resolveSearchEngine(prefs).template, trimmed))
}

/** Image-search URL for the same engine the user picked in browser settings. */
fun normalizeBrowserImageSearch(query: String, prefs: BrowserPreferences): String {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return normalizeBrowserAddress("", prefs)
    if (looksLikeUrl(trimmed)) return normalizeBrowserAddress(trimmed, prefs)
    val engine = resolveSearchEngine(prefs)
    val template = engine.imageTemplate.ifBlank { engine.template }
    return internationalizeBingUrl(applySearchTemplate(template, trimmed))
}

private fun applySearchTemplate(template: String, query: String): String {
    val resolved = template.ifBlank { "https://www.bing.com/search?q={query}" }
    val encoded = URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
    return if (resolved.contains("{query}")) {
        resolved.replace("{query}", encoded)
    } else {
        resolved + encoded
    }
}

fun detectLoginWall(snapshot: org.json.JSONObject): Boolean {
    if (snapshot.optBoolean("login_wall")) return true
    val url = snapshot.optString("url").lowercase()
    if (url.contains("login") || url.contains("signin") || url.contains("auth") || url.contains("passport")) {
        if (snapshot.optBoolean("has_password_field")) return true
    }
    return snapshot.optBoolean("has_password_field") && snapshot.optBoolean("has_login_form")
}

internal const val BrowserChallengeTakeoverReason =
    "Human verification is on this page. Stop immediately with GUI_TASK_NEEDS_TEACHING. " +
        "Do not search, guess URLs, or retry navigation. The user will complete 人机验证 " +
        "in the in-chat browser card, then tap 已完成验证."

internal const val BrowserLoginTakeoverReason =
    "This page needs sign-in. Stop with GUI_TASK_NEEDS_TEACHING. " +
        "The user can sign in in the in-chat browser card, then tap 我已登录."

internal const val BrowserDisconnectedTakeoverReason =
    "Page tools are not ready yet. Call page_snapshot once. " +
        "Do not tabs_navigate, reload, close tabs, or screenshot. " +
        "Do not ask the user to open a browser card. Do not stop with GUI_TASK_NEEDS_TEACHING. " +
        "Never say web access is limited."

internal fun detectBrowserChallenge(
    url: String,
    title: String = "",
    text: String = "",
): Boolean {
    if (isMissingBrowserPage(url, title, text)) return false
    val loc = "$url $title".lowercase()
    if (challengeLocationMarkers.any { loc.contains(it) }) return true
    val body = text.replace(Regex("\\s+"), " ").trim().lowercase()
    if (body.isBlank()) return false
    if (challengeWidgetMarkers.any { body.contains(it) }) return true
    if (body.length > 2800) return false
    return challengePhraseMarkers.any { body.contains(it) }
}

private val challengeLocationMarkers = listOf(
    "recaptcha",
    "hcaptcha",
    "h-captcha",
    "cf-chl",
    "challenges.cloudflare",
    "just a moment",
    "verify you are human",
    "are you a robot",
    "checking your browser",
    "attention required",
    "人机验证",
    "安全验证",
    "访问验证",
    "滑动验证",
)

private val challengeWidgetMarkers = listOf(
    "g-recaptcha",
    "h-captcha",
    "cf-turnstile",
    "geetest",
    "recaptcha",
)

private val challengePhraseMarkers = listOf(
    "verify you are human",
    "are you a robot",
    "checking your browser before accessing",
    "enable javascript and cookies to continue",
    "please complete the security check",
    "人机验证",
    "请完成验证",
    "完成安全验证",
    "滑动验证",
    "安全验证",
)
