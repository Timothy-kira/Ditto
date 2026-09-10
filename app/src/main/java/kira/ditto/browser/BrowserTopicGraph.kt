package kira.ditto.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class BrowserTopicStatus {
    Running,
    Reported,
}

data class BrowserTopicTab(
    val topicId: String,
    val query: String,
    val primaryTabId: String = "",
    val imageTabId: String = "",
    val hits: List<BrowserDeskHit> = emptyList(),
    val images: List<BrowserDeskImage> = emptyList(),
    val status: BrowserTopicStatus = BrowserTopicStatus.Running,
) {
    val geckoTabIds: Set<String>
        get() = linkedSetOf<String>().apply {
            if (primaryTabId.isNotBlank()) add(primaryTabId)
            if (imageTabId.isNotBlank()) add(imageTabId)
        }
}

private const val MaxImagesPerTopic = 6
internal const val MaxBrowserTopicsPerSession = 6
private val TopicStopwords = setOf(
    "图片", "照片", "美图", "配图", "附图", "截图", "图鉴", "推荐", "必吃", "高清", "实拍",
    "image", "photo", "picture", "images", "photos",
)
private val TopicParticlePattern = Regex("[的了吗吧呢啊与和并及]|一下|搜索|搜搜|看看")

private data class TopicSessionBucket(
    val tabs: LinkedHashMap<String, BrowserTopicTab> = LinkedHashMap(),
    var lastTopicId: String = "",
)

/**
 * Research groups owned by the built-in browser. Same lifecycle as tabs:
 * open with a topic, collect hits/images, destroy when that session's turn
 * settles or pauses. Never written to [kira.ditto.data.BrowserPreferences.lastTabUrls].
 */
object BrowserTopicGraph {
    private val lock = Any()
    private val buckets = LinkedHashMap<String, TopicSessionBucket>()
    private val _state = MutableStateFlow<List<BrowserTopicTab>>(emptyList())
    val state: StateFlow<List<BrowserTopicTab>> = _state.asStateFlow()
    /**
     * Which session's tabs the current call belongs to.
     *
     * This was one global field written by every `bindSession`. Two browser subagents research in
     * parallel over separate MCP sessions, so their calls interleave: agent B binds its session,
     * agent A's next `ensure` resolves against B's bucket, and A's search lands in B's tab group.
     * That is the tab-group interference. The binding is now per thread — one MCP request runs on
     * one thread — with the last binding kept only as a fallback for callers outside a request.
     */
    private val requestSessionId = ThreadLocal<String>()

    @Volatile
    private var lastBoundSessionId: String = ""

    private val activeSessionId: String
        get() = requestSessionId.get()?.trim()?.takeIf { it.isNotBlank() } ?: lastBoundSessionId

    fun bindSession(sessionId: String) {
        val id = sessionId.trim()
        requestSessionId.set(id)
        synchronized(lock) {
            lastBoundSessionId = id
            publishLocked()
        }
    }

    fun snapshot(sessionId: String = activeSessionId): List<BrowserTopicTab> =
        synchronized(lock) { bucketLocked(sessionId).tabs.values.toList() }

    fun ensure(
        arguments: JSONObject,
        sessionId: String = activeSessionId,
        tool: String = "",
    ): String {
        // Helper tools carry a `query` too, but theirs is a ranking hint, not a new line of
        // research. `browser_fetch_many query="latest technology news September 2026"` used to mint
        // a second group next to the agent's own "technology September" — two cards, one search.
        if (tool.isNotBlank() && !browserToolOpensTopic(tool)) return lastTopicId(sessionId)
        val hint = arguments.optString("topic_id").ifBlank { arguments.optString("topicId") }.trim()
        val query = arguments.optString("query").ifBlank { arguments.optString("q") }.trim()
        val url = arguments.optString("url").trim()
        val forImages = arguments.optBoolean("images")
        val article = !forImages &&
            looksLikeHttpUrl(url) &&
            !isBrowserSerpUrl(url) &&
            !isBrowserSearchQuery(query, url, images = false)
        if (article) {
            if (hint.isNotBlank()) {
                val normalized = normalizeBrowserTopicId(hint).ifBlank { hint }
                val known = synchronized(lock) {
                    val bucket = bucketLocked(sessionId)
                    bucket.tabs.containsKey(normalized) || bucket.tabs.containsKey(hint)
                }
                if (known) return openOrGet(hint, hint, sessionId)
            }
            return lastTopicId(sessionId)
        }
        if (query.isBlank() && hint.isBlank()) return ""
        return openOrGet(query.ifBlank { hint }, hint, sessionId)
    }

