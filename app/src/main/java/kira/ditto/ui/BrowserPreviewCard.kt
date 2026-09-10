package kira.ditto.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kira.ditto.R
import kira.ditto.browser.AetherBrowserRuntime
import kira.ditto.browser.BrowserDesk
import kira.ditto.browser.BrowserDeskActivity
import kira.ditto.browser.BrowserDeskPreview
import kira.ditto.browser.BrowserDeskPreviewKind
import kira.ditto.browser.BrowserDeskState
import kira.ditto.browser.BrowserDeskVerb
import kira.ditto.browser.BrowserEngineSurface
import kira.ditto.browser.BrowserTopicGraph
import kira.ditto.browser.browserDeskCaption
import kira.ditto.browser.browserTopicRailItems
import kira.ditto.browser.isBrowserLeadReminderLeak
import kira.ditto.browser.previewFromResult
import kira.ditto.browser.mergeBrowserPreviewIntoOutput
import kira.ditto.browser.browserPreviewForTopic
import kira.ditto.browser.browserPreviewWeight
import kira.ditto.browser.browserTopicKeysRelated
import mozilla.components.lib.state.ext.flow
import kira.ditto.browser.previewsByTopicFromOutput
import kira.ditto.browser.sanitizeBrowserDeskPreviewForCard
import kira.ditto.browser.displayBrowserSearchHits
import kira.ditto.browser.browserHitUrlMatches
import kira.ditto.browser.humanizeBrowserSearchQuery
import kira.ditto.browser.looksLikeHttpUrl
import kira.ditto.data.PersonaAvatarSpec
import kira.ditto.ui.theme.AetherIsDark
import kira.ditto.ui.theme.AetherPrimary
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherSurface
import kira.ditto.ui.theme.AetherSurfaceHigh
import kotlinx.coroutines.delay
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView

private val BrowserPreviewSearchAccent = Color(0xFFC45BC8)
private val BrowserPreviewCardGray = Color(0xFF808080)
private const val BrowserPreviewCardAlpha = 0.88f
private val BrowserPreviewTabIconSize = 18.dp
private val BrowserPreviewMinTabWidth = 48.dp
private val BrowserPreviewLabeledTabMinWidth = 108.dp
private val BrowserPreviewAvatarTabMinWidth = 32.dp
private val BrowserPreviewOverflowWidth = 32.dp
private val BrowserPreviewBodyFade = 12.dp
private val BrowserPreviewCardCorner = 20.dp
private val BrowserPreviewResizeOutset = 10.dp
private val BrowserPreviewResizeStroke = 3.dp
private val BrowserPreviewResizeGap = 5.dp
private val BrowserPreviewHeaderIconSize = 20.dp
private val BrowserPreviewHeaderPadHorizontal = 14.dp
private val BrowserPreviewHeaderPadVertical = 10.dp
internal const val BrowserPreviewMinInlineTabs = 2

internal data class BrowserPreviewHost(
    val visible: Boolean = false,
    val attachToPending: Boolean = false,
    val identityKey: String = "",
    val deskState: BrowserDeskState = BrowserDeskState(),
    val invocations: List<ChatToolInvocation> = emptyList(),
    val sessionRunning: Boolean = false,
    val messageIds: Set<String> = emptySet(),
    /**
     * Every agent message of the turn in progress.
     *
     * [messageIds] only fills once a message has a committed browser invocation, which happens at
     * the end of the turn — so it cannot answer "is this card the running turn?" while the turn is
     * running. This can.
     */
    val currentTurnMessageIds: Set<String> = emptySet(),
    /**
     * Whether the turn in progress has actually touched the browser.
     *
     * [currentTurnMessageIds] answers "is this the current turn"; this answers "did the current
     * turn do browser work". Those are different questions, and conflating them is what made a
     * plain-text turn inherit the previous turn's card: the desk stays populated across the turn
     * boundary, so a temporal guard alone lets any current-turn message pick up stale research.
     */
    val currentTurnBrowserWork: Boolean = false,
    val workspaceDirectory: String = "",
    val allowRootImageRead: Boolean = false,
    val onOpenLink: (String) -> Unit = {},
    val onVerifyContinue: () -> Unit = {},
) {
    fun shouldShowOnPending(): Boolean = visible && attachToPending

    fun shouldShowOnMessages(messages: List<ChatMessage>): Boolean {
        val onCurrentLive = messages.any { message -> message.id in messageIds }
        if (attachToPending && onCurrentLive) return false
        if (onCurrentLive) return true
        if (messages.any(::messageHasBrowserDeskWork)) return true
        // Fallback for the turn in progress, when it opened a page directly rather than through a
        // tool invocation the message carries.
        val belongsToCurrentTurn = messages.any { message -> message.id in currentTurnMessageIds }
        if (!belongsToCurrentTurn) return false
        // Causality first, then content.
        //
        // `currentTurnBrowserWork` is the whole fix: without it the desk's own state was the only
        // evidence, and the desk outlives the turn that filled it, so a plain-text turn wore the
        // previous turn's card. With it, either desk signal is admissible - once this turn provably
        // touched the browser, an open surface with no results yet is a browser turn that has only
        // just started, and that is exactly when the card should already be on screen.
        //
        // `sessionRunning` because this branch only exists for the turn in progress. Once the turn
        // settles its invocations are committed to the message and the earlier branches answer.
        if (!sessionRunning || !currentTurnBrowserWork) return false
        val desk = BrowserDesk.state.value
        return browserDeskHasResearch(desk) || browserDeskSurfaceIsOpen(desk)
    }

    /**
     * A page the user is still looking at.
     *
     * Sticky on purpose and by design: `TurnRetired(keepLiveSurface = true)` deliberately carries
     * `openedUrl` across the turn boundary. That makes it the right signal for the Pending slot and
     * the wrong one for a message, because it says nothing about which turn opened the page.
     */
    internal fun browserDeskSurfaceIsOpen(desk: BrowserDeskState): Boolean =
        desk.openedUrl.isNotBlank() || desk.keepLiveSurface || desk.userTakeover

    /**
     * Research results sitting on the desk right now.
     *
     * Also not by itself an answer to "did *this* turn do browser work" - the previews outlive the
     * turn that produced them - which is why the fallback pairs it with a per-turn signal.
     */
    internal fun browserDeskHasResearch(desk: BrowserDeskState): Boolean =
        desk.preview.kind != BrowserDeskPreviewKind.Empty || desk.previewsByTopic.isNotEmpty()
}

/**
 * Whether this card should read the live BrowserDesk rather than what the messages saved.
 *
 * `shouldShowOnMessages` already falls back to the live desk when [liveMessageIds] is not populated
 * yet; this decision did not, so the card went on screen attached to an empty state and stayed
 * empty until the turn ended and the ids filled in. That asymmetry is the whole of "网页卡片是回答
 * 结束后才出现的": the hits were in the desk the entire time and nobody was reading them.
 *
 * [liveMessageIds] only fills once a message carries a committed browser invocation, which is a
 * moment near the end of the turn. [currentTurnMessageIds] is every agent message after the last
 * user message, so it is populated from the first streamed token — and it excludes earlier turns,
 * whose cards must keep showing their own saved results.
 */
internal fun browserPreviewUsesLiveDesk(
    placement: BrowserPreviewPlacement,
    messageIds: List<String>,
    liveMessageIds: Set<String>,
    currentTurnMessageIds: Set<String>,
    sessionRunning: Boolean,
): Boolean {
    if (placement == BrowserPreviewPlacement.Pending) return true
    if (messageIds.any { id -> id in liveMessageIds }) return true
    return sessionRunning && messageIds.any { id -> id in currentTurnMessageIds }
}

internal fun browserPreviewLiveMessageIds(
    lastBrowserMessageIds: Set<String>,
    currentTurnAgentMessageIds: Set<String>,
): Set<String> {
    if (currentTurnAgentMessageIds.isEmpty()) return emptySet()
    return lastBrowserMessageIds.filterTo(linkedSetOf()) { id ->
        id in currentTurnAgentMessageIds
    }
}

internal val LocalBrowserPreviewHost = staticCompositionLocalOf { BrowserPreviewHost() }

internal fun messageHasBrowserDeskWork(message: ChatMessage): Boolean =
    (message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty())
        .any { it.isBrowserDeskSubagent() }

internal fun browserDeskInvocationsOnMessages(messages: List<ChatMessage>): List<ChatToolInvocation> =
    coalesceParallelBrowserAgents(
        messages.flatMap { message ->
            message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty()
        }.filter { invocation ->
            invocation.isBrowserDeskSubagent() && !isBuiltinKimiFetchTool(invocation.toolName)
        }.withoutRedundantFetchAgents(),
    )

