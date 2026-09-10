package kira.ditto.browser

import kira.ditto.data.markdownSourceHost

internal const val BrowserActPrefix = "[[browser-act:"
internal const val BrowserResumePrefix = "[[browser-resume:"
internal const val BrowserResumeLoginMarker = "[[browser-resume:login]]"

internal const val BrowserVerifyContinueUserText =
    "人机验证已完成，请从当前页面继续。不要换搜索词，不要猜网址。"

internal const val BrowserLoginContinueUserText =
    "我已登录，请从当前页面继续。不要换搜索词，不要猜网址。"

internal data class BrowserActMarker(
    val label: String,
    val url: String,
)

internal fun parseBrowserActMarker(text: String): BrowserActMarker? {
    val trimmed = text.trim()
    if (!trimmed.startsWith(BrowserActPrefix) || !trimmed.endsWith("]]")) return null
    val inner = trimmed.substring(BrowserActPrefix.length, trimmed.length - 2).trim()
    if (inner.isBlank()) return null
    val pipe = inner.lastIndexOf('|')
    if (pipe <= 0 || pipe >= inner.lastIndex) return null
    val label = inner.substring(0, pipe).trim()
    val url = sanitizeBrowserActUrl(inner.substring(pipe + 1))
    if (label.isBlank() || url.isBlank()) return null
    return BrowserActMarker(label = label.take(32), url = url)
}

internal fun browserActFollowUpUserText(label: String, url: String): String {
    val target = sanitizeBrowserActUrl(url)
    // A label that is the address says the address twice: "帮我打开并操作：https://evermind.ai/careers"
    // followed by the same URL on its own line. The URL belongs on the second line, where the
    // follow-up parser reads it; the first line is meant to say what the page is.
    val named = label.trim()
        .takeIf { it.isNotBlank() && browserActLabelQuality(it) > 1 }
        ?: markdownSourceHost(target).ifBlank { "这个页面" }
    // The label names the thing (a job title, an event) rather than repeating "打开网页并操作", so the
    // text actually sent has to carry the intent itself or the follow-up stops being recognised as
    // an operate request.
    val command = if (looksLikeBrowserOperateIntent(named)) named else "帮我打开并操作：$named"
    if (target.isBlank()) return command
    return "$command\n$target"
}

internal fun looksLikeBrowserOperateFollowUp(userText: String): Boolean =
    parseBrowserActFollowUpFromUserText(userText) != null

internal fun browserActOperateUrlFromUserText(userText: String): String =
    parseBrowserActFollowUpFromUserText(userText)?.url.orEmpty()

internal fun looksLikeBrowserLookupIntent(text: String): Boolean {
    val compact = text.lowercase()
    if (BrowserLookupZh.any { compact.contains(it) }) return true
    return BrowserLookupEn.containsMatchIn(compact)
}

internal fun looksLikeBrowserLookupThenOperate(text: String): Boolean {
    if (text.isBlank() || looksLikeBrowserOperateFollowUp(text)) return false
    if (looksLikeBrowserPageResume(text)) return false
    return looksLikeBrowserLookupIntent(text) && looksLikeBrowserOperateIntent(text)
}

internal fun looksLikeBrowserVerifyContinue(text: String): Boolean {
    val compact = text.trim()
    if (compact.isBlank()) return false
    if (compact == BrowserVerifyContinueUserText) return true
    return compact.contains("人机验证已完成") ||
        compact.contains("已完成验证", ignoreCase = false)
}

internal fun looksLikeBrowserLoginContinue(text: String): Boolean {
    val compact = text.trim()
    if (compact.isBlank()) return false
    if (compact == BrowserLoginContinueUserText) return true
    return compact == "我已登录" ||
        compact.startsWith("我已登录") ||
        compact.contains("I'm signed in", ignoreCase = true)
}

internal fun looksLikeBrowserPageResume(text: String): Boolean =
    looksLikeBrowserVerifyContinue(text) || looksLikeBrowserLoginContinue(text)

