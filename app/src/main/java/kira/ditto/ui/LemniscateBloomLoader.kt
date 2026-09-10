package kira.ditto.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kira.ditto.ui.theme.AetherPrimary

// Faithful port of "Lemniscate Bloom" from the math-curve-loaders gallery
// (Bernoulli lemniscate with a breathing radius and a fading particle trail):
//   a = 20 + 7s, x = 50 + a·cos t/(1+sin²t), y = 50 + a·sin t·cos t/(1+sin²t)
// inside a 100×100 view box; s pulses over 5000ms, the head orbits in 5600ms.
private const val LemniscateViewBox = 100f
private const val LemniscateA = 20f
private const val LemniscateBoost = 7f
private const val LemniscateOrbitDurationMs = 5600
private const val LemniscatePulseDurationMs = 5000
private const val LemniscateParticleCount = 24
private const val LemniscateTrailSpan = 0.4f
private const val LemniscateTrackSteps = 64
private const val LemniscateTrackStrokeWidth = 4.8f

private fun lemniscatePoint(progress: Float, detailScale: Float): Offset {
    val t = progress * (2f * PI.toFloat())
    val a = LemniscateA + detailScale * LemniscateBoost
    val sinT = sin(t)
    val cosT = cos(t)
    val denom = 1f + sinT * sinT
    return Offset(
        x = LemniscateViewBox / 2f + a * cosT / denom,
        y = LemniscateViewBox / 2f + a * sinT * cosT / denom,
    )
}

private fun normalizeProgress(progress: Float): Float {
    val wrapped = progress % 1f
    return if (wrapped < 0f) wrapped + 1f else wrapped
}

/**
 * "Lemniscate Bloom" loading indicator: a breathing infinity-sign track with a
 * bright particle head and a fading trail, used for running subagent members.
 *
 * [phaseOffset] (0..1) shifts both the orbit and the pulse so several loaders
 * on screen never run in lockstep — pass a stable per-row value.
 */
@Composable
fun LemniscateBloomLoader(
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    color: Color = AetherPrimary,
    phaseOffset: Float = 0f,
) {
    val transition = rememberInfiniteTransition(label = "lemniscate_bloom")
    val orbitProgress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = LemniscateOrbitDurationMs, easing = LinearEasing),
        ),
        label = "lemniscate_orbit",
    )
    val pulseProgress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = LemniscatePulseDurationMs, easing = LinearEasing),
        ),
        label = "lemniscate_pulse",
    )

    Canvas(modifier = modifier.size(size)) {
        val scale = this.size.minDimension / LemniscateViewBox
        val phase = phaseOffset - floor(phaseOffset)
        // Read animation state in the draw phase so only this tiny canvas is
        // invalidated; reading it during composition used to recompose the
        // complete running card every frame.
        val pulseAngle = normalizeProgress(pulseProgress.value + phase) * (2f * PI.toFloat())
        val detailScale = 0.52f + ((sin(pulseAngle + 0.55f) + 1f) / 2f) * 0.48f
        val headProgress = normalizeProgress(orbitProgress.value + phase)

        val track = Path()
        for (step in 0..LemniscateTrackSteps) {
            val point = lemniscatePoint(step.toFloat() / LemniscateTrackSteps, detailScale)
            val x = point.x * scale
            val y = point.y * scale
            if (step == 0) track.moveTo(x, y) else track.lineTo(x, y)
        }
        track.close()
        drawPath(
            path = track,
            color = color,
            alpha = 0.1f,
            style = Stroke(
                width = LemniscateTrackStrokeWidth * scale,
                cap = StrokeCap.Round,
            ),
        )

        for (index in 0 until LemniscateParticleCount) {
            val tailOffset = index.toFloat() / (LemniscateParticleCount - 1)
            val point = lemniscatePoint(
                progress = normalizeProgress(headProgress - tailOffset * LemniscateTrailSpan),
                detailScale = detailScale,
            )
            val fade = (1f - tailOffset).pow(0.56f)
            drawCircle(
                color = color,
                radius = (0.9f + fade * 2.7f) * scale,
                center = Offset(point.x * scale, point.y * scale),
                alpha = 0.04f + fade * 0.96f,
            )
        }
    }
}