internal fun browserPreviewFromInvocations(
    invocations: List<ChatToolInvocation>,
): BrowserDeskPreview? {
    val mergedHits = ArrayList<kira.ditto.browser.BrowserDeskHit>()
    var first: BrowserDeskPreview? = null
    for (invocation in invocations) {
        val built = previewFromResult(invocation.toolName, invocation.outputJson)
            ?: previewFromResult("search_web", invocation.outputJson)
            ?: previewFromResult("tabs_navigate", invocation.outputJson)
            ?: continue
        if (first == null) first = built
        mergedHits += built.hits
    }
    val hits = mergedHits.distinctBy { it.url.ifBlank { it.title } }.take(12)
    if (first != null) {
        return if (hits.isEmpty()) first else first.copy(
            kind = if (first.kind == BrowserDeskPreviewKind.Empty) {
                BrowserDeskPreviewKind.Search
            } else {
                first.kind
            },
            hits = hits,
        )
    }
    val query = invocations.firstNotNullOfOrNull { invocation ->
        invocationSearchQuery(invocation).takeIf { it.isNotBlank() }
    }
    return query?.let { title ->
        BrowserDeskPreview(kind = BrowserDeskPreviewKind.Search, title = title)
    }
}

internal fun invocationSearchQuery(invocation: ChatToolInvocation): String {
    if (invocation.isPunchedWebFetch() || isBuiltinKimiFetchTool(invocation.toolName)) return ""
    val arguments = runCatching { org.json.JSONObject(invocation.argumentsJson) }.getOrNull()
        ?: return ""
    if (invocation.isCoalescedBrowserSwarm()) {
        val items = arguments.optJSONArray("items")
        val first = items?.optString(0).orEmpty().trim()
        return sanitizeBrowserPreviewSearchQuery(first)
    }
    val query = arguments.optString("query")
        .ifBlank { arguments.optString("text_query") }
        .ifBlank { arguments.optString("q") }
        .trim()
    if (query.contains(" · ")) return sanitizeBrowserPreviewSearchQuery(query.substringBefore(" · "))
    return sanitizeBrowserPreviewSearchQuery(query)
}

internal fun effectiveBrowserDeskState(
    live: BrowserDeskState,
    invocations: List<ChatToolInvocation>,
    stored: Map<String, BrowserDeskPreview> = emptyMap(),
): BrowserDeskState {
    val storedTopics = invocations.fold(LinkedHashMap<String, BrowserDeskPreview>()) { acc, invocation ->
        acc.putAll(previewsByTopicFromOutput(invocation.outputJson))
        acc
    }
    // One read path, in order of how much it can be trusted: the live desk while the turn runs,
    // then the snapshot saved on the message, then whatever the tool outputs happen to carry.
    val byTopic = when {
        live.previewsByTopic.isNotEmpty() -> live.previewsByTopic
        stored.isNotEmpty() -> stored
        else -> storedTopics
    }
    val rebuilt = byTopic.values
        .maxByOrNull { browserPreviewWeight(it) }
        ?.takeIf { it.hits.isNotEmpty() || it.body.isNotBlank() || it.images.isNotEmpty() }
        ?: browserPreviewFromInvocations(invocations)
    val livePreview = sanitizeBrowserDeskPreviewForCard(live.preview)
    if (livePreview != null) {
        val hits = if (livePreview.hits.isNotEmpty()) {
            livePreview.hits
        } else {
            rebuilt?.hits.orEmpty()
        }
        val preview = if (hits.isNotEmpty() && livePreview.hits.isEmpty()) {
            livePreview.copy(
                kind = BrowserDeskPreviewKind.Search,
                title = livePreview.title.ifBlank { rebuilt?.title.orEmpty() },
                hits = hits,
            )
        } else {
            livePreview
        }
        return live.copy(preview = preview, previewsByTopic = byTopic)
    }
    return live.copy(preview = rebuilt ?: live.preview, previewsByTopic = byTopic)
}

internal fun attachBrowserDeskPreviewToMessages(
    messages: List<ChatMessage>,
    desk: BrowserDeskState,
): List<ChatMessage> {
    val preview = sanitizeBrowserDeskPreviewForCard(desk.preview)
        ?: desk.previewsByTopic.values.firstNotNullOfOrNull(::sanitizeBrowserDeskPreviewForCard)
        ?: return messages
    val deskQuery = browserDeskCaption(
        description = "",
        prompt = "",
        deskDetail = desk.activities.firstOrNull()?.detail.orEmpty(),
        deskTitle = preview.title,
    ).ifBlank { preview.title }.trim()
    val candidates = messages.mapIndexedNotNull { index, message ->
        if (messageHasBrowserDeskWork(message)) index to message else null
    }
    if (candidates.isEmpty()) return messages
    val runningIndex = candidates.lastOrNull { (_, message) ->
        (message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty())
            .any { it.isBrowserDeskSubagent() && it.isRunning }
    }?.first
    val matchingIndex = candidates.lastOrNull { (_, message) ->
        browserDeskQueriesOnMessage(message).any { query ->
            queriesBelongTogether(query, deskQuery)
        }
    }?.first
    val targetIndex = runningIndex ?: matchingIndex ?: candidates.last().first
    val existingOnTarget = browserPreviewFromInvocations(
        messages[targetIndex].toolInvocations +
            messages[targetIndex].reasoningTrace?.toolInvocations.orEmpty(),
    )
    // 只要新 preview 有实质内容（hits/body/images），就应该更新消息，
    // 不能仅因 hits 为空就跳过 fetch 产生的 Article。
    val previewHasContent = preview.hits.isNotEmpty() ||
        preview.body.isNotBlank() ||
        preview.images.isNotEmpty()
    if (!previewHasContent && existingOnTarget != null && existingOnTarget.hits.isNotEmpty()) {
        return messages
    }
    if (runningIndex == null && matchingIndex == null && !previewHasContent) {
        if (existingOnTarget != null && existingOnTarget.hits.isNotEmpty()) return messages
    }
    val message = messages[targetIndex]
    val tools = message.toolInvocations
    val trace = message.reasoningTrace
    val traceTools = trace?.toolInvocations.orEmpty()
    val nextTools = tools.map { invocation ->
        invocation.withAttachedBrowserPreview(preview, desk.previewsByTopic)
    }
    val nextTrace = trace?.copy(
        toolInvocations = traceTools.map { invocation ->
            invocation.withAttachedBrowserPreview(preview, desk.previewsByTopic)
        },
    )
    // The snapshot the card actually reads back. Attaching it to the tool outputs above still
    // happens for the webmcp calls that own their JSON, but that is not where it can be trusted:
    // the ACP tool result lands afterwards and replaces `outputJson` wholesale, so a punched
    // WebSearch always ended up holding the agent's plain text and nothing else. On the message
    // nothing overwrites it.
    val snapshot = buildMap {
        putAll(message.browserPreviewsByTopic)
        desk.previewsByTopic.forEach { (topicId, topicPreview) ->
            val existing = this[topicId]
            if (existing == null || browserPreviewWeight(topicPreview) >= browserPreviewWeight(existing)) {
                put(topicId, topicPreview)
            }
        }
        if (isEmpty() && preview.hits.isNotEmpty()) {
            put(preview.title.ifBlank { "browser" }, preview)
        }
    }
    if (nextTools == tools && nextTrace == trace && snapshot == message.browserPreviewsByTopic) {
        return messages
    }
    return messages.toMutableList().apply {
        set(
            targetIndex,
            message.copy(
                toolInvocations = nextTools,
                reasoningTrace = nextTrace,
                browserPreviewsByTopic = snapshot,
            ),
        )
    }
}

private fun browserDeskQueriesOnMessage(message: ChatMessage): List<String> =
    (message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty())
        .filter { it.isBrowserDeskSubagent() }
        .map { invocationSearchQuery(it) }
        .filter { it.isNotBlank() }

private fun queriesBelongTogether(left: String, right: String): Boolean {
    val a = left.trim()
    val b = right.trim()
    if (a.isBlank() || b.isBlank()) return false
    return a.equals(b, ignoreCase = true) || a.contains(b, ignoreCase = true) || b.contains(a, ignoreCase = true)
}

internal fun hydrateBrowserDeskFromMessages(messages: List<ChatMessage>) {
    val invocations = messages.flatMap { message ->
        message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty()
    }.filter { it.isBrowserDeskSubagent() }
    if (invocations.isEmpty()) return
    val byTopic = LinkedHashMap<String, BrowserDeskPreview>()
    invocations.forEach { invocation ->
        byTopic.putAll(previewsByTopicFromOutput(invocation.outputJson))
    }
    val preview = browserPreviewFromInvocations(invocations) ?: return
    BrowserDesk.hydrate(preview, byTopic)
}

private fun ChatToolInvocation.withAttachedBrowserPreview(
    preview: BrowserDeskPreview,
    byTopic: Map<String, BrowserDeskPreview>,
): ChatToolInvocation {
    if (!isBrowserDeskSubagent()) return this
    val topic = runCatching { org.json.JSONObject(argumentsJson) }.getOrNull()
        ?.optString("topic_id")
        .orEmpty()
        .ifBlank { invocationSearchQuery(this) }
    val topicPreview = byTopic[topic] ?: preview
    val merged = mergeBrowserPreviewIntoOutput(outputJson, topicPreview, byTopic)
    return if (merged == outputJson) this else copy(outputJson = merged)
}

internal enum class BrowserPreviewPlacement {
    Pending,
    Message,
}

