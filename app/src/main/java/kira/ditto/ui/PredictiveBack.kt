package kira.ditto.ui

import android.graphics.Rect as AndroidRect
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * System Android 13+ predictive-back progress (PredictiveBackHandler).
 * Keep this off so OEM/system animations do not fight the in-app edge swipe.
 * [PredictiveBackHost] still runs the custom edge-swipe transform used by
 * settings secondary pages.
 */
internal const val SystemPredictiveBackEnabled = false

private val PredictiveBackSettleSpec = tween<Float>(220)
private val PredictiveBackCommitSpec = tween<Float>(160)
private const val PredictiveBackCommitDebounceMs = 400L

class PredictiveBackGestureState {
    var progress by mutableFloatStateOf(0f)
        internal set
    var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        internal set
}

@Composable
fun rememberPredictiveBackGestureState(): PredictiveBackGestureState =
    remember { PredictiveBackGestureState() }

@Composable
fun AetherPredictiveBackHandler(
    enabled: Boolean = true,
    state: PredictiveBackGestureState = rememberPredictiveBackGestureState(),
    onBack: () -> Unit,
) {
    val latestOnBack by rememberUpdatedState(onBack)
    if (!SystemPredictiveBackEnabled) {
        BackHandler(enabled) { latestOnBack() }
        return
    }
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event ->
                state.swipeEdge = event.swipeEdge
                state.progress = event.progress
            }
            latestOnBack()
        } catch (_: CancellationException) {
            animate(state.progress, 0f, animationSpec = PredictiveBackSettleSpec) { value, _ ->
                state.progress = value
            }
        }
    }
}

@Composable
fun PredictiveBackHost(
    enabled: Boolean = true,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberPredictiveBackGestureState()
    val latestOnBack by rememberUpdatedState(onBack)
    val lastCommitAt = remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val edgePx = with(LocalDensity.current) { 72.dp.toPx() }
    val commitBack: () -> Unit = {
        val now = SystemClock.uptimeMillis()
        if (now - lastCommitAt.longValue >= PredictiveBackCommitDebounceMs) {
            lastCommitAt.longValue = now
            latestOnBack()
            state.progress = 0f
        }
    }
    AetherPredictiveBackHandler(enabled = enabled, state = state, onBack = commitBack)
    ExcludeSystemBackGesture(enabled = enabled, edgePx = edgePx)
    Box(modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (enabled) {
                        Modifier.pointerInput(enabled) {
                            detectPredictiveBackGesture(
                                state = state,
                                edgePx = edgePx,
                                scope = scope,
                                onBack = commitBack,
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .predictiveBackTransform(state),
        ) {
            content()
        }
    }
}

@Composable
private fun ExcludeSystemBackGesture(
    enabled: Boolean,
    edgePx: Float,
) {
    val view = LocalView.current
    DisposableEffect(view, enabled, edgePx) {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onDispose { }
        } else {
            fun applyRects() {
                val height = view.height.coerceAtLeast(1)
                val width = edgePx.toInt().coerceIn(1, view.width.coerceAtLeast(1))
                view.systemGestureExclusionRects = listOf(
                    AndroidRect(0, 0, width, height),
                )
            }
            val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyRects() }
            view.addOnLayoutChangeListener(listener)
            applyRects()
            onDispose {
                view.removeOnLayoutChangeListener(listener)
                view.systemGestureExclusionRects = emptyList()
            }
        }
    }
}

@Composable
fun Modifier.predictiveBackTransform(state: PredictiveBackGestureState): Modifier {
    val progress = state.progress
    val swipeEdge = state.swipeEdge
    return graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        if (progress <= 0.001f) return@graphicsLayer
        val scale = 1f - (0.1f * progress)
        scaleX = scale
        scaleY = scale
        translationX = if (swipeEdge == BackEventCompat.EDGE_RIGHT) {
            -progress * size.width * 0.38f
        } else {
            progress * size.width * 0.38f
        }
        shape = RoundedCornerShape(32.dp * progress)
        clip = true
        shadowElevation = 22f * progress
        cameraDistance = 12f * density
    }
}

private suspend fun PointerInputScope.detectPredictiveBackGesture(
    state: PredictiveBackGestureState,
    edgePx: Float,
    scope: CoroutineScope,
    onBack: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val width = size.width.toFloat().coerceAtLeast(1f)
        val fromLeft = down.position.x <= edgePx
        val fromRight = down.position.x >= width - edgePx
        if (!fromLeft && !fromRight) return@awaitEachGesture
        state.swipeEdge = if (fromRight) BackEventCompat.EDGE_RIGHT else BackEventCompat.EDGE_LEFT
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        val slop = viewConfiguration.touchSlop
        var dragging = false
        val pointerId = down.id
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            tracker.addPosition(change.uptimeMillis, change.position)
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            if (!dragging) {
                if (abs(dx) < slop && abs(dy) < slop) {
                    if (!change.pressed) break
                    continue
                }
                val towardBack = if (fromRight) dx < -slop else dx > slop
                val horizontal = abs(dx) > abs(dy) * 1.15f
                if (!towardBack || !horizontal) break
                dragging = true
            }
            change.consume()
            val raw = if (fromRight) -dx else dx
            val fraction = (raw / (width * 0.55f)).coerceIn(0f, 1f)
            if (!change.pressed) {
                val vx = tracker.calculateVelocity().x
                val towardVelocity = if (fromRight) -vx else vx
                val start = fraction
                        val commit = start >= 0.22f || towardVelocity > 900f
                        scope.launch {
                            if (commit) {
                                animate(start, 1f, animationSpec = PredictiveBackCommitSpec) { value, _ ->
                                    state.progress = value
                                }
                                onBack()
                            } else {
                                animate(start, 0f, animationSpec = PredictiveBackSettleSpec) { value, _ ->
                                    state.progress = value
                                }
                            }
                        }
                break
            }
            state.progress = fraction
        }
    }
}
