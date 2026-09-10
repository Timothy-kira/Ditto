package kira.ditto.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import kira.ditto.platform.LocalReduceMotion
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant

/**
 * Traveling highlight across status labels such as "思考中" / "设置工作环境中".
 * The highlight is a text brush so layout stays stable; only the glyph color
 * changes as the band slides.
 */
@Composable
fun ShimmerStatusText(
    text: String,
    modifier: Modifier = Modifier,
    travelDurationMillis: Int = 1800,
    pauseDurationMillis: Int = 1000,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    if (LocalReduceMotion.current) {
        Text(
            text = text,
            modifier = modifier,
            style = style,
            color = AetherOnSurfaceVariant,
            maxLines = maxLines,
            overflow = overflow,
        )
        return
    }
    val totalDurationMillis = travelDurationMillis + pauseDurationMillis
    val travelDistance = 420f
    val shimmerOffset by rememberInfiniteTransition(label = "status_shimmer").animateFloat(
        initialValue = -travelDistance,
        targetValue = travelDistance,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = totalDurationMillis
                travelDistance at travelDurationMillis using LinearEasing
                travelDistance at totalDurationMillis
            },
        ),
        label = "status_shimmer_offset",
    )
    val brush = Brush.linearGradient(
        colors = listOf(
            AetherOnSurfaceVariant.copy(alpha = 0.42f),
            AetherOnSurface.copy(alpha = 0.96f),
            AetherOnSurfaceVariant.copy(alpha = 0.42f),
        ),
        start = Offset(shimmerOffset - 180f, 0f),
        end = Offset(shimmerOffset + 180f, 0f),
    )
    Text(
        text = text,
        modifier = modifier,
        style = style.copy(brush = brush),
        maxLines = maxLines,
        overflow = overflow,
    )
}