internal data class BrowserPreviewAgentTab(
    val id: String,
    val name: String,
    /**
     * What this tab is *about* - the search term, or the page title when there is no query.
     *
     * The chip used to print [name], which comes from the subagent roster. Subagent identity only
     * exists once a launch invocation has been committed, while a topic exists the moment the
     * browser touches it, so the chip spent the whole run reading "Gecko" or "Agent 2" and the
     * research group it was showing had nothing to do with the person named on it.
     */
    val topicLabel: String = "",
    val topicId: String,
    val sourceToolCallId: String?,
    val running: Boolean,
    val failed: Boolean,
    val verb: BrowserDeskVerb?,
    val avatar: PersonaAvatarSpec? = null,
    val personIndex: Int = 0,
    /** The research group the rail paired this tab with. Empty when nothing is filed yet. */
    val preview: BrowserDeskPreview = BrowserDeskPreview(),
)

internal fun browserPreviewPeople(
    identityKey: String,
    invocations: List<ChatToolInvocation>,
    names: List<String>,
    deskState: BrowserDeskState = BrowserDeskState(),
): List<SubagentPersonUi> {
    val occupied = linkedSetOf<String>()
    val people = ArrayList<SubagentPersonUi>()
    var start = 0
    val hidePunchedFetch = invocations.hasNonFetchBrowserAgent()
    for (invocation in invocations) {
        if (isBuiltinKimiFetchTool(invocation.toolName)) continue
        if (hidePunchedFetch && invocation.isPunchedWebFetch()) continue
        val info = parseSubagentLaunch(invocation.toolName, invocation.argumentsJson, invocation.toolKind)
            ?: continue
        val members = if (invocation.isCoalescedBrowserSwarm()) {
            val topicCount = browserTopicRailItems(deskState, emptyList()).size.coerceAtLeast(1)
            List(topicCount) { index ->
                SwarmMemberView(
                    index = index,
                    item = "topic-${index + 1}",
                    prompt = "",
                    result = null,
                )
            }
        } else {
            swarmMemberViews(info, invocation.outputJson)
        }
        val batch = subagentPeopleForLaunch(
            identityKey = identityKey,
            toolCallId = invocation.id,
            info = info,
            members = members,
            names = names,
            occupied = occupied,
            startIndex = start,
        )
        people += batch
        start += batch.size
    }
    return people.ifEmpty { browserDeskPeople(identityKey, invocations, names) }
}

internal fun browserPreviewPeopleForVisibleTabs(
    tabs: List<BrowserPreviewAgentTab>,
    people: List<SubagentPersonUi>,
): List<SubagentPersonUi> {
    if (tabs.isEmpty()) return people
    return tabs.mapIndexed { index, tab ->
        val matched = people.firstOrNull { person ->
            person.sourceToolCallId == tab.sourceToolCallId &&
                (person.index == tab.personIndex || person.name == tab.name)
        } ?: people.getOrNull(index) ?: people.lastOrNull()
        matched?.copy(
            index = tab.personIndex,
            name = tab.name,
            avatar = tab.avatar ?: matched.avatar,
            sourceToolCallId = tab.sourceToolCallId,
        )
    }.filterNotNull()
}

/**
 * What to print on a tab: the topic's own search term, else the page it settled on.
 *
 * A topic id is already a normalised query string, so it is a usable label on its own - and unlike
 * the roster of subagent names it is available from the first browser call of the turn.
 */
internal fun browserTopicLabel(topicId: String, preview: BrowserDeskPreview): String {
    preview.title.trim().takeIf { it.isNotBlank() }?.let { return it.take(28) }
    topicId.trim().takeIf { it.isNotBlank() && !it.startsWith("about:") }?.let { return it.take(28) }
    return preview.hits.firstOrNull()?.title.orEmpty().trim().take(28)
}

/**
 * Pair a research group with the person shown on its tab, by topic rather than by position.
 *
 * `people.getOrNull(index)` zipped two streams that fill at different speeds: topics appear as soon
 * as the browser touches one, people only once a launch invocation is committed. Matching on the
 * topic id first means a late-arriving roster reorders nothing; the positional path stays as the
 * last resort for turns where no person carries a topic at all.
 */
internal fun personForTopic(
    people: List<SubagentPersonUi>,
    topicIds: List<String>,
    topicId: String,
    index: Int,
): SubagentPersonUi? {
    if (people.isEmpty()) return null
    people.firstOrNull { person -> person.member?.item.orEmpty().isNotBlank() &&
        browserTopicKeysRelated(person.member!!.item, topicId)
    }?.let { return it }
    val position = topicIds.indexOf(topicId).takeIf { it >= 0 } ?: index
    return people.getOrNull(position) ?: people.lastOrNull()
}

internal fun browserPreviewAgentTabs(
    people: List<SubagentPersonUi>,
    invocations: List<ChatToolInvocation>,
    deskState: BrowserDeskState,
    sessionRunning: Boolean,
): List<BrowserPreviewAgentTab> {
    val busy = browserDeskHeaderBusy(
        sessionRunning = sessionRunning,
        invocations = invocations,
        activities = deskState.activities,
    )
    val verb = deskState.activities.firstOrNull()?.verb
        ?: previewKindVerb(deskState.preview.kind)
    val swarmItems = invocations.firstNotNullOfOrNull { invocation ->
        if (invocation.isCoalescedBrowserSwarm()) return@firstNotNullOfOrNull null
        parseSubagentLaunch(invocation.toolName, invocation.argumentsJson, invocation.toolKind)
            ?.takeIf { it.isSwarm }
            ?.items
    }.orEmpty()
    // How many browser agents this turn actually launched. Topics are minted per query, so
    // without this the rail counts queries and calls each one a subagent. A coalesced swarm is
    // one invocation standing for several agents, so count its members, not the invocation.
    // Only launches count. `?: 1` used to fall through for every invocation, so the two searches and
    // one fetch that a single agent performed each added an "agent" — the cap then never bit and the
    // rail split one agent's work across a tab per query.
    val agentCount = invocations.sumOf { invocation ->
        val launch = parseSubagentLaunch(invocation.toolName, invocation.argumentsJson, invocation.toolKind)
            ?: return@sumOf 0
        if (launch.isSwarm) launch.items.size else 1
    }.coerceAtLeast(if (invocations.isEmpty()) 0 else 1)
    val rail = browserTopicRailItems(deskState, swarmItems, agentCount = agentCount)
    val failedByCall = invocations.associate { invocation ->
        invocation.id to (
            !invocation.isRunning &&
                subagentCallFailed(
                    parseSubagentLaunch(invocation.toolName, invocation.argumentsJson, invocation.toolKind)
                        ?: return@associate invocation.id to false,
                    invocation.outputJson,
                )
            )
    }
    if (rail.size > 1) {
        return rail.mapIndexed { index, item ->
            val person = personForTopic(people, rail.map { it.topicId }, item.topicId, index)
            val callId = person?.sourceToolCallId
            val invocation = invocations.firstOrNull { it.id == callId }
            BrowserPreviewAgentTab(
                id = item.topicId,
                name = person?.name ?: "Agent ${index + 1}",
                topicLabel = browserTopicLabel(item.topicId, item.preview),
                topicId = item.topicId,
                preview = item.preview,
                sourceToolCallId = callId,
                running = invocation?.isRunning == true || (busy && invocation == null),
                failed = callId?.let { failedByCall[it] } == true,
                verb = verb,
                avatar = person?.avatar,
                personIndex = person?.index ?: index,
            )
        }
    }
    val topicId = rail.firstOrNull()?.topicId.orEmpty()
    val railPreview = rail.firstOrNull()?.preview ?: BrowserDeskPreview()
    val person = people.firstOrNull()
    val invocation = invocations.firstOrNull { it.id == person?.sourceToolCallId }
        ?: invocations.firstOrNull { it.isBrowserDeskSubagent() && !it.isPunchedWebFetch() }
    return listOf(
        BrowserPreviewAgentTab(
            id = person?.sourceToolCallId ?: invocation?.id ?: "browser",
            name = person?.name ?: "Gecko",
            topicLabel = browserTopicLabel(topicId, railPreview),
            topicId = topicId,
            preview = railPreview,
            sourceToolCallId = person?.sourceToolCallId ?: invocation?.id,
            running = invocation?.isRunning == true || busy,
            failed = (person?.sourceToolCallId ?: invocation?.id)?.let { failedByCall[it] } == true,
            verb = verb,
            avatar = person?.avatar,
            personIndex = person?.index ?: 0,
        ),
    )
}

/**
 * The search term to print above this tab's results.
 *
 * The invocation list and the activity list describe the browser as a whole, so with several
 * research groups in the rail they hand back the same string for every tab: switching subagents
 * changed the results underneath but never the search box above them. Pass the tab's [topicId] and
 * the turn-wide candidates are kept only when they belong to this group.
 */
