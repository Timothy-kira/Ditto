package kira.ditto.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.sp
import kira.ditto.R
import kira.ditto.data.AppLanguage
import kira.ditto.data.AppThemeMode
import kira.ditto.platform.LocalReduceMotion
import kira.ditto.platform.rememberPlatformAccessibilityPreferences

private val LightHighContrastAetherColors = lightColorScheme(
    primary = LightHighContrastAetherPalette.primary,
    onPrimary = LightHighContrastAetherPalette.onPrimary,
    primaryContainer = LightHighContrastAetherPalette.primaryContainer,
    onPrimaryContainer = LightHighContrastAetherPalette.onPrimaryContainer,
    secondary = LightHighContrastAetherPalette.secondary,
    onSecondary = LightHighContrastAetherPalette.onSecondary,
    secondaryContainer = LightHighContrastAetherPalette.secondaryContainer,
    onSecondaryContainer = LightHighContrastAetherPalette.onSecondaryContainer,
    background = LightHighContrastAetherPalette.background,
    surface = LightHighContrastAetherPalette.surface,
    surfaceVariant = LightHighContrastAetherPalette.surfaceVariant,
    surfaceContainerHighest = LightHighContrastAetherPalette.surfaceVariant,
    onSurface = LightHighContrastAetherPalette.onSurface,
    onSurfaceVariant = LightHighContrastAetherPalette.onSurfaceVariant,
    tertiary = LightHighContrastAetherPalette.tertiary,
    error = LightHighContrastAetherPalette.error,
    outline = LightHighContrastAetherPalette.outline,
)

private val DarkHighContrastAetherColors = darkColorScheme(
    primary = DarkHighContrastAetherPalette.primary,
    onPrimary = DarkHighContrastAetherPalette.onPrimary,
    primaryContainer = DarkHighContrastAetherPalette.primaryContainer,
    onPrimaryContainer = DarkHighContrastAetherPalette.onPrimaryContainer,
    secondary = DarkHighContrastAetherPalette.secondary,
    onSecondary = DarkHighContrastAetherPalette.onSecondary,
    secondaryContainer = DarkHighContrastAetherPalette.secondaryContainer,
    onSecondaryContainer = DarkHighContrastAetherPalette.onSecondaryContainer,
    background = DarkHighContrastAetherPalette.background,
    surface = DarkHighContrastAetherPalette.surface,
    surfaceVariant = DarkHighContrastAetherPalette.surfaceVariant,
    surfaceContainerHighest = DarkHighContrastAetherPalette.surfaceVariant,
    onSurface = DarkHighContrastAetherPalette.onSurface,
    onSurfaceVariant = DarkHighContrastAetherPalette.onSurfaceVariant,
    tertiary = DarkHighContrastAetherPalette.tertiary,
    error = DarkHighContrastAetherPalette.error,
    outline = DarkHighContrastAetherPalette.outline,
)

private val LightAetherColors = lightColorScheme(
    primary = LightAetherPalette.primary,
    onPrimary = LightAetherPalette.onPrimary,
    primaryContainer = LightAetherPalette.primaryContainer,
    onPrimaryContainer = LightAetherPalette.onPrimaryContainer,
    secondary = LightAetherPalette.secondary,
    onSecondary = LightAetherPalette.onSecondary,
    secondaryContainer = LightAetherPalette.secondaryContainer,
    onSecondaryContainer = LightAetherPalette.onSecondaryContainer,
    background = LightAetherPalette.background,
    surface = LightAetherPalette.surface,
    surfaceVariant = LightAetherPalette.surfaceVariant,
    surfaceContainerHighest = LightAetherPalette.surfaceVariant,
    onSurface = LightAetherPalette.onSurface,
    onSurfaceVariant = LightAetherPalette.onSurfaceVariant,
    tertiary = LightAetherPalette.tertiary,
    error = LightAetherPalette.error,
    outline = LightAetherPalette.outline,
)

private val DarkAetherColors = darkColorScheme(
    primary = DarkAetherPalette.primary,
    onPrimary = DarkAetherPalette.onPrimary,
    primaryContainer = DarkAetherPalette.primaryContainer,
    onPrimaryContainer = DarkAetherPalette.onPrimaryContainer,
    secondary = DarkAetherPalette.secondary,
    onSecondary = DarkAetherPalette.onSecondary,
    secondaryContainer = DarkAetherPalette.secondaryContainer,
    onSecondaryContainer = DarkAetherPalette.onSecondaryContainer,
    background = DarkAetherPalette.background,
    surface = DarkAetherPalette.surface,
    surfaceVariant = DarkAetherPalette.surfaceVariant,
    surfaceContainerHighest = DarkAetherPalette.surfaceVariant,
    onSurface = DarkAetherPalette.onSurface,
    onSurfaceVariant = DarkAetherPalette.onSurfaceVariant,
    tertiary = DarkAetherPalette.tertiary,
    error = DarkAetherPalette.error,
    outline = DarkAetherPalette.outline,
)

val VazirmatnFontFamily = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold)
)

private fun getAetherTypography(fontFamily: FontFamily) = Typography(
    headlineLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.9).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 29.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 31.sp
    ),
    titleMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 25.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 28.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp
    ),
    bodySmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    )
)

@Composable
fun AetherTheme(
    themeMode: AppThemeMode = AppThemeMode.System,
    language: AppLanguage = AppLanguage.English,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.System -> isSystemInDarkTheme()
        AppThemeMode.Light -> false
        AppThemeMode.Dark -> true
    }
    val accessibility = rememberPlatformAccessibilityPreferences()
    SideEffect {
        updateAetherPalette(darkTheme, accessibility.increasedContrast)
    }
    val currentFontFamily = if (language == AppLanguage.Persian) {
        VazirmatnFontFamily
    } else {
        FontFamily.SansSerif
    }
    val layoutDirection = if (language == AppLanguage.Persian) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    val typography = remember(currentFontFamily) {
        getAetherTypography(currentFontFamily)
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalReduceMotion provides accessibility.reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = when {
                darkTheme && accessibility.increasedContrast -> DarkHighContrastAetherColors
                darkTheme -> DarkAetherColors
                accessibility.increasedContrast -> LightHighContrastAetherColors
                else -> LightAetherColors
            },
            typography = typography,
            content = content
        )
    }
}
