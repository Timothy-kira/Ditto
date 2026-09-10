package kira.ditto.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/** 每帧向底部移动剩余距离的比例；距离大时快、接近底部时逐行减速。 */
internal const val StreamFollowFraction = 0.22f

private const val UnmeasuredScrollMax = Int.MAX_VALUE

internal fun streamFollowStepPx(
    distance: Float,
    fraction: Float = StreamFollowFraction,
): Float {
    if (distance <= 0f) return 0f
    return (distance * fraction).coerceIn(1f, distance)
}

/** Keep the item's visual top on screen when it grows instead of pinning the bottom. */
internal fun conversationKeepItemTopDeltaPx(
    previousOffset: Int?,
    currentOffset: Int?,
    previousSize: Int? = null,
    currentSize: Int? = null,
): Float {
    if (previousOffset == null || currentOffset == null) return 0f
    val offsetDelta = (currentOffset - previousOffset).toFloat()
    val sizeDelta = if (previousSize != null && currentSize != null) {
        (currentSize - previousSize).toFloat()
    } else {
        0f
    }
    return offsetDelta + sizeDelta
}

/**
 * With [reverseLayout] chats, index 0 is the visual bottom. Pinning that
 * item lets streaming text grow upward without chasing every height change.
 */
suspend fun LazyListState.scrollToConversationEnd(reverseLayout: Boolean = false) {
    snapToConversationEnd(reverseLayout)
}

suspend fun LazyListState.autoFollowConversationEnd(
    reverseLayout: Boolean = false,
    followContentSize: Boolean = true,
) {
    snapshotFlow { layoutInfo.totalItemsCount }.first { it > 0 }
    snapToConversationEnd(reverseLayout)
    var lastCount = layoutInfo.totalItemsCount
    var lastEndSize = endItemInfo(reverseLayout)?.size
    var lastEndOffset = endItemInfo(reverseLayout)?.offset
    while (true) {
        snapshotFlow {
            val item = endItemInfo(reverseLayout)
            AutoFollowFrame(
                count = layoutInfo.totalItemsCount,
                size = item?.size,
                offset = item?.offset,
                overflow = conversationEndOverflowPx(reverseLayout) ?: 0f,
            )
        }.first { frame ->
            frame.count != lastCount ||
                (followContentSize && abs(frame.overflow) >= 1f) ||
                (!followContentSize && frame.size != lastEndSize)
        }
        if (isScrollInProgress) {
            withFrameMillis { }
            lastCount = layoutInfo.totalItemsCount
            lastEndSize = endItemInfo(reverseLayout)?.size
            lastEndOffset = endItemInfo(reverseLayout)?.offset
            continue
        }
        val count = layoutInfo.totalItemsCount
        if (count != lastCount) {
            lastCount = count
            snapToConversationEnd(reverseLayout)
            lastEndSize = endItemInfo(reverseLayout)?.size
            lastEndOffset = endItemInfo(reverseLayout)?.offset
            continue
        }
        if (followContentSize) {
            followConversationEnd(reverseLayout)
            lastEndSize = endItemInfo(reverseLayout)?.size
            lastEndOffset = endItemInfo(reverseLayout)?.offset
            continue
        }
        val delta = conversationKeepItemTopDeltaPx(
            previousOffset = lastEndOffset,
            currentOffset = endItemInfo(reverseLayout)?.offset,
            previousSize = lastEndSize,
            currentSize = endItemInfo(reverseLayout)?.size,
        )
        if (abs(delta) >= 1f) {
            scrollBy(delta)
        }
        lastEndSize = endItemInfo(reverseLayout)?.size
        lastEndOffset = endItemInfo(reverseLayout)?.offset
    }
}

suspend fun ScrollState.followStreamEnd() {
    snapshotFlow { maxValue }
        .first { it > 0 && it != UnmeasuredScrollMax }
    scrollTo(maxValue)
    while (true) {
        snapshotFlow { maxValue }
            .first { it > value && it != UnmeasuredScrollMax }
        while (value < maxValue) {
            val target = maxValue
            if (target == UnmeasuredScrollMax) break
            val distance = target - value
            val step = streamFollowStepPx(distance.toFloat()).toInt().coerceIn(1, distance)
            scrollTo(value + step)
            withFrameMillis { }
        }
    }
}

private data class AutoFollowFrame(
    val count: Int,
    val size: Int?,
    val offset: Int?,
    val overflow: Float,
)

private suspend fun LazyListState.snapToConversationEnd(reverseLayout: Boolean) {
    val count = layoutInfo.totalItemsCount
    if (count <= 0) return
    val targetIndex = if (reverseLayout) 0 else count - 1
    if (layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
        scrollToItem(targetIndex)
    }
    val overflow = conversationEndOverflowPx(reverseLayout) ?: return
    if (overflow != 0f) {
        scrollBy(overflow)
    }
}

private suspend fun LazyListState.followConversationEnd(reverseLayout: Boolean) {
    val count = layoutInfo.totalItemsCount
    if (count <= 0) return
    val targetIndex = if (reverseLayout) 0 else count - 1
    if (layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
        scrollToItem(targetIndex)
    }
    while (true) {
        val overflow = conversationEndOverflowPx(reverseLayout) ?: return
        if (abs(overflow) < 1f) return
        val step = streamFollowStepPx(abs(overflow))
        scrollBy(if (overflow > 0f) step else -step)
        withFrameMillis { }
    }
}

private fun LazyListState.endItemInfo(reverseLayout: Boolean): LazyListItemInfo? {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return null
    val targetIndex = if (reverseLayout) 0 else info.totalItemsCount - 1
    return info.visibleItemsInfo.firstOrNull { it.index == targetIndex }
}

private fun LazyListState.conversationEndOverflowPx(reverseLayout: Boolean): Float? {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return null
    val targetIndex = if (reverseLayout) 0 else info.totalItemsCount - 1
    val item = info.visibleItemsInfo.firstOrNull { it.index == targetIndex } ?: return null
    val targetBottom = info.viewportEndOffset - info.afterContentPadding
    return ((item.offset + item.size) - targetBottom).toFloat()
}

fun LazyListState.isAtConversationEnd(
    thresholdPx: Int = 32,
    reverseLayout: Boolean = false,
): Boolean {
    if (layoutInfo.totalItemsCount == 0) return true
    val overflow = conversationEndOverflowPx(reverseLayout) ?: return false
    return overflow <= thresholdPx
}