internal fun browserPreviewSearchQuery(
    preview: BrowserDeskPreview,
    activities: List<BrowserDeskActivity>,
    invocations: List<ChatToolInvocation> = emptyList(),
    topicId: String = "",
): String {
    // Queries that were actually run, versus names for the research group.
    //
    // A punched WebSearch becomes `Launching browser agent: 时政新闻 2025年9月`, and that string is
    // also the topic id — so it matched its own topic, sorted first, and won every time. Meanwhile
    // the agent went on to search 时政新闻 2026年9月8日 and the card kept printing the label it was
    // launched under. A launch names the group; it is not a search anyone performed.
    val executed = buildList {
        invocations.forEach { invocation ->
            if (parseSubagentLaunch(invocation.toolName, invocation.argumentsJson, invocation.toolKind) != null) {
                return@forEach
            }
            add(invocationSearchQuery(invocation))
        }
        activities.forEach { activity ->
            if (activity.verb == BrowserDeskVerb.Searching && activity.detail.isNotBlank()) {
                add(activity.detail)
            }
        }
    }
    val launchLabels = invocations.mapNotNull { invocation ->
        parseSubagentLaunch(invocation.toolName, invocation.argumentsJson, invocation.toolKind)
            ?.let { invocationSearchQuery(invocation) }
    }
    val shared = executed + launchLabels + buildList {
        val title = preview.title.trim()
        if (title.isNotBlank() &&
            (preview.kind == BrowserDeskPreviewKind.Search || preview.hits.isNotEmpty())
        ) {
            add(title)
        }
    }
    // What this tab knows about itself: the query its own preview was filed under, and the group
    // it belongs to. An image search files its query as the preview title too.
    val own = buildList {
        val title = preview.title.trim()
        if (title.isNotBlank() &&
            (
                preview.kind == BrowserDeskPreviewKind.Search ||
                    preview.kind == BrowserDeskPreviewKind.Images ||
                    preview.hits.isNotEmpty()
                )
        ) {
            add(title)
        }
    }
    val scoped = if (topicId.isBlank()) {
        executed
    } else {
        executed.filter { candidate -> browserTopicKeysRelated(candidate, topicId) }
    }
    // A real search belonging to this group, then the query this group's own hits were filed under,
    // then anything else this turn ran, then the label it was launched with.
    return (scoped + own + shared + listOf(topicId))
        .map(::sanitizeBrowserPreviewSearchQuery)
        .firstOrNull { query -> query.isNotBlank() }
        .orEmpty()
}

internal fun sanitizeBrowserPreviewSearchQuery(raw: String): String {
    if (raw.isBlank() || isBrowserLeadReminderLeak(raw)) return ""
    val query = humanizeBrowserSearchQuery(raw)
    if (query.length < 2 || looksLikePreviewUrlLabel(query) || isPlaceholderBrowserTopic(query)) return ""
    return query
}

private fun looksLikePreviewUrlLabel(text: String): Boolean {
    val t = text.trim().lowercase()
    if (t.startsWith("http://") || t.startsWith("https://")) return true
    return Regex("""^(?:www\.)?[a-z0-9.-]+\.[a-z]{2,}$""").matches(t)
}

internal fun browserPreviewTabTitleRes(
    running: Boolean,
    failed: Boolean,
    verb: BrowserDeskVerb?,
): Int = when {
    failed -> R.string.browser_preview_tab_failed
    !running -> R.string.browser_preview_tab_done
    verb == BrowserDeskVerb.Searching -> R.string.browser_preview_tab_searching
    verb == BrowserDeskVerb.Reading -> R.string.browser_preview_tab_reading
    verb == BrowserDeskVerb.Opening -> R.string.browser_preview_tab_opening
    verb == BrowserDeskVerb.Inspecting -> R.string.browser_preview_tab_inspecting
    else -> R.string.browser_preview_tab_operating
}

private fun previewKindVerb(kind: BrowserDeskPreviewKind): BrowserDeskVerb? = when (kind) {
    BrowserDeskPreviewKind.Search -> BrowserDeskVerb.Searching
    BrowserDeskPreviewKind.Article -> BrowserDeskVerb.Reading
    BrowserDeskPreviewKind.Images -> BrowserDeskVerb.Searching
    BrowserDeskPreviewKind.Snapshot -> BrowserDeskVerb.Inspecting
    BrowserDeskPreviewKind.Empty -> null
}

@Composable
internal fun BrowserPreviewCardHost(
    placement: BrowserPreviewPlacement,
    messages: List<ChatMessage> = emptyList(),
    topPadding: Dp = 0.dp,
) {
    val host = LocalBrowserPreviewHost.current
    val messageInvocations = remember(messages) { browserDeskInvocationsOnMessages(messages) }
    val show = when (placement) {
        BrowserPreviewPlacement.Pending -> host.shouldShowOnPending()
        BrowserPreviewPlacement.Message ->
            host.shouldShowOnMessages(messages) || messageInvocations.isNotEmpty()
    }
    if (!show) return
    val context = LocalContext.current
    LaunchedEffect(host.visible) {
        if (host.visible) AetherBrowserRuntime.warmAsync(context)
    }
    val useLiveDesk = browserPreviewUsesLiveDesk(
        placement = placement,
        messageIds = messages.map { it.id },
        liveMessageIds = host.messageIds,
        currentTurnMessageIds = host.currentTurnMessageIds,
        sessionRunning = host.sessionRunning,
    )
    val invocations = if (placement == BrowserPreviewPlacement.Message && messageInvocations.isNotEmpty()) {
        messageInvocations
    } else {
        host.invocations
    }
    val storedPreviews = remember(messages) {
        messages.fold(LinkedHashMap<String, BrowserDeskPreview>()) { acc, message ->
            acc.putAll(message.browserPreviewsByTopic)
            acc
        }
    }
    val deskState = effectiveBrowserDeskState(
        live = if (useLiveDesk) host.deskState else BrowserDeskState(),
        invocations = invocations,
        stored = storedPreviews,
    )
    val identityKey = if (placement == BrowserPreviewPlacement.Message && messages.isNotEmpty()) {
        messages.joinToString(separator = ",") { message -> message.id }
    } else {
        host.identityKey
    }
    BrowserPreviewCard(
        identityKey = identityKey,
        invocations = invocations,
        deskState = deskState,
        sessionRunning = if (useLiveDesk) host.sessionRunning else false,
        workspaceDirectory = host.workspaceDirectory,
        allowRootImageRead = host.allowRootImageRead,
        onOpenLink = host.onOpenLink,
        onVerifyContinue = host.onVerifyContinue,
        topPadding = topPadding,
    )
}