    fun openOrGet(
        query: String,
        topicIdHint: String = "",
        sessionId: String = activeSessionId,
    ): String {
        val normalizedQuery = query.trim()
        val requested = normalizeBrowserTopicId(topicIdHint.ifBlank { normalizedQuery })
        if (requested.isBlank()) return ""
        synchronized(lock) {
            val bucket = bucketLocked(sessionId)
            val existingId = findMergeTargetLocked(bucket, requested)
            if (existingId != null) {
                val current = bucket.tabs.getValue(existingId)
                if (normalizedQuery.isNotBlank() && current.query.isBlank()) {
                    bucket.tabs[existingId] = current.copy(query = normalizedQuery)
                    publishLocked()
                }
                bucket.lastTopicId = existingId
                lastBoundSessionId = sessionId.trim()
                return existingId
            }
            if (bucket.tabs.size >= MaxBrowserTopicsPerSession) {
                val fallback = bucket.lastTopicId.ifBlank { bucket.tabs.keys.last() }
                bucket.lastTopicId = fallback
                return fallback
            }
            bucket.tabs[requested] = BrowserTopicTab(
                topicId = requested,
                query = normalizedQuery.ifBlank { requested },
            )
            bucket.lastTopicId = requested
            lastBoundSessionId = sessionId.trim()
            publishLocked()
            return requested
        }
    }

    fun ensureWorkspace(
        topicId: String,
        sessionId: String = activeSessionId,
        query: String = "",
    ): BrowserTopicTab? {
        val id = openOrGet(query.ifBlank { topicId }, topicId, sessionId)
        if (id.isBlank()) return null
        return synchronized(lock) { bucketLocked(sessionId).tabs[id] }
    }

    fun noteBrowserSwarm(sessionId: String, toolName: String, argumentsJson: String) {
        if (!looksLikeBrowserSwarm(toolName, argumentsJson)) return
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return
        val items = arguments.optJSONArray("items") ?: return
        for (index in 0 until items.length()) {
            val item = items.optString(index).trim()
            if (item.isBlank()) continue
            openOrGet(item, item, sessionId)
        }
    }

    fun recordHits(
        query: String,
        hits: List<BrowserDeskHit>,
        topicIdHint: String = "",
        sessionId: String = activeSessionId,
    ) {
        if (hits.isEmpty()) {
            openOrGet(query, topicIdHint, sessionId)
            return
        }
        val topicId = openOrGet(query, topicIdHint, sessionId)
        if (topicId.isBlank()) return
        synchronized(lock) {
            val bucket = bucketLocked(sessionId)
            val current = bucket.tabs[topicId] ?: return
            bucket.tabs[topicId] = current.copy(
                hits = (hits + current.hits).distinctBy { hit -> hit.url.ifBlank { hit.title } },
            )
            publishLocked()
        }
    }

    fun recordImages(
        query: String,
        images: List<BrowserDeskImage>,
        topicIdHint: String = "",
        sessionId: String = activeSessionId,
    ) {
        val usable = images.filter { image ->
            isUsableTopicImage(image.url) && imageMatchesTopic(query, image.alt, image.url)
        }
        if (usable.isEmpty()) {
            openOrGet(query, topicIdHint, sessionId)
            return
        }
        val topicId = openOrGet(query, topicIdHint, sessionId)
        if (topicId.isBlank()) return
        synchronized(lock) {
            val bucket = bucketLocked(sessionId)
            val current = bucket.tabs[topicId] ?: return
            bucket.tabs[topicId] = current.copy(
                images = (current.images + usable)
                    .distinctBy { image -> image.url }
                    .take(MaxImagesPerTopic),
            )
            publishLocked()
        }
    }

    fun ingestPreview(preview: BrowserDeskPreview, sessionId: String = activeSessionId) {
        if (
            preview.kind != BrowserDeskPreviewKind.Search &&
            preview.kind != BrowserDeskPreviewKind.Images
        ) {
            return
        }
        val query = preview.title.trim()
        if (query.isBlank()) return
        if (preview.hits.isNotEmpty()) recordHits(query, preview.hits, sessionId = sessionId)
        if (preview.images.isNotEmpty()) recordImages(query, preview.images, sessionId = sessionId)
    }

