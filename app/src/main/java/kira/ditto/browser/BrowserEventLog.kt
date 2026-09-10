package kira.ditto.browser

import kira.ditto.data.KnowledgeCitation

/**
 * Every change to the browser desk, as an append-only fact.
 *
 * The desk used to be written in six places, each assigning `_state.value` directly. That is what
 * made update *order* decide correctness - and the citation-settlement bug was exactly that: the
 * verdict was written to the live desk while the card had already switched to reading the stored
 * message snapshot, so a correct value went somewhere nobody was looking. Bugs of that shape only
 * exist while several parallel writable states do.
 *
 * With the log as the only writer, `BrowserDeskState` becomes `fold(events)`. "Write the desk before
 * the snapshot, or after?" stops being a question with a right answer and becomes a question with no
 * meaning, because both are computed from the same list.
 *
 * These events carry resolved values rather than instructions. `Published` holds the preview that
 * was published, not the tool call that caused it. That keeps [applyBrowserDeskEvent] a pure
 * function of the event, so a replay a week later cannot drift from what the live desk showed - it
 * has nothing left to recompute.
 */
sealed interface BrowserDeskEvent {
    val atMillis: Long

    /** The user (or the host) moved the card to a different research group. */
    data class TopicSelected(
        val topicId: String,
        override val atMillis: Long,
    ) : BrowserDeskEvent

    /**
     * A tool call produced a new view of the desk.
     *
     * The reading/opened/takeover fields are snapshots of the in-flight observations at the moment
     * of publication. They are captured into the event rather than read from live fields at replay
     * time, because those fields describe *now* and an event describes *then*.
     */
    data class Published(
        val preview: BrowserDeskPreview,
        val topicId: String,
        val previewsByTopic: Map<String, BrowserDeskPreview>,
        val pages: List<BrowserDeskPreview>,
        val activities: List<BrowserDeskActivity>,
        val sources: List<KnowledgeCitation>,
        val readingUrl: String,
        val readingUrls: List<String>,
        val readingExcerpt: String,
        val openedUrl: String,
        val userTakeover: Boolean,
        val userTakeoverReason: String,
        val keepLiveSurface: Boolean,
        override val atMillis: Long,
    ) : BrowserDeskEvent

    /** The answer is finished and these are the pages it actually used. */
    data class CitationsApplied(
        val preview: BrowserDeskPreview,
        val previewsByTopic: Map<String, BrowserDeskPreview>,
        override val atMillis: Long,
    ) : BrowserDeskEvent

    /** A stored snapshot was loaded back onto the desk when a session was reopened. */
    data class Hydrated(
        val preview: BrowserDeskPreview,
        val previewsByTopic: Map<String, BrowserDeskPreview>,
        val uiPreviewTopicId: String,
        override val atMillis: Long,
    ) : BrowserDeskEvent

    /**
     * The turn ended and the research it produced no longer belongs on the card.
     *
     * [keepLiveSurface] separates "the user is still looking at a page we opened for them" from
     * "everything goes". Carrying the per-topic previews across that boundary is why opening the
     * card once showed the previous task's page.
     */
    data class TurnRetired(
        val keepLiveSurface: Boolean,
        val openedUrl: String,
        override val atMillis: Long,
    ) : BrowserDeskEvent

    /** Everything gone: a new chat, or a session switch. */
    data class Cleared(override val atMillis: Long) : BrowserDeskEvent

    /**
     * An execution receipt moved.
     *
     * This is the TaskStore capability on the desk log: running, waiting_user,
     * needs_verification, failed, completed. UI and `browser_tasks` read [BrowserDeskState.tasks],
     * not a tool's output JSON.
     */
    data class TaskProgress(
        val task: BrowserDeskTask,
        val seq: Long,
        override val atMillis: Long,
    ) : BrowserDeskEvent
}

/**
 * The state after one more fact.
 *
 * Pure, total, and the only place a `BrowserDeskState` is ever built. Being pure is what makes the
 * equivalence test meaningful: `replayBrowserDesk(log)` and the live state are the same computation
 * run in two different orders of arrival, so they cannot disagree unless something wrote the state
 * without going through here.
 */
