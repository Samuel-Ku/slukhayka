package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1187 — a local import has no site-provided total, and its chapters only
 * carry a duration once something measured them. The card used to take the sum
 * of the KNOWN ones for the book's total, so the total grew as the listener
 * listened and a position inside a later chapter "finished" the book:
 * three 45-second chapters, the first one played, a listener 60 s into the
 * third chapter read as `60 >= 45` → «Завершена».
 *
 * The owner's decision (2026-10-11) is the rule here: while the time is
 * incomplete the card shows the unknown state («Тривалість невідома»), a
 * partial sum never decides completion, and a chapter position is never
 * compared with the time of the whole book. A book is finished only against a
 * total that is actually known — the site's own value, or a sum in which EVERY
 * chapter has been measured.
 */
class LocalBookCompletionTest {

    private fun localBook(id: String = "local") = AudiobookEntity(
        id = id,
        title = "Локальна",
        author = "Автор",
        narrator = "",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        // A local import is exactly the blank sourceUrl the probe used to skip.
        sourceUrl = "",
        totalDurationSeconds = 0L
    )

    private fun chapter(bookId: String, index: Int, duration: Long) = ChapterEntity(
        id = "$bookId-ch$index",
        bookId = bookId,
        chapterIndex = index,
        title = "Розділ ${index + 1}",
        durationSeconds = duration
    )

    private fun progress(
        bookId: String,
        chapterIndex: Int,
        position: Long
    ) = PlaybackProgressEntity(
        editionId = "ed-$bookId",
        bookId = bookId,
        currentChapterIndex = chapterIndex,
        currentPositionSeconds = position,
        lastListenedAt = 1_000L
    )

    // -----------------------------------------------------------------
    // (а) a partially measured local book is NEVER finished by position
    // -----------------------------------------------------------------

    @Test
    fun `a partially measured local book is not finished by a position in a later chapter`() {
        // Only chapter 0 was ever played, so only it has a measured duration.
        val chapters = listOf(chapter("local", 0, 45L), chapter("local", 1, 0L), chapter("local", 2, 0L))
        val progress = progress("local", chapterIndex = 2, position = 60L)

        val built = buildLibraryBooks(listOf(localBook()), listOf(progress), mapOf("local" to chapters)).single()

        assertFalse("60 s into the third chapter must not finish a book of unknown length", built.isCompleted)
        assertTrue("it is a book being listened to, not a finished one", built.isListening)
        assertEquals(
            "a partial sum is not the book's duration — the honest value is unknown",
            0L,
            built.totalDurationSeconds
        )
        assertEquals(0f, built.percent, 0.0001f)
    }

    @Test
    fun `one listened chapter never becomes the whole book's duration`() {
        val chapters = listOf(chapter("local", 0, 45L), chapter("local", 1, 0L), chapter("local", 2, 0L))

        val built = buildLibraryBooks(
            listOf(localBook()),
            listOf(progress("local", chapterIndex = 1, position = 0L)),
            mapOf("local" to chapters)
        ).single()

        assertEquals(0L, built.totalDurationSeconds)
        assertFalse(built.isCompleted)
    }

    // -----------------------------------------------------------------
    // (б) a truly finished local book is finished
    // -----------------------------------------------------------------

    @Test
    fun `a fully measured local book whose position reached the end is finished`() {
        val chapters = listOf(chapter("local", 0, 45L), chapter("local", 1, 45L), chapter("local", 2, 45L))
        // Playback wrote the last chapter's position at its own measured end.
        val progress = progress("local", chapterIndex = 2, position = 45L)

        val built = buildLibraryBooks(listOf(localBook()), listOf(progress), mapOf("local" to chapters)).single()

        assertEquals("every chapter is measured — the sum IS the book", 135L, built.totalDurationSeconds)
        assertTrue("135 of 135 seconds — the book is finished", built.isCompleted)
        assertFalse(built.isListening)
    }

    @Test
    fun `a fully measured local book with a position before the end is not finished`() {
        val chapters = listOf(chapter("local", 0, 45L), chapter("local", 1, 45L), chapter("local", 2, 45L))

        val built = buildLibraryBooks(
            listOf(localBook()),
            listOf(progress("local", chapterIndex = 2, position = 44L)),
            mapOf("local" to chapters)
        ).single()

        assertEquals(135L, built.totalDurationSeconds)
        assertFalse(built.isCompleted)
    }

    // -----------------------------------------------------------------
    // The streamed book is untouched: its site total stays authoritative
    // -----------------------------------------------------------------

    @Test
    fun `a streamed book keeps its site-provided total with unknown chapters`() {
        val streamed = localBook("online").copy(sourceUrl = "https://4read.org/online.html", totalDurationSeconds = 60_071L)
        val chapters = listOf(chapter("online", 0, 1205L), chapter("online", 1, 0L), chapter("online", 2, 0L))

        val built = buildLibraryBooks(
            listOf(streamed),
            listOf(progress("online", chapterIndex = 1, position = 452L)),
            mapOf("online" to chapters)
        ).single()

        assertEquals(60_071L, built.totalDurationSeconds)
        assertFalse(built.isCompleted)
        assertTrue(built.isListening)
    }
}