@Composable
internal fun BrowserPreviewCard(
    identityKey: String,
    invocations: List<ChatToolInvocation>,
    deskState: BrowserDeskState,
    sessionRunning: Boolean,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    onOpenLink: (String) -> Unit,
    onVerifyContinue: () -> Unit = {},
    topPadding: Dp = 0.dp,
) {
    val names = subagentPersonNames()
    val topicKeys = deskState.previewsByTopic.keys.toList()
    val people = remember(identityKey, invocations, names, topicKeys) {
        browserPreviewPeople(identityKey, invocations, names, deskState)
    }
    val tabs = remember(people, invocations, deskState, sessionRunning) {
        browserPreviewAgentTabs(people, invocations, deskState, sessionRunning)
    }
    var dismissed by rememberSaveable(identityKey) { mutableStateOf(setOf<String>()) }
    val visibleTabs = tabs.filter { tab -> tab.id !in dismissed }
    if (visibleTabs.isEmpty()) return
    val sheetPeople = remember(visibleTabs, people) {
        browserPreviewPeopleForVisibleTabs(visibleTabs, people)
    }
    var selectedId by rememberSaveable(identityKey) { mutableStateOf<String?>(null) }
    val resolvedId = selectedId?.takeIf { id -> visibleTabs.any { it.id == id } }
        ?: deskState.uiPreviewTopicId.takeIf { id ->
            id.isNotBlank() && visibleTabs.any { it.id == id || it.topicId == id }
        }
        ?: visibleTabs.first().id
    val selected = visibleTabs.firstOrNull { it.id == resolvedId } ?: visibleTabs.first()
    // The rail already paired this tab with a research group; re-resolving by key here would
    // undo that pairing and hand back an empty preview.
    val shownPreview = selected.preview
        .takeIf { it.kind != BrowserDeskPreviewKind.Empty || it.hits.isNotEmpty() }
        .let { it ?: browserPreviewForTopic(deskState, selected.topicId) }
        .let { preview ->
        preview.copy(hits = displayBrowserSearchHits(preview.hits))
    }
    val running = selected.running || browserDeskHeaderBusy(
        sessionRunning = sessionRunning,
        invocations = invocations,
        activities = deskState.activities,
    )
    val query = browserPreviewSearchQuery(
        preview = shownPreview,
        activities = deskState.activities,
        invocations = invocations,
        topicId = selected.topicId,
    )
    val searching = running && (
        shownPreview.kind == BrowserDeskPreviewKind.Search ||
            deskState.activities.any { it.verb == BrowserDeskVerb.Searching } ||
            (query.isNotBlank() && shownPreview.hits.isEmpty())
        )
    val viewingPage = deskState.openedUrl.isNotBlank() ||
        deskState.userTakeover ||
        deskState.keepLiveSurface
    val revealResults = browserPreviewRevealResults(
        searching = searching,
        hitCount = shownPreview.hits.size,
        viewingPage = viewingPage,
        kind = shownPreview.kind,
    )
    val showSearchField = !viewingPage && (
        shownPreview.kind == BrowserDeskPreviewKind.Search ||
            shownPreview.hits.isNotEmpty() ||
            query.isNotBlank()
        )
    var live by rememberSaveable(identityKey) { mutableStateOf(false) }
    var expanded by rememberSaveable(identityKey) { mutableStateOf(false) }
    var playedEnter by rememberSaveable(identityKey) { mutableStateOf(false) }
    var sheetVisible by rememberSaveable(identityKey) { mutableStateOf(false) }
    var sheetPersonIndex by rememberSaveable(identityKey) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else 180f,
        animationSpec = tween(220),
        label = "browser_preview_chevron",
    )
    LaunchedEffect(identityKey) {
        if (!playedEnter) {
            expanded = true
            playedEnter = true
        }
    }
    LaunchedEffect(revealResults) {
        if (revealResults) expanded = true
    }
    LaunchedEffect(viewingPage) {
        if (viewingPage) live = true
    }
    LaunchedEffect(deskState.openedUrl, deskState.userTakeover, selected.topicId) {
        val url = deskState.openedUrl
        if (url.isBlank() && !deskState.userTakeover && !deskState.keepLiveSurface) {
            return@LaunchedEffect
        }
        expanded = true
        if (url.isNotBlank()) {
            AetherBrowserRuntime.navigateInTopic(context, url, selected.topicId)
        }
    }
    val halfScreen = (LocalConfiguration.current.screenHeightDp.dp / 2).coerceAtLeast(220.dp)
    val bodyMinHeight = (halfScreen - 48.dp).coerceAtLeast(160.dp)
    var extraBodyHeightPx by rememberSaveable(identityKey) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val maxExtraPx = with(density) {
        ((LocalConfiguration.current.screenHeightDp.dp * 0.82f) - bodyMinHeight)
            .coerceAtLeast(0.dp)
            .toPx()
    }
    val extraBodyHeight = with(density) { extraBodyHeightPx.toDp() }
    val bodyMaxHeight = bodyMinHeight + extraBodyHeight
    val fill = browserPreviewCardFill()
    val brandTitle = stringResource(R.string.browser_preview_card_title)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
    ) {
        GmailCardSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    end = if (expanded) BrowserPreviewResizeOutset else 0.dp,
                    bottom = if (expanded) BrowserPreviewResizeOutset else 0.dp,
                ),
            frostBackdrop = true,
            shape = RoundedCornerShape(BrowserPreviewCardCorner),
        ) {
            Column(
            modifier = Modifier
                .fillMaxWidth()
                .browserPreviewScrollIsolation(),
        ) {
            BrowserPreviewHeader(
                expanded = expanded,
                title = brandTitle,
                chevronRotation = chevronRotation,
                tabs = visibleTabs,
                selectedId = selected.id,
                onSelect = { tab ->
                    selectedId = tab.id
                    if (tab.topicId.isNotBlank()) BrowserDesk.selectUiTopic(tab.topicId)
                },
                onAvatarClick = { tab ->
                    if (tab.id == selected.id) {
                        sheetPersonIndex = sheetPeople.indexOfFirst { person ->
                            person.index == tab.personIndex || person.name == tab.name
                        }.takeIf { it >= 0 } ?: tab.personIndex
                        sheetVisible = true
                    } else {
                        selectedId = tab.id
                        if (tab.topicId.isNotBlank()) BrowserDesk.selectUiTopic(tab.topicId)
                    }
                },
                onClose = { tab ->
                    dismissed = dismissed + tab.id
                    if (selectedId == tab.id) selectedId = null
                },
                onToggle = { expanded = !expanded },
            )
            BrowserPreviewExpandingBody(expanded = expanded) {
                BrowserExecutionSummary(deskState, selected.topicId)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (viewingPage) {
                        if (!deskState.userTakeover) {
                            BrowserPreviewBackRow(
                                onBack = {
                                    BrowserDesk.clearOpenedUrl()
                                    live = false
                                },
                            )
                        }
                        if (deskState.userTakeover) {
                            BrowserPreviewVerifyBar(
                                login = deskState.userTakeoverReason == "login",
                                onDone = onVerifyContinue,
                            )
                        }
                    } else {
                        if (showSearchField) {
                            BrowserPreviewSearchFieldHost(
                                query = query,
                                searching = searching,
                            )
                        }
                        if (revealResults && !live) {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(animationSpec = tween(220)) + expandVertically(
                                    animationSpec = tween(280),
                                    expandFrom = Alignment.Top,
                                ),
                                exit = fadeOut(animationSpec = tween(180)) + shrinkVertically(
                                    animationSpec = tween(240),
                                    shrinkTowards = Alignment.Top,
                                ),
                            ) {
                                BrowserPreviewClippedBody(maxHeight = bodyMaxHeight, fill = fill) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        AnimatedContent(
                                            targetState = selected.id,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clipToBounds(),
                                            transitionSpec = {
                                                val from = visibleTabs.indexOfFirst { tab ->
                                                    tab.id == initialState
                                                }
                                                val to = visibleTabs.indexOfFirst { tab ->
                                                    tab.id == targetState
                                                }
                                                browserPreviewTabSwipeTransform(goingRight = to >= from)
                                            },
                                            label = "browser_preview_tab_page",
                                        ) { tabId ->
                                            val tab = visibleTabs.firstOrNull { it.id == tabId }
                                                ?: selected
                                            val pagePreview = tab.preview
                                                .takeIf {
                                                    it.kind != BrowserDeskPreviewKind.Empty ||
                                                        it.hits.isNotEmpty()
                                                }
                                                .let {
                                                    it ?: browserPreviewForTopic(deskState, tab.topicId)
                                                }
                                                .let { preview ->
                                                preview.copy(hits = displayBrowserSearchHits(preview.hits))
                                            }
                                            val pageRunning = tab.running || running
                                            BrowserPreviewPageBody(
                                                preview = pagePreview,
                                                searching = pageRunning && (
                                                    pagePreview.kind ==
                                                        BrowserDeskPreviewKind.Search ||
                                                        pagePreview.hits.isEmpty()
                                                    ),
                                                workspaceDirectory = workspaceDirectory,
                                                allowRootImageRead = allowRootImageRead,
                                                onOpenLink = { url ->
                                                    if (looksLikeHttpUrl(url)) {
                                                        BrowserDesk.requestOpenUrl(url)
                                                    } else {
                                                        onOpenLink(url)
                                                    }
                                                },
                                                readingUrl = deskState.readingUrl,
                                                readingUrls = deskState.readingUrls,
                                                readingExcerpt = deskState.readingExcerpt,
                                                resultsMaxHeight = bodyMaxHeight,
                                            )
                                        }
                                        BrowserPreviewModeToggle(
                                            live = live,
                                            onChange = { live = it },
                                        )
                                        Spacer(Modifier.height(BrowserPreviewBodyFade))
                                    }
                                }
                            }
                        } else if (revealResults) {
                            BrowserPreviewModeToggle(
                                live = live,
                                onChange = { live = it },
                            )
                        }
                    }
                    if (viewingPage || live) {
                        BrowserPreviewLivePane(
                            topicId = selected.topicId,
                            expectedUrl = deskState.openedUrl,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (viewingPage) 6.dp else 0.dp)
                                .height((bodyMaxHeight - 36.dp).coerceAtLeast(160.dp))
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Spacer(Modifier.height(BrowserPreviewBodyFade))
                    }
                }
            }
            }
        }
        if (expanded) {
            BrowserPreviewCornerResizeHandle(
                maxExtraPx = maxExtraPx,
                onHeightDelta = { delta ->
                    extraBodyHeightPx = (extraBodyHeightPx + delta).coerceIn(0f, maxExtraPx)
                },
            )
        }
    }
    if (sheetVisible) {
        val sheetInvocation = invocations.firstOrNull { invocation ->
            invocation.id == selected.sourceToolCallId
        } ?: invocations.firstOrNull()
        val sheetInfo = sheetInvocation?.let { invocation ->
            parseSubagentLaunch(invocation.toolName, invocation.argumentsJson, invocation.toolKind)
        }
        if (sheetInvocation != null && sheetInfo != null) {
            SubagentDetailSheet(
                toolInvocation = sheetInvocation,
                info = sheetInfo,
                people = sheetPeople.ifEmpty { people },
                failed = selected.failed,
                initialPersonIndex = sheetPersonIndex,
                onDismiss = { sheetVisible = false },
            )
        }
    }
}

