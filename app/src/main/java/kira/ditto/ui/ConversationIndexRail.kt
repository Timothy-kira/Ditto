package kira.ditto.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kira.ditto.data.visibleUserMessageText
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherSurfaceHigh
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class ConversationPromptTick(
    val messageId: String,
    val preview: String,
    val itemIndex: Int,
)

internal const val ConversationIndexRailMaxTicks = 24

internal fun conversationPromptPreview(text: String, maxChars: Int = 6): String {
    val cleaned = text.visibleUserMessageText().trim().replace(Regex("\\s+"), " ")
    if (cleaned.isEmpty()) return "…"
    return if (cleaned.length <= maxChars) cleaned else cleaned.take(maxChars)
}

internal fun conversationLazyPrefixCount(
    pendingInputCount: Int,
    showPendingGeneration: Boolean,
    isCompacting: Boolean,
): Int {
    var count = 2
    count += pendingInputCount
    if (showPendingGeneration) count += 1
    if (isCompacting) count += 1
    return count
}

internal fun conversationLazyIndexForItem(
    prefixCount: Int,
    conversationItemCount: Int,
    itemIndex: Int,
): Int = prefixCount + (conversationItemCount - 1 - itemIndex)

internal fun shouldShowConversationIndexRail(ticks: List<ConversationPromptTick>): Boolean =
    ticks.size >= 2

internal fun conversationIndexRailVisibleTicks(
    ticks: List<ConversationPromptTick>,
    maxCount: Int = ConversationIndexRailMaxTicks,
): List<ConversationPromptTick> {
    if (ticks.size <= maxCount) return ticks
    if (maxCount <= 1) return listOf(ticks.last())
    return List(maxCount) { slot ->
        ticks[(slot.toLong() * ticks.lastIndex / (maxCount - 1)).toInt()]
    }
}

internal fun fisheyeBarWidth(
    distance: Float,
    minWidth: Float,
    maxWidth: Float,
    sigma: Float = 1.35f,
): Float {
    val gauss = exp(-(distance * distance) / (2f * sigma * sigma))
    return minWidth + (maxWidth - minWidth) * gauss
}

internal fun conversationPromptFocusFromVisibleItems(
    visibleCenters: List<Pair<Int, Int>>,
    viewportCenter: Int,
    prefixCount: Int,
    conversationItemCount: Int,
    promptItemIndices: List<Int>,
): Int? {
    if (promptItemIndices.isEmpty() || conversationItemCount <= 0) return null
    val rangeEnd = prefixCount + conversationItemCount
    return visibleCenters.mapNotNull { (lazyIndex, center) ->
        if (lazyIndex < prefixCount || lazyIndex >= rangeEnd) return@mapNotNull null
        val itemIndex = conversationItemCount - 1 - (lazyIndex - prefixCount)
        val tickIndex = promptItemIndices.minByOrNull { abs(it - itemIndex) }
            ?.let { nearest -> promptItemIndices.indexOf(nearest) }
            ?: return@mapNotNull null
        tickIndex to abs(center - viewportCenter)
    }.minByOrNull { it.second }?.first
}

private val IndexRailWidth = 28.dp
private val IndexRailMaxBarWidth = 18.dp
private val IndexRailMinBarWidth = 7.dp
private val IndexRailBarHeight = 2.dp
private val IndexRailBarGap = 5.dp
private val IndexRailVerticalPad = 6.dp

