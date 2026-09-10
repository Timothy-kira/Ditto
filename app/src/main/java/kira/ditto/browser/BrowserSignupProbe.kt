package kira.ditto.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a candidate URL turns out to be once you actually look at it.
 *
 * Host heuristics alone cannot tell a signup page from an article about one: a reprint on
 * toutiao.com and the real form on a SCRM host both mention 报名. The only reliable test is
 * whether the page carries a form you could actually fill in — so fetch it and check.
 */
internal data class SignupProbe(
    val requestedUrl: String,
    val finalUrl: String,
    val title: String,
    val score: Int,
    val hasForm: Boolean,
    val fieldCount: Int,
    val textChars: Int,
    val evidence: List<String>,
    val outbound: List<String>,
    val reachable: Boolean,
) {
    val verified: Boolean get() = reachable && score >= VerifiedScore

    fun toJson(): JSONObject = JSONObject()
        .put("url", finalUrl.ifBlank { requestedUrl })
        .put("requested_url", requestedUrl)
        .put("title", title)
        .put("score", score)
        .put("verified", verified)
        .put("has_form", hasForm)
        .put("fields", fieldCount)
        .put("chars", textChars)
        .put("reachable", reachable)
        .apply {
            if (evidence.isNotEmpty()) {
                put("evidence", JSONArray().also { list -> evidence.forEach(list::put) })
            }
            if (outbound.isNotEmpty()) {
                put("candidate_links", JSONArray().also { list -> outbound.take(6).forEach(list::put) })
            }
        }

    companion object {
        const val VerifiedScore = 8
    }
}

/** Hosts that exist to host forms. A match here is strong evidence on its own. */
private val SignupFormHosts = listOf(
    "jinshuju.net", "jsj.top", "wjx.cn", "wjx.top", "sojump.com",
    "shimo.im", "feishu.cn", "larksuite.com", "wenjuan.com",
    "huodongxing.com", "bagevent.com", "eventbrite.com", "lu.ma", "luma.com",
    "jingsocial.com", "scrm.", "hdxu.cn", "vzan.com", "hd.ai",
)

private val SignupWordsZh = listOf(
    "立即报名", "我要报名", "马上报名", "报名参赛", "在线报名", "提交报名",
    "报名入口", "报名表", "立即注册", "免费注册", "参赛报名", "立即申请",
)

private val SignupWordsEn = Regex(
    "(register now|sign ?up|apply now|registration form|join the hackathon|submit entry)",
    RegexOption.IGNORE_CASE,
)

private val ArticleMarkers = listOf("阅读原文", "转载", "版权声明", "免责声明", "原文链接")