internal fun markdownHasBrowserResumeMarker(markdown: String): Boolean =
    markdown.replace("\r\n", "\n").lineSequence().any { line ->
        parseBrowserResumeKind(line.trim()) != null
    }

internal fun parseBrowserResumeKind(text: String): String? {
    val trimmed = text.trim()
    if (!trimmed.startsWith(BrowserResumePrefix) || !trimmed.endsWith("]]")) return null
    val inner = trimmed.substring(BrowserResumePrefix.length, trimmed.length - 2).trim()
    return inner.takeIf { it.equals("login", ignoreCase = true) }
}

internal fun looksLikeBrowserLoginTeaching(markdown: String): Boolean {
    if (!markdown.contains("GUI_TASK_NEEDS_TEACHING")) return false
    val body = markdown.lowercase()
    return body.contains("login") ||
        body.contains("log in") ||
        body.contains("sign in") ||
        body.contains("signin") ||
        markdown.contains("登录") ||
        markdown.contains("登陆")
}

internal fun ensureBrowserResumeAnswer(
    markdown: String,
    userTakeoverReason: String = "",
): String {
    val cleaned = stripInvalidBrowserActLines(stripBrowserCompactionNoise(markdown))
    if (markdownHasBrowserResumeMarker(cleaned)) return cleaned
    val login = userTakeoverReason.trim().equals("login", ignoreCase = true) ||
        looksLikeBrowserLoginTeaching(cleaned)
    if (!login) return cleaned
    return if (cleaned.isBlank()) {
        BrowserResumeLoginMarker
    } else {
        cleaned.trimEnd() + "\n\n" + BrowserResumeLoginMarker
    }
}

internal fun browserActLabelFromUserText(text: String): String {
    val compact = text.lowercase()
    return when {
        compact.contains("报名") ||
            compact.contains("register") ||
            compact.contains("enroll") ||
            compact.contains("signup") ||
            compact.contains("sign up") -> "帮我打开官网报名"
        compact.contains("预约") || compact.contains("book") -> "帮我打开网页预约"
        compact.contains("填表") ||
            compact.contains("填写") ||
            compact.contains("fill") -> "帮我打开网页填表"
        else -> "帮我打开网页并操作"
    }.take(32)
}

internal fun markdownHasBrowserActMarker(markdown: String): Boolean =
    firstBrowserActMarkerIn(markdown) != null

private val MarkdownLinkWithLabel = Regex("""\[([^\]\n]{1,60})]\((https?://[^)\s]+)\)""")
private val BoldCellPattern = Regex("""\*\*([^*\n]{2,40})\*\*""")

/** Link texts that name the action, not the thing. On their own they tell the user nothing. */
private val GenericBrowserActLabels = setOf(
    "申请", "投递", "报名", "链接", "详情", "查看", "点击", "前往", "进入",
    "立即申请", "立即报名", "了解更多", "查看详情",
    "apply", "link", "details", "view", "register", "sign up", "learn more", "open",
)

/**
 * Every place in this answer a person might want to act on, named by the thing itself.
 *
 * An answer that lists nine jobs, each with an "申请" link, used to get exactly one capsule, on an
 * arbitrarily chosen row, labelled with a phrase derived from the user's original message. Both
 * halves of that were wrong: the count and the name.
 */
internal fun labeledBrowserActCandidates(markdown: String, limit: Int = 5): List<BrowserActMarker> {
    if (markdown.isBlank()) return emptyList()
    val byUrl = LinkedHashMap<String, String>()
    markdown.replace("\r\n", "\n").lineSequence().forEach { line ->
        if (line.trimStart().startsWith(BrowserActPrefix)) return@forEach
        MarkdownLinkWithLabel.findAll(line).forEach { match ->
            val url = sanitizeBrowserActUrl(match.groupValues[2])
            if (url.isBlank() || isPlaceholderExampleHost(url)) return@forEach
            val label = browserActRowLabel(line, match.groupValues[1].trim())
            // The same page is usually linked twice: once inline where the anchor text is the bare
            // address, and once under 来源 where it carries the page's actual name. Keeping
            // whichever came first labelled the capsule "evermind.ai/zh/careers"; keep the one that
            // reads like a name.
            val existing = byUrl[url]
            if (existing == null || browserActLabelQuality(label) > browserActLabelQuality(existing)) {
                byUrl[url] = label
            }
        }
    }
    return byUrl.entries.take(limit.coerceAtLeast(1)).map { (url, label) ->
        BrowserActMarker(label = label.take(32), url = url)
    }
}

