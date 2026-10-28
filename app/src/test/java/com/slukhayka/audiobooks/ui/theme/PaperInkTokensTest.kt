package com.slukhayka.audiobooks.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The «Папір і голос» token contract.
 *
 * The direction's promise is measurable: every text role clears 4.5:1, every
 * graphic that carries meaning clears 3:1, no role drops below 11 sp, and the
 * component sizes match the prototype that was shown to the user. A palette or
 * size edit that breaks one of these fails here instead of shipping a screen
 * nobody can read.
 *
 * Pairs are checked against the roles in [PaperInkPalette], not against a
 * screenshot, so the test survives renderer and density changes.
 */
class PaperInkTokensTest {

    private val light: ColorScheme = paperInkLightScheme()
    private val dark: ColorScheme = paperInkDarkScheme()

    private fun assertText(scheme: String, name: String, fg: Color, bg: Color) {
        val ratio = ColorContrast.ratio(fg, bg)
        assertTrue(
            "$scheme · $name is ${"%.2f".format(ratio)}:1, needs ${ColorContrast.TEXT_FLOOR}",
            ColorContrast.meetsTextFloor(fg, bg),
        )
    }

    private fun assertGraphic(scheme: String, name: String, fg: Color, bg: Color) {
        val ratio = ColorContrast.ratio(fg, bg)
        assertTrue(
            "$scheme · $name is ${"%.2f".format(ratio)}:1, needs ${ColorContrast.LARGE_TEXT_FLOOR}",
            ratio >= ColorContrast.LARGE_TEXT_FLOOR,
        )
    }

