package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #40 decision 1 — person labels and the person-page navigation state,
 * pure JVM: the author/narrator lines are tappable only when the name is a
 * real one (never the repository's fabricated 4read placeholders), and a
 * tap decodes into the site's `/xfsearch/<kind>/<name>/` shape.
 */
class BookDisplayTest {

    private fun bookWith(author: String = "Реальний Автор", narrator: String = "Реальна Читачка") =
        AudiobookEntity(
            id = "book-display",
            title = "Книга",
            author = author,
            narrator = narrator,
            description = "",
            coverDrawableRes = 0,
            sourceUrl = "https://4read.org/test-book/",
            genre = "",
        )

    // ------------------------------------------------------------------
    // displayAuthor / displayNarrator scrub
    // ------------------------------------------------------------------

    @Test
    fun `real author and narrator pass through`() {
        val book = bookWith()
        assertEquals("Реальний Автор", book.displayAuthor)
        assertEquals("Реальна Читачка", book.displayNarrator)
    }

    @Test
    fun `fabricated 4read placeholder narrator is blanked`() {
        val book = bookWith(narrator = "4read Voice Narrator")
        assertEquals("", book.displayNarrator)
    }

    @Test
    fun `fabricated 4read placeholder author is blanked`() {
        val book = bookWith(author = "Аудиокнига 4read.org")
        assertEquals("", book.displayAuthor)
    }

    // ------------------------------------------------------------------
    // isRealPersonName — the tappable-vs-plain-text decision
    // ------------------------------------------------------------------

    @Test
    fun `real names are navigable`() {
        assertTrue(isRealPersonName("Тарас Шевченко"))
        assertTrue(isRealPersonName("Ім'я"))
    }

    @Test
    fun `blank and fabricated names stay plain text`() {
        assertFalse(isRealPersonName(""))
        assertFalse(isRealPersonName("   "))
        assertFalse(isRealPersonName("4read.org"))
        assertFalse(isRealPersonName("4read Voice Narrator"))
        assertFalse(isRealPersonName("Аудиокнига 4read.org"))
    }

    // ------------------------------------------------------------------
    // bookPersonPath — the navigation state of a person tap
    // ------------------------------------------------------------------

    @Test
    fun `author tap decodes to the avtor xfsearch path`() {
        assertEquals("/xfsearch/avtor/Реальний Автор/", bookPersonPath("avtor", "Реальний Автор"))
    }

    @Test
    fun `narrator tap decodes to the chitaet xfsearch path`() {
        assertEquals("/xfsearch/chitaet/Ім'я/", bookPersonPath("chitaet", "Ім'я"))
    }

    @Test
    fun `names with spaces or apostrophes stay raw in the path`() {
        // The repository URL-encodes on fetch (android.net.Uri.encode), so
        // the assembled path must keep the raw Cyrillic site shape.
        assertEquals("/xfsearch/avtor/Тарас Шевченко/", bookPersonPath("avtor", "Тарас Шевченко"))
    }
}