    fun attachGeckoTab(
        tabId: String,
        query: String,
        topicIdHint: String = "",
        asImageTab: Boolean = false,
        sessionId: String = activeSessionId,
    ) {
        val id = tabId.trim()
        if (id.isBlank()) return
        val topicId = resolveTopicForTab(query, topicIdHint, sessionId)
        if (topicId.isBlank()) return
        bindGeckoTab(topicId, id, asImageTab, sessionId)
    }

    fun bindGeckoTab(
        topicId: String,
        tabId: String,
        asImageTab: Boolean,
        sessionId: String = activeSessionId,
    ) {
        val id = tabId.trim()
        val topic = topicId.trim()
        if (id.isBlank() || topic.isBlank()) return
        synchronized(lock) {
            val bucket = bucketLocked(sessionId)
            val current = bucket.tabs[topic] ?: return
            bucket.tabs[topic] = if (asImageTab) {
                current.copy(imageTabId = id)
            } else {
                current.copy(primaryTabId = id)
            }
            bucket.lastTopicId = topic
            publishLocked()
        }
    }

    fun primaryTabId(topicId: String, sessionId: String = activeSessionId): String =
        synchronized(lock) { bucketLocked(sessionId).tabs[topicId.trim()]?.primaryTabId.orEmpty() }

    fun imageTabId(topicId: String, sessionId: String = activeSessionId): String =
        synchronized(lock) { bucketLocked(sessionId).tabs[topicId.trim()]?.imageTabId.orEmpty() }

    fun lastTopicId(sessionId: String = activeSessionId): String =
        synchronized(lock) { bucketLocked(sessionId).lastTopicId }

    fun topicForGeckoTab(tabId: String): String {
        val id = tabId.trim()
        if (id.isBlank()) return ""
        synchronized(lock) {
            buckets.values.forEach { bucket ->
                bucket.tabs.values.forEach { tab ->
                    if (id in tab.geckoTabIds) return tab.topicId
                }
            }
            return ""
        }
    }

    fun markReported(topicId: String, sessionId: String = activeSessionId) {
        val id = topicId.trim()
        if (id.isBlank()) return
        synchronized(lock) {
            val bucket = bucketLocked(sessionId)
            val current = bucket.tabs[id] ?: return
            bucket.tabs[id] = current.copy(status = BrowserTopicStatus.Reported)
            publishLocked()
        }
    }

    fun ownsGeckoTab(tabId: String): Boolean {
        val id = tabId.trim()
        if (id.isBlank()) return false
        synchronized(lock) {
            return buckets.values.any { bucket ->
                bucket.tabs.values.any { tab -> id in tab.geckoTabIds }
            }
        }
    }

    fun takeGeckoTabIdsAndClear(sessionId: String = activeSessionId): Set<String> {
        synchronized(lock) {
            val key = sessionKey(sessionId)
            val bucket = buckets.remove(key) ?: TopicSessionBucket()
            if (sessionKey(lastBoundSessionId) == key) {
                lastBoundSessionId = buckets.keys.lastOrNull().orEmpty()
            }
            publishLocked()
            return bucket.tabs.values.flatMap { it.geckoTabIds }.toSet()
        }
    }

    fun clear() {
        synchronized(lock) {
            buckets.clear()
            lastBoundSessionId = ""
            requestSessionId.remove()
            publishLocked()
        }
    }

    /** Drop this thread's session binding once its request is done. */
    fun unbindSession() {
        requestSessionId.remove()
    }

    /**
     * The Gecko tab this session was last working in.
     *
     * Tools that carry neither url nor query used to fall back to the globally selected tab, which
     * is whatever tab another agent most recently opened. Answer from this session's own group.
     */
    fun lastPrimaryTabId(sessionId: String = activeSessionId): String {
        synchronized(lock) {
            val bucket = bucketLocked(sessionId)
            val topicId = bucket.lastTopicId.ifBlank { return "" }
            return bucket.tabs[topicId]?.primaryTabId.orEmpty()
        }
    }

    fun clearSession(sessionId: String): Set<String> = takeGeckoTabIdsAndClear(sessionId)

