package kira.ditto.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kira.ditto.platform.LocalReduceMotion
import kira.ditto.shared.resources.Res
import kira.ditto.shared.resources.aether_mark
import kira.ditto.shared.resources.onboarding_aether_icon
import kira.ditto.ui.theme.AetherBackground
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.TimeSource

internal const val CharacterFadeDurationMillis = 520
internal const val CharacterFadeStaggerMillis = 22

// A burst of streamed text is scheduled within this window so the animation
// never lags behind fast providers; steady token streams keep the full stagger.
private const val CharacterFadeMaxScheduleAheadMillis = 360L

data class CharacterFadeMask(
    val opaqueCount: Int,
    val alphas: FloatArray,
) {
    val fadeStart: Int get() = opaqueCount
    val fadeEndExclusive: Int get() = opaqueCount + alphas.size

    fun alphaAt(sourceIndex: Int): Float {
        if (sourceIndex < opaqueCount) return 1f
        return alphas.getOrElse(sourceIndex - opaqueCount) { 1f }
    }

    fun isFading(): Boolean = alphas.isNotEmpty() && alphas.any { it < 0.995f }
}

/**
 * Continuous per-character timeline. Each streamed character keeps its own
 * appearance time, so arriving chunks never restart or "pop" the characters
 * that are still mid-fade (the previous implementation restarted a single
 * Animatable on every chunk, snapping half-faded characters to full opacity).
 */
private class CharacterFadeTimeline {
    private val origin = TimeSource.Monotonic.markNow()
    private var trackedText: String = ""
    private val startTimes = ArrayDeque<Long>()
    private var nextSlot = 0L
    var opaqueCount: Int = 0
        private set

    fun nowMillis(): Long = origin.elapsedNow().inWholeMilliseconds

    fun snapToEnd(text: String) {
        trackedText = text
        opaqueCount = text.length
        startTimes.clear()
        nextSlot = 0L
    }

    fun advance(text: String, staggerMillis: Int) {
        if (text == trackedText) return
        if (!text.startsWith(trackedText)) {
            trackedText = ""
            opaqueCount = 0
            startTimes.clear()
            nextSlot = 0L
        }
        val incoming = text.length - trackedText.length
        if (incoming > 0) {
            val now = nowMillis()
            val horizon = now + CharacterFadeMaxScheduleAheadMillis
            var slot = maxOf(nextSlot, now).coerceAtMost(horizon)
            val step = if (incoming > 1) {
                minOf(staggerMillis.toLong(), ((horizon - slot) / (incoming - 1)).coerceAtLeast(0L))
            } else {
                staggerMillis.toLong()
            }
            repeat(incoming) {
                startTimes.addLast(slot)
                slot += step
            }
            nextSlot = (startTimes.lastOrNull() ?: now) + staggerMillis
        }
        trackedText = text
    }

    fun compact(now: Long, fadeMillis: Int) {
        while (startTimes.isNotEmpty() && now - startTimes.first() >= fadeMillis) {
            startTimes.removeFirst()
            opaqueCount++
        }
    }

    fun isFinished(fadeMillis: Int): Boolean {
        val last = startTimes.lastOrNull() ?: return true
        return nowMillis() - last >= fadeMillis
    }

    fun pendingAlphas(now: Long, fadeMillis: Int): FloatArray =
        FloatArray(startTimes.size) { index ->
            val raw = ((now - startTimes[index]).toFloat() / fadeMillis).coerceIn(0f, 1f)
            CharacterFadeEasing.transform(raw)
        }
}

private val EmptyFadeAlphas = FloatArray(0)