@Composable
private fun BrowserPreviewHeader(
    expanded: Boolean,
    title: String,
    chevronRotation: Float,
    tabs: List<BrowserPreviewAgentTab>,
    selectedId: String,
    onSelect: (BrowserPreviewAgentTab) -> Unit,
    onAvatarClick: (BrowserPreviewAgentTab) -> Unit,
    onClose: (BrowserPreviewAgentTab) -> Unit,
    onToggle: () -> Unit,
) {
    val stripTint = AetherOnSurface.copy(alpha = if (AetherIsDark) 0.08f else 0.05f)
    var headerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var selectedTabRect by remember { mutableStateOf<Rect?>(null) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> headerCoords = coords }
            .then(
                if (expanded) {
                    Modifier.drawBehind {
                        val hole = selectedTabRect
                        if (hole == null) {
                            drawRect(stripTint)
                            return@drawBehind
                        }
                        val notch = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    rect = Rect(
                                        left = hole.left,
                                        top = hole.top.coerceAtLeast(0f),
                                        right = hole.right,
                                        bottom = size.height,
                                    ),
                                    topLeft = CornerRadius(12.dp.toPx()),
                                    topRight = CornerRadius(12.dp.toPx()),
                                ),
                            )
                        }
                        clipPath(notch, ClipOp.Difference) {
                            drawRect(stripTint)
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = !expanded,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onToggle,
                )
                .padding(
                    start = BrowserPreviewHeaderPadHorizontal,
                    end = BrowserPreviewHeaderPadHorizontal,
                    top = BrowserPreviewHeaderPadVertical,
                    bottom = if (expanded) 0.dp else BrowserPreviewHeaderPadVertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (expanded) {
                    BrowserPreviewTabStrip(
                        tabs = tabs,
                        selectedId = selectedId,
                        onSelect = onSelect,
                        onAvatarClick = onAvatarClick,
                        onClose = onClose,
                        onSelectedBounds = { coords ->
                            val header = headerCoords
                            if (header != null && header.isAttached && coords.isAttached) {
                                selectedTabRect = header.localBoundingBoxOf(coords, clipBounds = false)
                            }
                        },
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_firefox_card),
                            contentDescription = title,
                            modifier = Modifier.size(BrowserPreviewHeaderIconSize),
                        )
                        SpotifyBlurTitle(
                            text = title,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Icon(
                imageVector = Icons.Rounded.KeyboardDoubleArrowLeft,
                contentDescription = stringResource(
                    if (expanded) R.string.browser_preview_collapse else R.string.browser_preview_expand,
                ),
                tint = AetherOnSurfaceVariant,
                modifier = Modifier
                    .size(BrowserPreviewHeaderIconSize)
                    .graphicsLayer { rotationZ = chevronRotation }
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onToggle,
                    ),
            )
        }
    }
}

@Composable
private fun BrowserPreviewTabStrip(
    tabs: List<BrowserPreviewAgentTab>,
    selectedId: String,
    onSelect: (BrowserPreviewAgentTab) -> Unit,
    onAvatarClick: (BrowserPreviewAgentTab) -> Unit,
    onClose: (BrowserPreviewAgentTab) -> Unit,
    onSelectedBounds: (LayoutCoordinates) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val avatarOnly = browserPreviewTabsUseAvatarOnly(
            tabCount = tabs.size,
            availableWidth = maxWidth,
        )
        val minTabWidth = if (avatarOnly) BrowserPreviewAvatarTabMinWidth else BrowserPreviewMinTabWidth
        val overflowReserve = if (tabs.size > BrowserPreviewMinInlineTabs) {
            BrowserPreviewOverflowWidth
        } else {
            0.dp
        }
        val fitCount = browserPreviewInlineTabCount(
            tabCount = tabs.size,
            availableWidth = maxWidth,
            minTabWidth = minTabWidth,
            overflowWidth = overflowReserve,
        )
        val (inline, overflow) = splitBrowserPreviewTabs(tabs, selectedId, fitCount)
        var overflowOpen by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            inline.forEach { tab ->
                val selected = tab.id == selectedId
                BrowserPreviewTabChip(
                    tab = tab,
                    selected = selected,
                    showStatus = !avatarOnly || selected,
                    modifier = if (inline.size > 1 && (!avatarOnly || selected)) {
                        Modifier.weight(1f)
                    } else {
                        Modifier
                    },
                    onSelect = { onSelect(tab) },
                    onAvatarClick = { onAvatarClick(tab) },
                    onSelectedBounds = if (selected) onSelectedBounds else null,
                )
            }
            if (overflow.isNotEmpty()) {
                Box {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.browser_preview_more_tabs),
                        tint = AetherOnSurfaceVariant,
                        modifier = Modifier
                            .size(BrowserPreviewHeaderIconSize)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { overflowOpen = true },
                    )
                    BrowserPreviewOverflowPopup(
                        expanded = overflowOpen,
                        tabs = overflow,
                        onDismissRequest = { overflowOpen = false },
                        onSelect = { tab ->
                            overflowOpen = false
                            onSelect(tab)
                        },
                        onClose = { tab ->
                            overflowOpen = false
                            onClose(tab)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserPreviewBackRow(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onBack,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.browser_preview_back),
            tint = AetherOnSurface,
            modifier = Modifier.size(BrowserPreviewHeaderIconSize),
        )
        Text(
            text = stringResource(R.string.browser_preview_back),
            style = MaterialTheme.typography.labelLarge,
            color = AetherOnSurface,
        )
    }
}

@Composable
private fun BrowserPreviewVerifyBar(login: Boolean, onDone: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                if (login) R.string.browser_preview_login_banner else R.string.browser_preview_verify_banner,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurface,
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(
                    lerp(AetherSurface, BrowserPreviewCardGray, 0.16f)
                        .copy(alpha = BrowserPreviewCardAlpha),
                )
                .clickable(onClick = onDone)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    if (login) R.string.browser_preview_login_done else R.string.browser_preview_verify_done,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = AetherOnSurface,
                maxLines = 1,
            )
        }
    }
}

internal fun browserPreviewRevealResults(
    searching: Boolean,
    hitCount: Int,
    viewingPage: Boolean,
    kind: BrowserDeskPreviewKind,
): Boolean {
    if (viewingPage || hitCount > 0) return true
    // The body is where results land as they arrive. Hiding it for the whole search phase meant
    // the card showed a lone search box until the turn finished, and every hit that streamed in
    // mid-run went unseen. BrowserPreviewSearchHits already renders its own "searching" line for
    // the empty case, so reveal as soon as the preview is anything at all.
    if (searching) return kind != BrowserDeskPreviewKind.Empty
    return kind == BrowserDeskPreviewKind.Article ||
        kind == BrowserDeskPreviewKind.Images ||
        kind == BrowserDeskPreviewKind.Snapshot
}

internal fun browserPreviewTabsUseAvatarOnly(
    tabCount: Int,
    availableWidth: Dp,
    labeledMinWidth: Dp = BrowserPreviewLabeledTabMinWidth,
): Boolean {
    if (tabCount <= 1) return false
    return availableWidth.value < labeledMinWidth.value * tabCount
}

internal fun browserPreviewInlineTabCount(
    tabCount: Int,
    availableWidth: Dp,
    minTabWidth: Dp = BrowserPreviewMinTabWidth,
    overflowWidth: Dp = BrowserPreviewOverflowWidth,
    minInline: Int = BrowserPreviewMinInlineTabs,
): Int {
    if (tabCount <= 0) return 0
    if (tabCount <= minInline) return tabCount
    val minWidthPx = minTabWidth.value
    if (minWidthPx <= 0f) return minInline.coerceAtMost(tabCount)
    val canFitAll = (availableWidth.value / minWidthPx).toInt() >= tabCount
    if (canFitAll) return tabCount
    val withOverflow = ((availableWidth.value - overflowWidth.value) / minWidthPx).toInt()
    return withOverflow.coerceAtLeast(minInline).coerceAtMost(tabCount)
}

internal fun splitBrowserPreviewTabs(
    tabs: List<BrowserPreviewAgentTab>,
    selectedId: String,
    maxInline: Int,
): Pair<List<BrowserPreviewAgentTab>, List<BrowserPreviewAgentTab>> {
    if (maxInline >= tabs.size) return tabs to emptyList()
    val keep = maxInline.coerceAtLeast(1)
    val selectedIndex = tabs.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    if (selectedIndex < keep) {
        return tabs.take(keep) to tabs.drop(keep)
    }
    val leading = (keep - 1).coerceAtLeast(0)
    val inline = tabs.take(leading) + tabs[selectedIndex]
    val overflow = tabs.filterNot { tab -> inline.any { it.id == tab.id } }
    return inline to overflow
}

internal fun browserPreviewTabSwipeTransform(goingRight: Boolean): ContentTransform {
    val travel = spring<IntOffset>(
        dampingRatio = 0.72f,
        stiffness = Spring.StiffnessMediumLow,
    )
    val enter = slideInHorizontally(animationSpec = travel) { width ->
        if (goingRight) width else -width
    } + fadeIn(tween(150))
    val exit = slideOutHorizontally(animationSpec = travel) { width ->
        if (goingRight) -width else width
    } + fadeOut(tween(130))
    return ContentTransform(
        targetContentEnter = enter,
        initialContentExit = exit,
        sizeTransform = SizeTransform(clip = true),
    )
}