/**
 * How much this label tells a person, so two labels for the same page can be compared.
 *
 * A capsule is read at a glance: "EverMind 招聘页面" is a place, "evermind.ai/zh/careers" is an
 * address, and "申请" is a verb with no object.
 */
internal fun browserActLabelQuality(label: String): Int {
    val trimmed = label.trim()
    if (trimmed.isBlank()) return 0
    if (trimmed.startsWith("http", ignoreCase = true)) return 1
    // A bare address written without the scheme still reads as an address.
    if (trimmed.contains('/') && !trimmed.contains(' ')) return 1
    if (Regex("""^[a-z0-9.-]+\.[a-z]{2,}$""", RegexOption.IGNORE_CASE).matches(trimmed)) return 1
    if (trimmed.lowercase() in GenericBrowserActLabels) return 2
    return 3
}

/** "申请" is the verb; the row it sits in carries the job title. Prefer the row. */
private fun browserActRowLabel(line: String, linkText: String): String {
    val generic = linkText.length <= 2 || linkText.lowercase() in GenericBrowserActLabels
    if (!generic && linkText.isNotBlank()) return linkText
    BoldCellPattern.find(line)?.let { return it.groupValues[1].trim() }
    val cells = line.split('|')
        .map { it.trim().trim('*', '#', '-', ' ') }
        .filter { cell ->
            cell.isNotBlank() &&
                !cell.contains("](") &&
                !cell.startsWith("http") &&
                cell.any { ch -> !ch.isDigit() }
        }
    return cells.maxByOrNull { it.length }?.ifBlank { linkText } ?: linkText.ifBlank { "打开这个页面" }
}

/**
 * A human name for [url], read out of the answer that mentions it.
 *
 * The model is asked to label its capsules with the thing's own name and sometimes writes the
 * address instead — `[[browser-act:https://evermind.ai/careers|https://evermind.ai/careers]]` — and
 * a capsule reading like an address tells the user nothing about where it goes. The answer almost
 * always names the page on the line where it links it: `招聘主页：[https://…](https://…)`. Take that.
 */
internal fun browserActNameForUrl(markdown: String, url: String): String {
    if (markdown.isBlank() || url.isBlank()) return ""
    val want = url.trim()
    markdown.replace("\r\n", "\n").lineSequence().forEach { line ->
        if (line.trimStart().startsWith(BrowserActPrefix)) return@forEach
        val match = MarkdownLinkWithLabel.findAll(line).firstOrNull { candidate ->
            sanitizeBrowserActUrl(candidate.groupValues[2]) == want
        } ?: return@forEach
        val anchor = match.groupValues[1].trim()
        if (browserActLabelQuality(anchor) > 1) return anchor.take(32)
        // The anchor is the address too, so the name is whatever introduces it on this line:
        // "招聘主页：[…](…)" or "Careers page - […](…)".
        val lead = line.substring(0, match.range.first)
            .trim()
            .trimEnd('：', ':', '-', '—', '–', '(', '（', ' ')
            .substringAfterLast('|')
            .trim()
            .trim('*', '#', '>', ' ')
        if (lead.isNotBlank() && browserActLabelQuality(lead) > 1) return lead.take(32)
        BoldCellPattern.find(line)?.let { bold -> return bold.groupValues[1].trim().take(32) }
    }
    return ""
}

