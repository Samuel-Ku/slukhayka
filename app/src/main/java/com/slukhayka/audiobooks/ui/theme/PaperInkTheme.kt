package com.slukhayka.audiobooks.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The «Папір і голос» theme, applied subtree-first while screens migrate.
 *
 * The direction is rolled out screen by screen (the way the previous design
 * system migrated `ListenScreen` first): a screen that has been rebuilt on the
 * new tokens is wrapped in this theme and the rest of the app keeps the legacy
 * one until its own migration lands. This avoids a "new fonts on old layout"
 * global switch that would make every un-migrated screen look broken instead of
 * unfinished.
 *
 * Two schemes, one hierarchy: [paperInkLightScheme] is the warm «Папір» day
 * theme, [paperInkDarkScheme] is the green-cast «Чорнило» night theme. The
 * player is intended to always use the dark one (product decision A) — screens
 * that need it can pass `darkTheme = true` regardless of the system setting.
 */
/**
 * True inside a [PaperInkTheme] subtree.
 *
 * It lets SHARED legacy components (the canonical section header, chips, rows)
 * upgrade their typography only on migrated screens, without changing every
 * un-migrated screen at once. Read it as a migration seam; delete it when the
 * last screen moves to [PaperInkTheme].
 */
val LocalPaperInkDesign = staticCompositionLocalOf { false }

/**
 * The listener's density choice. A layout preference read by the row/poster
 * primitives; it never shrinks a touch target below 48 dp.
 */
val LocalContentDensity = staticCompositionLocalOf { ContentDensity.COMFORTABLE }

@Composable
fun PaperInkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(darkTheme) {
        if (darkTheme) paperInkDarkScheme() else paperInkLightScheme()
    }
    CompositionLocalProvider(LocalPaperInkDesign provides true) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PaperInkType.typography,
            shapes = PaperInkShapes,
            content = content,
        )
    }
}

/**
 * Migration seam for a single rebuilt block while its screen still uses the
 * legacy theme.
 *
 * The block is wrapped in [PaperInkTheme] and the scheme is chosen from the
 * OUTER background's luminance: the legacy dark scheme is graphite-dark and the
 * paper scheme is light, so the new block follows whichever scheme the screen
 * already shows without asking the caller for a `darkTheme` flag. When the root
 * theme switches to [PaperInkTheme], this adapter becomes a no-op and should be
 * deleted with the last migrated block.
 */
@Composable
fun PaperInkThemedContent(content: @Composable () -> Unit) {
    val darkTheme = ColorContrast.relativeLuminance(MaterialTheme.colorScheme.background) < 0.5
    PaperInkTheme(darkTheme = darkTheme, content = content)
}
