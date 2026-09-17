package com.slukhayka.audiobooks.ui.components

import androidx.compose.ui.graphics.Color
import com.slukhayka.audiobooks.ui.theme.ColorContrast
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec-54 T11 (#862) — the typographic placeholder's text is readable on EVERY
 * genre fill it can produce, not just the one someone looked at. The choice
 * mirrors `BookCoverImage`: white while it clears the 4.5:1 floor, else
 * near-black.
 */
class CoverPlaceholderContrastTest {

    private val nearBlack = Color(0xFF101418)

    private fun chosenTextColor(fill: Color): Color =
        if (ColorContrast.meetsTextFloor(Color.White, fill)) Color.White else nearBlack

    private fun assertReadable(genre: String?, fill: Color) {
        val text = chosenTextColor(fill)
        val ratio = ColorContrast.ratio(text, fill)
        assertTrue(
            "placeholder «${genre ?: "unknown"}» ratio ${"%.2f".format(ratio)} must be ≥ ${ColorContrast.TEXT_FLOOR}",
            ColorContrast.meetsTextFloor(text, fill)
        )
    }

    @Test
    fun `every genre accent carries readable typography`() {
        val genres = listOf(
            "кіберпанк", "фантастика", "класика", "детектив", "антиутопія",
            "жахи", "пригоди", "роман", "поезія", "історія", "біографія",
            "науково-популярне", "дитяча", null, "", "невідомий жанр"
        )

        genres.forEach { genre ->
            val fill = genreAccentColor(genre) ?: Color(0xFF6750A4) // the brand primary fallback
            assertReadable(genre, fill)
        }
    }

    @Test
    fun `the fallback accent also carries readable typography`() {
        assertReadable(null, Color(0xFF6750A4))
    }

    @Test
    fun `a known genre still gets its own colour`() {
        assertTrue("cyberpunk keeps its neon violet", genreAccentColor("кіберпанк") != null)
        assertTrue("an unknown genre has no accent of its own", genreAccentColor("щось інше") == null)
    }
}