/** Give every capsule in [markdown] a label that names its page rather than repeating its address. */
internal fun repairBrowserActLabels(markdown: String): String {
    if (markdown.isBlank() || !markdown.contains(BrowserActPrefix)) return markdown
    return markdown.replace("\r\n", "\n").lineSequence().joinToString("\n") { line ->
        val marker = parseBrowserActMarker(line.trim()) ?: return@joinToString line
        if (browserActLabelQuality(marker.label) > 1) return@joinToString line
        val name = browserActNameForUrl(markdown, marker.url)
            .ifBlank { markdownSourceHost(marker.url) }
        if (name.isBlank()) line else "[[browser-act:$name|${marker.url}]]"
    }
}

internal fun stripAllBrowserActLines(markdown: String): String =
    markdown.replace("\r\n", "\n").lineSequence()
        .filterNot { it.trim().startsWith(BrowserActPrefix) }
        .joinToString("\n")
        .trimEnd()

internal fun firstBrowserActMarkerIn(markdown: String): BrowserActMarker? {
    markdown.lineSequence().forEach { line ->
        parseBrowserActMarker(line.trim())?.let { return it }
    }
    return BrowserActAnywhere.findAll(markdown).firstNotNullOfOrNull {
        parseBrowserActMarker(it.value)
    }
}

internal fun stripInvalidBrowserActLines(markdown: String): String {
    if (markdown.isBlank()) return markdown
    return markdown.replace("\r\n", "\n").lineSequence()
        .filterNot { line ->
            val trimmed = line.trim()
            trimmed.startsWith(BrowserActPrefix) && parseBrowserActMarker(trimmed) == null
        }
        .joinToString("\n")
        .trim()
}

internal fun stripBrowserCompactionNoise(markdown: String): String {
    if (markdown.isBlank()) return markdown
    return markdown.replace("\r\n", "\n").lineSequence()
        .filterNot { line ->
            val trimmed = line.trim()
            trimmed.startsWith("Compacting conversation", ignoreCase = true) ||
                trimmed.startsWith("Compacted conversation", ignoreCase = true) ||
                trimmed.contains("压缩对话上下文")
        }
        .joinToString("\n")
        .trim()
}

internal fun ensureBrowserActAnswer(
    markdown: String,
    userText: String,
    officialUrl: String,
): String {
    // Repair before anything reads a label: a marker the model wrote itself never went through the
    // naming rules below, so its label reached the button unchecked.
    val cleaned = repairBrowserActLabels(
        stripInvalidBrowserActLines(stripBrowserCompactionNoise(markdown)),
    )
    if (!looksLikeBrowserLookupThenOperate(userText)) return cleaned
    val picked = pickOfficialBrowserActUrl(
        labeledOfficialSignupUrls(cleaned).map { it to "官方报名页" } +
            httpUrlsInText(cleaned).map { it to "" } +
            listOf(officialUrl to ""),
        userText = userText,
    )
    val labeled = labeledBrowserActCandidates(cleaned)
    if (labeled.size >= 2) {
        // The answer offers a list. Give every item its own capsule with its own name, instead of
        // picking one row for the user and calling it "帮我打开网页并操作".
        val body = stripAllBrowserActLines(cleaned)
        val capsules = labeled.joinToString("\n") { "[[browser-act:${it.label}|${it.url}]]" }
        return if (body.isBlank()) capsules else body.trimEnd() + "\n\n" + capsules
    }
    val existing = firstBrowserActMarkerIn(cleaned)
    if (existing != null) {
        if (
            picked.isNotBlank() &&
            picked != existing.url &&
            officialBrowserActScore(picked, userText = userText) >
                officialBrowserActScore(existing.url, userText = userText)
        ) {
            return replaceBrowserActMarker(
                markdown = cleaned,
                label = browserActLabelFromUserText(userText),
                url = picked,
            )
        }
        return cleaned
    }
    if (picked.isBlank()) return cleaned
    // Name the destination. "帮我打开网页并操作" restates the verb the user already used and says
    // nothing about where the capsule goes; the answer almost always names the page somewhere, so
    // take that name and only fall back to the verb when nothing in the answer names it.
    val label = labeled.firstOrNull { it.url == picked }?.label
        ?.takeIf { browserActLabelQuality(it) >= 3 }
        ?: labeled.firstOrNull { it.url == picked }?.label
        ?: browserActLabelFromUserText(userText)
    val capsule = "[[browser-act:$label|$picked]]"
    return if (cleaned.isBlank()) {
        "已找到相关页面。需要操作请点下面胶囊。\n\n$capsule"
    } else {
        cleaned.trimEnd() + "\n\n" + capsule
    }
}

