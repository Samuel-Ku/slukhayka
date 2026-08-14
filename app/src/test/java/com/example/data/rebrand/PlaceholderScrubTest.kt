package com.example.data.rebrand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec-20 T3 (#124) — the pure brand-scrub rules, pinned without Robolectric:
 * any brand-bearing author/narrator/genre blanks out, and the branded
 * description templates/URLs are stripped while the meaningful part survives.
 * The startup SQL in [com.example.data.db.AudiobookDao.scrubLegacyPlaceholders]
 * mirrors these rules exactly.
 */
class PlaceholderScrubTest {

    // --- brand detection ----------------------------------------------------

    @Test
    fun `brand token is detected case-insensitively`() {
        assertTrue(PlaceholderScrub.containsBrand("4read.org"))
        assertTrue(PlaceholderScrub.containsBrand("4READ Каталог"))
        assertTrue(PlaceholderScrub.containsBrand("Аудіокнига з каталогу 4read.org."))
        assertFalse(PlaceholderScrub.containsBrand(""))
        assertFalse(PlaceholderScrub.containsBrand("Тарас Шевченко"))
        assertFalse(PlaceholderScrub.containsBrand("з джерела sluhay"))
    }

    // --- author / narrator / genre -----------------------------------------

    @Test
    fun `branded placeholder author blanks out`() {
        assertEquals("", PlaceholderScrub.author("4read.org"))
        assertEquals("", PlaceholderScrub.author("Аудиокнига 4read.org"))
        assertEquals("Тарас Шевченко", PlaceholderScrub.author("Тарас Шевченко"))
        assertEquals("", PlaceholderScrub.author(""))
    }

    @Test
    fun `branded placeholder narrator blanks out`() {
        assertEquals("", PlaceholderScrub.narrator("4read Voice Narrator"))
        assertEquals("", PlaceholderScrub.narrator("4read narrator"))
        assertEquals("Валерій Завалко", PlaceholderScrub.narrator("Валерій Завалко"))
        assertEquals("", PlaceholderScrub.narrator(""))
    }

    @Test
    fun `branded placeholder genre blanks out`() {
        assertEquals("", PlaceholderScrub.genre("4read Каталог"))
        assertEquals("Фантастика", PlaceholderScrub.genre("Фантастика"))
        assertEquals("", PlaceholderScrub.genre(""))
    }

    // --- description --------------------------------------------------------

    @Test
    fun `catalog template prefix and url are stripped`() {
        assertEquals(
            "Джерело: 7589-neostannij-bij.html",
            PlaceholderScrub.description(
                "Аудіокнига з каталогу 4read.org. Джерело: https://4read.org/7589-neostannij-bij.html"
            )
        )
    }

    @Test
    fun `russian portal template prefix and url are stripped`() {
        assertEquals(
            "Джерело: 7589.html",
            PlaceholderScrub.description(
                "Аудиокнига с портала 4read.org. Джерело: https://4read.org/7589.html"
            )
        )
    }

    @Test
    fun `import template prefix and url are stripped`() {
        assertEquals(
            "Джерело: kobzar.html",
            PlaceholderScrub.description("Аудіокнига з джерела 4read. Джерело: https://4read.org/kobzar.html")
        )
    }

    @Test
    fun `search template prefix is stripped`() {
        assertEquals(
            "Темна матерія. Джерело: x.html",
            PlaceholderScrub.description(
                "Книга знайдена на порталі 4read.org за запитом \"Темна матерія\". Джерело: https://4read.org/x.html"
            )
        )
    }

    @Test
    fun `http scheme urls are stripped too`() {
        assertEquals("Джерело: y.html", PlaceholderScrub.description("Джерело: http://4read.org/y.html"))
    }

    @Test
    fun `neutral description passes through and trims`() {
        assertEquals("", PlaceholderScrub.description(""))
        assertEquals("Звичайний опис", PlaceholderScrub.description("  Звичайний опис  "))
        assertEquals(
            "Аудіокнига з джерела sluhay. Джерело: https://sluhay.com/x.html",
            PlaceholderScrub.description("Аудіокнига з джерела sluhay. Джерело: https://sluhay.com/x.html")
        )
    }
}