@Composable
private fun BrowserPreviewTabChip(
    tab: BrowserPreviewAgentTab,
    selected: Boolean,
    showStatus: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onAvatarClick: () -> Unit,
    onSelectedBounds: ((LayoutCoordinates) -> Unit)? = null,
) {
    val title = stringResource(
        browserPreviewTabTitleRes(tab.running, tab.failed, tab.verb),
        tab.topicLabel.ifBlank { tab.name },
    )
    Row(
        modifier = modifier
            .widthIn(
                min = if (showStatus) BrowserPreviewMinTabWidth else BrowserPreviewAvatarTabMinWidth,
                max = if (showStatus) 168.dp else 48.dp,
            )
            .then(
                if (onSelectedBounds != null) {
                    Modifier.onGloballyPositioned(onSelectedBounds)
                } else {
                    Modifier
                },
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onSelect,
            )
            .padding(
                start = if (showStatus) 8.dp else 6.dp,
                end = if (showStatus) 8.dp else 6.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BrowserPreviewTabAvatar(
            tab = tab,
            modifier = Modifier
                .size(BrowserPreviewTabIconSize)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onAvatarClick,
                ),
        )
        if (showStatus) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) AetherOnSurface else AetherOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun BrowserPreviewOverflowPopup(
    expanded: Boolean,
    tabs: List<BrowserPreviewAgentTab>,
    onDismissRequest: () -> Unit,
    onSelect: (BrowserPreviewAgentTab) -> Unit,
    onClose: (BrowserPreviewAgentTab) -> Unit,
) {
    val menuVisibility = remember { MutableTransitionState(false) }
    menuVisibility.targetState = expanded
    if (!menuVisibility.currentState && !menuVisibility.targetState) return
    val density = LocalDensity.current
    val popupOffset = with(density) { IntOffset(x = 0, y = 8.dp.roundToPx()) }
    Popup(
        alignment = Alignment.TopEnd,
        offset = popupOffset,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        AnimatedVisibility(
            visibleState = menuVisibility,
            enter = fadeIn() +
                scaleIn(initialScale = 0.92f, transformOrigin = TransformOrigin(1f, 0f)) +
                slideInVertically(initialOffsetY = { -it / 10 }),
            exit = fadeOut() +
                scaleOut(targetScale = 0.96f, transformOrigin = TransformOrigin(1f, 0f)) +
                slideOutVertically(targetOffsetY = { -it / 12 }),
        ) {
            AetherCapsuleSurface(
                modifier = Modifier.width(228.dp),
                shape = AetherCapsulePanelShape,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    tabs.forEach { tab ->
                        val title = stringResource(
                            browserPreviewTabTitleRes(tab.running, tab.failed, tab.verb),
                            tab.topicLabel.ifBlank { tab.name },
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .clickable { onSelect(tab) }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BrowserPreviewTabAvatar(
                                tab = tab,
                                modifier = Modifier.size(BrowserPreviewTabIconSize),
                            )
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = AetherOnSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.browser_preview_close_tab),
                                tint = AetherOnSurfaceVariant,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onClose(tab) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserPreviewSearchFieldHost(
    query: String,
    searching: Boolean,
) {
    var typedCount by rememberSaveable(query) { mutableIntStateOf(0) }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            typedCount = 0
            return@LaunchedEffect
        }
        if (typedCount > query.length) typedCount = 0
        val step = when {
            query.length <= 12 -> 38L
            query.length <= 28 -> 26L
            else -> 16L
        }
        while (typedCount < query.length) {
            typedCount += 1
            delay(step)
        }
    }
    val typed = query.take(typedCount)
    val typing = typedCount < query.length && query.isNotBlank()
    BrowserPreviewSearchField(
        typed = typed,
        query = query,
        showCaret = typing || searching,
        searching = searching,
    )
}

@Composable
private fun BrowserPreviewPageBody(
    preview: BrowserDeskPreview,
    searching: Boolean,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    onOpenLink: (String) -> Unit,
    readingUrl: String = "",
    readingUrls: List<String> = emptyList(),
    readingExcerpt: String = "",
    resultsMaxHeight: Dp = 220.dp,
) {
    val hits = displayBrowserSearchHits(preview.hits)
    if (hits.isNotEmpty() || preview.kind == BrowserDeskPreviewKind.Search) {
        BrowserPreviewSearchHits(
            hits = hits,
            searching = searching,
            onOpenLink = onOpenLink,
            readingUrl = readingUrl,
            readingUrls = readingUrls,
            readingExcerpt = readingExcerpt,
            maxHeight = resultsMaxHeight,
        )
        return
    }
    when (preview.kind) {
        BrowserDeskPreviewKind.Article, BrowserDeskPreviewKind.Empty, BrowserDeskPreviewKind.Search ->
            BrowserDeskArticle(preview)
        BrowserDeskPreviewKind.Snapshot -> Text(
            text = preview.body,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
            color = AetherOnSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
        )
        BrowserDeskPreviewKind.Images -> if (preview.images.isNotEmpty()) {
            BrowserDeskImageStrip(
                images = preview.images,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
                decodeImages = true,
            )
        } else {
            BrowserDeskArticle(preview)
        }
    }
}

@Composable
private fun BrowserPreviewSearchHits(
    hits: List<kira.ditto.browser.BrowserDeskHit>,
    searching: Boolean,
    onOpenLink: (String) -> Unit,
    readingUrl: String = "",
    readingUrls: List<String> = emptyList(),
    readingExcerpt: String = "",
    maxHeight: Dp = 220.dp,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .browserPreviewScrollIsolation()
            .verticalScroll(scroll),
    ) {
        when {
            searching && hits.isEmpty() -> {
                Text(
                    text = stringResource(R.string.browser_preview_searching),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
            }
            !searching && hits.isEmpty() -> {
                Text(
                    text = stringResource(R.string.browser_preview_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
            }
            else -> {
                // Every page currently being read, so a three-URL batch fetch lights all three
                // rows instead of whichever one happened to be stored last.
                val activeReads = (readingUrls + readingUrl).filter { it.isNotBlank() }
                fun isBeingRead(hit: kira.ditto.browser.BrowserDeskHit): Boolean =
                    activeReads.any { target -> browserHitUrlMatches(hit.url, target) }
                // Three bands, not two. "Cited" now means the answer used the page, so a page the
                // agent merely opened has to sit in a band of its own instead of borrowing the
                // citation badge - that borrowing is why the card and the answer disagreed.
                val citedHits = hits.filter { hit -> hit.cited || hit.read || isBeingRead(hit) }
                val unreadHits = hits.filter { hit -> !hit.cited && !hit.read && !isBeingRead(hit) }
                citedHits.forEachIndexed { index, hit ->
                    key(hit.url.ifBlank { hit.title }) {
                        if (index > 0) {
                            BrowserPreviewHitDivider()
                        }
                        BrowserPreviewHitRow(
                            hit = hit,
                            reading = isBeingRead(hit),
                            readingExcerpt = readingExcerpt,
                            onOpenLink = onOpenLink,
                        )
                    }
                }
                AnimatedContent(
                    targetState = unreadHits.map { hit -> hit.url.ifBlank { hit.title } },
                    transitionSpec = {
                        (slideInHorizontally(tween(280)) { width -> width } + fadeIn(tween(180))) togetherWith
                            (slideOutHorizontally(tween(240)) { width -> width } + fadeOut(tween(160)))
                    },
                    label = "browser_unread_hits",
                ) { unreadKeys ->
                    val visibleUnread = unreadHits.filter { hit ->
                        unreadKeys.contains(hit.url.ifBlank { hit.title })
                    }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        visibleUnread.forEachIndexed { index, hit ->
                            key(hit.url.ifBlank { hit.title }) {
                                if (citedHits.isNotEmpty() || index > 0) {
                                    BrowserPreviewHitDivider()
                                }
                                BrowserPreviewHitRow(
                                    hit = hit,
                                    reading = false,
                                    readingExcerpt = readingExcerpt,
                                    onOpenLink = onOpenLink,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserPreviewHitDivider() {
    HorizontalDivider(
        modifier = Modifier.fillMaxWidth(),
        thickness = 0.5.dp,
        color = BrowserPreviewCardGray.copy(alpha = if (AetherIsDark) 0.45f else 0.28f),
    )
}

@Composable
private fun BrowserPreviewHitRow(
    hit: kira.ditto.browser.BrowserDeskHit,
    reading: Boolean,
    readingExcerpt: String,
    onOpenLink: (String) -> Unit,
) {
    val scroll = rememberScrollState()
    val body = readingExcerpt.ifBlank { hit.readExcerpt }.ifBlank { hit.snippet }
    LaunchedEffect(reading) {
        if (!reading) scroll.scrollTo(0)
    }
    // What the agent is doing to this page belongs on this page's card, not in a row of tool
    // chips: a breathing rule along the top while it is being read, and a settled tint once it
    // has been quoted.
    val readingPulse = rememberInfiniteTransition(label = "browser_hit_reading")
    val pulseAlpha by readingPulse.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(760, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "browser_hit_reading_pulse",
    )
    val restingFill = browserPreviewCardFill()
    val citedFill = AetherPrimary.copy(alpha = if (AetherIsDark) 0.16f else 0.09f)
    val fillColor by animateColorAsState(
        targetValue = if (hit.cited) citedFill else restingFill,
        animationSpec = tween(durationMillis = 420, easing = LinearEasing),
        label = "browser_hit_fill",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hit.url.isBlank()) Modifier
                else Modifier.clickable { onOpenLink(hit.url) },
            ),
        shape = RoundedCornerShape(12.dp),
        color = fillColor,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AnimatedVisibility(
                visible = reading,
                enter = fadeIn(tween(180)) + expandVertically(tween(200)),
                exit = fadeOut(tween(220)) + shrinkVertically(tween(240)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(AetherPrimary.copy(alpha = pulseAlpha)),
                )
            }
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AnimatedVisibility(
                    visible = hit.cited,
                    enter = fadeIn(tween(280)) + expandHorizontally(tween(300)),
                    exit = fadeOut(tween(160)) + shrinkHorizontally(tween(200)),
                ) {
                    Text(
                        text = stringResource(R.string.browser_preview_cited),
                        style = MaterialTheme.typography.labelSmall,
                        color = AetherOnSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, end = 2.dp),
                    )
                }
                Text(
                    text = hit.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            // The sentence the answer and this page share. Shown only when the resolver could pin
            // one down, so its presence is itself the claim: this is the line that was used.
            AnimatedVisibility(
                visible = hit.cited && hit.citedQuote.isNotBlank(),
                enter = fadeIn(tween(240)) + expandVertically(tween(260)),
                exit = fadeOut(tween(160)) + shrinkVertically(tween(200)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(16.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(AetherPrimary.copy(alpha = 0.55f)),
                    )
                    Text(
                        text = hit.citedQuote,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (body.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clipToBounds(),
                ) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                        maxLines = if (reading) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (reading) {
                            Modifier.verticalScroll(scroll)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserPreviewTabAvatar(
    tab: BrowserPreviewAgentTab,
    modifier: Modifier = Modifier,
) {
    val spec = tab.avatar
    if (spec != null) {
        HumationAvatarImage(
            spec = spec,
            modifier = modifier.clip(CircleShape),
            size = BrowserPreviewTabIconSize,
            contentDescription = tab.name,
        )
    } else {
        BrowserPreviewGeckoMark(modifier = modifier)
    }
}

@Composable
private fun BrowserPreviewSearchField(
    typed: String,
    query: String,
    showCaret: Boolean,
    searching: Boolean,
) {
    val placeholder = stringResource(R.string.browser_preview_search_placeholder)
    val infinite = rememberInfiniteTransition(label = "browser_preview_caret")
    val blinking by infinite.animateFloat(
        initialValue = 0.12f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(420),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "browser_preview_caret_alpha",
    )
    val caretAlpha = if (showCaret) blinking else 0f
    val shown = typed.ifBlank {
        if (!searching && query.isBlank()) placeholder else ""
    }
    val dimPlaceholder = typed.isBlank() && query.isBlank() && !searching
    val fieldFill = lerp(AetherSurfaceHigh, BrowserPreviewSearchAccent, 0.07f).copy(alpha = 0.70f)
    val typedScroll = rememberScrollState()
    LaunchedEffect(typed) {
        typedScroll.scrollTo(typedScroll.maxValue)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(fieldFill)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = if (searching) BrowserPreviewSearchAccent else AetherOnSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(typedScroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = shown,
                style = MaterialTheme.typography.bodyMedium,
                color = if (dimPlaceholder) AetherOnSurfaceVariant else AetherOnSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
            if (showCaret) {
                Box(
                    modifier = Modifier
                        .padding(start = 1.dp)
                        .width(1.5.dp)
                        .height(16.dp)
                        .graphicsLayer { alpha = caretAlpha }
                        .background(BrowserPreviewSearchAccent, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

@Composable
private fun BrowserPreviewModeToggle(
    live: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.browser_preview_text),
            style = MaterialTheme.typography.labelLarge,
            color = if (!live) AetherOnSurface else AetherOnSurfaceVariant,
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onChange(false) },
        )
        Text(
            text = stringResource(R.string.browser_preview_live),
            style = MaterialTheme.typography.labelLarge,
            color = if (live) AetherOnSurface else AetherOnSurfaceVariant,
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onChange(true) },
        )
    }
}

@Composable
private fun BrowserPreviewLivePane(
    topicId: String,
    expectedUrl: String = "",
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activityForeground by BrowserEngineSurface.activityForeground.collectAsState()
    if (activityForeground) {
        Text(
            text = stringResource(R.string.browser_preview_live_in_browser),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = modifier.padding(12.dp),
        )
        return
    }
    val components = remember(context) {
        AetherBrowserRuntime.peek() ?: AetherBrowserRuntime.get(context)
    }
    // The engine session for this research group, not whatever tab happens to be globally
    // selected — with two agents working, the selected tab is regularly the other one's page.
    fun liveSessionFor(state: mozilla.components.browser.state.state.BrowserState): EngineSession? {
        val bound = BrowserTopicGraph.primaryTabId(topicId)
        val tab = state.tabs.firstOrNull { it.id == bound && bound.isNotBlank() }
        return tab?.engineState?.engineSession
    }
    // This used to poll the store every 50 ms for the whole time the card was on screen: a 20 Hz
    // wake-up on the main dispatcher competing with the very page it is trying to render. The
    // store already emits on every change.
    val sessionEpoch by produceState(0, components, topicId, expectedUrl) {
        var lastId = 0
        components.store.flow().collect { state ->
            val id = liveSessionFor(state)?.let { System.identityHashCode(it) } ?: 0
            if (id != lastId) {
                lastId = id
                value += 1
            }
        }
    }
    sessionEpoch
    if (liveSessionFor(components.store.state) == null) {
        Text(
            text = stringResource(R.string.browser_preview_page_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = modifier.padding(12.dp),
        )
        return
    }
    AndroidView(
        modifier = modifier.browserPreviewScrollIsolation(),
        factory = { ctx ->
            val engineView = AetherBrowserRuntime.takeEngineViewForCompose(ctx)
            engineView.asView().apply {
                tag = EngineViewHost(engineView)
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                            browserPreviewDisallowParentIntercept(view, true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            browserPreviewDisallowParentIntercept(view, false)
                    }
                    false
                }
            }
        },
        update = { view ->
            sessionEpoch
            val host = view.tag as? EngineViewHost ?: return@AndroidView
            val state = components.store.state
            val bound = BrowserTopicGraph.primaryTabId(topicId)
            val tab = state.tabs.firstOrNull { it.id == bound && bound.isNotBlank() }
            val session = tab?.engineState?.engineSession
            if (session == null) {
                host.renderedSession = null
                view.visibility = android.view.View.INVISIBLE
                return@AndroidView
            }
            view.visibility = android.view.View.VISIBLE
            if (host.renderedSession === session && host.renderedTabId == tab.id) {
                return@AndroidView
            }
            host.engineView.render(session)
            host.renderedSession = session
            host.renderedTabId = tab.id
        },
        onRelease = { view ->
            val host = view.tag as? EngineViewHost ?: return@AndroidView
            AetherBrowserRuntime.parkEngineView(host.engineView)
        },
    )
}

private class EngineViewHost(
    val engineView: EngineView,
    var renderedTabId: String? = null,
    var renderedSession: EngineSession? = null,
)

@Composable
private fun BrowserPreviewExpandingBody(
    expanded: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(animationSpec = tween(220)) + expandVertically(
            animationSpec = tween(280),
            expandFrom = Alignment.Top,
        ),
        exit = fadeOut(animationSpec = tween(180)) + shrinkVertically(
            animationSpec = tween(240),
            shrinkTowards = Alignment.Top,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 14.dp,
                    end = 14.dp,
                    top = 6.dp,
                )
                .browserPreviewScrollIsolation(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun BrowserPreviewClippedBody(
    maxHeight: Dp,
    fill: Color,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clipToBounds()
            .browserPreviewScrollIsolation(),
    ) {
        content()
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(BrowserPreviewBodyFade)
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to fill)),
        )
    }
}

@Composable
private fun BoxScope.BrowserPreviewCornerResizeHandle(
    maxExtraPx: Float,
    onHeightDelta: (Float) -> Unit,
) {
    val view = LocalView.current
    val description = stringResource(R.string.browser_preview_resize)
    val strokeColor = AetherOnSurfaceVariant.copy(alpha = if (AetherIsDark) 0.58f else 0.40f)
    Canvas(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(44.dp)
            .semantics { contentDescription = description }
            .pointerInput(maxExtraPx) {
                detectVerticalDragGestures(
                    onDragStart = { browserPreviewDisallowParentIntercept(view, true) },
                    onDragEnd = { browserPreviewDisallowParentIntercept(view, false) },
                    onDragCancel = { browserPreviewDisallowParentIntercept(view, false) },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        onHeightDelta(dragAmount)
                    },
                )
            },
    ) {
        val handlePx = size.minDimension
        val cornerPx = BrowserPreviewCardCorner.toPx()
        val outsetPx = BrowserPreviewResizeOutset.toPx()
        val gapPx = BrowserPreviewResizeGap.toPx()
        val strokePx = BrowserPreviewResizeStroke.toPx()
        val center = Offset(
            x = handlePx - outsetPx - cornerPx,
            y = handlePx - outsetPx - cornerPx,
        )
        val radius = cornerPx + gapPx + strokePx / 2f
        drawArc(
            color = strokeColor,
            startAngle = 0f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}

private val BrowserPreviewConsumeLeftoverVertical = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = Offset(0f, available.y)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y)
}

private fun browserPreviewDisallowParentIntercept(view: View, disallow: Boolean) {
    var current: ViewParent? = view.parent
    while (current != null) {
        if (current is ViewGroup) current.requestDisallowInterceptTouchEvent(disallow)
        current = current.parent
    }
}

@Composable
private fun Modifier.browserPreviewScrollIsolation(): Modifier {
    val view = LocalView.current
    return this
        .nestedScroll(BrowserPreviewConsumeLeftoverVertical)
        .pointerInput(view) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                browserPreviewDisallowParentIntercept(view, true)
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.changes.all { !it.pressed }) break
                    }
                } finally {
                    browserPreviewDisallowParentIntercept(view, false)
                }
            }
        }
}

@Composable
private fun browserPreviewCardFill(): Color =
    lerp(AetherSurface, BrowserPreviewCardGray, 0.05f).copy(alpha = BrowserPreviewCardAlpha)

@Composable
private fun BrowserPreviewGeckoMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_gecko_engine),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