@Composable
fun rememberCharacterFadeMask(
    text: String,
    fadeMillis: Int = CharacterFadeDurationMillis,
    staggerMillis: Int = CharacterFadeStaggerMillis,
): CharacterFadeMask {
    val reduceMotion = LocalReduceMotion.current
    val timeline = remember { CharacterFadeTimeline() }
    var frameTick by remember { mutableLongStateOf(0L) }

    if (reduceMotion || text.isEmpty()) {
        timeline.snapToEnd(text)
        return CharacterFadeMask(text.length, EmptyFadeAlphas)
    }

    timeline.advance(text, staggerMillis)

    LaunchedEffect(text) {
        while (!timeline.isFinished(fadeMillis)) {
            withFrameMillis { frameTick = it }
        }
        frameTick++
    }

    // Reading frameTick subscribes this scope to one recomposition per frame
    // while characters are still fading.
    @Suppress("UNUSED_EXPRESSION")
    frameTick

    val now = timeline.nowMillis()
    timeline.compact(now, fadeMillis)
    val alphas = timeline.pendingAlphas(now, fadeMillis)
    if (alphas.isEmpty()) {
        return CharacterFadeMask(text.length, EmptyFadeAlphas)
    }
    return CharacterFadeMask(timeline.opaqueCount, alphas)
}

@Composable
fun CharacterFadeInText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = AetherOnSurfaceVariant,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    val mask = rememberCharacterFadeMask(text)
    Text(
        text = buildAnnotatedString {
            appendCharacterFadedSegment(
                text = text,
                sourceOffset = 0,
                mask = mask,
                color = color,
                highlightLatest = false,
            )
        },
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
    )
}

fun AnnotatedString.Builder.appendCharacterFadedSegment(
    text: String,
    sourceOffset: Int,
    mask: CharacterFadeMask?,
    color: Color,
    highlightLatest: Boolean = true,
) {
    if (text.isEmpty()) return
    if (mask == null || !mask.isFading()) {
        append(text)
        return
    }
    val segmentEnd = sourceOffset + text.length
    val fadeStart = mask.fadeStart.coerceIn(sourceOffset, segmentEnd)
    val fadeEnd = mask.fadeEndExclusive.coerceIn(sourceOffset, segmentEnd)
    if (fadeEnd <= fadeStart) {
        append(text)
        return
    }
    val localStart = fadeStart - sourceOffset
    val localEnd = fadeEnd - sourceOffset
    if (localStart > 0) append(text.substring(0, localStart))
    var index = localStart
    while (index < localEnd) {
        val quantized = (mask.alphaAt(sourceOffset + index) * 24f).roundToInt()
        var end = index + 1
        while (end < localEnd && (mask.alphaAt(sourceOffset + end) * 24f).roundToInt() == quantized) {
            end++
        }
        val progress = (quantized / 24f).coerceIn(0f, 1f)
        pushStyle(
            SpanStyle(
                color = characterFadeSpanColor(
                    progress = progress,
                    settled = color,
                    highlightLatest = highlightLatest,
                ),
            ),
        )
        append(text.substring(index, end))
        pop()
        index = end
    }
    if (localEnd < text.length) append(text.substring(localEnd))
}

/**
 * Streaming body keeps glyphs fully opaque so the white highlight rides the
 * newest characters. Progress 0 is the just-arrived tail; 1 is settled body color.
 * Thinking / status text should pass [highlightLatest] = false.
 */
internal fun characterFadeSpanColor(
    progress: Float,
    settled: Color,
    highlightLatest: Boolean,
): Color {
    val clamped = progress.coerceIn(0f, 1f)
    if (!highlightLatest) {
        return settled.copy(alpha = clamped)
    }
    val highlight = if (settled.luminance() >= 0.5f) {
        Color.White
    } else {
        lerp(Color.White, settled, 0.42f)
    }
    return lerp(highlight, settled, clamped)
}

private val CharacterFadeEasing = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)
private val SplashPopEasing = CubicBezierEasing(0.22f, 1.2f, 0.36f, 1f)

@Composable
fun DittoLaunchSplash(
    onFinished: () -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val scale = remember { Animatable(0.62f) }
    val overlayAlpha = remember { Animatable(1f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            onFinished()
            return@LaunchedEffect
        }
        scale.animateTo(1.08f, tween(320, easing = SplashPopEasing))
        scale.animateTo(1f, tween(140))
        delay(80)
        overlayAlpha.animateTo(0f, tween(220))
        onFinished()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlayAlpha.value }
            .background(AetherBackground),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(Res.drawable.aether_mark),
            contentDescription = stringResource(Res.string.onboarding_aether_icon),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(128.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
        )
    }
}
