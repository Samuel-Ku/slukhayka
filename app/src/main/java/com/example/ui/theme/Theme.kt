package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CyberPrimary,
    onPrimary = CyberOnPrimary,
    primaryContainer = CyberSurfaceVariant,
    onPrimaryContainer = CyberTextPrimary,
    secondary = CyberSecondary,
    onSecondary = CyberOnSecondary,
    secondaryContainer = CyberCardBorder,
    onSecondaryContainer = CyberTextPrimary,
    tertiary = CyberAccent,
    background = CyberBg,
    onBackground = CyberTextPrimary,
    surface = CyberSurface,
    onSurface = CyberTextPrimary,
    surfaceVariant = CyberCardBg,
    onSurfaceVariant = CyberTextSecondary
)

@Composable
fun AudiobookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