@Composable
internal fun ConversationIndexRail(
    ticks: List<ConversationPromptTick>,
    listState: LazyListState,
    prefixCount: Int,
    conversationItemCount: Int,
    onNavigate: (ConversationPromptTick) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleTicks = remember(ticks) { conversationIndexRailVisibleTicks(ticks) }
    if (visibleTicks.isEmpty()) return
    val density = LocalDensity.current
    val maxBarWidthPx = with(density) { IndexRailMaxBarWidth.toPx() }
    val minBarWidthPx = with(density) { IndexRailMinBarWidth.toPx() }
    val barHeightPx = with(density) { IndexRailBarHeight.toPx() }
    val gapPx = with(density) { IndexRailBarGap.toPx() }
    val verticalPadPx = with(density) { IndexRailVerticalPad.toPx() }
    val clusterHeight = with(density) {
        (IndexRailVerticalPad * 2) +
            IndexRailBarHeight * visibleTicks.size +
            IndexRailBarGap * (visibleTicks.size - 1).coerceAtLeast(0)
    }
    val activeColor = AetherOnSurface
    val idleColor = AetherOnSurfaceVariant.copy(alpha = 0.42f)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var dragging by remember { mutableStateOf(false) }
    var previewVisible by remember { mutableStateOf(false) }
    val focusAnim = remember { Animatable(visibleTicks.lastIndex.toFloat()) }
    val latestTicks = rememberUpdatedState(visibleTicks)
    val latestOnNavigate = rememberUpdatedState(onNavigate)
    val draggingState = rememberUpdatedState(dragging)
    val stretch by animateFloatAsState(
        targetValue = if (dragging) 1.45f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "index_rail_stretch",
    )

    fun yForIndex(index: Float): Float {
        val step = barHeightPx + gapPx
        return verticalPadPx + index * step + barHeightPx / 2f
    }

    fun indexForY(y: Float): Float {
        if (visibleTicks.size <= 1) return 0f
        val step = barHeightPx + gapPx
        return ((y - verticalPadPx - barHeightPx / 2f) / step)
            .coerceIn(0f, visibleTicks.lastIndex.toFloat())
    }

    suspend fun snapFocusTo(index: Float, animate: Boolean) {
        val clamped = index.coerceIn(0f, latestTicks.value.lastIndex.toFloat().coerceAtLeast(0f))
        if (animate) {
            focusAnim.animateTo(
                clamped,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        } else {
            focusAnim.snapTo(clamped)
        }
    }

    fun emitNavigation(index: Float) {
        val list = latestTicks.value
        if (list.isEmpty()) return
        val tick = list.getOrNull(index.roundToInt().coerceIn(0, list.lastIndex)) ?: return
        latestOnNavigate.value(tick)
    }

    LaunchedEffect(visibleTicks.size) {
        if (!dragging && visibleTicks.isNotEmpty()) {
            val current = focusAnim.value.coerceIn(0f, visibleTicks.lastIndex.toFloat())
            focusAnim.snapTo(current)
        }
    }

    LaunchedEffect(listState, prefixCount, conversationItemCount, visibleTicks) {
        val promptItemIndices = visibleTicks.map { it.itemIndex }
        snapshotFlow {
            val info = listState.layoutInfo
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val centers = info.visibleItemsInfo.map { item ->
                item.index to (item.offset + item.size / 2)
            }
            conversationPromptFocusFromVisibleItems(
                visibleCenters = centers,
                viewportCenter = viewportCenter,
                prefixCount = prefixCount,
                conversationItemCount = conversationItemCount,
                promptItemIndices = promptItemIndices,
            )
        }
            .distinctUntilChanged()
            .collect { tickIndex ->
                if (!draggingState.value && tickIndex != null) {
                    snapFocusTo(tickIndex.toFloat(), animate = true)
                }
            }
    }

    LaunchedEffect(previewVisible, dragging) {
        if (!previewVisible || dragging) return@LaunchedEffect
        delay(1_200)
        if (!draggingState.value) previewVisible = false
    }

    val focus = focusAnim.value
    val barStretch = stretch
    val previewAlpha by animateFloatAsState(
        targetValue = if (previewVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "index_rail_preview_alpha",
    )
    val focusedTick = visibleTicks.getOrNull(focus.roundToInt().coerceIn(0, visibleTicks.lastIndex))
    val previewY = yForIndex(focus)

    Box(
        modifier = modifier
            .width(IndexRailWidth)
            .height(clusterHeight)
            .gpuOffscreenLayer()
            .pointerInput(visibleTicks) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = down.position
                    val slop = viewConfiguration.touchSlop
                    val tappedIndex = indexForY(start.y)
                    var liftedBeforeHold = false
                    var movedBeforeHold = false
                    val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull
                            if (!change.pressed) {
                                liftedBeforeHold = true
                                return@withTimeoutOrNull
                            }
                            if ((change.position - start).getDistance() > slop) {
                                movedBeforeHold = true
                                return@withTimeoutOrNull
                            }
                        }
                    }
                    if (movedBeforeHold) return@awaitEachGesture
                    if (liftedBeforeHold || held != null) {
                        down.consume()
                        scope.launch { snapFocusTo(tappedIndex, animate = true) }
                        emitNavigation(tappedIndex)
                        return@awaitEachGesture
                    }
                    down.consume()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    dragging = true
                    previewVisible = true
                    var lastIndex = focusAnim.value
                    drag(down.id) { change ->
                        change.consume()
                        lastIndex = indexForY(change.position.y)
                        scope.launch { snapFocusTo(lastIndex, animate = false) }
                    }
                    dragging = false
                    emitNavigation(lastIndex)
                }
            },
    ) {
        Canvas(
            modifier = Modifier
                .width(IndexRailWidth)
                .height(clusterHeight)
                .align(Alignment.Center),
        ) {
            visibleTicks.indices.forEach { index ->
                val width = fisheyeBarWidth(
                    distance = abs(index - focus),
                    minWidth = minBarWidthPx * (0.9f + 0.1f * barStretch),
                    maxWidth = maxBarWidthPx * barStretch,
                )
                val centerY = yForIndex(index.toFloat())
                val color = lerpIdleToActive(
                    idle = idleColor,
                    active = activeColor,
                    amount = fisheyeBarWidth(abs(index - focus), 0f, 1f),
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width - width, centerY - barHeightPx / 2f),
                    size = Size(width, barHeightPx),
                    cornerRadius = CornerRadius(barHeightPx / 2f, barHeightPx / 2f),
                )
            }
        }

        if (focusedTick != null && previewAlpha > 0.02f) {
            Text(
                text = focusedTick.preview,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = IndexRailWidth)
                    .offset { IntOffset(0, previewY.roundToInt() - 14) }
                    .alpha(previewAlpha)
                    .background(AetherSurfaceHigh.copy(alpha = 0.96f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

private fun lerpIdleToActive(idle: Color, active: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = idle.red + (active.red - idle.red) * t,
        green = idle.green + (active.green - idle.green) * t,
        blue = idle.blue + (active.blue - idle.blue) * t,
        alpha = idle.alpha + (active.alpha - idle.alpha) * t,
    )
}
