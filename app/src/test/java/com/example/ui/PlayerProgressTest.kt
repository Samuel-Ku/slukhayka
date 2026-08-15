package com.example.ui

import com.example.data.db.BookmarkEntity
import com.example.data.db.ChapterEntity
import com.example.ui.screens.calculatePlayerProgress
import com.example.ui.screens.calculateBookSeekTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerProgressTest {
    private val bookId = "player-progress-book"
    private val chapters = listOf(600L, 660L, 720L).mapIndexed { index, duration ->
        chapter(index, duration)
    }

    private fun chapter(index: Int, duration: Long) = ChapterEntity(
        "chapter-$index", bookId, index, "Розділ ${index + 1}", duration
    )

    @Test
    fun `maps chapter position boundaries and bookmarks onto book timeline`() {
        val bookmark = BookmarkEntity(
            id = 1,
            bookId = bookId,
            chapterIndex = 1,
            chapterTitle = chapters[1].title,
            timestampSeconds = 60,
            note = "",
            createdAt = 1_700_000_000_000L
        )

        val result = calculatePlayerProgress(
            chapters = chapters,
            currentChapterIndex = 1,
            currentPositionMs = 300_000,
            currentChapterDurationMs = chapters[1].durationSeconds * 1_000,
            bookmarks = listOf(bookmark)
        )

        assertEquals(300f / chapters[1].durationSeconds, result.chapterFraction, 0.0001f)
        assertEquals(2, result.chapterMarkers.size)
        assertEquals(
            (chapters[0].durationSeconds + 60f) / result.bookDurationSeconds,
            result.bookmarkMarkers.single(),
            0.0001f
        )
        assertEquals(chapters[0].durationSeconds + 300, result.bookPositionSeconds)
    }

    // ---------------------------------------------------------------------
    // Book timeline honesty (2026-08-08, device bug): only chapters that have
    // been PLAYED carry a real durationSeconds in Room (persistRealDurationIfKnown);
    // the rest are 0. Summing chapter durations then under-reports the book
    // (a 16:41 book showed "37:23"). The site-provided book total
    // (book.totalDurationSeconds) is authoritative, and unknown chapter
    // durations are spread evenly over the remainder so the book position,
    // fraction and seek all stay honest.
    // ---------------------------------------------------------------------

    @Test
    fun `book duration uses the authoritative book total when chapters are unknown`() {
        // Only chapters 0 and 1 were ever played (real durations); the rest 0.
        val sparseChapters = listOf(1205L, 1038L, 0L, 0L, 0L).mapIndexed { index, duration ->
            chapter(index, duration)
        }
        val bookTotal = 60_071L // 16:41:11 from the site

        val result = calculatePlayerProgress(
            chapters = sparseChapters,
            currentChapterIndex = 1,
            currentPositionMs = 452_000,
            currentChapterDurationMs = 1_038_000,
            bookmarks = emptyList(),
            bookTotalDurationSeconds = bookTotal
        )

        assertEquals("book duration must be the authoritative total, not the sum of known chapters",
            bookTotal, result.bookDurationSeconds)
        // Position: ch0 (1205) + position in ch1 (452) — both known chapters.
        assertEquals(1205L + 452L, result.bookPositionSeconds)
        // Fraction must be against the full book, not the shrunken sum.
        assertEquals((1205f + 452f) / bookTotal, result.bookFraction, 0.0001f)
    }

    @Test
    fun `book position spreads unknown chapter durations evenly over the remainder`() {
        // Chapters 0 and 3 known; 1 and 2 unknown -> each gets a share of
        // (bookTotal - knownSum) so the sum matches the authoritative total.
        val sparseChapters = listOf(1205L, 0L, 0L, 1038L).mapIndexed { index, duration ->
            chapter(index, duration)
        }
        val bookTotal = 60_071L

        val result = calculatePlayerProgress(
            chapters = sparseChapters,
            currentChapterIndex = 3,
            currentPositionMs = 300_000,
            currentChapterDurationMs = 1_038_000,
            bookmarks = emptyList(),
            bookTotalDurationSeconds = bookTotal
        )

        // knownSum = 1205 + 1038 = 2243; missing = 60071 - 2243 = 57828 over 2
        // unknown chapters -> 28914 each.
        val spreadEach = (bookTotal - 2243L) / 2
        assertEquals(1205L + spreadEach + spreadEach + 300L, result.bookPositionSeconds)
        assertEquals(
            "spread durations must sum to the authoritative book total",
            bookTotal,
            result.bookDurationSeconds
        )
    }

    @Test
    fun `book seek lands on a spread chapter when its duration is unknown`() {
        // Ch0 known (1205), ch1 unknown (0), ch2 known (1038); book total
        // 60071 -> ch1 gets spread so the whole track covers the real book.
        val sparseChapters = listOf(1205L, 0L, 1038L).mapIndexed { index, duration ->
            chapter(index, duration)
        }
        val bookTotal = 60_071L

        // 30% of the book lands after ch0 but before ch2 (spread ch1).
        val target = calculateBookSeekTarget(
            chapters = sparseChapters,
            currentChapterIndex = 1,
            currentChapterDurationMs = 0L,
            fraction = 0.3f,
            bookTotalDurationSeconds = bookTotal
        )!!

        val spreadCh1 = (bookTotal - (1205L + 1038L)) // missing, all to ch1
        val secondsIntoCh1 = (0.3f * bookTotal).toLong() - 1205L
        assertEquals(1, target.chapterIndex)
        assertEquals(secondsIntoCh1.coerceIn(0L, spreadCh1) * 1_000L, target.positionMs)
    }

    @Test
    fun `zero duration chapters use safe evenly spaced markers`() {
        val zeroDuration = chapters.map { it.copy(durationSeconds = 0) }

        val result = calculatePlayerProgress(
            chapters = zeroDuration,
            currentChapterIndex = 0,
            currentPositionMs = 0,
            currentChapterDurationMs = 0,
            bookmarks = emptyList()
        )

        assertEquals(0f, result.chapterFraction)
        assertEquals(0f, result.bookFraction)
        assertEquals(listOf(1f / 3f, 2f / 3f), result.chapterMarkers)
    }

    @Test
    fun `book seek resolves chapter and chapter relative position`() {
        val target = calculateBookSeekTarget(chapters, 1, chapters[1].durationSeconds * 1_000L, 0.5f)!!

        assertEquals(1, target.chapterIndex)
        assertEquals(390_000L, target.positionMs)
    }

    @Test
    fun `book seek uses measured duration when imported metadata is missing`() {
        val zeroDuration = chapters.map { it.copy(durationSeconds = 0L) }

        val target = calculateBookSeekTarget(zeroDuration, 1, 600_000L, 0.5f)!!

        assertEquals(1, target.chapterIndex)
        assertEquals(300_000L, target.positionMs)
    }
}
