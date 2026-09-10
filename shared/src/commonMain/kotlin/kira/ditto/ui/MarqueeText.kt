package kira.ditto.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.material3.Text
import kotlinx.coroutines.delay

/** Pixels the marquee must travel; 0 when the content fits and no scrolling is needed. */
fun marqueeOverflowPx(contentWidthPx: Int, containerWidthPx: Int): Int =
    (contentWidthPx - containerWidthPx).coerceAtLeast(0)

/** One-way scroll duration for [distancePx] at a constant reading speed. */
fun marqueeScrollDurationMillis(distancePx: Int, speedPxPerSecond: Float = 48f): Int =
    if (distancePx <= 0) 0 else (distancePx / speedPxPerSecond * 1000f).toInt().coerceAtLeast(600)

/** Pause at the start/end of each marquee loop so the text stays readable. */
const val MarqueeEdgePauseMillis = 1200L

/**
 * Single-line text that scrolls horizontally (marquee) only when it overflows
 * its container: scrolls to the end, pauses, rewinds to the start, pauses,
 * and loops. When the text fits it renders exactly like a normal ellipsized
 * [Text]. The offset is driven from `graphicsLayer`, so animating does not
 * recompose the surrounding list; each row owns its state, and leaving
 * composition (LazyList recycling) cancels the loop automatically.
 *
 * Content width is measured with an unbounded constraint. Measuring from
 * [androidx.compose.ui.text.TextLayoutResult.size] inside the clipped row
 * reports the container width and never triggers scrolling.
 */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
) {
    var containerWidthPx by remember { mutableIntStateOf(0) }
    val textMeasurer = rememberTextMeasurer()
    val contentWidthPx = remember(text, style) {
        if (text.isEmpty()) {
            0
        } else {
            textMeasurer.measure(
                text = AnnotatedString(text),
                style = style,
                overflow = TextOverflow.Visible,
                softWrap = false,
                maxLines = 1,
                constraints = Constraints(maxWidth = Constraints.Infinity),
            ).size.width
        }
    }
    val overflowPx = marqueeOverflowPx(contentWidthPx, containerWidthPx)
    val offset = remember { Animatable(0f) }

    LaunchedEffect(text, overflowPx) {
        offset.snapTo(0f)
        if (overflowPx <= 0) return@LaunchedEffect
        val scrollDuration = marqueeScrollDurationMillis(overflowPx)
        val rewindDuration = (scrollDuration / 2).coerceAtLeast(300)
        while (true) {
            delay(MarqueeEdgePauseMillis)
            offset.animateTo(overflowPx.toFloat(), tween(scrollDuration, easing = LinearEasing))
            delay(MarqueeEdgePauseMillis)
            offset.animateTo(0f, tween(rewindDuration, easing = LinearEasing))
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { containerWidthPx = it.width }
            .clipToBounds(),
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = if (overflowPx > 0) TextOverflow.Visible else TextOverflow.Ellipsis,
            textAlign = if (overflowPx > 0) null else textAlign,
            modifier = if (overflowPx > 0) {
                Modifier.graphicsLayer { translationX = -offset.value }
            } else {
                Modifier
            },
        )
    }
}
