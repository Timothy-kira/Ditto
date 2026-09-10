package kira.ditto.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.sp
import kira.ditto.data.AppLanguage
import kira.ditto.ui.theme.DarkAetherPalette
import kira.ditto.ui.theme.DarkHighContrastAetherPalette
import kira.ditto.ui.theme.LightAetherPalette
import kira.ditto.ui.theme.LightHighContrastAetherPalette
import kira.ditto.ui.theme.updateAetherPalette
import kira.ditto.data.AppThemeMode
import kira.ditto.platform.LocalReduceMotion
import kira.ditto.platform.rememberPlatformAccessibilityPreferences

private fun aetherLightColors(palette: kira.ditto.ui.theme.AetherPalette) = lightColorScheme(
    primary = palette.primary,
    onPrimary = palette.onPrimary,
    primaryContainer = palette.primaryContainer,
    onPrimaryContainer = palette.onPrimaryContainer,
    secondary = palette.secondary,
    onSecondary = palette.onSecondary,
    secondaryContainer = palette.secondaryContainer,
    onSecondaryContainer = palette.onSecondaryContainer,
    background = palette.background,
    surface = palette.surface,
    surfaceVariant = palette.surfaceVariant,
    surfaceContainerHighest = palette.surfaceVariant,
    onSurface = palette.onSurface,
    onSurfaceVariant = palette.onSurfaceVariant,
    tertiary = palette.tertiary,
    error = palette.error,
    outline = palette.outline,
)

private fun aetherDarkColors(palette: kira.ditto.ui.theme.AetherPalette) = darkColorScheme(
    primary = palette.primary,
    onPrimary = palette.onPrimary,
    primaryContainer = palette.primaryContainer,
    onPrimaryContainer = palette.onPrimaryContainer,
    secondary = palette.secondary,
    onSecondary = palette.onSecondary,
    secondaryContainer = palette.secondaryContainer,
    onSecondaryContainer = palette.onSecondaryContainer,
    background = palette.background,
    surface = palette.surface,
    surfaceVariant = palette.surfaceVariant,
    surfaceContainerHighest = palette.surfaceVariant,
    onSurface = palette.onSurface,
    onSurfaceVariant = palette.onSurfaceVariant,
    tertiary = palette.tertiary,
    error = palette.error,
    outline = palette.outline,
)

private val lightColors = aetherLightColors(LightAetherPalette)
private val lightHighContrastColors = aetherLightColors(LightHighContrastAetherPalette)
private val darkHighContrastColors = aetherDarkColors(DarkHighContrastAetherPalette)

private val darkColors = aetherDarkColors(DarkAetherPalette)

private val typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.9).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 29.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 31.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 25.sp,
    ),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
)

@Composable
internal fun SharedAetherTheme(
    themeMode: AppThemeMode = AppThemeMode.System,
    language: AppLanguage = AppLanguage.English,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.System -> isSystemInDarkTheme()
        AppThemeMode.Light -> false
        AppThemeMode.Dark -> true
    }
    val accessibility = rememberPlatformAccessibilityPreferences()
    SideEffect { updateAetherPalette(darkTheme, accessibility.increasedContrast) }
    val layoutDirection = if (language == AppLanguage.Persian) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalReduceMotion provides accessibility.reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = when {
                darkTheme && accessibility.increasedContrast -> darkHighContrastColors
                darkTheme -> darkColors
                accessibility.increasedContrast -> lightHighContrastColors
                else -> lightColors
            },
            typography = typography,
            content = content,
        )
    }
}
