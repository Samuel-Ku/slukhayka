package com.slukhayka.audiobooks.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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

/**
 * True inside a [PaperInkTheme] subtree whose scheme is «Чорнило».
 *
 * Material3's [androidx.compose.material3.ColorScheme] has no slot for the
 * direction's extra roles, and guessing the scheme from a background colour
 * would break the moment a block is wrapped in a tonal surface. The theme
 * itself states the answer, so [PaperInkColors] can read it.
 */
val LocalPaperInkDarkTheme = staticCompositionLocalOf { false }

/**
 * «Папір і голос» roles that Material3's ColorScheme has no slot for.
 *
 * Spec §3.1 splits the sound accent in two: `lamp` is the GRAPHIC role
 * (progress, play, rings — 3:1 is enough) and `lamp-ink` is the TEXT role
 * (11–12 sp copy must clear 4.5:1). Using `tertiary` for text silently dragged
 * `#A96A12` onto paper at 3.80:1 (F4 of the 2026-10-28 device audit), so text
 * reads this role instead.
 */
object PaperInkColors {

    /** Amber as TEXT: `LampInk` on paper (6.2:1), `LampNight` on ink (11.3:1). */
    val lampText: Color
        @Composable
        @ReadOnlyComposable
        get() = lampTextFor(LocalPaperInkDarkTheme.current)
}

/** The scheme-independent half of [PaperInkColors.lampText], for tests and callers outside composition. */
internal fun lampTextFor(darkTheme: Boolean): Color =
    if (darkTheme) PaperInkPalette.LampNight else PaperInkPalette.LampInk

/**
 * True when the composition's own ground is the ink page.
 *
 * The paper/ink decision is ONE rule — the background's luminance, not the
 * system theme and not a caller flag — so a block can move between themes
 * without carrying anything with it. This test used to exist as four private
 * copies (the [PaperInkThemedContent] seam, «Досягнення», «Медіатека» and the
 * system-bar call in `MainActivity`); a follow-up to F28 (#1641) keeps the one
 * definition here.
 */
@Composable
fun isPaperInkInkTheme(): Boolean =
    ColorContrast.relativeLuminance(MaterialTheme.colorScheme.background) < 0.5

/**
 * F28 (аудит 2026-10-28, #1625/#1641) — the printed statistic plate, used by
 * «Мій рік у книжках»: **ink in BOTH app themes**, the same language as the
 * achievements hero panel, because a number that anchors a screen reads as
 * print rather than as another tinted card. On paper it is the flat
 * [PaperInkPalette.NightSurface] plate; in the ink theme it steps up to the
 * raised [PaperInkPalette.NightHigh] so it does not disappear into the page.
 *
 * The text roles that ride it are measured in `PaperInkTokensTest`
 * (`Chalk` 14.00:1/12.53:1, `LampNight` 10.37:1/9.28:1,
 * `DustNight` 7.06:1/6.32:1 on the paper/ink plates).
 */
fun libraryYearPlateColor(inkTheme: Boolean): Color =
    if (inkTheme) PaperInkPalette.NightHigh else PaperInkPalette.NightSurface

@Composable
fun PaperInkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(darkTheme) {
        if (darkTheme) paperInkDarkScheme() else paperInkLightScheme()
    }
    CompositionLocalProvider(
        LocalPaperInkDesign provides true,
        LocalPaperInkDarkTheme provides darkTheme,
    ) {
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
    val darkTheme = isPaperInkInkTheme()
    PaperInkTheme(darkTheme = darkTheme, content = content)
}