fun applyBrowserDeskEvent(
    state: BrowserDeskState,
    event: BrowserDeskEvent,
): BrowserDeskState = when (event) {
    is BrowserDeskEvent.TopicSelected -> state.copy(
        uiPreviewTopicId = event.topicId,
        preview = state.previewsByTopic[event.topicId] ?: state.preview,
    )

    is BrowserDeskEvent.Published -> state.copy(
        preview = event.preview,
        previewsByTopic = event.previewsByTopic,
        pages = event.pages,
        activities = event.activities,
        sources = event.sources,
        uiPreviewTopicId = event.topicId,
        readingUrl = event.readingUrl,
        readingUrls = event.readingUrls,
        readingExcerpt = event.readingExcerpt,
        openedUrl = event.openedUrl,
        userTakeover = event.userTakeover,
        userTakeoverReason = event.userTakeoverReason,
        keepLiveSurface = event.keepLiveSurface,
        lastUpdatedMillis = event.atMillis,
    )

    is BrowserDeskEvent.CitationsApplied -> state.copy(
        preview = event.preview,
        previewsByTopic = event.previewsByTopic,
        lastUpdatedMillis = event.atMillis,
    )

    is BrowserDeskEvent.Hydrated -> BrowserDeskState(
        preview = event.preview,
        previewsByTopic = event.previewsByTopic,
        uiPreviewTopicId = event.uiPreviewTopicId,
        lastUpdatedMillis = event.atMillis,
    )

    is BrowserDeskEvent.TurnRetired -> if (event.keepLiveSurface) {
        state.copy(
            previewsByTopic = emptyMap(),
            pages = emptyList(),
            uiPreviewTopicId = "",
            openedUrl = event.openedUrl,
        )
    } else {
        state.copy(
            preview = BrowserDeskPreview(),
            previewsByTopic = emptyMap(),
            pages = emptyList(),
            uiPreviewTopicId = "",
            openedUrl = "",
            readingUrl = "",
            readingUrls = emptyList(),
            readingExcerpt = "",
            userTakeover = false,
            userTakeoverReason = "",
            keepLiveSurface = false,
        )
    }

    is BrowserDeskEvent.Cleared -> BrowserDeskState()

    is BrowserDeskEvent.TaskProgress -> state.copy(
        tasks = (listOf(event.task) + state.tasks.filter { it.id != event.task.id }).take(30),
        taskEventSeq = event.seq,
        lastUpdatedMillis = event.atMillis,
    )
}

/**
 * The whole desk from the whole log.
 *
 * This is what makes the live state recoverable rather than precious. `park` / `restoreParked` kept
 * a copy of the state around because losing it meant losing the card - the "pet" pattern. A desk
 * that can be replayed is cattle: if it is gone, rebuild it.
 */
fun replayBrowserDesk(events: List<BrowserDeskEvent>): BrowserDeskState =
    events.fold(BrowserDeskState()) { state, event -> applyBrowserDeskEvent(state, event) }

/**
 * Drop the events a replay can no longer change.
 *
 * [BrowserDeskEvent.Cleared] and [BrowserDeskEvent.Hydrated] both build a state from nothing, so
 * nothing before them can affect the result. Compacting at those points keeps the log bounded
 * without weakening it - the same reasoning as a snapshot boundary, just discovered rather than
 * written.
 */
fun compactBrowserDeskLog(events: List<BrowserDeskEvent>, softLimit: Int = 512): List<BrowserDeskEvent> {
    if (events.size <= softLimit) return events
    val lastReset = events.indexOfLast { event ->
        event is BrowserDeskEvent.Cleared || event is BrowserDeskEvent.Hydrated
    }
    if (lastReset > 0) return events.subList(lastReset, events.size).toList()
    // No reset to cut at: keep the tail. The head is unreachable in practice because every field of
    // the state is overwritten by a later Published, and dropping it is preferable to growing without
    // bound on a phone.
    return events.subList(events.size - softLimit, events.size).toList()
}