    /**
     * End a turn's tab groups, keeping only the page the next turn was told to stay on.
     *
     * Groups are per session, not per turn, so yesterday's research group was still a merge target
     * for today's query: a new task would be handed the previous task's tab, and its pages went on
     * running scripts in the background. Returns the Gecko tab ids the caller should close. The
     * page graph is untouched — knowledge is meant to survive the turn, open tabs are not.
     */
    fun retireTurn(sessionId: String, keepTabId: String = ""): Set<String> {
        val keep = keepTabId.trim()
        synchronized(lock) {
            val bucket = buckets[sessionKey(sessionId)] ?: return emptySet()
            val dropped = LinkedHashSet<String>()
            val survivors = LinkedHashMap<String, BrowserTopicTab>()
            bucket.tabs.forEach { (topicId, tab) ->
                if (keep.isNotBlank() && keep in tab.geckoTabIds) {
                    survivors[topicId] = tab
                } else {
                    dropped += tab.geckoTabIds
                }
            }
            bucket.tabs.clear()
            bucket.tabs.putAll(survivors)
            bucket.lastTopicId = survivors.keys.lastOrNull().orEmpty()
            publishLocked()
            return dropped
        }
    }

    private fun resolveTopicForTab(
        query: String,
        topicIdHint: String,
        sessionId: String,
    ): String {
        if (topicIdHint.isNotBlank()) {
            return openOrGet(query.ifBlank { topicIdHint }, topicIdHint, sessionId)
        }
        val trimmed = query.trim()
        if (trimmed.isNotBlank() && !looksLikeHttpUrl(trimmed)) {
            return openOrGet(trimmed, sessionId = sessionId)
        }
        synchronized(lock) { return bucketLocked(sessionId).lastTopicId }
    }

    private fun findMergeTargetLocked(bucket: TopicSessionBucket, topicId: String): String? {
        bucket.tabs[topicId]?.let { return topicId }
        return null
    }

    private fun bucketLocked(sessionId: String): TopicSessionBucket {
        val key = sessionKey(sessionId)
        return buckets.getOrPut(key) { TopicSessionBucket() }
    }

    private fun sessionKey(sessionId: String): String = sessionId.trim()

    private fun publishLocked() {
        _state.value = bucketLocked(activeSessionId).tabs.values.toList()
    }
}

internal data class BrowserTopicTarget(
    val topicId: String,
    val tabId: String,
    val asImageTab: Boolean,
)

/** Tools that legitimately start a new research group. Everything else joins the current one. */
private val TopicOpeningTools = setOf(
    "search_web",
    "search",
    "web_search",
    "search_images",
    "tabs_navigate",
    "browser_open",
)

internal fun browserToolOpensTopic(tool: String): Boolean {
    val name = tool.trim().lowercase().substringAfterLast("__").replace('-', '_')
    if (name.isBlank()) return true
    return name in TopicOpeningTools
}

internal fun resolveBrowserTopicTarget(
    arguments: JSONObject,
    forImages: Boolean,
    sessionId: String = "",
    tool: String = "",
): BrowserTopicTarget {
    val explicitTab = arguments.optString("tab_id").ifBlank { arguments.optString("tabId") }.trim()
    val topicId = BrowserTopicGraph.ensure(arguments, sessionId, tool).ifBlank {
        if (explicitTab.isNotBlank()) {
            BrowserTopicGraph.topicForGeckoTab(explicitTab)
        } else {
            BrowserTopicGraph.lastTopicId(sessionId)
        }
    }
    val boundTab = when {
        explicitTab.isNotBlank() -> explicitTab
        topicId.isBlank() -> ""
        forImages -> BrowserTopicGraph.imageTabId(topicId, sessionId)
        else -> BrowserTopicGraph.primaryTabId(topicId, sessionId)
    }
    return BrowserTopicTarget(
        topicId = topicId,
        tabId = boundTab,
        asImageTab = forImages,
    )
}

internal fun looksLikeBrowserSwarm(toolName: String, argumentsJson: String): Boolean {
    val name = toolName.trim()
    val isSwarm = name.equals("AgentSwarm", ignoreCase = true) ||
        name.contains("agentswarm", ignoreCase = true)
    if (!isSwarm) return false
    val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return false
    val type = arguments.optString("subagent_type")
        .ifBlank { arguments.optString("subagentType") }
        .trim()
        .lowercase()
    return type == "browser"
}

internal fun normalizeBrowserTopicId(raw: String): String {
    var text = raw.trim()
    if (text.isBlank()) return ""
    TopicStopwords.forEach { stop ->
        text = text.replace(stop, "", ignoreCase = true)
    }
    text = text.replace(Regex("\\s+"), " ").trim().trim('-', '—', '·', '/', '|')
    val cjk = Regex("[\\u4e00-\\u9fff]{2,}").findAll(text).map { it.value }.maxByOrNull { it.length }
    if (cjk != null) return cjk
    return text.take(32)
}