    @Test
    fun `every scheme text pair clears the 4_5 floor`() {
        listOf(light to "light", dark to "dark").forEach { (scheme, name) ->
            assertText(name, "onBackground/background", scheme.onBackground, scheme.background)
            assertText(name, "onSurface/surface", scheme.onSurface, scheme.surface)
            assertText(name, "onSurfaceVariant/surfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant)
            assertText(name, "onSurfaceVariant/surface", scheme.onSurfaceVariant, scheme.surface)
            assertText(name, "onPrimary/primary", scheme.onPrimary, scheme.primary)
            assertText(name, "onPrimaryContainer/primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer)
            assertText(name, "onSecondary/secondary", scheme.onSecondary, scheme.secondary)
            assertText(name, "onSecondaryContainer/secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer)
            assertText(name, "onTertiary/tertiary", scheme.onTertiary, scheme.tertiary)
            assertText(name, "onTertiaryContainer/tertiaryContainer", scheme.onTertiaryContainer, scheme.tertiaryContainer)
            assertText(name, "onError/error", scheme.onError, scheme.error)
            assertText(name, "onErrorContainer/errorContainer", scheme.onErrorContainer, scheme.errorContainer)
            assertText(name, "inverseOnSurface/inverseSurface", scheme.inverseOnSurface, scheme.inverseSurface)
            assertText(name, "inversePrimary/inverseSurface", scheme.inversePrimary, scheme.inverseSurface)
            assertText(name, "onSurface/surfaceContainer", scheme.onSurface, scheme.surfaceContainer)
            assertText(name, "onSurface/surfaceContainerHigh", scheme.onSurface, scheme.surfaceContainerHigh)
        }
    }

    @Test
    fun `graphics that carry meaning clear the 3 to 1 floor`() {
        // The lamp is the progress fill on both the paper and the progress track.
        assertGraphic("light", "lamp/paper", PaperInkPalette.Lamp, PaperInkPalette.Paper)
        assertGraphic("light", "lamp/track", PaperInkPalette.Lamp, PaperInkPalette.Track)
        // A chip/field border is the affordance, not decoration.
        assertGraphic("light", "lineStrong/paper", PaperInkPalette.LineStrong, PaperInkPalette.Paper)
        assertGraphic("light", "lineStrong/page", PaperInkPalette.LineStrong, PaperInkPalette.Page)
        assertGraphic("dark", "lineStrong/night", PaperInkPalette.LineStrongNight, PaperInkPalette.Night)
        // The dark progress fill is the same lamp role on the dark track.
        assertGraphic("dark", "lamp/night", PaperInkPalette.LampNight, PaperInkPalette.Night)
    }

    @Test
    fun `the extra paper roles stay readable`() {
        assertText("light", "dust2/paper", PaperInkPalette.Dust2, PaperInkPalette.Paper)
        assertText("light", "dust2/track", PaperInkPalette.Dust2, PaperInkPalette.Track)
        assertText("light", "lampInk/paper", PaperInkPalette.LampInk, PaperInkPalette.Paper)
        assertText("light", "onLampSoft/lampSoft", PaperInkPalette.OnLampSoft, PaperInkPalette.LampSoft)
        assertText("dark", "onLampSoftNight/lampSoftNight", PaperInkPalette.OnLampSoftNight, PaperInkPalette.LampSoftNight)
        assertText("dark", "dust/nightSurface", PaperInkPalette.DustNight, PaperInkPalette.NightSurface)
    }

    @Test
    fun `the lamp has a TEXT role that clears the floor in both schemes`() {
        // F4 of the 2026-10-28 device audit — `tertiary` is the GRAPHIC lamp
        // (3.80:1 on paper), yet three Library texts used it for 11–12 sp copy.
        // Text reads the darker/lighter twin instead: 6.2:1 on paper, 11.3:1
        // on ink.
        assertEquals(PaperInkPalette.LampInk, lampTextFor(darkTheme = false))
        assertEquals(PaperInkPalette.LampNight, lampTextFor(darkTheme = true))
        assertText("light", "lampText/background", lampTextFor(false), light.background)
        assertText("light", "lampText/paper", lampTextFor(false), PaperInkPalette.Paper)
        assertText("light", "lampText/page", lampTextFor(false), PaperInkPalette.Page)
        assertText("dark", "lampText/night", lampTextFor(true), dark.background)
        assertText("dark", "lampText/nightSurface", lampTextFor(true), PaperInkPalette.NightSurface)
        assertFalse(
            "the graphic lamp must keep failing as text, otherwise the split is pointless",
            ColorContrast.meetsTextFloor(light.tertiary, PaperInkPalette.Paper),
        )
    }

    @Test
    fun `the selected state is a 3 to 1 graphic in both schemes`() {
        // F3 — the selected pill is now filled with `primary` and draws
        // `onPrimary` on it. Both pairs must clear their floors in ink too,
        // where the old tonal container measured 1.49:1.
        listOf(light to "light", dark to "dark").forEach { (scheme, name) ->
            assertGraphic(name, "selected/background", scheme.primary, scheme.background)
            assertText(name, "onSelected/selected", scheme.onPrimary, scheme.primary)
            // The pill also has to read against the UNSELECTED well it sits in
            // (`surfaceVariant`), not only against the page ground — an 11 sp
            // label next to an unselected pill was the F3 defect.
            assertGraphic(name, "selected/unselected", scheme.primary, scheme.surfaceVariant)
        }
    }

    @Test
    fun `the schemes keep the same role hierarchy`() {
        val lightSurface = ColorContrast.relativeLuminance(light.surface)
        val darkSurface = ColorContrast.relativeLuminance(dark.surface)
        assertTrue("dark surface must be darker than the light one", darkSurface < lightSurface)

        val darkBackground = ColorContrast.relativeLuminance(dark.background)
        assertTrue("dark background must be darker than the dark surface", darkBackground < darkSurface)

        // Light «Папір»: the card surface is lighter than the ground, while the
        // wells step darker — paper, not the M3 dark-ladder convention. Dark
        // «Чорнило» is the opposite: elevation is expressed by light.
        assertTrue(
            "the light card surface must be lighter than the light background",
            ColorContrast.relativeLuminance(light.surface) >
                ColorContrast.relativeLuminance(light.background),
        )
        assertTrue(
            "the light containers must be darker than the light background",
            ColorContrast.relativeLuminance(light.surfaceContainerHighest) <
                ColorContrast.relativeLuminance(light.background),
        )
        assertTrue(
            "the dark surface must be lighter than the dark background",
            ColorContrast.relativeLuminance(dark.surface) >
                ColorContrast.relativeLuminance(dark.background),
        )
        assertTrue(
            "the dark containers must be lighter than the dark background",
            ColorContrast.relativeLuminance(dark.surfaceContainerHighest) >
                ColorContrast.relativeLuminance(dark.background),
        )

        assertEquals("both schemes define a primary", true, light.primary != Color.Unspecified && dark.primary != Color.Unspecified)
        assertEquals("both schemes define a lamp", true, light.tertiary != Color.Unspecified && dark.tertiary != Color.Unspecified)
    }

    @Test
    fun `no type role drops below eleven sp`() {
        val styles = listOf(
            PaperInkType.ScreenTitle,
            PaperInkType.SectionTitle,
            PaperInkType.BookTitleHero,
            PaperInkType.BookTitlePage,
            PaperInkType.BookTitlePlayer,
            PaperInkType.RowTitle,
            PaperInkType.Body,
            PaperInkType.BodyStrong,
            PaperInkType.Meta,
            PaperInkType.Label,
            PaperInkType.Micro,
            PaperInkType.Tab,
            PaperInkType.Timer,
            PaperInkType.TimerXL,
            PaperInkType.StatXL,
            PaperInkType.EmptyTitle,
        )
        styles.forEach { style ->
            assertTrue(
                "type role ${style.fontSize} is below 11 sp",
                style.fontSize.value >= 11f,
            )
        }
    }

    @Test
    fun `the type roles keep the intended relationships`() {
        assertEquals(FontWeight.ExtraBold, PaperInkType.ScreenTitle.fontWeight)
        assertTrue(PaperInkType.ScreenTitle.fontSize.value > PaperInkType.SectionTitle.fontSize.value)
        assertTrue(PaperInkType.SectionTitle.fontSize.value > PaperInkType.RowTitle.fontSize.value)
        assertTrue(PaperInkType.RowTitle.fontSize.value > PaperInkType.Label.fontSize.value)
        assertTrue(
            "Literata carries big book titles only",
            PaperInkType.BookTitlePlayer.fontFamily == PaperInkType.Book,
        )
        assertTrue(
            "UI roles are Onest",
            PaperInkType.Body.fontFamily == PaperInkType.Ui,
        )
        assertEquals("timers use tabular figures", "tnum", PaperInkType.Timer.fontFeatureSettings)
        assertEquals("the big timer uses tabular figures", "tnum", PaperInkType.TimerXL.fontFeatureSettings)
        assertSame(PaperInkType.Book, PaperInkType.StatXL.fontFamily)
    }

    @Test
    fun `the measured component sizes match the prototype`() {
        assertEquals(20.dp, PaperInkDimens.SpacePage)
        assertEquals(32.dp, PaperInkDimens.SpaceSection)
        assertEquals(48.dp, PaperInkDimens.TouchTarget)
        assertEquals(193.dp, PaperInkDimens.HeroHeight)
        assertEquals(112.dp, PaperInkDimens.BookRowHeight)
        assertEquals(64.dp, PaperInkDimens.MiniPlayerHeight)
        assertEquals(86.dp, PaperInkDimens.NavigationBarHeight)
        assertEquals(272.dp, PaperInkDimens.CoverPlayerWidth)
        assertEquals(408.dp, PaperInkDimens.CoverPlayerHeight)
        assertEquals(44.dp, PaperInkDimens.PlayerSeekHeight)
        // F8 (аудит 2026-10-28): прототип дає play-кнопці 80×72 і глиф 28–30.
        assertEquals(80.dp, PaperInkDimens.PlayerPlayButtonWidth)
        assertEquals(72.dp, PaperInkDimens.PlayerPlayButton)
        assertEquals(30.dp, PaperInkDimens.PlayerPlayGlyph)
        assertEquals(30.dp, PaperInkDimens.PlayerTransportGlyph)
        assertEquals(8.dp, PaperInkDimens.RadiusMicro)
        assertEquals(28.dp, PaperInkDimens.RadiusXl)
        assertEquals(999.dp, PaperInkDimens.RadiusFull)
    }

    @Test
    fun `every spacing token sits on the four dp grid`() {
        val spacing = listOf(
            PaperInkDimens.SpaceXs,
            PaperInkDimens.SpaceSm,
            PaperInkDimens.SpaceMd,
            PaperInkDimens.SpaceLg,
            PaperInkDimens.SpaceXl,
            PaperInkDimens.SpaceSection,
            PaperInkDimens.SpacePage,
        )
        spacing.forEach { value ->
            assertEquals("$value is not on the 4 dp grid", 0f, value.value % 4f, 0.0001f)
        }
    }
}
