package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

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