private val AnchorRegex = Regex(
    """<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val InputRegex = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE)
private val NotFoundTitles = listOf("未找到该页面", "页面不存在", "404", "not found", "page not found")

internal object BrowserSignupProbe {
    private const val MaxSeeds = 8
    private const val MaxHopCandidates = 6

    /**
     * Score a fetched document on "could a person register here?".
     * Positive signals are structural (a form, its fields, a form host); negative signals say
     * "this is an article about the event", which is exactly the trap we keep falling into.
     */
    fun scoreDocument(html: String, url: String, title: String): Triple<Int, Boolean, List<String>> {
        val evidence = mutableListOf<String>()
        var score = 0
        val lower = html.lowercase()
        val host = runCatching { java.net.URI(url.trim()).host.orEmpty().lowercase() }.getOrDefault("")

        val hasForm = lower.contains("<form")
        val inputs = InputRegex.findAll(html).count()
        val meaningfulInputs = InputRegex.findAll(html)
            .count { tag ->
                val t = tag.value.lowercase()
                !t.contains("type=\"hidden\"") && !t.contains("type='hidden'")
            }

        if (hasForm) {
            score += 3
            evidence += "form element"
        }
        if (meaningfulInputs > 0) {
            score += meaningfulInputs.coerceAtMost(6)
            evidence += "$meaningfulInputs visible input(s)"
        }
        if (Regex("type\\s*=\\s*[\"'](tel|email)[\"']", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            score += 4
            evidence += "phone/email field"
        }
        if (SignupFormHosts.any { host.contains(it) || url.lowercase().contains(it) }) {
            score += 6
            evidence += "form host"
        }

        val zhHit = SignupWordsZh.firstOrNull { html.contains(it) }
        if (zhHit != null) {
            score += 3
            evidence += "\"$zhHit\""
        }
        SignupWordsEn.find(html)?.let {
            score += 2
            evidence += "\"${it.value.trim()}\""
        }

        if (isBrowserReprintHost(host)) {
            score -= 8
            evidence += "reprint host"
        }
        val articleHit = ArticleMarkers.count { html.contains(it) }
        if (articleHit > 0) {
            score -= 2 * articleHit.coerceAtMost(2)
            evidence += "article markers"
        }
        val lowerTitle = title.lowercase()
        if (NotFoundTitles.any { lowerTitle.contains(it) }) {
            score -= 20
            evidence += "not-found title"
        }
        if (inputs == 0 && !hasForm) {
            evidence += "no form at all"
        }
        return Triple(score, hasForm, evidence)
    }

    /** Links on this page that look like they lead to the actual signup. */
    fun outboundCandidates(html: String, baseUrl: String): List<String> {
        if (html.isBlank()) return emptyList()
        val found = LinkedHashSet<String>()
        AnchorRegex.findAll(html).forEach { match ->
            val href = match.groupValues.getOrNull(1).orEmpty().trim()
            val label = match.groupValues.getOrNull(2).orEmpty()
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val absolute = runCatching { java.net.URI(baseUrl).resolve(href).toString() }
                .getOrDefault(href)
            val clean = sanitizeBrowserActUrl(absolute)
            if (clean.isBlank()) return@forEach
            val hay = (label + " " + clean).lowercase()
            val interesting = SignupWordsZh.any { label.contains(it) } ||
                SignupWordsEn.containsMatchIn(hay) ||
                SignupFormHosts.any { hay.contains(it) } ||
                label.contains("报名") ||
                label.contains("阅读原文")
            if (interesting && !isBrowserReprintHost(
                    runCatching { java.net.URI(clean).host.orEmpty().lowercase() }.getOrDefault(""),
                )
            ) {
                found += clean
            }
        }
        return found.toList()
    }

    private fun probeOne(url: String, doc: NeckoDocument?): SignupProbe {
        if (doc == null) {
            return SignupProbe(
                requestedUrl = url,
                finalUrl = url,
                title = "",
                score = -50,
                hasForm = false,
                fieldCount = 0,
                textChars = 0,
                evidence = listOf("unreachable"),
                outbound = emptyList(),
                reachable = false,
            )
        }
        val title = titleFromHtml(doc.html)
        val (score, hasForm, evidence) = scoreDocument(doc.html, doc.url.ifBlank { url }, title)
        val text = readableTextFromHtml(doc.html)
        return SignupProbe(
            requestedUrl = url,
            finalUrl = doc.url.ifBlank { url },
            title = title,
            score = score,
            hasForm = hasForm,
            fieldCount = InputRegex.findAll(doc.html).count(),
            textChars = text.length,
            evidence = evidence,
            outbound = outboundCandidates(doc.html, doc.url.ifBlank { url }),
            reachable = true,
        )
    }

    /**
     * Fetch every candidate at once and rank them. When no seed verifies, follow one hop through
     * the signup-looking links the seeds carry — that hop is what turns "a reprint article that
     * mentions 报名" into the form it actually points at.
     */
    suspend fun rank(
        components: AetherBrowserRuntime.Components,
        seeds: List<String>,
        sessionId: String = "",
        followHops: Boolean = true,
    ): List<SignupProbe> {
        val targets = seeds.map(::sanitizeBrowserActUrl)
            .filter { it.isNotBlank() }
            .distinctBy(::normalizeBrowsedUrl)
            .take(MaxSeeds)
        if (targets.isEmpty()) return emptyList()

        val fetched = components.fetchDocuments(targets)
        val probes = targets.map { url -> probeOne(url, fetched[url]) }.toMutableList()
        rememberFetched(targets, fetched, sessionId)

        if (followHops && probes.none { it.verified }) {
            val hop = probes
                .flatMap { it.outbound }
                .distinctBy(::normalizeBrowsedUrl)
                .filterNot { candidate ->
                    targets.any { normalizeBrowsedUrl(it) == normalizeBrowsedUrl(candidate) }
                }
                .take(MaxHopCandidates)
            if (hop.isNotEmpty()) {
                val hopDocs = components.fetchDocuments(hop)
                probes += hop.map { url -> probeOne(url, hopDocs[url]) }
                rememberFetched(hop, hopDocs, sessionId)
            }
        }
        return probes.sortedByDescending { it.score }
    }

    private fun rememberFetched(
        urls: List<String>,
        docs: Map<String, NeckoDocument?>,
        sessionId: String,
    ) {
        urls.forEach { url ->
            val doc = docs[url] ?: return@forEach
            val text = readableTextFromHtml(doc.html)
            if (text.length < BrowserPageLedger.MinUsefulChars) return@forEach
            val title = titleFromHtml(doc.html)
            BrowserPageLedger.ingest(
                url = doc.url.ifBlank { url },
                title = title,
                text = text,
                source = "necko-fetch",
                sessionId = sessionId,
            )
        }
    }
}