internal fun replaceBrowserActMarker(markdown: String, label: String, url: String): String {
    val capsule = "[[browser-act:$label|$url]]"
    var replaced = false
    val lines = markdown.replace("\r\n", "\n").lineSequence().joinToString("\n") { line ->
        if (!replaced && parseBrowserActMarker(line.trim()) != null) {
            replaced = true
            capsule
        } else {
            line
        }
    }
    if (replaced) return lines.trimEnd()
    return BrowserActAnywhere.replaceFirst(markdown, capsule).trimEnd()
}

internal fun httpUrlsInText(text: String): List<String> =
    BrowserActHttpUrlRegex.findAll(text)
        .map { sanitizeBrowserActUrl(it.value) }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()

internal fun pickOfficialBrowserActUrl(
    candidates: List<Pair<String, String>>,
    userText: String = "",
): String {
    val scored = candidates.mapNotNull { (raw, title) ->
        val url = sanitizeBrowserActUrl(raw)
        if (url.isBlank() || isPlaceholderExampleHost(url)) null
        else url to officialBrowserActScore(url, title, userText)
    }
    if (scored.isEmpty()) return ""
    var bestUrl = scored.first().first
    var bestScore = scored.first().second
    scored.drop(1).forEach { (url, score) ->
        if (score > bestScore) {
            bestUrl = url
            bestScore = score
        }
    }
    return bestUrl
}

internal fun officialBrowserActScore(
    url: String,
    title: String = "",
    userText: String = "",
): Int {
    val hay = "$url $title".lowercase()
    var score = 0
    if (isBrowserSiteHomeUrl(url)) score -= 8
    val host = browserPageHost(url)
    if (
        host.endsWith("nvidia.cn") ||
        host.endsWith("nvidia.com") ||
        host.contains("scrm.nvidia")
    ) {
        score += 6
    }
    if (
        listOf(
            "hackathon", "黑客松", "报名", "register", "signup", "sign-up",
            "enroll", "contest", "挑战赛", "/lp/",
        ).any { hay.contains(it) }
    ) {
        score += 4
    }
    if (listOf("official", "官网", "官方报名").any { hay.contains(it) || title.contains(it) }) {
        score += 2
    }
    if (listOf("/product", "/products/").any { hay.contains(it) }) score -= 2
    if (url.contains("#:~:text=", ignoreCase = true)) score -= 8
    val query = userText.lowercase()
    if (query.contains("第三届") && hay.contains("第三届")) score += 4
    if (
        (query.contains("第三届") || query.contains("nvidia")) &&
        (
            hay.contains("zero downtime") ||
                hay.contains("zero-downtime") ||
                host.contains("hustleailab") ||
                hay.contains("$5,000") ||
                hay.contains("$5000")
            )
    ) {
        score -= 8
    }
    if (isBrowserReprintHost(host)) score -= 8
    return score
}

internal fun isBrowserReprintHost(host: String): Boolean {
    val value = host.lowercase().trim()
    if (value.isBlank()) return false
    return value.endsWith("sohu.com") ||
        value.endsWith("toutiao.com") ||
        value.endsWith("x-techcon.com") ||
        value.endsWith("competehub.dev") ||
        value.endsWith("weixin.qq.com") ||
        value.contains("mp.weixin")
}

