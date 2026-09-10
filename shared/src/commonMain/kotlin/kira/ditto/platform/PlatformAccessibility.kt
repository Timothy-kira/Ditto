package kira.ditto.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

data class PlatformAccessibilityPreferences(
    val reduceMotion: Boolean = false,
    val increasedContrast: Boolean = false,
)

val LocalReduceMotion = compositionLocalOf { false }

@Composable
expect fun rememberPlatformAccessibilityPreferences(): PlatformAccessibilityPreferences
