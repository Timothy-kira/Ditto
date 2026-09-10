package kira.ditto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kira.ditto.ui.theme.AetherOnSurface

private val HeaderControlHalo = Color(0x18000000)

@Composable
fun HeaderCircleButton(
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    containerColor: Color = Color.White,
    iconTint: Color = AetherOnSurface,
    showHalo: Boolean = true,
) {
    Box(modifier = modifier.size(size)) {
        if (showHalo) {
            Box(
                modifier = Modifier.matchParentSize()
                    .offset(y = 4.dp)
                    .blur(14.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    .clip(CircleShape)
                    .background(HeaderControlHalo),
            )
        }
        Box(
            modifier = Modifier.matchParentSize()
                .clip(CircleShape)
                .background(if (enabled) containerColor else containerColor.copy(alpha = 0.55f))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                iconPainter != null -> Icon(
                    painter = iconPainter,
                    contentDescription = contentDescription,
                    tint = if (enabled) iconTint else iconTint.copy(alpha = 0.4f),
                    modifier = Modifier.size(iconSize),
                )

                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = if (enabled) iconTint else iconTint.copy(alpha = 0.4f),
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}
