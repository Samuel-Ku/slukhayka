package com.slukhayka.audiobooks.ui

import androidx.compose.ui.graphics.Color
import com.slukhayka.audiobooks.ui.components.genreAccentColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-22 T3 — the genre→accent mapping used by the cover-fallback art. Pure
 * function, so the mapping is pinned without any UI.
 */
class GenreAccentColorTest {

    @Test
    fun `cyberpunk maps to neon violet`() {
        assertEquals(Color(0xFF9C6BFF), genreAccentColor("Cyberpunk"))
    }

    @Test
    fun `science fiction maps to cosmic indigo`() {
        assertEquals(Color(0xFF5C6BC0), genreAccentColor("Фантастика"))
        assertEquals(Color(0xFF5C6BC0), genreAccentColor("Sci-Fi"))
    }

    @Test
    fun `classics use the warm amber brand hue`() {
        assertEquals(Color(0xFFE9A13B), genreAccentColor("Класика"))
    }

    @Test
    fun `matching is case and whitespace insensitive`() {
        assertEquals(Color(0xFF9C6BFF), genreAccentColor("  киБЕРпАнк  "))
        assertEquals(Color(0xFF9C6BFF), genreAccentColor("КІБЕРПАНК"))
    }

    @Test
    fun `unknown genre falls back to null`() {
        assertNull(genreAccentColor("Щось зовсім інше"))
        assertNull(genreAccentColor(null))
        assertNull(genreAccentColor(""))
        assertNull(genreAccentColor("   "))
    }

    @Test
    fun `fourread placeholder genre is not a real genre`() {
        assertNull(genreAccentColor("4read Каталог"))
    }
}
