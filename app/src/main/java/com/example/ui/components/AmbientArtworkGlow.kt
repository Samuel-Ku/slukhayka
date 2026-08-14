package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * OLED & Ambient Glow background component (Spec-20 Issue #95).
 * Renders an atmospheric blurred radial/vertical glow extracted from artwork
 * or theme accent over a deep dark / OLED surface.
 */
@Composable
fun AmbientArtworkBackdrop(
    accentColor: Color?,
    modifier: Modifier = Modifier,
    isOled: Boolean = false,
    alpha: Float = 0.18f,
    content: @Composable () -> Unit
) {
    val bg = if (isOled) Color(0xFF000000) else MaterialTheme.colorScheme.background
    val targetTint = accentColor ?: MaterialTheme.colorScheme.primary
    val animatedTint by animateColorAsState(
        targetValue = targetTint,
        animationSpec = tween(durationMillis = 600),
        label = "ambient_tint"
    )

    val topTint = lerp(bg, animatedTint, alpha)
    val midTint = lerp(bg, animatedTint, alpha * 0.4f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to topTint,
                    0.38f to midTint,
                    0.75f to bg,
                    1f to bg
                )
            )
    ) {
        content()
    }
}

/**
 * Radial glowing halo positioned behind book artwork.
 */
@Composable
fun ArtworkAmbientHalo(
    accentColor: Color?,
    modifier: Modifier = Modifier,
    radiusDp: Dp = 260.dp,
    intensity: Float = 0.35f
) {
    val targetTint = accentColor ?: MaterialTheme.colorScheme.primary
    val animatedTint by animateColorAsState(
        targetValue = targetTint,
        animationSpec = tween(durationMillis = 600),
        label = "halo_tint"
    )

    val density = androidx.compose.ui.platform.LocalDensity.current
    val radiusPx = with(density) { radiusDp.toPx() }

    Box(
        modifier = modifier
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        animatedTint.copy(alpha = intensity),
                        animatedTint.copy(alpha = intensity * 0.4f),
                        Color.Transparent
                    ),
                    radius = radiusPx
                )
            )
    )
}
