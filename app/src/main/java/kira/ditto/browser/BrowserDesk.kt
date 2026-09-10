package kira.ditto.browser

import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kira.ditto.data.KnowledgeCitation
import kira.ditto.data.markdownSourceHost
import kira.ditto.data.markdownSourceHostLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

enum class BrowserDeskVerb {
    Searching,
    Reading,
    Opening,
    Inspecting,
    Operating,
    Noting,
    Idle,
}

data class BrowserDeskActivity(
    val id: String,
    val verb: BrowserDeskVerb,
    val detail: String = "",
)

data class BrowserDeskHit(
    val title: String,
    val url: String,
    val snippet: String = "",
    /**
     * The answer actually leaned on this page.
     *
     * Written only by [BrowserDesk.applyCitations], from the resolver that compares the finished
     * answer against these hits. `cited` used to be set by `recordRead`, so every page the agent
     * fetched wore the badge whether or not a single word of the answer came from it - which is why
     * the card's citations and the bubble's footnotes never agreed.
     */
    val cited: Boolean = false,
    val citedAtMillis: Long = 0L,
    /** The agent opened and read this page. Says nothing about whether the answer used it. */
    val read: Boolean = false,
    val readAtMillis: Long = 0L,
    val readExcerpt: String = "",
    /** Which passage of the page the answer drew on, or -1 when only the page could be located. */
    val citedPassageOrdinal: Int = -1,
    /** The sentence the answer and the page share, when one could be pinned down. */
    val citedQuote: String = "",
)

enum class BrowserDeskPreviewKind {
    Empty,
    Search,
    Article,
    Snapshot,
    Images,
}

data class BrowserDeskPreview(
    val kind: BrowserDeskPreviewKind = BrowserDeskPreviewKind.Empty,
    val url: String = "",
    val title: String = "",
    val body: String = "",
    val hits: List<BrowserDeskHit> = emptyList(),
    val images: List<BrowserDeskImage> = emptyList(),
)

data class BrowserDeskImage(
    val url: String,
    val alt: String = "",
    /**
     * The page this image was harvested from.
     *
     * `searchImagesOnce` already scores images using their caption and page title and then dropped
     * both, so an image in an answer had no provenance and could not be cited like a web page can.
     */
    val sourceUrl: String = "",
    val caption: String = "",
    val topicId: String = "",
)

data class BrowserDeskState(
    val activities: List<BrowserDeskActivity> = emptyList(),
    val preview: BrowserDeskPreview = BrowserDeskPreview(),
    val pages: List<BrowserDeskPreview> = emptyList(),
    val sources: List<KnowledgeCitation> = emptyList(),
    val previewsByTopic: Map<String, BrowserDeskPreview> = emptyMap(),
    val uiPreviewTopicId: String = "",
    val lastUpdatedMillis: Long = 0L,
    val readingUrl: String = "",
    /**
     * Every page being read right now, not just the newest one.
     *
     * `browser_fetch_many` opens three URLs at once, which is most of the fetching a research turn
     * does. With a single slot the card could only ever pulse one row, and with the batch tool
     * unmapped it pulsed none.
     */
    val readingUrls: List<String> = emptyList(),
    val readingExcerpt: String = "",
    val openedUrl: String = "",
    val userTakeover: Boolean = false,
    val userTakeoverReason: String = "",
    val keepLiveSurface: Boolean = false,
    /**
     * Execution receipts for this conversation's browser tools.
     *
     * Folded from [BrowserDeskEvent.TaskProgress], not a second database. waiting_user here is
     * the same fact as [userTakeover]; needs_verification is an interrupted call that must not
     * be retried silently.
     */
    val tasks: List<BrowserDeskTask> = emptyList(),
    val taskEventSeq: Long = 0L,
)

enum class BrowserTaskState {
    Running,
    WaitingUser,
    NeedsVerification,
    Failed,
    Completed,
    ;

    val wire: String
        get() = when (this) {
            Running -> "running"
            WaitingUser -> "waiting_user"
            NeedsVerification -> "needs_verification"
            Failed -> "failed"
            Completed -> "completed"
        }
}

data class BrowserDeskTask(
    val id: String,
    val topic: String = "",
    val tab: String = "",
    val tool: String = "",
    val state: BrowserTaskState = BrowserTaskState.Running,
    val reason: String = "",
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("topic", topic)
        .put("tab", tab)
        .put("tool", tool)
        .put("state", state.wire)
        .put("reason", reason)
        .put("created", createdAtMillis)
        .put("updated", updatedAtMillis)
}

internal fun browserTaskStateFromResult(result: JSONObject): BrowserTaskState = when {
    result.optString("code") in setOf("outcome_unknown", "execution_interrupted") ->
        BrowserTaskState.NeedsVerification
    result.optBoolean("user_takeover") || result.optString("code") == "input_required" ->
        BrowserTaskState.WaitingUser
    !result.optBoolean("ok", true) -> BrowserTaskState.Failed
    else -> BrowserTaskState.Completed
}

data class BrowserFailedRecord(
    val url: String,
    val code: String = "not_found",
    val errmsg: String = "",
    val failedAtMillis: Long = 0L,
)

data class BrowserReadRecord(
    val url: String,
    val title: String = "",
    val excerpt: String = "",
    val charCount: Int = 0,
    val firstReadAtMillis: Long = 0L,
)

/**
 * How long the desk event log is allowed to get before it is compacted.
 *
 * A research turn produces a few dozen events, so this is several sessions of headroom. It only has
 * to be bounded - the log lives in memory and a phone has none to spare.
 */
private const val BrowserDeskLogSoftLimit = 512

private const val BrowserReadLedgerMax = 200
/** Default lookup loop: 1 first-hop query, then at most 3 expand keywords. */
internal const val BrowserSearchFirstHopLimit = 1
internal const val BrowserSearchExpandLimit = 3
internal const val BrowserSearchSoftBudget = BrowserSearchFirstHopLimit + BrowserSearchExpandLimit
internal const val BrowserSearchDeepBudget = 8

internal enum class WebSearchAdmit {
    Allow,
    WaitForFirstHop,
    Budget,
    Similar,
}

internal const val BrowserWaitTimeoutBudget = 2
internal const val BrowserPageJsBudget = 6
internal const val BrowserEmptyFormBudget = 2

private val LookupOnlyBlockedTools = setOf(
    "page_wait",
    "page_form",
    "page_js",
    "page_fill",
    "page_select",
    "page_keys",
    "page_file",
    "page_dialog",
    "page_batch",
)

object BrowserDesk {
    private val _state = MutableStateFlow(BrowserDeskState())
    val state: StateFlow<BrowserDeskState> = _state.asStateFlow()

    private val eventLock = Any()
    private val eventLog = ArrayList<BrowserDeskEvent>()

    /**
     * The only place `_state` is ever assigned.
     *
     * Six call sites used to write it directly, which is what made update *order* decide
     * correctness. With one writer the state is `fold(log)` by construction, so "before or after the
     * snapshot?" has no answer to get wrong - both are the same computation.
     */
    private fun emit(event: BrowserDeskEvent) {
        synchronized(eventLock) {
            eventLog += event
            if (eventLog.size > BrowserDeskLogSoftLimit) {
                val compacted = compactBrowserDeskLog(eventLog.toList(), BrowserDeskLogSoftLimit)
                eventLog.clear()
                eventLog += compacted
            }
            _state.value = applyBrowserDeskEvent(_state.value, event)
        }
    }

    /** The log itself, for replay and for the equivalence test that guards this design. */
    fun eventLog(): List<BrowserDeskEvent> = synchronized(eventLock) { eventLog.toList() }

    fun listTasks(limit: Int = 30): JSONArray = JSONArray().apply {
        _state.value.tasks
            .sortedByDescending { it.updatedAtMillis }
            .take(limit.coerceIn(1, 100))
            .forEach { put(it.toJson()) }
    }

    fun taskEvents(after: Long): JSONArray = JSONArray().apply {
        eventLog()
            .filterIsInstance<BrowserDeskEvent.TaskProgress>()
            .filter { it.seq > after }
            .take(100)
            .forEach { event ->
                put(
                    JSONObject()
                        .put("seq", event.seq)
                        .put("task_id", event.task.id)
                        .put("state", event.task.state.wire)
                        .put("reason", event.task.reason)
                        .put("time", event.atMillis),
                )
            }
    }

    private fun recordTaskResult(id: String, tool: String, result: JSONObject) {
        val now = System.currentTimeMillis()
        val existing = _state.value.tasks.firstOrNull { it.id == id }
        recordTaskProgress(
            (existing ?: BrowserDeskTask(
                id = id,
                topic = activityTopics[id].orEmpty(),
                tool = tool,
                createdAtMillis = now,
            )).copy(
                tool = tool.ifBlank { existing?.tool.orEmpty() },
                state = browserTaskStateFromResult(result),
                reason = result.optString("code").take(120),
                updatedAtMillis = now,
            ),
        )
    }

    private fun recordTaskProgress(task: BrowserDeskTask) {
        emit(
            BrowserDeskEvent.TaskProgress(
                task = task,
                seq = taskSeq.incrementAndGet(),
                atMillis = task.updatedAtMillis,
            ),
        )
    }

    private val inflight = ConcurrentHashMap<String, BrowserDeskActivity>()
    private val sourceByUrl = LinkedHashMap<String, KnowledgeCitation>()
    private val readLedger = LinkedHashMap<String, BrowserReadRecord>()
    private val failedLedger = LinkedHashMap<String, BrowserFailedRecord>()
    private val searchCountThisTurn = AtomicInteger(0)
    private val waitTimeoutsThisTurn = AtomicInteger(0)
    private val pageJsThisTurn = AtomicInteger(0)
    private val emptyFormListsThisTurn = AtomicInteger(0)
    private val firstHopGranted = AtomicBoolean(false)
    private val firstWebSearchReturned = AtomicBoolean(false)
    private val deepSearchThisTurn = AtomicBoolean(false)
    private val lookupThenOperateThisTurn = AtomicBoolean(false)
    private val operateUrlThisTurn = AtomicReference("")
    private val firstHopQuery = AtomicReference("")
    private val firstHopLatch = AtomicReference(CountDownLatch(1))
    private val admittedQueries = CopyOnWriteArrayList<String>()
    private val activityTopics = ConcurrentHashMap<String, String>()
    private val readingUrl = AtomicReference("")
    private val readingUrls = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val readingExcerpt = AtomicReference("")
    private val openedUrl = AtomicReference("")
    /**
     * Which turn we are on. Bumped whenever the user sends a message.
     *
     * Nothing else in the browser stack knew where one task ended and the next began, so the
     * previous turn's tab groups, recovery strikes and still-running pages carried straight into
     * the next one: the new task inherited a penalty box it never earned and competed for CPU with
     * pages nobody was reading any more.
     */
    private val turnSeq = AtomicInteger(0)

