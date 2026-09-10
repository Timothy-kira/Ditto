package kira.ditto.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kira.ditto.platform.LocalReduceMotion
import kira.ditto.ui.theme.AetherPrimary

@Composable
fun ModelContextUsageEdge(
    usageFraction: Float,
    isCompacting: Boolean,
    modifier: Modifier = Modifier,
    color: Color = AetherPrimary,
) {
    val reduceMotion = LocalReduceMotion.current
    val animatedUsage by animateFloatAsState(
        targetValue = usageFraction.coerceIn(0f, 1f),
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            tween(durationMillis = 520)
        },
        label = "model_context_usage_edge",
    )
    val compactSweep by rememberInfiniteTransition(label = "model_context_compact_sweep")
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_280, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "model_context_compact_offset",
        )

    Spacer(
        modifier = modifier.drawWithCache {
            val stroke = 2.dp.toPx()
            val glow = 3.6.dp.toPx()
            val inset = stroke / 2f
            val stadium = modelSelectorStadiumPath(size, inset)
            val measure = PathMeasure().apply { setPath(stadium, forceClosed = true) }
            val length = measure.length
            val strokeStyle = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val glowStyle = Stroke(width = glow, cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind {
                if (size.width <= stroke * 2f || size.height <= stroke * 2f || length <= 0f) return@onDrawBehind
                val warningMix = ((animatedUsage - 0.72f) / 0.28f).coerceIn(0f, 1f)
                val usageColor = lerp(color, Color(0xFFE8A23A), warningMix)

                if (isCompacting) {
                    drawPath(stadium, usageColor.copy(alpha = 0.18f), style = strokeStyle)
                    val window = length * 0.34f
                    val start = (compactSweep * length) % length
                    drawMeasuredSegment(
                        measure = measure,
                        length = length,
                        start = start,
                        stop = start + window,
                        color = usageColor,
                        glowStyle = glowStyle,
                        strokeStyle = strokeStyle,
                    )
                    val trailStart = (start - window * 0.55f + length) % length
                    drawMeasuredSegment(
                        measure = measure,
                        length = length,
                        start = trailStart,
                        stop = trailStart + window * 0.42f,
                        color = usageColor.copy(alpha = 0.42f),
                        glowStyle = glowStyle,
                        strokeStyle = strokeStyle,
                    )
                    return@onDrawBehind
                }

                if (animatedUsage <= 0.004f) return@onDrawBehind
                drawMeasuredSegment(
                    measure = measure,
                    length = length,
                    start = 0f,
                    stop = length * animatedUsage,
                    color = usageColor,
                    glowStyle = glowStyle,
                    strokeStyle = strokeStyle,
                )
            }
        },
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMeasuredSegment(
    measure: PathMeasure,
    length: Float,
    start: Float,
    stop: Float,
    color: Color,
    glowStyle: Stroke,
    strokeStyle: Stroke,
) {
    val dest = Path()
    val wrappedStart = ((start % length) + length) % length
    val segment = stop - start
    if (segment <= 0f) return
    val wrappedStop = wrappedStart + segment
    if (wrappedStop <= length) {
        measure.getSegment(wrappedStart, wrappedStop, dest, startWithMoveTo = true)
    } else {
        measure.getSegment(wrappedStart, length, dest, startWithMoveTo = true)
        measure.getSegment(0f, wrappedStop - length, dest, startWithMoveTo = false)
    }
    drawPath(dest, color.copy(alpha = 0.26f), style = glowStyle)
    drawPath(dest, color.copy(alpha = 0.94f), style = strokeStyle)
}

internal fun modelSelectorStadiumPath(size: Size, inset: Float): Path {
    val left = inset
    val top = inset
    val right = size.width - inset
    val bottom = size.height - inset
    val radius = (bottom - top) / 2f
    val midX = (left + right) / 2f
    return Path().apply {
        moveTo(midX, top)
        lineTo((right - radius).coerceAtLeast(midX), top)
        arcTo(
            Rect(Offset(right - 2f * radius, top), Size(radius * 2f, radius * 2f)),
            startAngleDegrees = -90f,
            sweepAngleDegrees = 180f,
            forceMoveTo = false,
        )
        lineTo((left + radius).coerceAtMost(midX), bottom)
        arcTo(
            Rect(Offset(left, top), Size(radius * 2f, radius * 2f)),
            startAngleDegrees = 90f,
            sweepAngleDegrees = 180f,
            forceMoveTo = false,
        )
        lineTo(midX, top)
        close()
    }
}
