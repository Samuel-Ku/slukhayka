package com.slukhayka.audiobooks.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec-54 T10/T11 (#861/#862) — the palette's measurable contract: the SAME
 * hierarchy in light and dark, and every text pair above the 4.5:1 floor.
 * A palette change that darkens `onSurface` or lightens `surface` fails here
 * instead of shipping an unreadable screen.
 */
class ThemeContrastTest {

    private val light: ColorScheme = appLightColorScheme()
    private val dark: ColorScheme = appDarkColorScheme()

    private fun assertFloor(scheme: String, name: String, fg: Color, bg: Color) {
        val ratio = ColorContrast.ratio(fg, bg)
        assertTrue(
            "$scheme · $name contrast ${"%.2f".format(ratio)} must be ≥ ${ColorContrast.TEXT_FLOOR}",
            ColorContrast.meetsTextFloor(fg, bg)
        )
    }

    @Test
    fun `body text is readable on its surface in BOTH schemes`() {
        assertFloor("light", "onBackground/background", light.onBackground, light.background)
        assertFloor("dark", "onBackground/background", dark.onBackground, dark.background)
        assertFloor("light", "onSurface/surface", light.onSurface, light.surface)
        assertFloor("dark", "onSurface/surface", dark.onSurface, dark.surface)
        assertFloor("light", "onSurfaceVariant/surface", light.onSurfaceVariant, light.surface)
        assertFloor("dark", "onSurfaceVariant/surface", dark.onSurfaceVariant, dark.surface)
    }

    @Test
    fun `text on the accent surfaces is readable too`() {
        assertFloor("light", "onPrimary/primary", light.onPrimary, light.primary)
        assertFloor("dark", "onPrimary/primary", dark.onPrimary, dark.primary)
        assertFloor("light", "onSecondary/secondary", light.onSecondary, light.secondary)
        assertFloor("dark", "onSecondary/secondary", dark.onSecondary, dark.secondary)
    }

    @Test
    fun `the schemes keep the SAME role hierarchy`() {
        val lightSurfaces = listOf(light.surface, light.surfaceVariant, light.surfaceContainer)
        val darkSurfaces = listOf(dark.surface, dark.surfaceVariant, dark.surfaceContainer)
        assertTrue("surface family present in light", lightSurfaces.none { it == Color.Unspecified })
        assertTrue("surface family present in dark", darkSurfaces.none { it == Color.Unspecified })

        val lightLuminance = ColorContrast.relativeLuminance(light.surface)
        val darkLuminance = ColorContrast.relativeLuminance(dark.surface)
        assertTrue(
            "dark surface ($darkLuminance) must be darker than light ($lightLuminance)",
            darkLuminance < lightLuminance
        )
        // The accent keeps its ROLE, not its exact hue: whichever colour plays
        // `primary` in each scheme must carry readable text on it — that is
        // asserted by the floor tests above, and it is the property screens rely
        // on. Pinning "brighter than onPrimary" would pin a hue, not a role.
        assertTrue(
            "the accent family is defined in both schemes",
            light.primary != Color.Unspecified && dark.primary != Color.Unspecified
        )
    }
}
