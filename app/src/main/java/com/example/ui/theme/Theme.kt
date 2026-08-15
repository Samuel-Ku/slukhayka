package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// MD3 tonal containers (surfaceContainerLow…Highest) — the tonal elevation
// ladder between `surface` and `outline`. Consumed by the nav bar
// (surfaceContainer), modal sheets (surfaceContainerLow), dialogs
// (surfaceContainerHigh), input fills / progress tracks
// (surfaceContainerHighest) and chips / icon wells (surfaceContainerHigh).
// Values step from the palette's surface → card.
private val DarkColorScheme = darkColorScheme(
    primary = AppAccentDark,
    onPrimary = AppOnAccentDark,
    primaryContainer = AppAccentContainerDark,
    onPrimaryContainer = AppOnAccentContainerDark,
    secondary = AppTextMutedDark,
    onSecondary = AppOnAccentDark,
    secondaryContainer = AppCardDark,
    onSecondaryContainer = AppTextPrimaryDark,
    tertiary = AppAccentDark,
    background = AppBgDark,
    onBackground = AppTextPrimaryDark,
    surface = AppSurfaceDark,
    onSurface = AppTextPrimaryDark,
    surfaceVariant = AppCardDark,
    onSurfaceVariant = AppTextMutedDark,
    surfaceDim = AppBgDark,
    surfaceBright = Color(0xFF2F3542),
    surfaceContainerLowest = Color(0xFF0E1015),
    surfaceContainerLow = Color(0xFF1C2029),
    surfaceContainer = AppCardDark,
    surfaceContainerHigh = Color(0xFF252A34),
    surfaceContainerHighest = Color(0xFF2B313D),
    inverseSurface = AppTextPrimaryDark,
    inverseOnSurface = AppBgDark,
    // inversePrimary sits ON inverseSurface (light in dark mode): a dark amber
    // keeps WCAG contrast on the light inverse surface.
    inversePrimary = AppAccentLight,
    surfaceTint = AppAccentDark,
    outline = AppBorderDark,
    outlineVariant = AppBorderDark
)

private val LightColorScheme = lightColorScheme(
    primary = AppAccentLight,
    onPrimary = AppOnAccentLight,
    primaryContainer = AppAccentContainerLight,
    onPrimaryContainer = AppOnAccentContainerLight,
    secondary = AppTextMutedLight,
    onSecondary = AppOnAccentLight,
    secondaryContainer = AppCardLight,
    onSecondaryContainer = AppTextPrimaryLight,
    tertiary = AppAccentLight,
    background = AppBgLight,
    onBackground = AppTextPrimaryLight,
    surface = AppSurfaceLight,
    onSurface = AppTextPrimaryLight,
    surfaceVariant = AppCardLight,
    onSurfaceVariant = AppTextMutedLight,
    // Light ladder: containers step DARKER from surface; surfaceDim is the
    // darkest of all (not lighter than the containers).
    surfaceDim = Color(0xFFF0E9DB),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFCF9F3),
    surfaceContainerLow = Color(0xFFF7F2E9),
    surfaceContainer = AppCardLight,
    surfaceContainerHigh = Color(0xFFEDE5D4),
    surfaceContainerHighest = Color(0xFFE6DCC8),
    inverseSurface = AppTextPrimaryLight,
    inverseOnSurface = AppBgLight,
    // inversePrimary sits ON inverseSurface (dark in light mode): a light amber
    // keeps WCAG contrast on the dark inverse surface.
    inversePrimary = AppOnAccentContainerDark,
    surfaceTint = AppAccentLight,
    outline = AppBorderLight,
    outlineVariant = AppBorderLight
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(AppDimens.RadiusInner),
    small = RoundedCornerShape(AppDimens.RadiusInner),
    medium = RoundedCornerShape(AppDimens.RadiusCard),
    large = RoundedCornerShape(AppDimens.RadiusHero),
    extraLarge = RoundedCornerShape(AppDimens.RadiusHero)
)

/**
 * The app's single design system (wayfinder #23). Dark (graphite-navy) is the
 * primary theme; the warm "paper" light scheme is the secondary. The active
 * scheme follows the system setting by default (themes ticket #37) — pass
 * [darkTheme] explicitly to force either scheme (the snapshot suites do).
 */
@Composable
fun AudiobookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
