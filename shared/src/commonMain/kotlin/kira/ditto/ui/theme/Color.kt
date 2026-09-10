package kira.ditto.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

data class AetherPalette(
    val background: Color,
    val backgroundGradientTop: Color,
    val settingsBackground: Color,
    val sidebarBackground: Color,
    val sidebarControl: Color,
    val settingsIcon: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHigher: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val outlineSoft: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val error: Color,
    val messageBubble: Color,
    val scrim: Color,
)

val LightAetherPalette = AetherPalette(
    background = Color(0xFFFFFFFF),
    backgroundGradientTop = Color(0xFFFFFFFF),
    settingsBackground = Color(0xFFF2F2F7),
    sidebarBackground = Color(0xFFF9F9F9),
    sidebarControl = Color(0xFFF3F3F2),
    settingsIcon = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFF3F3F2),
    surfaceHigher = Color(0xFFECECEC),
    surfaceVariant = Color(0xFFE5E5E5),
    outline = Color(0xFFD9D9D9),
    outlineSoft = Color(0xFFE7E7E7),
    onSurface = Color(0xFF202123),
    onSurfaceVariant = Color(0xFF6B6B6B),
    primary = Color(0xFFAD7BF9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF1E5FF),
    onPrimaryContainer = Color(0xFF4D2F8E),
    secondary = Color(0xFF4A7B6B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDF1E8),
    onSecondaryContainer = Color(0xFF1E4A3B),
    tertiary = Color(0xFF9A7DF8),
    error = Color(0xFFB43E3E),
    messageBubble = Color(0xFFF0E3FF),
    scrim = Color(0x22000000),
)

val DarkAetherPalette = AetherPalette(
    background = Color(0xFF151619),
    backgroundGradientTop = Color(0xFF1B1D22),
    settingsBackground = Color(0xFF151619),
    sidebarBackground = Color(0xFF1C1F23),
    sidebarControl = Color(0xFF24282D),
    settingsIcon = Color(0xFFF3F1EC),
    surface = Color(0xFF1C1F23),
    surfaceHigh = Color(0xFF24282D),
    surfaceHigher = Color(0xFF2C3036),
    surfaceVariant = Color(0xFF343941),
    outline = Color(0xFF4A5059),
    outlineSoft = Color(0xFF3D424A),
    onSurface = Color(0xFFF3F1EC),
    onSurfaceVariant = Color(0xFFB9B4AA),
    primary = Color(0xFFC0AEFF),
    onPrimary = Color(0xFF251448),
    primaryContainer = Color(0xFF3A275F),
    onPrimaryContainer = Color(0xFFF0E9FF),
    secondary = Color(0xFF89C8AF),
    onSecondary = Color(0xFF143126),
    secondaryContainer = Color(0xFF24483A),
    onSecondaryContainer = Color(0xFFDDF6EA),
    tertiary = Color(0xFFD1C2FF),
    error = Color(0xFFFF8E8E),
    messageBubble = Color(0xFF32264A),
    scrim = Color(0x66000000),
)

val LightHighContrastAetherPalette = LightAetherPalette.copy(
    outline = Color(0xFFA39E95),
    outlineSoft = Color(0xFFBBB6AC),
    onSurface = Color(0xFF111214),
    onSurfaceVariant = Color(0xFF514D46),
    primary = Color(0xFF6545D6),
    primaryContainer = Color(0xFFE7D5FF),
    onPrimaryContainer = Color(0xFF35186F),
    error = Color(0xFFB43E3E),
)

val DarkHighContrastAetherPalette = DarkAetherPalette.copy(
    outline = Color(0xFF777F89),
    outlineSoft = Color(0xFF656C75),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFD2CCC1),
    primary = Color(0xFFD5C8FF),
    primaryContainer = Color(0xFF4C3378),
    onPrimaryContainer = Color(0xFFFFFFFF),
    error = Color(0xFFFFB0B0),
)

private var currentPalette by mutableStateOf(LightAetherPalette)

fun updateAetherPalette(darkTheme: Boolean, increasedContrast: Boolean = false) {
    val palette = when {
        darkTheme && increasedContrast -> DarkHighContrastAetherPalette
        darkTheme -> DarkAetherPalette
        increasedContrast -> LightHighContrastAetherPalette
        else -> LightAetherPalette
    }
    if (currentPalette != palette) {
        currentPalette = palette
    }
}

val AetherBackground: Color
    get() = currentPalette.background

val AetherBackgroundGradientTop: Color
    get() = currentPalette.backgroundGradientTop

val AetherSettingsBackground: Color
    get() = currentPalette.settingsBackground

val AetherSidebarBackground: Color
    get() = currentPalette.sidebarBackground

val AetherSidebarControl: Color
    get() = currentPalette.sidebarControl

val AetherSettingsIcon: Color
    get() = currentPalette.settingsIcon

val AlpineIconBlue = Color(0xFF2196F3)
val AlpineReadyGreen = Color(0xFF22C55E)

private val AetherPalette.isDark: Boolean
    get() = (background.red + background.green + background.blue) / 3f < 0.5f

val AetherIsDark: Boolean
    get() = currentPalette.isDark

/** Composer directive chips (Agent Mode / Swarm / Goal). Light-blue in light theme. */
val AetherComposerChipBackground: Color
    get() = if (currentPalette.isDark) Color(0xFF243A52) else Color(0xFFE8F1FF)

val AetherComposerChipForeground: Color
    get() = if (currentPalette.isDark) Color(0xFF90CAF9) else AlpineIconBlue

val AetherSurface: Color
    get() = currentPalette.surface

val AetherSurfaceHigh: Color
    get() = currentPalette.surfaceHigh

val AetherSurfaceHigher: Color
    get() = currentPalette.surfaceHigher

val AetherSurfaceVariant: Color
    get() = currentPalette.surfaceVariant

val AetherOutline: Color
    get() = currentPalette.outline

val AetherOutlineSoft: Color
    get() = currentPalette.outlineSoft

val AetherOnSurface: Color
    get() = currentPalette.onSurface

val AetherOnSurfaceVariant: Color
    get() = currentPalette.onSurfaceVariant

val AetherPrimary: Color
    get() = currentPalette.primary

val AetherOnPrimary: Color
    get() = currentPalette.onPrimary

val AetherPrimaryContainer: Color
    get() = currentPalette.primaryContainer

val AetherOnPrimaryContainer: Color
    get() = currentPalette.onPrimaryContainer

val AetherSecondary: Color
    get() = currentPalette.secondary

val AetherOnSecondary: Color
    get() = currentPalette.onSecondary

val AetherSecondaryContainer: Color
    get() = currentPalette.secondaryContainer

val AetherOnSecondaryContainer: Color
    get() = currentPalette.onSecondaryContainer

val AetherTertiary: Color
    get() = currentPalette.tertiary

val AetherError: Color
    get() = currentPalette.error

val AetherMessageBubble: Color
    get() = currentPalette.messageBubble

val AetherScrim: Color
    get() = currentPalette.scrim
