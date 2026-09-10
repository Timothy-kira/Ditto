package kira.ditto.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import kira.ditto.platform.LocalReduceMotion

private val LinearWavyWavelength = 24.dp
private val LinearWavyStroke = 4.dp
private val LinearWavyTrackGap = 6.dp
private val LinearWavyAppearEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Material 3 Expressive linear wavy progress indicator.
 * Drawn locally so Android (older Compose BOM) and iOS share the same motion.
 */
@Composable
fun LinearWavyProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color,
    trackColor: Color,
    strokeWidth: Dp = LinearWavyStroke,
    wavelength: Dp = LinearWavyWavelength,
) {
    LinearWavyProgressIndicatorImpl(
        progress = progress,
        indeterminate = false,
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        strokeWidth = strokeWidth,
        wavelength = wavelength,
    )
}

@Composable
fun LinearWavyProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color,
    trackColor: Color,
    strokeWidth: Dp = LinearWavyStroke,
    wavelength: Dp = LinearWavyWavelength,
) {
    LinearWavyProgressIndicatorImpl(
        progress = { 1f },
        indeterminate = true,
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        strokeWidth = strokeWidth,
        wavelength = wavelength,
    )
}

@Composable
private fun LinearWavyProgressIndicatorImpl(
    progress: () -> Float,
    indeterminate: Boolean,
    modifier: Modifier,
    color: Color,
    trackColor: Color,
    strokeWidth: Dp,
    wavelength: Dp,
) {
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val wavelengthPx = with(density) { wavelength.toPx() }
    val strokePx = with(density) { strokeWidth.toPx() }
    val gapPx = with(density) { LinearWavyTrackGap.toPx() }
    val appearFraction = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            appearFraction.snapTo(1f)
        } else {
            appearFraction.animateTo(
                1f,
                animationSpec = tween(durationMillis = 400, easing = LinearWavyAppearEasing),
            )
        }
    }
    val phase by rememberInfiniteTransition(label = "linear_wavy_phase").animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "linear_wavy_phase_value",
    )
    val appear = appearFraction.value
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        val centerY = height / 2f
        val maxAmplitude = ((height - strokePx) / 2f).coerceAtLeast(1f)
        val clampedProgress = progress().coerceIn(0f, 1f)
        val indicatorEnd = if (indeterminate) width else width * clampedProgress
        val amplitude = when {
            reduceMotion -> 0f
            indeterminate -> maxAmplitude * appear
            else -> maxAmplitude * (1f - clampedProgress).coerceIn(0f, 1f) * appear
        }
        val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)
        if (indicatorEnd < width - gapPx) {
            drawLine(
                color = trackColor,
                start = Offset((indicatorEnd + gapPx).coerceAtMost(width), centerY),
                end = Offset(width, centerY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }
        if (indicatorEnd <= strokePx) return@Canvas
        val wavePath = Path()
        val step = 2f
        var x = 0f
        var started = false
        while (x <= indicatorEnd) {
            val y = centerY + amplitude * sin((x / wavelengthPx) * 2f * PI.toFloat() - phase)
            if (!started) {
                wavePath.moveTo(x, y)
                started = true
            } else {
                wavePath.lineTo(x, y)
            }
            x += step
        }
        val endY = centerY + amplitude * sin((indicatorEnd / wavelengthPx) * 2f * PI.toFloat() - phase)
        if (started) {
            wavePath.lineTo(indicatorEnd, endY)
            drawPath(path = wavePath, color = color, style = stroke)
        }
    }
}
