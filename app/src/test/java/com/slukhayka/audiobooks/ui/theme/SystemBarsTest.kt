package com.slukhayka.audiobooks.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2 of the 2026-10-28 device audit — the system bars belong to the CHOSEN
 * theme, not to the system one.
 *
 * Contract here: which scheme the bars describe (glyph polarity + ground) for
 * every input the app root can have, including the product's always-ink
 * exception for the full-screen player.
 */
class SystemBarsTest {

    @Test
    fun `a paper app asks for dark glyphs on paper ground`() {
        val appearance = systemBarAppearanceFor(darkTheme = false)
        assertTrue("«Папір» must draw dark glyphs", appearance.darkIcons)
        assertEquals(paperInkLightScheme().background, appearance.ground)
    }

    @Test
    fun `an ink app asks for light glyphs on ink ground`() {
        val appearance = systemBarAppearanceFor(darkTheme = true)
        assertFalse("«Чорнило» must draw light glyphs", appearance.darkIcons)
        assertEquals(paperInkDarkScheme().background, appearance.ground)
    }

    /**
     * The audit measured the bug in pixels: near-white glyphs on paper were
     * ≈1.05:1. The scheme's own ground is the number the platform draws on, so
     * the polarity must be the one that passes 4.5:1 and the opposite must fail
     * it — otherwise the "fix" would be a coin flip.
     */
    @Test
    fun `the glyph polarity is the one that keeps the bars legible`() {
        val paper = systemBarAppearanceFor(darkTheme = false)
        val ink = systemBarAppearanceFor(darkTheme = true)
        assertTrue(
            "dark glyphs on paper must pass the text floor",
            ColorContrast.meetsTextFloor(Color.Black, paper.ground),
        )
        assertFalse(
            "white glyphs on paper are the audited bug, they must fail the floor",
            ColorContrast.meetsTextFloor(Color.White, paper.ground),
        )
        assertTrue(
            "light glyphs on ink must pass the text floor",
            ColorContrast.meetsTextFloor(Color.White, ink.ground),
        )
        assertFalse(
            "dark glyphs on ink are the inverse bug, they must fail the floor",
            ColorContrast.meetsTextFloor(Color.Black, ink.ground),
        )
    }

    @Test
    fun `the app-wide choice drives the bars when the player is not covering the window`() {
        assertFalse(systemBarsDarkTheme(appDarkTheme = false, fullPlayerOverlayVisible = false, playerAlwaysInk = true))
        assertTrue(systemBarsDarkTheme(appDarkTheme = true, fullPlayerOverlayVisible = false, playerAlwaysInk = false))
    }

    @Test
    fun `the full-screen always-ink player keeps light glyphs over a paper app`() {
        assertTrue(systemBarsDarkTheme(appDarkTheme = false, fullPlayerOverlayVisible = true, playerAlwaysInk = true))
    }

    @Test
    fun `a player that follows the app keeps the app polarity`() {
        assertFalse(systemBarsDarkTheme(appDarkTheme = false, fullPlayerOverlayVisible = true, playerAlwaysInk = false))
        assertTrue(systemBarsDarkTheme(appDarkTheme = true, fullPlayerOverlayVisible = true, playerAlwaysInk = false))
    }
}