    fun turnId(): Int = turnSeq.get()

    private val userTakeover = AtomicBoolean(false)
    private val userTakeoverReason = AtomicReference("")
    private val liveSurfaceHeld = AtomicBoolean(false)
    private val taskSeq = AtomicLong(0)

    fun begin(tool: String, arguments: JSONObject): String {
        val id = UUID.randomUUID().toString()
        val topicId = BrowserTopicGraph.ensure(arguments, tool = tool)
        if (topicId.isNotBlank()) activityTopics[id] = topicId
        val activity = BrowserDeskActivity(
            id = id,
            verb = verbForTool(tool, arguments, running = true),
            detail = detailForTool(tool, arguments),
        )
        inflight[id] = activity
        recordSource(
            url = arguments.optString("url").ifBlank { arguments.optJSONArray("urls")?.optString(0).orEmpty() },
            title = arguments.optString("title").ifBlank { arguments.optString("query") },
        )
        noteReadingStart(activity.verb, arguments, topicId)
        recordTaskProgress(
            BrowserDeskTask(
                id = id,
                topic = topicId,
                tool = tool,
                state = BrowserTaskState.Running,
                createdAtMillis = System.currentTimeMillis(),
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
        // Announce the activity, but do not seed a brand-new research group with whatever page
        // happens to be on screen - that is how a subagent tab comes to show the previous task's
        // page before it has fetched anything of its own.
        val ownPreview = if (topicId.isNotBlank()) _state.value.previewsByTopic[topicId] else null
        publish(
            preview = ownPreview ?: _state.value.preview,
            topicId = topicId,
            claimTopic = ownPreview != null,
        )
        return id
    }

    fun complete(id: String, tool: String, rawResult: String) {
        inflight.remove(id)
        val resultJson = runCatching { JSONObject(rawResult) }.getOrElse {
            JSONObject().put("ok", true)
        }
        recordTaskResult(id, tool, resultJson)
        val topicId = activityTopics.remove(id).orEmpty().ifBlank { BrowserTopicGraph.lastTopicId() }
        val incoming = previewFromResult(tool, rawResult)
        val current = _state.value
        val existing = if (topicId.isNotBlank()) {
            current.previewsByTopic[topicId]
                ?: current.previewsByTopic.entries
                    .firstOrNull { (key, _) -> browserTopicKeysRelated(key, topicId) }?.value
                ?: if (current.previewsByTopic.isEmpty()) current.preview else BrowserDeskPreview()
        } else {
            current.preview
        }
        val preview = mergeBrowserTopicPreview(existing, incoming, tool)
        if (isInPlaceArticleTool(tool, incoming)) {
            clearReading()
        }
        recordPreviewSources(preview)
        if (
            preview.kind == BrowserDeskPreviewKind.Search ||
            preview.kind == BrowserDeskPreviewKind.Images
        ) {
            BrowserTopicGraph.ingestPreview(preview)
        }
        publish(preview, topicId)
    }

    fun fail(id: String) {
        recordTaskResult(
            id,
            _state.value.tasks.firstOrNull { it.id == id }?.tool.orEmpty(),
            JSONObject().put("ok", false).put("code", "execution_interrupted"),
        )
        inflight.remove(id)
        activityTopics.remove(id)
        if (inflight.values.none { activity ->
                activity.verb == BrowserDeskVerb.Reading || activity.verb == BrowserDeskVerb.Opening
            }
        ) {
            clearReading()
        }
        publish(_state.value.preview, _state.value.uiPreviewTopicId)
    }

    fun selectUiTopic(topicId: String) {
        val id = topicId.trim()
        if (id.isBlank()) return
        emit(BrowserDeskEvent.TopicSelected(topicId = id, atMillis = System.currentTimeMillis()))
    }


    fun hydrate(
        preview: BrowserDeskPreview,
        byTopic: Map<String, BrowserDeskPreview> = emptyMap(),
    ) {
        if (preview.kind == BrowserDeskPreviewKind.Empty && byTopic.isEmpty()) return
        inflight.clear()
        activityTopics.clear()
        taskSeq.set(0)
        clearReading()
        openedUrl.set("")
        userTakeover.set(false)
        userTakeoverReason.set("")
        liveSurfaceHeld.set(false)
        emit(
            BrowserDeskEvent.Hydrated(
                preview = preview,
                previewsByTopic = byTopic,
                uiPreviewTopicId = byTopic.keys.firstOrNull().orEmpty(),
                atMillis = System.currentTimeMillis(),
            ),
        )
    }

    fun clear() {
        inflight.clear()
        activityTopics.clear()
        taskSeq.set(0)
        synchronized(sourceByUrl) { sourceByUrl.clear() }
        resetTurnBudgets()
        deepSearchThisTurn.set(false)
        lookupThenOperateThisTurn.set(false)
        operateUrlThisTurn.set("")
        clearReading()
        openedUrl.set("")
        userTakeover.set(false)
        userTakeoverReason.set("")
        liveSurfaceHeld.set(false)
        emit(BrowserDeskEvent.Cleared(atMillis = System.currentTimeMillis()))
    }

    fun clearReadLedger() {
        synchronized(readLedger) { readLedger.clear() }
        synchronized(failedLedger) { failedLedger.clear() }
    }

    fun lookupFailed(url: String): BrowserFailedRecord? {
        val key = normalizeBrowsedUrl(url).ifBlank { url.trim() }
        if (key.isBlank()) return null
        synchronized(failedLedger) {
            return failedLedger[key]
        }
    }

    fun recordFailed(url: String, code: String, errmsg: String = "") {
        val key = normalizeBrowsedUrl(url).ifBlank { url.trim() }
        if (key.isBlank()) return
        if (isStaleMissingPageTitle(url, errmsg)) return
        synchronized(failedLedger) {
            if (failedLedger.containsKey(key)) return
            failedLedger[key] = BrowserFailedRecord(
                url = url.trim(),
                code = code.trim().ifBlank { "not_found" },
                errmsg = errmsg.trim(),
                failedAtMillis = System.currentTimeMillis(),
            )
            while (failedLedger.size > BrowserReadLedgerMax) {
                val oldest = failedLedger.keys.firstOrNull() ?: break
                failedLedger.remove(oldest)
            }
        }
    }

    fun lookupRead(url: String): BrowserReadRecord? {
        val key = normalizeBrowsedUrl(url)
        if (key.isBlank()) return null
        synchronized(readLedger) {
            return readLedger[key]
        }
    }

    fun recordRead(url: String, title: String, text: String) {
        val key = normalizeBrowsedUrl(url)
        if (key.isBlank()) return
        // This page is read; the rest of a batch may still be in flight, so drop just this one.
        readingUrls.removeAll { candidate -> normalizeBrowsedUrl(candidate) == key }
        if (normalizeBrowsedUrl(readingUrl.get()) == key) {
            readingUrl.set(readingUrls.firstOrNull().orEmpty())
        }
        val excerpt = text.trim().replace(Regex("\\s+"), " ").take(200)
        synchronized(readLedger) {
            val existing = readLedger.remove(key)
            if (existing != null) {
                readLedger[key] = existing
            } else {
                readLedger[key] = BrowserReadRecord(
                    url = url.trim(),
                    title = title.trim(),
                    excerpt = excerpt,
                    charCount = text.length,
                    firstReadAtMillis = System.currentTimeMillis(),
                )
                while (readLedger.size > BrowserReadLedgerMax) {
                    val oldest = readLedger.keys.firstOrNull() ?: break
                    readLedger.remove(oldest)
                }
            }
        }
        noteFetchedHit(url, excerpt.ifBlank { text }, title)
        finishReading()
    }

    fun finishReading() {
        clearReading()
        publish(_state.value.preview, _state.value.uiPreviewTopicId)
    }

    /** The user tapped a result: show them that page, whatever kind of turn this is. */
    fun requestOpenUrl(url: String) {
        val trimmed = url.trim()
        if (!looksLikeHttpUrl(trimmed)) return
        openedUrl.set(trimmed)
        liveSurfaceHeld.set(true)
        publish(_state.value.preview, _state.value.uiPreviewTopicId)
    }

    /** The page the current turn asked to stay on, if any. */
    fun heldLiveUrl(): String = openedUrl.get().orEmpty()

    /**
     * A turn that has been searching and has no page to operate on is doing research. Its card
     * shows the pages it found; the live web surface is not what the user is looking at.
     */
    private fun researchOnlyTurn(): Boolean =
        !userTakeover.get() &&
            operateUrlThisTurn.get().orEmpty().isBlank() &&
            searchCountThisTurn.get() > 0

    /**
     * Hold the live web surface on a page.
     *
     * Every browser tool call used to land here — `WebMcpHost.execute` calls it for anything that
     * needs a workspace — so a plain search ended with `keepLiveSurface` set and the card showing
     * the live Gecko tab, which was still parked on the search engine's results page. During a
     * lookup the card's job is the result cards; the live surface is for "open this page and do
     * something on it".
     */
    fun holdLivePage(url: String = "") {
        val trimmed = url.trim()
        // There must be an actual page to hold. `WebMcpHost.execute` calls this for anything that
        // needs a workspace, passing the query when there is no url — so a plain search set
        // keepLiveSurface with no page at all, and the card switched to the live Gecko tab, which
        // was still parked on the search engine's results page.
        if (!looksLikeHttpUrl(trimmed) || isBrowserSerpUrl(trimmed)) return
        if (researchOnlyTurn()) return
        openedUrl.set(trimmed)
        liveSurfaceHeld.set(true)
        publish(_state.value.preview, _state.value.uiPreviewTopicId)
    }

    fun requestUserTakeover(url: String, reason: String = "challenge") {
        val trimmed = url.trim()
        if (looksLikeHttpUrl(trimmed)) openedUrl.set(trimmed)
        userTakeover.set(true)
        userTakeoverReason.set(reason.trim().ifBlank { "challenge" })
        liveSurfaceHeld.set(true)
        armUserTakeoverGate()
        publish(_state.value.preview, _state.value.uiPreviewTopicId)
    }

    fun clearUserTakeover() {
        if (!userTakeover.get() && userTakeoverReason.get().orEmpty().isBlank()) return
        userTakeover.set(false)
        userTakeoverReason.set("")
        publish(_state.value.preview, _state.value.uiPreviewTopicId)
    }

    /**
     * Set while a tool call is parked waiting for the user to clear a login or a captcha.
     *
     * Until now a challenge ended the turn: the tool returned GUI_TASK_NEEDS_TEACHING, the agent
     * stopped, and tapping 已完成验证 sent a fresh user message that started the whole task again.
     * Holding the call open across the takeover is what makes the continuation seamless — the user
     * signs in, the same call reads the page it was already on, and the turn never breaks.
     */
    private val takeoverGate = AtomicReference<CountDownLatch?>(null)

    fun armUserTakeoverGate() {
        takeoverGate.compareAndSet(null, CountDownLatch(1))
    }

    fun isAwaitingUserTakeover(): Boolean = takeoverGate.get() != null

    /** @return true when a tool call was parked on the gate and has now been released. */
    fun completeUserTakeover(): Boolean {
        val gate = takeoverGate.getAndSet(null)
        clearUserTakeover()
        gate?.countDown()
        return gate != null
    }

    fun awaitUserTakeover(timeoutMs: Long): Boolean {
        val gate = takeoverGate.get() ?: return false
        if (timeoutMs <= 0L) return false
        val cleared = runCatching {
            gate.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!cleared) takeoverGate.compareAndSet(gate, null)
        return cleared
    }

    fun clearOpenedUrl() {
        if (openedUrl.get().isBlank() && !userTakeover.get() && !liveSurfaceHeld.get()) return
        openedUrl.set("")
        userTakeover.set(false)
        userTakeoverReason.set("")
        liveSurfaceHeld.set(false)
        publish(_state.value.preview, _state.value.uiPreviewTopicId)
    }

    private fun noteFetchedHit(url: String, excerpt: String, title: String = "") {
        val current = _state.value
        val preview = current.preview
        val hitTitle = current.previewsByTopic.values.flatMap { it.hits }
            .plus(preview.hits)
            .firstOrNull { hit -> browserHitUrlMatches(hit.url, url) }
            ?.title
            .orEmpty()
            .ifBlank { title }
        recordSource(url, hitTitle)
        if (preview.hits.isEmpty()) {
            publish(preview, current.uiPreviewTopicId)
            return
        }
        val hits = orderCitedHits(markReadHits(preview.hits, url, excerpt))
        if (hits == preview.hits) {
            publish(preview, current.uiPreviewTopicId)
            return
        }
        val next = preview.copy(hits = hits)
        val byTopic = if (current.uiPreviewTopicId.isNotBlank()) {
            current.previewsByTopic + (current.uiPreviewTopicId to next)
        } else {
            current.previewsByTopic.mapValues { (_, page) ->
                if (page.hits.any { hit -> browserHitUrlMatches(hit.url, url) }) {
                    page.copy(hits = orderCitedHits(markReadHits(page.hits, url, excerpt)))
                } else {
                    page
                }
            }
        }
        emit(
            BrowserDeskEvent.Published(
                preview = next,
                topicId = current.uiPreviewTopicId,
                previewsByTopic = byTopic,
                pages = current.pages,
                activities = current.activities,
                sources = synchronized(sourceByUrl) { sourceByUrl.values.toList() },
                readingUrl = readingUrl.get(),
                readingUrls = readingUrls.toList(),
                readingExcerpt = readingExcerpt.get(),
                openedUrl = openedUrl.get(),
                userTakeover = userTakeover.get(),
                userTakeoverReason = userTakeoverReason.get().orEmpty(),
                keepLiveSurface = current.keepLiveSurface,
                atMillis = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Record which pages the finished answer actually cited.
     *
     * Called once when a turn's answer is complete. Until this runs, every hit is at most `read`;
     * this is the only writer of `cited`, which is what keeps the card's badges and the bubble's
     * footnotes describing the same list.
     */
    fun applyCitations(citations: List<BrowserCitation>) {
        val current = _state.value
        val byTopic = current.previewsByTopic.mapValues { (topicId, preview) ->
            val scoped = citations.filter { it.topicId.isBlank() || it.topicId == topicId }
            val hits = applyCitationsToHits(preview.hits, scoped)
            if (hits == preview.hits) preview else preview.copy(hits = orderCitedHits(hits))
        }
        val previewHits = applyCitationsToHits(current.preview.hits, citations)
        val preview = if (previewHits == current.preview.hits) {
            current.preview
        } else {
            current.preview.copy(hits = orderCitedHits(previewHits))
        }
        if (byTopic == current.previewsByTopic && preview == current.preview) return
        emit(
            BrowserDeskEvent.CitationsApplied(
                preview = preview,
                previewsByTopic = byTopic,
                atMillis = System.currentTimeMillis(),
            ),
        )
    }

    fun noteSearch(): Int = searchCountThisTurn.incrementAndGet()

    fun searchCount(): Int = searchCountThisTurn.get()

    internal fun configureLookupLoop(
        deepSearch: Boolean,
        operateUrl: String = "",
        lookupThenOperate: Boolean = false,
        keepLivePage: Boolean = false,
    ) {
        turnSeq.incrementAndGet()
        takeoverGate.getAndSet(null)?.countDown()
        if (keepLivePage) {
            userTakeover.set(false)
            userTakeoverReason.set("")
            liveSurfaceHeld.set(true)
            emptyFormListsThisTurn.set(0)
            waitTimeoutsThisTurn.set(0)
            pageJsThisTurn.set(0)
            val official = sanitizeBrowserActUrl(operateUrl)
            operateUrlThisTurn.set(official)
            if (official.isNotBlank()) openedUrl.set(official)
            // Keeping the live page does not mean keeping the last task's research. Those
            // per-topic previews belong to the turn that produced them; carrying them over is
            // why opening the card showed the previous task's page.
            emit(
                BrowserDeskEvent.TurnRetired(
                    keepLiveSurface = true,
                    openedUrl = openedUrl.get(),
                    atMillis = System.currentTimeMillis(),
                ),
            )
            publish(_state.value.preview, "")
            return
        }
        resetTurnBudgets()
        openedUrl.set("")
        userTakeover.set(false)
        userTakeoverReason.set("")
        liveSurfaceHeld.set(false)
        clearReading()
        // A fresh task starts with a blank browser card. Leaving the previous turn's preview in
        // place is why an email-drafting turn opened with the last task's web card still on screen.
        // Two consecutive updates used to do this, the second repeating most of the first. One
        // event says what happened - the turn was retired - and the projection decides what that
        // means for each field.
        emit(
            BrowserDeskEvent.TurnRetired(
                keepLiveSurface = false,
                openedUrl = "",
                atMillis = System.currentTimeMillis(),
            ),
        )
        deepSearchThisTurn.set(deepSearch)
        val official = sanitizeBrowserActUrl(operateUrl)
        operateUrlThisTurn.set(official)
        lookupThenOperateThisTurn.set(lookupThenOperate && official.isBlank())
    }

    internal fun isDeepSearchThisTurn(): Boolean = deepSearchThisTurn.get()

    internal fun operateUrlThisTurn(): String = operateUrlThisTurn.get().orEmpty()

    internal fun isLookupThenOperateThisTurn(): Boolean = lookupThenOperateThisTurn.get()

    internal fun lookupOnlyBlockedMessage(tool: String): String? {
        if (!lookupThenOperateThisTurn.get()) return null
        val name = tool.trim().lowercase().substringAfterLast("__").replace('-', '_')
        if (name !in LookupOnlyBlockedTools) return null
        return "Lookup round only: do not call $name. Search, open result pages, harvest the official URL, then stop."
    }

    internal fun officialActUrl(userText: String = ""): String =
        officialBrowserActUrl(_state.value, userText)

    fun notePageJs(): Int = pageJsThisTurn.incrementAndGet()

    fun pageJsCount(): Int = pageJsThisTurn.get()

    fun noteEmptyFormList(): Int = emptyFormListsThisTurn.incrementAndGet()

    fun noteFormFieldsFound() {
        emptyFormListsThisTurn.set(0)
    }

    fun emptyFormLists(): Int = emptyFormListsThisTurn.get()

    private fun resetTurnBudgets() {
        searchCountThisTurn.set(0)
        waitTimeoutsThisTurn.set(0)
        pageJsThisTurn.set(0)
        emptyFormListsThisTurn.set(0)
        firstHopGranted.set(false)
        firstWebSearchReturned.set(false)
        firstHopQuery.set("")
        firstHopLatch.getAndSet(CountDownLatch(1)).countDown()
        admittedQueries.clear()
    }

    internal fun admitWebSearch(query: String = ""): WebSearchAdmit {
        val normalized = normalizeLookupQuery(query)
        if (normalized.isNotBlank() && admittedQueries.any { it == normalized }) {
            return WebSearchAdmit.Allow
        }
        if (!deepSearchThisTurn.get() && !firstWebSearchReturned.get()) {
            if (firstHopGranted.compareAndSet(false, true)) {
                firstHopQuery.set(normalized)
                noteSearch()
                if (normalized.isNotBlank()) admittedQueries += normalized
                return WebSearchAdmit.Allow
            }
            if (normalized.isNotBlank() && normalized == firstHopQuery.get()) {
                return WebSearchAdmit.Allow
            }
            return WebSearchAdmit.WaitForFirstHop
        }
        if (normalized.isNotBlank() && admittedQueries.any { searchQueriesTooSimilar(it, normalized) }) {
            return WebSearchAdmit.Similar
        }
        val budget = if (deepSearchThisTurn.get()) BrowserSearchDeepBudget else BrowserSearchSoftBudget
        if (searchCount() >= budget) return WebSearchAdmit.Budget
        noteSearch()
        if (normalized.isNotBlank()) admittedQueries += normalized
        return WebSearchAdmit.Allow
    }

    internal fun lastAdmittedQuery(): String = admittedQueries.lastOrNull().orEmpty()

    internal fun markWebSearchReturned() {
        firstWebSearchReturned.set(true)
        firstHopLatch.get().countDown()
    }

    internal fun waitForFirstWebSearch(timeoutMs: Long = 12_000L): Boolean {
        if (deepSearchThisTurn.get() || firstWebSearchReturned.get()) return true
        if (timeoutMs <= 0L) return firstWebSearchReturned.get()
        runCatching { firstHopLatch.get().await(timeoutMs, TimeUnit.MILLISECONDS) }
        return firstWebSearchReturned.get()
    }

    internal fun admitWebSearchAfterWait(
        query: String,
        timeoutMs: Long = 12_000L,
    ): WebSearchAdmit {
        val first = admitWebSearch(query)
        if (first != WebSearchAdmit.WaitForFirstHop) return first
        if (!waitForFirstWebSearch(timeoutMs)) return WebSearchAdmit.WaitForFirstHop
        return admitWebSearch(query)
    }

    fun noteWaitTimeout(): Int = waitTimeoutsThisTurn.incrementAndGet()

    fun waitTimeouts(): Int = waitTimeoutsThisTurn.get()

    fun recordSearch(query: String, url: String, hits: List<BrowserDeskHit>) {
        val incoming = BrowserDeskPreview(
            kind = if (hits.isEmpty()) BrowserDeskPreviewKind.Snapshot else BrowserDeskPreviewKind.Search,
            url = url,
            title = query,
            hits = hits,
        )
        BrowserTopicGraph.recordHits(query, hits)
        val topicId = BrowserTopicGraph.ensure(
            org.json.JSONObject().put("query", query),
            tool = "search_web",
        )
        // This runs mid-tool, so it is what the live card renders while the agent works. Publishing
        // the raw preview replaced the topic outright, and each follow-up search or image lookup
        // wiped the hits the previous one had put on screen. Merge the way complete() does.
        val current = _state.value
        val existing = if (topicId.isNotBlank()) {
            current.previewsByTopic[topicId] ?: BrowserDeskPreview()
        } else {
            current.preview
        }
        publish(mergeBrowserTopicPreview(existing, incoming, "search"), topicId)
    }

    fun annotateToolResult(tool: String, raw: String): String {
        val name = tool.trim().lowercase().substringAfterLast("__").replace('-', '_')
        if (name != "page_read" && name != "tabs_navigate") return raw
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        val url = json.optString("url")
        if (name == "page_read" && json.optBoolean("ok", true)) {
            val text = json.optString("text")
            if (url.isNotBlank() && text.isNotBlank()) {
                val existing = lookupRead(url)
                if (existing != null) {
                    json.put("already_read", true)
                    json.put("first_read_at", existing.firstReadAtMillis)
                }
                recordRead(url, json.optString("title"), text)
                return json.toString()
            }
        }
        if (name == "tabs_navigate") {
            val existing = lookupRead(url)
            if (existing != null) {
                json.put("already_read", true)
                json.put("first_read_at", existing.firstReadAtMillis)
                return json.toString()
            }
        }
        return raw
    }

    private fun publish(
        preview: BrowserDeskPreview,
        topicId: String = "",
        claimTopic: Boolean = true,
    ) {
        val pages = rememberPreviewPages(_state.value.pages, preview)
        val current = _state.value
        val byTopic = if (claimTopic && topicId.isNotBlank() &&
            preview.kind != BrowserDeskPreviewKind.Empty
        ) {
            current.previewsByTopic + (topicId to preview)
        } else {
            current.previewsByTopic
        }
        val uiId = current.uiPreviewTopicId.ifBlank { topicId }
        val shown = if (uiId.isNotBlank()) byTopic[uiId] ?: preview else preview
        // The in-flight observations are snapshotted into the event rather than read at replay
        // time: those fields describe now, and an event describes then.
        emit(
            BrowserDeskEvent.Published(
                preview = shown,
                topicId = uiId,
                previewsByTopic = byTopic,
                pages = pages,
                activities = inflight.values.toList(),
                sources = synchronized(sourceByUrl) { sourceByUrl.values.toList() },
                readingUrl = readingUrl.get(),
                readingUrls = readingUrls.toList(),
                readingExcerpt = readingExcerpt.get(),
                openedUrl = openedUrl.get(),
                userTakeover = userTakeover.get(),
                userTakeoverReason = userTakeoverReason.get().orEmpty(),
                keepLiveSurface = liveSurfaceHeld.get(),
                atMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun noteReadingStart(
        verb: BrowserDeskVerb,
        arguments: JSONObject,
        topicId: String,
    ) {
        if (verb != BrowserDeskVerb.Reading && verb != BrowserDeskVerb.Opening) return
        if (arguments.optBoolean("images")) return
        // A batch fetch carries its targets in `urls`; reading only `url` left the whole batch
        // invisible on the card.
        val batch = arguments.optJSONArray("urls")
        val targets = buildList {
            if (batch != null) {
                for (index in 0 until batch.length()) add(batch.optString(index).trim())
            }
            add(arguments.optString("url").ifBlank { arguments.optString("query") }.trim())
        }.filter { candidate -> looksLikeHttpUrl(candidate) && !isBrowserSerpUrl(candidate) }
        if (targets.isEmpty()) return
        val url = targets.first()
        readingUrls.addAllAbsent(targets)
        readingUrl.set(url)
        val existing = if (topicId.isNotBlank()) {
            _state.value.previewsByTopic[topicId] ?: _state.value.preview
        } else {
            _state.value.preview
        }
        val hit = existing.hits.firstOrNull { hit -> browserHitUrlMatches(hit.url, url) }
        readingExcerpt.set(
            hit?.readExcerpt?.ifBlank { hit.snippet }.orEmpty().ifBlank { hit?.snippet.orEmpty() },
        )
    }

    private fun clearReading() {
        readingUrl.set("")
        readingUrls.clear()
        readingExcerpt.set("")
    }

    fun noteReadingTarget(url: String) {
        val trimmed = url.trim()
        if (!looksLikeHttpUrl(trimmed) || isBrowserSerpUrl(trimmed)) return
        readingUrl.set(trimmed)
        readingUrls.addAllAbsent(listOf(trimmed))
        val hit = _state.value.previewsByTopic.values.flatMap { it.hits }
            .plus(_state.value.preview.hits)
            .firstOrNull { hit -> browserHitUrlMatches(hit.url, trimmed) }
        readingExcerpt.set(
            hit?.readExcerpt?.ifBlank { hit.snippet }.orEmpty().ifBlank { hit?.snippet.orEmpty() },
        )
        publish(_state.value.preview, _state.value.uiPreviewTopicId)
    }

    private fun recordPreviewSources(preview: BrowserDeskPreview) {
        preview.hits.filter { hit -> hit.cited }.forEach { hit ->
            recordSource(hit.url, hit.title)
        }
        if (
            preview.kind == BrowserDeskPreviewKind.Article &&
            looksLikeHttpUrl(preview.url) &&
            !isBrowserSerpUrl(preview.url)
        ) {
            recordSource(preview.url, preview.title)
        }
    }

    private fun recordSource(url: String, title: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return
        if (isNonContentBrowserHost(trimmed)) return
        synchronized(sourceByUrl) {
            if (sourceByUrl.containsKey(trimmed)) return
            val index = sourceByUrl.size + 1
            val host = markdownSourceHost(trimmed)
            sourceByUrl[trimmed] = KnowledgeCitation(
                index = index,
                sourceName = title.trim().ifBlank { markdownSourceHostLabel(trimmed).ifBlank { host } },
                text = title.trim().ifBlank { host },
                url = trimmed,
            )
        }
    }
}

internal fun officialBrowserActUrl(state: BrowserDeskState, userText: String = ""): String {
    val candidates = buildList {
        fun addPage(page: BrowserDeskPreview) {
            if (page.kind == BrowserDeskPreviewKind.Article) add(page.url to page.title)
            page.hits.forEach { hit -> add(hit.url to hit.title) }
        }
        add(state.openedUrl to state.preview.title)
        add(state.readingUrl to "")
        addPage(state.preview)
        state.previewsByTopic.values.forEach(::addPage)
    }
    return pickOfficialBrowserActUrl(candidates, userText = userText)
}

internal fun rememberPreviewPages(
    current: List<BrowserDeskPreview>,
    preview: BrowserDeskPreview,
): List<BrowserDeskPreview> {
    if (preview.kind == BrowserDeskPreviewKind.Empty) return current
    val key = previewPageKey(preview)
    val without = current.filterNot { previewPageKey(it) == key }
    return (without + preview).takeLast(6)
}

internal fun previewPageKey(preview: BrowserDeskPreview): String =
    listOf(preview.kind.name, preview.url.trim(), preview.title.trim())
        .joinToString("|")
        .ifBlank { preview.body.take(48) }

internal fun verbForTool(
    tool: String,
    arguments: JSONObject = JSONObject(),
    running: Boolean,
): BrowserDeskVerb {
    val name = tool.trim().lowercase().substringAfterLast("__").replace('-', '_')
    val query = arguments.optString("query").trim()
    val url = arguments.optString("url").trim()
    return when (name) {
        "search_web", "search_images", "history_search" -> BrowserDeskVerb.Searching
        // browser_open is the main navigation tool and browser_fetch_many does most of the page
        // reading, yet neither was named here: both fell to the `else` and reported "Noting", so
        // the card had no verb to show while the browser was doing its actual work.
        "tabs_navigate", "browser_open" ->
            if (isBrowserSearchQuery(query, url, arguments.optBoolean("images"))) {
                BrowserDeskVerb.Searching
            } else {
                BrowserDeskVerb.Opening
            }
        "http_fetch", "page_read", "page_grep", "fetchurl", "fetch_url", "fetch", "fetch_web_url",
        "browser_fetch_many", "page_recall",
        ->
            BrowserDeskVerb.Reading
        "page_settle", "browser_find_signup" -> BrowserDeskVerb.Inspecting
        "tabs_manage" -> BrowserDeskVerb.Opening
        "page_snapshot", "page_inspect", "page_image", "resources_list", "network_recent" ->
            BrowserDeskVerb.Inspecting
        "page_click", "page_fill", "page_select", "page_scroll", "page_keys", "page_hover",
        "page_form", "page_file", "page_dialog", "page_wait", "page_batch", "page_js",
        "page_screenshot", "passwords", "webmcp_call",
        -> BrowserDeskVerb.Operating
        else -> if (running) BrowserDeskVerb.Noting else BrowserDeskVerb.Idle
    }
}

internal fun detailForTool(tool: String, arguments: JSONObject): String {
    val name = tool.trim().lowercase().substringAfterLast("__").replace('-', '_')
    val query = arguments.optString("query").ifBlank {
        arguments.optJSONArray("queries")?.optString(0).orEmpty()
    }
    val url = arguments.optString("url").ifBlank { arguments.optJSONArray("urls")?.optString(0).orEmpty() }
    return when (name) {
        "search_web", "search_images", "history_search", "page_grep" ->
            humanizeBrowserSearchQuery(query).ifBlank { query.trim() }
        "tabs_navigate", "browser_open" -> {
            val human = humanizeBrowserSearchQuery(query).ifBlank { query.trim() }
            human.ifBlank { url.trim().ifBlank { markdownSourceHost(url) } }
        }
        "http_fetch", "page_read", "fetchurl", "fetch_url", "fetch", "fetch_web_url",
        "browser_fetch_many", "page_recall",
        ->
            url.trim().ifBlank { query.trim() }
        else -> markdownSourceHost(url).ifBlank { url.trim() }
    }
}

internal fun isBrowserSearchQuery(query: String, url: String, images: Boolean): Boolean {
    if (images) return true
    if (query.isBlank()) return false
    if (url.startsWith("http://") || url.startsWith("https://")) return false
    return !(query.startsWith("http://") || query.startsWith("https://"))
}

internal fun normalizeLookupQuery(raw: String): String =
    raw.lowercase()
        .replace(Regex("[\\s\\p{Punct}·—、，。！？：；“”‘’（）()\\[\\]【】]+"), "")
        .trim()

internal fun searchQueriesTooSimilar(left: String, right: String): Boolean {
    val a = normalizeLookupQuery(left)
    val b = normalizeLookupQuery(right)
    if (a.isBlank() || b.isBlank()) return false
    if (a == b) return true
    if (lookupQueryAddsOfficialAngle(left, right)) return false
    val short = if (a.length <= b.length) a else b
    val long = if (a.length <= b.length) b else a
    if (short.length >= 4 && long.contains(short)) {
        return true
    }
    val leftGrams = lookupCharBigrams(a)
    val rightGrams = lookupCharBigrams(b)
    if (leftGrams.isEmpty() || rightGrams.isEmpty()) return false
    val intersection = leftGrams.intersect(rightGrams).size
    val union = leftGrams.union(rightGrams).size
    return union > 0 && intersection * 100 / union >= 62
}

internal fun lookupQueryAddsOfficialAngle(left: String, right: String): Boolean {
    val leftMarks = lookupOfficialAngleMarks(left)
    val rightMarks = lookupOfficialAngleMarks(right)
    return leftMarks != rightMarks && (leftMarks.isNotEmpty() || rightMarks.isNotEmpty())
}

private fun lookupOfficialAngleMarks(raw: String): Set<String> {
    val hay = raw.lowercase()
    val marks = linkedSetOf<String>()
    if (hay.contains("官网") || hay.contains("官方") || hay.contains("official")) {
        marks += "official"
    }
    if (
        hay.contains("报名") ||
        hay.contains("报名页") ||
        hay.contains("register") ||
        hay.contains("signup") ||
        hay.contains("sign-up")
    ) {
        marks += "signup"
    }
    if (
        hay.contains("nvidia.cn") ||
        hay.contains("scrm.nvidia") ||
        hay.contains("site:nvidia") ||
        hay.contains("site:developer.nvidia")
    ) {
        marks += "nvidia-site"
    }
    return marks
}

private fun lookupCharBigrams(value: String): Set<String> {
    if (value.length <= 2) return setOf(value)
    return (0 until value.length - 1).mapTo(linkedSetOf()) { index ->
        value.substring(index, index + 2)
    }
}

internal fun browserDeskCaption(
    description: String,
    prompt: String,
    deskDetail: String = "",
    deskTitle: String = "",
): String {
    val preferred = listOf(deskDetail, deskTitle, description, prompt)
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() && !isBrowserLeadReminderLeak(it) }
        .orEmpty()
    return preferred
}

internal fun isBrowserLeadReminderLeak(text: String): Boolean {
    val first = text.trim().lineSequence().firstOrNull().orEmpty()
    return first.startsWith("Web, search, fetch") ||
        first.startsWith("WebSearch and FetchURL") ||
        first.contains("built-in Gecko browser") ||
        first.contains("uses Aether's built-in Gecko")
}

/**
 * Hits out of the plain-text search result the agent's WebSearch tool returns.
 *
 * Not every browser turn goes through the webmcp tools. A punched-through WebSearch is served by
 * the loopback gateway, and what lands in the tool output is the agent's *rendered* form of it:
 *
 * ```
 * Title: TechStartups https://techstartups.com › top-tech-news-today...
 * Site: techstartups.com
 * URL: https://techstartups.com/2026/09/04/top-tech-news-today-...
 * Snippet: 4 days ago · It's Friday, September 4, 2026, and the AI boom …
 *
 * ---
 * ```
 *
 * Every reader on the card's side parsed JSON and gave up on this, so a turn that searched four
 * times rebuilt to a preview holding the query and no hits — which is a card showing a lone search
 * box, for the whole turn and for the message afterwards. The format is one we emit ourselves, so
 * it is safe to parse: blocks split on `---`, `Key: value` lines within a block.
 */
internal fun searchHitsFromAgentText(rawResult: String): List<BrowserDeskHit> {
    val text = rawResult.trim()
    if (text.isEmpty() || !text.contains("URL:")) return emptyList()
    if (text.startsWith("{") || text.startsWith("[")) return emptyList()
    val hits = LinkedHashMap<String, BrowserDeskHit>()
    text.split(Regex("""\n\s*-{3,}\s*\n""")).forEach { block ->
        var title = ""
        var url = ""
        var snippet = ""
        block.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Title:", ignoreCase = true) ->
                    title = trimmed.removePrefix("Title:").removePrefix("title:").trim()
                trimmed.startsWith("URL:", ignoreCase = true) ->
                    url = trimmed.removePrefix("URL:").removePrefix("url:").trim()
                trimmed.startsWith("Snippet:", ignoreCase = true) ->
                    snippet = trimmed.removePrefix("Snippet:").removePrefix("snippet:").trim()
            }
        }
        if (!looksLikeHttpUrl(url) || isBrowserSerpUrl(url)) return@forEach
        // The rendered title trails the breadcrumb the engine drew next to it
        // ("TechStartups https://techstartups.com › top-tech-news…"); keep the name.
        val cleanTitle = title
            .substringBefore(" https://")
            .substringBefore(" http://")
            .trim()
            .ifBlank { url }
        hits.putIfAbsent(
            url,
            BrowserDeskHit(title = cleanTitle.take(180), url = url, snippet = snippet.take(280)),
        )
    }
    return hits.values.toList()
}

internal fun previewFromResult(tool: String, rawResult: String): BrowserDeskPreview? {
    val json = runCatching { JSONObject(rawResult) }.getOrNull() ?: run {
        val textHits = searchHitsFromAgentText(rawResult)
        return if (textHits.isEmpty()) {
            null
        } else {
            BrowserDeskPreview(kind = BrowserDeskPreviewKind.Search, hits = textHits)
        }
    }
    val payload = json.optJSONObject("structuredContent") ?: json
    payload.optJSONObject(AetherBrowserPreviewKey)?.let { stored ->
        browserDeskPreviewFromPersistJson(stored)?.let { return it }
    }
    val name = tool.trim().lowercase().substringAfterLast("__").replace('-', '_')
    val images = extractPreviewImages(payload)
    if (name == "search_images") {
        return BrowserDeskPreview(
            kind = BrowserDeskPreviewKind.Images,
            url = payload.optString("url"),
            title = payload.optString("query").ifBlank { payload.optString("title") },
            images = images,
        )
    }
    if (name == "search_web" || name == "history_search") {
        val hits = extractPreviewHits(payload)
        val pages = payload.optJSONArray("pages")
        val body = firstPageText(pages)
        return BrowserDeskPreview(
            kind = when {
                payload.optBoolean("images") && images.isNotEmpty() -> BrowserDeskPreviewKind.Images
                hits.isNotEmpty() -> BrowserDeskPreviewKind.Search
                body.isNotBlank() -> BrowserDeskPreviewKind.Article
                else -> BrowserDeskPreviewKind.Search
            },
            url = payload.optJSONArray("queries")?.optJSONObject(0)?.optString("search_url").orEmpty(),
            title = payload.optJSONArray("queries")?.optJSONObject(0)?.optString("query").orEmpty(),
            body = body,
            hits = hits,
            images = images,
        )
    }
    if (name == "http_fetch") {
        val pages = payload.optJSONArray("pages")
        if (pages != null && pages.length() > 0) {
            val hits = buildList {
                for (index in 0 until pages.length()) {
                    val page = pages.optJSONObject(index) ?: continue
                    add(
                        BrowserDeskHit(
                            title = markdownSourceHost(page.optString("url")).ifBlank { page.optString("url") },
                            url = page.optString("url"),
                            snippet = page.optString("text").replace("\\s+".toRegex(), " ").trim().take(140),
                        ),
                    )
                }
            }
            val first = pages.optJSONObject(0) ?: JSONObject()
            return BrowserDeskPreview(
                kind = BrowserDeskPreviewKind.Article,
                url = first.optString("url"),
                title = markdownSourceHost(first.optString("url")),
                body = first.optString("text"),
                hits = hits,
                images = images.ifEmpty { extractPreviewImages(first) },
            )
        }
        return BrowserDeskPreview(
            kind = BrowserDeskPreviewKind.Article,
            url = payload.optString("url"),
            title = markdownSourceHost(payload.optString("url")),
            body = payload.optString("text"),
            images = images,
        )
    }
    val hits = extractPreviewHits(payload)
    val url = payload.optString("url")
        .ifBlank { payload.optJSONArray("search_results")?.optJSONObject(0)?.optString("url").orEmpty() }
    if (hits.isNotEmpty()) {
        return BrowserDeskPreview(
            kind = when {
                payload.optBoolean("images") && images.isNotEmpty() -> BrowserDeskPreviewKind.Images
                else -> BrowserDeskPreviewKind.Search
            },
            url = url,
            title = payload.optString("query")
                .ifBlank { payload.optString("title") }
                .ifBlank { markdownSourceHost(url) },
            body = payload.optString("text").ifBlank { payload.optString("tree") },
            hits = hits,
            images = images,
        )
    }
    val tree = payload.optString("tree")
    val text = payload.optString("text")
    val title = payload.optString("title").ifBlank { markdownSourceHost(url) }
    return when {
        tree.isNotBlank() -> BrowserDeskPreview(
            kind = BrowserDeskPreviewKind.Snapshot,
            url = url,
            title = title,
            body = tree,
            images = images,
        )
        text.isNotBlank() || images.isNotEmpty() -> BrowserDeskPreview(
            kind = BrowserDeskPreviewKind.Article,
            url = url,
            title = title,
            body = text,
            images = images,
        )
        else -> null
    }
}

private fun extractPreviewHits(payload: JSONObject): List<BrowserDeskHit> {
    val hits = ArrayList<BrowserDeskHit>()
    val queries = payload.optJSONArray("queries")
    if (queries != null) {
        for (index in 0 until queries.length()) {
            val group = queries.optJSONObject(index) ?: continue
            collectHits(group.optJSONArray("hits"), hits)
        }
    }
    collectHits(payload.optJSONArray("hits"), hits)
    collectHits(payload.optJSONArray("search_results"), hits)
    collectHits(payload.optJSONArray("results"), hits)
    return displayBrowserSearchHits(hits)
}

internal const val AetherBrowserPreviewKey = "aether_browser_preview"
internal const val AetherBrowserPreviewsByTopicKey = "aether_browser_previews_by_topic"

internal fun looksLikeDomainLabel(text: String, url: String = ""): Boolean {
    val raw = text.trim()
    if (raw.isBlank()) return false
    val t = raw.lowercase().removePrefix("www.")
    if (' ' in t) return false
    if (t.startsWith("http://") || t.startsWith("https://")) return true
    val host = markdownSourceHost(url).lowercase()
    if (host.isNotBlank() && (t == host || host.endsWith(".$t") || t.endsWith(".$host"))) return true
    return Regex("^[a-z0-9.-]+\\.[a-z]{2,}$").matches(t)
}

private val BrowserInlineHttpUrl = Regex("""(?i)https?://[^\s<>\])]+""")

internal fun stripBrowserDisplayUrls(text: String): String =
    text.replace(BrowserInlineHttpUrl, " ").replace(Regex("\\s+"), " ").trim()

internal fun cleanBrowserSearchTitle(raw: String, url: String): String {
    var title = stripBrowserDisplayUrls(raw.trim())
    val host = markdownSourceHost(url)
    if (host.isNotBlank()) {
        title = title.replace(host, " ", ignoreCase = true)
        title = title.replace("www.$host", " ", ignoreCase = true)
    }
    title = title.replace(Regex("\\s+"), " ").trim(' ', '-', '·', '|', ':', '—', '/', '>', '.')
    if (title.isBlank() || looksLikeDomainLabel(title, url)) return ""
    return title
}

internal fun displayBrowserSearchHits(hits: List<BrowserDeskHit>): List<BrowserDeskHit> {
    val cleaned = hits.mapNotNull { hit ->
        val host = markdownSourceHost(hit.url)
        val title = cleanBrowserSearchTitle(hit.title.trim(), hit.url)
        if (title.isBlank()) return@mapNotNull null
        hit.copy(
            title = title,
            snippet = cleanBrowserSearchSnippet(hit.snippet, title, host, hit.url),
        )
    }.filter { hit ->
        hit.url.isNotBlank()
    }.distinctBy { hit ->
        normalizeBrowsedUrl(hit.url).ifBlank { hit.url }.ifBlank { hit.title }
    }
    return orderCitedHits(cleaned).take(8)
}

internal fun cleanBrowserSearchSnippet(
    raw: String,
    title: String,
    host: String = "",
    url: String = "",
): String {
    val lines = raw.split('\n', '\r').map { line -> line.trim() }.filter { it.isNotBlank() }
    val kept = ArrayList<String>()
    lines.forEach { line ->
        var snippet = stripBrowserDisplayUrls(line.replace(Regex("\\s+"), " ").trim())
        listOf(title, host, markdownSourceHost(url)).map { it.trim() }.filter { it.isNotBlank() }.forEach { lead ->
            if (snippet.startsWith(lead, ignoreCase = true)) {
                snippet = snippet.substring(lead.length).trimStart(' ', '-', '·', '|', ':', '—', '/')
            }
        }
        if (host.isNotBlank()) {
            snippet = snippet.replace(host, " ", ignoreCase = true).replace(Regex("\\s+"), " ").trim()
        }
        if (
            snippet.isBlank() ||
            snippet.equals(title, ignoreCase = true) ||
            snippet.equals(host, ignoreCase = true) ||
            looksLikeDomainLabel(snippet, url)
        ) {
            return@forEach
        }
        kept += snippet
    }
    return kept.joinToString(" ").take(140)
}

internal fun browserHitUrlMatches(left: String, right: String): Boolean {
    val a = normalizeBrowsedUrl(left).ifBlank { left.trim() }
    val b = normalizeBrowsedUrl(right).ifBlank { right.trim() }
    if (a.isBlank() || b.isBlank()) return false
    return a.equals(b, ignoreCase = true)
}

internal fun markReadHits(
    hits: List<BrowserDeskHit>,
    url: String,
    excerpt: String,
    nowMillis: Long = System.currentTimeMillis(),
): List<BrowserDeskHit> {
    if (url.isBlank() || hits.isEmpty()) return hits
    val cleaned = excerpt.replace(Regex("\\s+"), " ").trim().take(800)
    return hits.map { hit ->
        if (!browserHitUrlMatches(hit.url, url)) return@map hit
        hit.copy(
            read = true,
            readAtMillis = hit.readAtMillis.takeIf { it > 0L } ?: nowMillis,
            readExcerpt = cleaned.ifBlank { hit.readExcerpt.ifBlank { hit.snippet } },
        )
    }
}

/**
 * Stamp the resolver's verdict onto a topic's hits.
 *
 * Anything not named by [citations] is cleared, so re-running the resolver on a revised answer
 * cannot leave a stale badge behind.
 */
internal fun applyCitationsToHits(
    hits: List<BrowserDeskHit>,
    citations: List<BrowserCitation>,
    nowMillis: Long = System.currentTimeMillis(),
): List<BrowserDeskHit> {
    if (hits.isEmpty()) return hits
    return hits.map { hit ->
        val match = citations.firstOrNull { browserHitUrlMatches(hit.url, it.url) }
        if (match == null) {
            if (!hit.cited) hit else hit.copy(cited = false, citedAtMillis = 0L, citedPassageOrdinal = -1, citedQuote = "")
        } else {
            hit.copy(
                cited = true,
                citedAtMillis = hit.citedAtMillis.takeIf { it > 0L } ?: nowMillis,
                citedPassageOrdinal = match.passageOrdinal,
                citedQuote = match.quote,
            )
        }
    }
}

internal fun orderCitedHits(hits: List<BrowserDeskHit>): List<BrowserDeskHit> {
    if (hits.none { it.cited || it.read }) return hits
    val cited = hits.filter { it.cited }.sortedBy { hit -> hit.citedAtMillis }
    val readOnly = hits.filter { !it.cited && it.read }.sortedBy { hit -> hit.readAtMillis }
    val rest = hits.filter { !it.cited && !it.read }
    return cited + readOnly + rest
}

internal fun isInPlaceArticleTool(tool: String, preview: BrowserDeskPreview?): Boolean {
    val name = tool.trim().lowercase().substringAfterLast("__").replace('-', '_')
    if (name == "page_read" || name == "page_grep" || name == "http_fetch") return true
    if (name == "fetchurl" || name == "fetch_url" || name == "fetch" || name == "fetch_web_url") return true
    if (name != "tabs_navigate") return false
    val kind = preview?.kind ?: return true
    return kind != BrowserDeskPreviewKind.Search && kind != BrowserDeskPreviewKind.Images
}

internal fun mergeBrowserTopicPreview(
    existing: BrowserDeskPreview,
    incoming: BrowserDeskPreview?,
    tool: String,
): BrowserDeskPreview {
    if (incoming == null) return existing
    val mergedHits = displayBrowserSearchHits(
        (existing.hits + incoming.hits).distinctBy { hit ->
            normalizeBrowsedUrl(hit.url).ifBlank { hit.url }.ifBlank { hit.title }
        },
    )
    if (incoming.kind == BrowserDeskPreviewKind.Search && incoming.hits.isNotEmpty()) {
        return incoming.copy(
            title = incoming.title.ifBlank { existing.title },
            hits = orderCitedHits(mergedHits),
            images = incoming.images.ifEmpty { existing.images },
        )
    }
    if (incoming.kind == BrowserDeskPreviewKind.Images && incoming.images.isNotEmpty() && existing.hits.isEmpty()) {
        return incoming
    }
    if (mergedHits.isNotEmpty()) {
        val cited = if (incoming.hits.isEmpty()) {
            markReadHits(mergedHits, incoming.url, incoming.body)
        } else {
            mergedHits
        }
        // A fetch of a page that is not in this group's hit list is a standalone article and
        // should read as one. Reading a page that IS in the list is different: the list has to
        // stay on screen with that row marked as read. Keying the rule on body alone dropped the
        // hit rows the moment the agent opened its first result, which is a large part of why the
        // card looked empty while the agent worked.
        val incomingUrl = normalizeBrowsedUrl(incoming.url).ifBlank { incoming.url }
        val incomingUrlInHits = incomingUrl.isNotBlank() && mergedHits.any { hit ->
            normalizeBrowsedUrl(hit.url).ifBlank { hit.url } == incomingUrl
        }
        val isArticleWithBody = incoming.kind == BrowserDeskPreviewKind.Article &&
            incoming.body.isNotBlank() &&
            !incomingUrlInHits
        val keepSearch = !isArticleWithBody && (
            existing.kind == BrowserDeskPreviewKind.Search ||
                incoming.kind == BrowserDeskPreviewKind.Search ||
                isInPlaceArticleTool(tool, incoming)
            )
        return incoming.copy(
            kind = if (keepSearch) BrowserDeskPreviewKind.Search else incoming.kind,
            title = existing.title.ifBlank { incoming.title },
            hits = orderCitedHits(cited),
            images = incoming.images.ifEmpty { existing.images },
        )
    }
    return incoming
}

internal fun BrowserDeskPreview.toPersistJson(): JSONObject = JSONObject()
    .put("kind", kind.name)
    .put("url", url)
    .put("title", title)
    .put("body", body.take(2_000))
    .put(
        "hits",
        JSONArray().apply {
            hits.forEach { hit ->
                put(
                    JSONObject()
                        .put("title", hit.title)
                        .put("url", hit.url)
                        .put("snippet", hit.snippet)
                        .put("cited", hit.cited)
                        .put("cited_at", hit.citedAtMillis)
                        .put("read", hit.read)
                        .put("read_at", hit.readAtMillis)
                        .put("cited_passage", hit.citedPassageOrdinal)
                        .put("cited_quote", hit.citedQuote)
                        .put("excerpt", hit.readExcerpt),
                )
            }
        },
    )
    .put(
        "images",
        JSONArray().apply {
            images.forEach { image ->
                put(JSONObject().put("url", image.url).put("alt", image.alt))
            }
        },
    )

internal fun browserDeskPreviewFromPersistJson(json: JSONObject): BrowserDeskPreview? {
    val kind = runCatching {
        BrowserDeskPreviewKind.valueOf(json.optString("kind"))
    }.getOrDefault(BrowserDeskPreviewKind.Search)
    val hits = displayBrowserSearchHits(extractPreviewHits(json))
    val url = json.optString("url")
    val title = json.optString("title").ifBlank { json.optString("query") }
    val body = json.optString("body").ifBlank { json.optString("text") }
    val images = extractPreviewImages(json)
    if (kind == BrowserDeskPreviewKind.Empty &&
        title.isBlank() &&
        body.isBlank() &&
        hits.isEmpty() &&
        images.isEmpty()
    ) {
        return null
    }
    return BrowserDeskPreview(
        kind = if (hits.isNotEmpty() && kind == BrowserDeskPreviewKind.Empty) {
            BrowserDeskPreviewKind.Search
        } else {
            kind
        },
        url = url,
        title = title,
        body = body,
        hits = hits,
        images = images,
    )
}

internal fun mergeBrowserPreviewIntoOutput(
    outputJson: String,
    preview: BrowserDeskPreview,
    byTopic: Map<String, BrowserDeskPreview> = emptyMap(),
): String {
    val payload = runCatching { JSONObject(outputJson) }.getOrNull()
        ?: JSONObject().put("text", outputJson)
    val rawSanitized = sanitizeBrowserDeskPreviewForCard(preview) ?: preview
    val previous = payload.optJSONObject(AetherBrowserPreviewKey)
        ?.let { browserDeskPreviewFromPersistJson(it) }
    val keptHits = displayBrowserSearchHits(
        (previous?.hits.orEmpty() + extractPreviewHits(payload) + rawSanitized.hits).distinctBy { hit ->
            normalizeBrowsedUrl(hit.url).ifBlank { hit.url }.ifBlank { hit.title }
        },
    )
    val sanitized = if (keptHits.isEmpty()) {
        rawSanitized
    } else {
        rawSanitized.copy(
            kind = if (
                rawSanitized.kind == BrowserDeskPreviewKind.Images &&
                rawSanitized.hits.isEmpty()
            ) {
                rawSanitized.kind
            } else {
                BrowserDeskPreviewKind.Search
            },
            title = rawSanitized.title.ifBlank { previous?.title.orEmpty() },
            hits = keptHits,
        )
    }
    payload.put(AetherBrowserPreviewKey, sanitized.toPersistJson())
    if (byTopic.isNotEmpty()) {
        payload.put(
            AetherBrowserPreviewsByTopicKey,
            JSONObject().apply {
                byTopic.forEach { (topicId, topicPreview) ->
                    put(topicId, topicPreview.toPersistJson())
                }
            },
        )
    }
    if (sanitized.hits.isNotEmpty() &&
        payload.optJSONArray("hits") == null &&
        payload.optJSONArray("search_results") == null
    ) {
        payload.put(
            "hits",
            JSONArray().apply {
                sanitized.hits.forEach { hit ->
                    put(
                        JSONObject()
                            .put("title", hit.title)
                            .put("url", hit.url)
                            .put("snippet", hit.snippet)
                            .put("cited", hit.cited)
                            .put("cited_at", hit.citedAtMillis)
                            .put("read", hit.read)
                            .put("read_at", hit.readAtMillis)
                            .put("cited_passage", hit.citedPassageOrdinal)
                            .put("cited_quote", hit.citedQuote)
                            .put("excerpt", hit.readExcerpt),
                    )
                }
            },
        )
        if (payload.optString("query").isBlank() && sanitized.title.isNotBlank()) {
            payload.put("query", sanitized.title)
        }
    }
    return payload.toString()
}

internal fun previewsByTopicFromOutput(outputJson: String): Map<String, BrowserDeskPreview> {
    val payload = runCatching { JSONObject(outputJson) }.getOrNull() ?: return emptyMap()
    val stored = payload.optJSONObject(AetherBrowserPreviewsByTopicKey) ?: return emptyMap()
    val result = LinkedHashMap<String, BrowserDeskPreview>()
    stored.keys().forEach { key ->
        val preview = stored.optJSONObject(key)?.let(::browserDeskPreviewFromPersistJson) ?: return@forEach
        result[key] = preview
    }
    return result
}

private fun collectHits(array: JSONArray?, into: MutableList<BrowserDeskHit>) {
    if (array == null) return
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val title = item.optString("title").trim()
        val url = item.optString("url").ifBlank { item.optString("image") }
        if (url.startsWith("about:search-budget") ||
            url.startsWith("about:lookup-loop") ||
            url.startsWith("about:lookup-similar")
        ) continue
        if (title.isBlank() && url.isBlank()) continue
        val legacy = !item.has("read")
        into += BrowserDeskHit(
            title = title,
            url = url,
            snippet = item.optString("snippet").ifBlank { item.optString("text") }.trim().take(140),
            // Snapshots written before the read/cited split carry only `cited`, and there it meant
            // "read". Replaying it as `read` keeps historic cards honest; replaying it as `cited`
            // would keep claiming the answer used every page the agent opened.
            cited = legacy.let { if (it) false else item.optBoolean("cited") },
            citedAtMillis = if (legacy) 0L else item.optLong("cited_at"),
            read = if (legacy) item.optBoolean("cited") else item.optBoolean("read"),
            readAtMillis = if (legacy) item.optLong("cited_at") else item.optLong("read_at"),
            citedPassageOrdinal = item.optInt("cited_passage", -1),
            citedQuote = item.optString("cited_quote"),
            readExcerpt = item.optString("excerpt"),
        )
    }
}

private fun extractPreviewImages(payload: JSONObject): List<BrowserDeskImage> {
    val images = ArrayList<BrowserDeskImage>()
    val array = payload.optJSONArray("images")
    if (array != null) {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            if (item != null) {
                val url = item.optString("src")
                    .ifBlank { item.optString("image") }
                    .ifBlank { item.optString("url") }
                if (url.startsWith("http") && isUsableTopicImage(url)) {
                    images += BrowserDeskImage(
                        url = url,
                        alt = item.optString("alt").ifBlank { item.optString("title") },
                    )
                }
            } else {
                val url = array.optString(index)
                if (url.startsWith("http") && isUsableTopicImage(url)) images += BrowserDeskImage(url = url)
            }
        }
    }
    val queries = payload.optJSONArray("queries")
    if (queries != null) {
        for (index in 0 until queries.length()) {
            val group = queries.optJSONObject(index) ?: continue
            val hits = group.optJSONArray("hits") ?: continue
            for (hitIndex in 0 until hits.length()) {
                val hit = hits.optJSONObject(hitIndex) ?: continue
                val url = hit.optString("image")
                if (url.startsWith("http") && isUsableTopicImage(url)) {
                    images += BrowserDeskImage(url = url, alt = hit.optString("title"))
                }
            }
        }
    }
    extractImagesFromSnapshotTree(payload.optString("tree"), payload.optString("title")).forEach { image ->
        images += image
    }
    return images.distinctBy { it.url }.take(24)
}

internal data class BrowserDeskMosaicTile(
    val key: String,
    val fullWidth: Boolean,
    val preview: BrowserDeskPreview = BrowserDeskPreview(),
    val activity: BrowserDeskActivity? = null,
)

internal fun looksLikeBrowserAccessibilityTree(text: String): Boolean {
    if (text.isBlank()) return false
    if (!text.contains("@e")) return false
    return Regex("""@e\d+""").containsMatchIn(text)
}

internal fun sanitizeBrowserDeskPreviewForCard(preview: BrowserDeskPreview): BrowserDeskPreview? {
    if (preview.kind == BrowserDeskPreviewKind.Empty) return null
    if (isPlaceholderExampleHost(preview.url)) return null
    val hits = preview.hits.filterNot { hit -> isPlaceholderExampleHost(hit.url) }
    val treeBody = looksLikeBrowserAccessibilityTree(preview.body)
    val dropTree = treeBody || preview.kind == BrowserDeskPreviewKind.Snapshot
    val body = if (dropTree) "" else preview.body
    val title = preview.title.trim().ifBlank { markdownSourceHost(preview.url) }
    val kind = when {
        preview.images.isNotEmpty() &&
            hits.isEmpty() &&
            (preview.kind == BrowserDeskPreviewKind.Images || dropTree) ->
            BrowserDeskPreviewKind.Images
        dropTree -> BrowserDeskPreviewKind.Article
        preview.kind == BrowserDeskPreviewKind.Images && preview.images.isEmpty() ->
            BrowserDeskPreviewKind.Article
        else -> preview.kind
    }
    val cleaned = preview.copy(kind = kind, title = title, body = body, hits = hits)
    if (cleaned.title.isBlank() &&
        cleaned.body.isBlank() &&
        cleaned.hits.isEmpty() &&
        cleaned.images.isEmpty()
    ) {
        return null
    }
    return cleaned
}

internal fun browserDeskActivityTileKey(activity: BrowserDeskActivity): String {
    val host = markdownSourceHost(activity.detail)
    val token = host.ifBlank {
        activity.detail.trim().lowercase().replace(Regex("\\s+"), " ").take(48)
    }.ifBlank { "idle" }
    return "act-${activity.verb.name.lowercase()}-$token"
}

internal fun browserDeskPageTileKey(preview: BrowserDeskPreview): String {
    val url = normalizeBrowsedUrl(preview.url).ifBlank { preview.url.trim() }
    return "page-${url.ifBlank { "unknown" }}"
}

internal fun browserDeskMosaicTiles(state: BrowserDeskState, maxCards: Int = 4): List<BrowserDeskMosaicTile> {
    val activityTiles = state.activities.map { activity ->
        BrowserDeskMosaicTile(
            key = browserDeskActivityTileKey(activity),
            fullWidth = false,
            activity = activity,
        )
    }
    val current = sanitizeBrowserDeskPreviewForCard(state.preview)?.let { preview ->
        BrowserDeskMosaicTile(
            key = browserDeskPageTileKey(preview),
            fullWidth = false,
            preview = preview,
        )
    }
    val activityBudget = (maxCards - if (current != null) 1 else 0).coerceAtLeast(0)
    val keptActivities = if (activityBudget <= 0) {
        emptyList()
    } else {
        activityTiles.takeLast(activityBudget)
    }
    val tiles = (keptActivities + listOfNotNull(current)).distinctBy { it.key }.take(maxCards)
    if (tiles.size <= 1) return tiles.map { it.copy(fullWidth = true) }
    return tiles
}

internal data class BrowserTopicRailItem(
    val topicId: String,
    val preview: BrowserDeskPreview,
)

internal fun browserTopicRailItems(
    state: BrowserDeskState,
    swarmItems: List<String> = emptyList(),
    agentCount: Int = 0,
): List<BrowserTopicRailItem> {
    // Both sources must go through the same normalisation. Feeding one raw and one normalised
    // into the same set guarantees a duplicate the moment a label carries a stopword: the swarm
    // item "杭州美食 特色小吃 推荐" normalises to "杭州美食 特色小吃", the preview key does not, and
    // the rail shows the same research group twice.
    val ordered = LinkedHashSet<String>()
    val previewByKey = LinkedHashMap<String, BrowserDeskPreview>()
    fun keyOf(raw: String): String = normalizeBrowserTopicId(raw).ifBlank { raw.trim() }
    state.previewsByTopic.forEach { (topicId, preview) ->
        val key = keyOf(topicId)
        if (key.isBlank()) return@forEach
        // Keep the richest preview when two raw ids collapse to the same key.
        val existing = previewByKey[key]
        if (existing == null || browserPreviewWeight(preview) > browserPreviewWeight(existing)) {
            previewByKey[key] = preview
        }
    }
    swarmItems.forEach { item ->
        val key = keyOf(item)
        if (key.isBlank()) return@forEach
        // Fold the launch label onto the key its own results were filed under, so the tab and its
        // content stay one thing instead of two half-empty tabs.
        ordered.add(previewByKey.keys.firstOrNull { browserTopicKeysRelated(it, key) } ?: key)
    }
    // The rail is a row of agents. With no swarm labels to go on it used to take one row per
    // topic, and a topic is minted per distinct query — so a single 合肥美食 agent that ran six
    // image searches ("合肥庐州烤鸭", "合肥臭鳜鱼徽菜", …) grew six "subagent" cards. Cap the rail at
    // however many browser agents are actually running; agentCount 0 means the caller does not
    // know, and then every topic still gets a row.
    if (ordered.isEmpty()) {
        // A tab is a research group, and a group earns one by having found something of its own.
        // Capping at the number of agents was too blunt in both directions: one agent researching
        // four subjects got a single tab that never split while the work was visibly happening,
        // and the six image lookups a 合肥美食 agent ran still each looked like a subagent. Search
        // results and read articles are research; an image lookup or a bare snapshot is that same
        // agent glancing at something, so it folds into whichever group it belongs to.
        val researched = previewByKey.entries
            .filter { (_, preview) ->
                (preview.kind == BrowserDeskPreviewKind.Search && preview.hits.isNotEmpty()) ||
                    (preview.kind == BrowserDeskPreviewKind.Article && preview.body.isNotBlank())
            }
            .map { it.key }
        val ranked = when {
            researched.isNotEmpty() -> researched.toSet()
            // Nothing has landed yet: keep the rail at one tab per agent so it does not flicker
            // through a row per in-flight lookup before the first results arrive.
            agentCount > 0 && previewByKey.size > agentCount ->
                previewByKey.entries
                    .sortedByDescending { browserPreviewWeight(it.value) }
                    .take(agentCount)
                    .map { it.key }
                    .toSet()
            else -> previewByKey.keys.toSet()
        }
        previewByKey.keys.filter { it in ranked }.forEach(ordered::add)
    }
    // Pair each tab with a research group, then hand the leftovers to the tabs that matched
    // nothing.
    //
    // A swarm label and the query the agent actually ran are two different strings normalised two
    // different ways: the label "latest tech news September 2025 AI breakthroughs" reduces to
    // `latest tech news September 2025 A`, while its own results were filed under
    // `tech September AI breakthroughs`. Neither contains the other, so containment cannot bridge
    // them and the tab resolved to an empty preview — which is why the card showed a search box and
    // nothing else while four searches were streaming in. A tab that matched nothing takes the
    // richest group no other tab has claimed; being off by a label beats showing nothing.
    val claimed = HashSet<String>()
    val direct = ordered.map { topicId ->
        val key = previewByKey.keys.firstOrNull { candidate ->
            candidate !in claimed && (candidate == topicId || browserTopicKeysRelated(candidate, topicId))
        }
        if (key != null) claimed += key
        topicId to key
    }
    val spare = previewByKey.keys
        .filterNot { it in claimed }
        .sortedByDescending { browserPreviewWeight(previewByKey.getValue(it)) }
        .toMutableList()
    return direct.map { (topicId, key) ->
        val resolved = key ?: spare.removeFirstOrNull()
        BrowserTopicRailItem(
            topicId = topicId,
            preview = resolved?.let(previewByKey::get) ?: BrowserDeskPreview(),
        )
    }
}

/** How much a preview actually has to show, used to pick a winner when ids collapse. */
internal fun browserPreviewWeight(preview: BrowserDeskPreview): Int =
    preview.hits.size * 2 + preview.images.size + preview.body.length / 200

/**
 * Do two topic keys name the same research group?
 *
 * A subagent's rail label and the query it actually runs are different strings that normalise
 * differently: the launch "西安美食攻略 必吃特色小吃" reduces to 西安美食攻略, while the search
 * "西安美食 肉夹馍 羊肉泡馍 凉皮 特色小吃" reduces to 西安美食. Exact-key lookup therefore misses,
 * the tab renders an empty preview for the whole run, and the results only surface once the turn
 * is persisted and re-read from the tool output. Containment closes that gap without merging two
 * genuinely different groups (西安美食 vs 西安景点 share no containment).
 */
internal fun browserTopicKeysRelated(a: String, b: String): Boolean {
    val left = normalizeBrowserTopicId(a).ifBlank { a.trim() }
    val right = normalizeBrowserTopicId(b).ifBlank { b.trim() }
    if (left.isBlank() || right.isBlank()) return false
    if (left.equals(right, ignoreCase = true)) return true
    val shorter = if (left.length <= right.length) left else right
    val longer = if (left.length <= right.length) right else left
    if (shorter.length < 2) return false
    return longer.contains(shorter, ignoreCase = true)
}

/**
 * The preview a rail tab should render, resolved by topic rather than by exact key.
 *
 * Falling back to [BrowserDeskState.preview] on a miss is what put another agent's page inside a
 * tab, so that fallback now applies only while nothing at all has been filed.
 */
internal fun browserPreviewForTopic(
    state: BrowserDeskState,
    topicId: String,
): BrowserDeskPreview {
    val id = topicId.trim()
    if (id.isBlank() || state.previewsByTopic.isEmpty()) return state.preview
    state.previewsByTopic[id]?.let { return it }
    val related = state.previewsByTopic.entries
        .filter { (key, _) -> browserTopicKeysRelated(key, id) }
        .maxByOrNull { (_, preview) -> browserPreviewWeight(preview) }
    if (related != null) return related.value
    return BrowserDeskPreview()
}

internal fun browserDeskShowsTopicRail(
    state: BrowserDeskState,
    swarmItems: List<String> = emptyList(),
): Boolean = browserTopicRailItems(state, swarmItems).size > 1

internal fun collectBrowserInlineImages(
    state: BrowserDeskState,
    curatedOnly: Boolean = false,
): List<BrowserDeskImage> {
    val collected = ArrayList<BrowserDeskImage>()
    (state.pages + state.preview + state.previewsByTopic.values).forEach { page ->
        if (curatedOnly && page.kind != BrowserDeskPreviewKind.Images) return@forEach
        collected += page.images
        if (!curatedOnly) {
            collected += extractImagesFromSnapshotTree(page.body, page.title)
        }
    }
    return collected.distinctBy { it.url }
}

private val snapshotImageUrlPattern = Regex("""https?://[^\s\"]+""")
private val snapshotImageAltPattern = Regex("\"([^\"]+)\"")
private val snapshotImageSizePattern = Regex("""\b\d{2,4}x\d{2,4}\b""", RegexOption.IGNORE_CASE)
private val snapshotImageExtPattern = Regex(
    """\.(?:jpe?g|png|webp|gif|bmp)(?:$|[?#])""",
    RegexOption.IGNORE_CASE,
)

internal fun extractImagesFromSnapshotTree(tree: String, pageTitle: String = ""): List<BrowserDeskImage> {
    if (tree.isBlank()) return emptyList()
    val images = ArrayList<BrowserDeskImage>()
    tree.lineSequence().forEach { line ->
        val urls = snapshotImageUrlPattern.findAll(line).map { match -> match.value }.toList()
        if (urls.isEmpty()) return@forEach
        val lower = line.lowercase()
        val looksLikeImageLine =
            lower.contains(" image ") ||
                lower.contains(" img ") ||
                lower.contains("role=img") ||
                snapshotImageSizePattern.containsMatchIn(line)
        urls.forEach { raw ->
            val url = raw.trim().trimEnd(',', ';', ')', ']')
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@forEach
            val lowerUrl = url.lowercase()
            if (
                lowerUrl.contains("1x1") ||
                lowerUrl.contains("favicon") ||
                lowerUrl.contains("/pixel") ||
                lowerUrl.contains("sprite")
            ) {
                return@forEach
            }
            val looksLikeImageUrl =
                lowerUrl.contains("tse") && lowerUrl.contains("bing.net") ||
                    lowerUrl.contains("th.bing.com") ||
                    lowerUrl.contains("bing.net/th") ||
                    snapshotImageExtPattern.containsMatchIn(lowerUrl) ||
                    lowerUrl.contains("imgurl=") ||
                    lowerUrl.contains("murl=")
            if (!looksLikeImageLine && !looksLikeImageUrl) return@forEach
            val alt = snapshotImageAltPattern.find(line)?.groupValues?.getOrNull(1)
                ?.trim()
                .orEmpty()
            images += BrowserDeskImage(url = url, alt = alt)
        }
    }
    return images.distinctBy { it.url }
}

private fun firstPageText(pages: JSONArray?): String {
    if (pages == null || pages.length() == 0) return ""
    return pages.optJSONObject(0)?.optString("text").orEmpty()
}

private val BrowsedUrlTrackingParams = setOf(
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
    "spm", "from", "fbclid", "gclid", "yclid",
)

internal fun normalizeBrowsedUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return ""
    val scheme = uri.scheme.orEmpty().lowercase()
    if (scheme != "http" && scheme != "https") return ""
    var host = uri.host.orEmpty().lowercase()
    if (host.startsWith("www.")) host = host.removePrefix("www.")
    if (host.startsWith("m.") && host.count { it == '.' } >= 2) host = host.removePrefix("m.")
    if (host.isBlank()) return ""
    val port = when {
        uri.port <= 0 -> ""
        scheme == "http" && uri.port == 80 -> ""
        scheme == "https" && uri.port == 443 -> ""
        else -> ":${uri.port}"
    }
    var path = uri.path.orEmpty()
    if (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
    val query = uri.rawQuery.orEmpty()
        .split("&")
        .map { it.trim() }
        .filter { part ->
            if (part.isBlank()) return@filter false
            val name = part.substringBefore("=").lowercase()
            name.isNotBlank() && name !in BrowsedUrlTrackingParams && !name.startsWith("utm_")
        }
        .sorted()
        .joinToString("&")
    return buildString {
        append(scheme).append("://").append(host).append(port).append(path)
        if (query.isNotBlank()) append("?").append(query)
    }
}

internal fun browserFetchFailedMarkdown(url: String, code: String, errmsg: String): String {
    val host = markdownSourceHost(url).ifBlank { url }
    return buildString {
        append("# Fetch failed: ").append(host).append("\n\n")
        append("status: failed\n")
        append("reason: ").append(code.trim()).append(" ").append(errmsg.trim()).append("\n\n")
        append("This page could not be read. Try a different result from your search list, ")
        append("or search again with different terms. Do not retry this exact URL.\n")
    }
}

internal fun browserFetchSkippedMarkdown(record: BrowserReadRecord): String {
    return buildString {
        append("# Already read: ").append(record.title.ifBlank { record.url }).append("\n\n")
        append("url: ").append(record.url).append("\n")
        append("status: skipped_duplicate\n")
        append("You already read this page earlier in this conversation (")
        append(record.charCount)
        append(" chars). Reuse what you extracted then; do not fetch it again. Pick a different source.\n")
        if (record.excerpt.isNotBlank()) {
            append("\n")
            append(record.excerpt)
            append("\n")
        }
    }
}