internal fun labeledOfficialSignupUrls(markdown: String): List<String> {
    if (markdown.isBlank()) return emptyList()
    return OfficialSignupUrlRegex.findAll(markdown)
        .map { sanitizeBrowserActUrl(it.groupValues[1]) }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}

internal fun parseBrowserActFollowUpFromUserText(text: String): BrowserActMarker? {
    val raw = text.trim()
    if (raw.isBlank() || !looksLikeBrowserOperateIntent(raw)) return null
    val url = extractBrowserActHttpUrl(raw) ?: return null
    val label = raw.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() && extractBrowserActHttpUrl(it) == null }
        .orEmpty()
        .ifBlank { "帮我打开网页并操作" }
        .take(32)
    return BrowserActMarker(label = label, url = url)
}

internal fun sanitizeBrowserActUrl(raw: String): String {
    val trimmed = stripBrowserTextFragment(
        raw.trim()
            .trim('`', '"', '\'', '“', '”')
            .trimEnd('.', ',', ';', ')', ']', '>', '"', '\'', '`'),
    )
    if (!looksLikeHttpUrl(trimmed) || isBrowserSerpUrl(trimmed)) return ""
    if (isBrowserSiteHomeUrl(trimmed)) return ""
    return trimmed
}

internal fun stripBrowserTextFragment(url: String): String {
    val marker = url.indexOf("#:~:text=", ignoreCase = true)
    if (marker >= 0) return url.substring(0, marker)
    return url
}

internal fun isBrowserSiteHomeUrl(url: String): Boolean {
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
    val host = uri.host.orEmpty()
    if (host.isBlank()) return false
    val path = uri.path.orEmpty().trimEnd('/')
    return path.isEmpty()
}

internal fun looksLikeBrowserOperateIntent(text: String): Boolean {
    val compact = text.lowercase()
    if (BrowserOperateZh.any { compact.contains(it) }) return true
    return BrowserOperateEn.containsMatchIn(compact)
}

private fun extractBrowserActHttpUrl(text: String): String? {
    val match = BrowserActHttpUrlRegex.find(text) ?: return null
    return sanitizeBrowserActUrl(match.value).takeIf { it.isNotBlank() }
}

private val BrowserOperateZh = listOf(
    "打开",
    "报名",
    "填表",
    "填写",
    "操作",
    "预约",
    "注册",
    "申请",
    "提交",
)

private val BrowserLookupZh = listOf(
    "搜索",
    "搜一下",
    "搜搜",
    "查询",
    "查一下",
    "找一下",
    "了解一下",
    "搜",
)

private val BrowserOperateEn = Regex(
    """\b(open|fill|register|apply|book|enroll|operate|signup|sign\s*up)\b""",
    RegexOption.IGNORE_CASE,
)

private val BrowserLookupEn = Regex(
    """\b(search|look\s*up|find\s+info)\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * A bare URL inside prose.
 *
 * The old class stopped only at whitespace, `]` and `|`. Chinese prose has no spaces, so on
 * `…在 [evermind.ai/zh/careers](https://evermind.ai/zh/careers)。让我打开看看有哪些岗位。页面已经…`
 * the match ran straight through the closing paren and swallowed two sentences, and the capsule
 * shipped that as its URL — which is the truncated, garbled bubble the user gets on tapping it.
 * A closing paren ends a markdown link, and CJK punctuation ends a sentence; neither belongs in a
 * URL.
 */
private val BrowserActHttpUrlRegex = Regex(
    """https?://[^\s\]|)。，、；：！？（）【】《》「」“”]+""",
    RegexOption.IGNORE_CASE,
)

private val BrowserActAnywhere = Regex(
    """\[\[browser-act:[^|\]]+\|https?://[^\]\s]+]]""",
    RegexOption.IGNORE_CASE,
)

private val OfficialSignupUrlRegex = Regex(
    """官方(?:报名)?(?:页|入口|链接)?[：:\s]+(https?://[^\s\]|）)]+)""",
)
