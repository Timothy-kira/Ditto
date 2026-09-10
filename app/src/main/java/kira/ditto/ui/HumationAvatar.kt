package kira.ditto.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kira.ditto.data.PersonaAvatarSpec
import kira.ditto.data.humation.HumationRenderer
import kira.ditto.ui.theme.AetherSettingsIcon

@Composable
fun HumationAvatarImage(
    spec: PersonaAvatarSpec,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx().coerceAtLeast(1) }
    val bitmap = remember(spec, px) {
        runCatching { HumationRenderer.renderBitmap(context, spec, px) }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(AetherSettingsIcon.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = LucideIcons.Slime,
                contentDescription = contentDescription,
                tint = AetherSettingsIcon,
                modifier = Modifier.size(size * 0.62f),
            )
        }
    }
}

@Composable
fun HumationColorSwatch(
    hex: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val color = remember(hex) { androidColor(hex) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .then(
                if (selected) Modifier.border(2.dp, AetherSettingsIcon, CircleShape)
                else Modifier.border(1.dp, AetherSettingsIcon.copy(alpha = 0.2f), CircleShape),
            )
            .clickable(onClick = onClick),
    )
}

private fun androidColor(hex: String): androidx.compose.ui.graphics.Color {
    val raw = hex.trim().removePrefix("#")
    val value = raw.toLongOrNull(16) ?: return androidx.compose.ui.graphics.Color.White
    val argb = when (raw.length) {
        6 -> 0xFF000000L or value
        8 -> value
        else -> 0xFFFFFFFFL
    }
    return androidx.compose.ui.graphics.Color(argb.toInt())
}