internal fun looksLikeHttpUrl(value: String): Boolean =
    value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true)

internal fun headingMatchesTopic(heading: String, topicId: String): Boolean {
    val topic = topicId.trim()
    if (topic.isBlank()) return false
    val normalizedHeading = normalizeBrowserTopicId(heading).ifBlank { heading.trim() }
    if (normalizedHeading.isBlank()) return false
    return normalizedHeading.contains(topic) ||
        topic.contains(normalizedHeading) ||
        heading.contains(topic)
}

internal fun topicAnchorTokens(query: String): List<String> {
    var text = query.trim()
    if (text.isBlank()) return emptyList()
    TopicStopwords.forEach { stop ->
        text = text.replace(stop, " ", ignoreCase = true)
    }
    text = text.replace(TopicParticlePattern, " ").replace(Regex("\\s+"), " ").trim()
    val tokens = LinkedHashSet<String>()
    Regex("[\\u4e00-\\u9fff]{2,}").findAll(text).forEach { match ->
        val run = match.value
        tokens += run
        if (run.length > 2) {
            val cap = minOf(4, run.length)
            for (size in 2..cap) {
                tokens += run.substring(0, size)
            }
        }
    }
    Regex("[A-Za-z]{3,}").findAll(text).forEach { match ->
        tokens += match.value.lowercase()
    }
    return tokens.toList()
}

internal fun topicPrimaryAnchor(query: String): String {
    val tokens = topicAnchorTokens(query)
    val cjk = tokens.filter { token ->
        token.length in 2..4 && token.any { ch -> ch in '\u4e00'..'\u9fff' }
    }
    if (cjk.isNotEmpty()) return cjk.first()
    return tokens.maxByOrNull { it.length }.orEmpty()
}

internal fun imageMatchesTopic(query: String, alt: String, url: String): Boolean {
    if (!isUsableTopicImage(url)) return false
    val anchor = topicPrimaryAnchor(query)
    if (anchor.isBlank()) return true
    val decoded = runCatching {
        java.net.URLDecoder.decode(url, Charsets.UTF_8.name())
    }.getOrDefault(url)
    val haystack = "$alt $url $decoded".lowercase()
    return haystack.contains(anchor.lowercase())
}

/**
 * How well does this image belong to *this* subject?
 *
 * The labels are not equally trustworthy. A caption sits with the picture, so it describes it.
 * An alt that equals the page title describes the article the picture was lifted from — which is
 * how a photo of a research-centre launch came to be presented as a dish. Weight them
 * accordingly, and subtract when the labels name a different subject from the same list.
 */
internal fun scoreImageForSubject(
    subject: String,
    alt: String,
    caption: String,
    pageTitle: String,
    url: String,
    altIsPageTitle: Boolean,
    otherSubjects: List<String> = emptyList(),
): Int {
    val needle = subject.trim()
    if (needle.isBlank()) return 0
    if (!isUsableTopicImage(url)) return -100
    val decoded = runCatching {
        java.net.URLDecoder.decode(url, Charsets.UTF_8.name())
    }.getOrDefault(url)

    var score = 0
    if (caption.contains(needle, ignoreCase = true)) score += 6
    if (alt.contains(needle, ignoreCase = true)) score += if (altIsPageTitle) 2 else 4
    if (decoded.contains(needle, ignoreCase = true)) score += 2
    if (!altIsPageTitle && pageTitle.contains(needle, ignoreCase = true)) score += 1

    // A label that names a different subject and not this one is evidence against, not neutral.
    val labels = listOf(caption, alt, pageTitle)
    val mineHit = labels.any { it.contains(needle, ignoreCase = true) }
    otherSubjects
        .filter { it.isNotBlank() && !it.equals(needle, ignoreCase = true) }
        .forEach { rival ->
            if (labels.any { it.contains(rival, ignoreCase = true) } && !mineHit) score -= 4
        }
    return score
}

internal fun isUsableTopicImage(url: String): Boolean {
    val trimmed = url.trim()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
    val lower = trimmed.lowercase()
    return !lower.contains("1x1") &&
        !lower.contains("favicon") &&
        !lower.contains("/pixel") &&
        !lower.contains("sprite")
}

internal fun shouldPersistBrowserTab(tabId: String, url: String): Boolean {
    if (url.isBlank() || url.startsWith("about:")) return false
    if (isNonContentBrowserHost(url)) return false
    if (BrowserTopicGraph.ownsGeckoTab(tabId)) return false
    return true
}
