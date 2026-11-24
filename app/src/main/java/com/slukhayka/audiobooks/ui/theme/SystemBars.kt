package com.slukhayka.audiobooks.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * What the system bars must look like for ONE resolved app scheme (F2 of the
 * 2026-10-28 device audit).
 *
 * `enableEdgeToEdge()` with no arguments decides "dark or light icons" from the
 * SYSTEM `uiMode`, not from the listener's «Папір»/«Чорнило» choice: with a
 * forced paper app and a dark system the status bar drew near-white icons on
 * paper (≈1.05:1), and the reverse in ink-over-light-system. The bars are part
 * of the app, so they follow the app's own scheme.
 */
internal data class SystemBarAppearance(
    /** True → dark glyphs on a light ground («Папір»); false → light glyphs. */
    val darkIcons: Boolean,
    /** The colour the window paints behind the bars, from the same palette. */
    val ground: Color,
)

/**
 * The bar appearance for a resolved app scheme. The ground is the scheme's own
 * `background`, so the strip above the status bar cannot drift from the body
 * the listener sees (the audit measured legacy paper `#FAF6EE` above new paper
 * `#F1EEE4` — a visible full-width band).
 */
internal fun systemBarAppearanceFor(darkTheme: Boolean): SystemBarAppearance {
    val scheme = if (darkTheme) paperInkDarkScheme() else paperInkLightScheme()
    return SystemBarAppearance(darkIcons = !darkTheme, ground = scheme.background)
}

/**
 * Whether THIS frame's bars are dark-themed.
 *
 * The app-wide choice decides, with one exception the product already made:
 * the full-screen player is always «Чорнило» (decision A), so while it covers
 * the window its frame keeps light icons even when the app underneath is on
 * paper. The pane placement (≥840 dp) does not cover the window, so it obeys
 * the app scheme like the rest of the content beside it.
 */
internal fun systemBarsDarkTheme(
    appDarkTheme: Boolean,
    fullPlayerOverlayVisible: Boolean,
    playerAlwaysInk: Boolean,
): Boolean = appDarkTheme || (fullPlayerOverlayVisible && playerAlwaysInk)

/**
 * Applies [darkTheme] to the system bars of the host window: glyph appearance
 * AND the colour behind the bars.
 *
 * Compose cannot express this as a modifier — it is window state — so the ONE
 * call site (the app root) owns it. [remember] keeps the allocation off the
 * recomposition path; [SideEffect] makes the write happen after every frame
 * that could have changed it.
 */
@Composable
fun PaperInkSystemBars(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val appearance = remember(darkTheme) { systemBarAppearanceFor(darkTheme) }
    // Composition-agnostic memo: the app root recomposes on every playback
    // tick, and re-setting window state then would be pure waste.
    val applied = remember { arrayOfNulls<SystemBarAppearance>(1) }
    SideEffect {
        if (applied[0] == appearance) return@SideEffect
        applied[0] = appearance
        val window = view.context.findActivity()?.window ?: return@SideEffect
        window.setBackgroundDrawable(ColorDrawable(appearance.ground.toArgb()))
        WindowCompat.getInsetsController(window, view).apply {
            // "light bars" is the platform spelling of DARK glyphs.
            isAppearanceLightStatusBars = appearance.darkIcons
            isAppearanceLightNavigationBars = appearance.darkIcons
        }
    }
}

/** The activity that owns [this] context, if any (composition can run in a preview/test host). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
