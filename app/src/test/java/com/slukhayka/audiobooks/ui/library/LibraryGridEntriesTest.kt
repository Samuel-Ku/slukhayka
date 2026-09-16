package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Медіатека grid is data before it is UI (v1.5 review): the screen maps a
 * lazy-grid index back to a book for focus return and for the availability
 * queue, so the shape of that list is worth pinning without a screen.
 */
class LibraryGridEntriesTest {

    private fun book(
        id: String,
        progress: PlaybackProgressEntity?,
        totalSeconds: Long = 36_000L,
        cumulativeSeconds: Long = 0L
    ) = LibraryBook(
        book = AudiobookEntity(
            id = id,
            title = "Книга $id",
            author = "Автор",
            narrator = "Читець",
            description = "Фікстура",
            coverDrawableRes = 0,
            genre = "класика",
            sourceUrl = "https://fixtures.4read.org/$id.html"
        ),
        progress = progress,
        cumulativePositionSeconds = cumulativeSeconds,
        totalDurationSeconds = totalSeconds
    )

    private fun progress(id: String, completed: Boolean = false) = PlaybackProgressEntity(
        editionId = "edition-$id",
        bookId = id,
        currentChapterIndex = 2,
        currentPositionSeconds = 120L,
        lastListenedAt = 1_700_000_000_000L,
        isCompleted = completed
    )

    private val listening = book("listening", progress("listening"), cumulativeSeconds = 9_000L)
    private val alsoListening = book("listening-2", progress("listening-2"), cumulativeSeconds = 3_000L)
    private val unstarted = book("unstarted", progress = null)
    private val completed = book("completed", progress("completed", completed = true), cumulativeSeconds = 36_000L)
    private val all = listOf(listening, unstarted, completed)

    @Test
    fun `every book of the browsing grid appears exactly once`() {
        val entries = libraryGridEntries(
            browsing = true,
            gridMode = false,
            visible = all,
            continueBook = listening,
            denseTitle = "Усі"
        )

        val hero = entries.filterIsInstance<LibraryGridEntry.Continue>().map { it.book }
        val books = entries.filterIsInstance<LibraryGridEntry.BookEntry>().map { it.book }
        val shelved = entries.filterIsInstance<LibraryGridEntry.Shelf>().flatMap { it.books }
        // Exactly once across the hero, the rows and the shelf — the resume card
        // is not a second copy of a row.
        assertEquals(all.toSet(), (hero + books + shelved).toSet())
        assertEquals(all.size, hero.size + books.size + shelved.size)
        assertEquals(listOf(listening), hero)
        assertTrue(books.none { it.book.id == listening.book.id })
    }

    @Test
    fun `the three listening states partition the library`() {
        // [LibraryBook] derives exactly three mutually exclusive states; if a
        // future change breaks that, books would silently vanish from the grid.
        all.forEach { entry ->
            val states = listOf(entry.isListening, entry.isNew, entry.isCompleted).count { it }
            assertEquals("book ${entry.book.id} must be in exactly one state", 1, states)
        }
    }

    @Test
    fun `browsing opens with the resume card and shelves the unstarted`() {
        val entries = libraryGridEntries(
            browsing = true,
            gridMode = false,
            visible = all + alsoListening,
            continueBook = listening,
            denseTitle = "Усі"
        )

        assertTrue(entries.first() is LibraryGridEntry.Continue)
        assertEquals(
            listOf("Слухаю", "Нові", "Завершені"),
            entries.filterIsInstance<LibraryGridEntry.Section>().map { it.title }
        )
        // The hero book is lifted out of «Слухаю»: two in progress, one on the
        // resume card, one left in the section.
        assertEquals(
            listOf(1),
            entries.filterIsInstance<LibraryGridEntry.Section>()
                .filter { it.title == "Слухаю" }
                .map { it.count }
        )
        assertEquals(
            listOf(unstarted),
            entries.filterIsInstance<LibraryGridEntry.Shelf>().single().books
        )
    }

    @Test
    fun `grid mode has no shelf — the unstarted books are tiles in the grid`() {
        val entries = libraryGridEntries(
            browsing = true,
            gridMode = true,
            visible = all,
            continueBook = null,
            denseTitle = "Усі"
        )

        assertTrue(entries.none { it is LibraryGridEntry.Shelf })
        assertTrue(entries.none { it is LibraryGridEntry.Continue })
        assertEquals(all.size, entries.count { it is LibraryGridEntry.BookEntry })
    }

    @Test
    fun `narrowing down leaves nothing but books under one header`() {
        val entries = libraryGridEntries(
            browsing = false,
            gridMode = false,
            visible = all,
            continueBook = listening,
            denseTitle = "Слухаю",
            denseTrailing = "разом 9 год"
        )

        assertEquals(
            LibraryGridEntry.Section("Слухаю", all.size, "разом 9 год"),
            entries.first()
        )
        assertEquals(all.size, entries.count { it is LibraryGridEntry.BookEntry })
        assertTrue(entries.none { it is LibraryGridEntry.Shelf || it is LibraryGridEntry.Continue })
    }

    @Test
    fun `an empty library renders no grid at all`() {
        assertTrue(
            libraryGridEntries(
                browsing = false,
                gridMode = false,
                visible = emptyList(),
                continueBook = null,
                denseTitle = "Усі"
            ).isEmpty()
        )
        assertTrue(
            libraryGridEntries(
                browsing = true,
                gridMode = false,
                visible = emptyList(),
                continueBook = null,
                denseTitle = "Усі"
            ).isEmpty()
        )
    }

    @Test
    fun `the book in progress is found whether it is the hero or a row`() {
        val entries = libraryGridEntries(
            browsing = true,
            gridMode = false,
            // alsoListening stays in the section while `listening` becomes the hero.
            visible = all + alsoListening,
            continueBook = listening,
            denseTitle = "Усі"
        )

        // The hero card holds it…
        assertTrue(entries.any { it is LibraryGridEntry.Continue && it.showsBook(listening.book.id) })
        // …and a plain row answers for itself.
        assertTrue(entries.any { it is LibraryGridEntry.BookEntry && it.showsBook(alsoListening.book.id) })
        // The shelf holds books, but it is chrome: it never claims one.
        assertTrue(entries.filterIsInstance<LibraryGridEntry.Shelf>().none { it.showsBook(unstarted.book.id) })
        // Chrome never claims to be a book.
        assertTrue(entries.filter { it is LibraryGridEntry.Section }.none { it.showsBook(listening.book.id) })
        assertEquals(
            listOf(listening),
            entries.mapNotNull { it.shownBook }.filter { it.book.id == listening.book.id }
        )
    }

    @Test
    fun `grid keys are unique`() {
        val entries = libraryGridEntries(
            browsing = true,
            gridMode = false,
            visible = all,
            continueBook = listening,
            denseTitle = "Усі"
        )
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }
}
