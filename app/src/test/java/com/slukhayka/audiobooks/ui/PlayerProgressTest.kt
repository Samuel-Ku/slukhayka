package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.listening.bookProgress
import com.slukhayka.audiobooks.data.listening.effectiveChapterDurations
import com.slukhayka.audiobooks.ui.screens.calculatePlayerProgress
import com.slukhayka.audiobooks.ui.screens.calculatePlayerMarkers
import com.slukhayka.audiobooks.ui.screens.calculateBookSeekTarget
import com.slukhayka.audiobooks.ui.screens.lastCreatedBookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    // #1707 — markers are position-independent, so the player remembers them
    // and hands them back to calculatePlayerProgress. The remembered path must
    // be byte-identical to the one-shot computation, otherwise the cache would
    // be a behaviour change rather than an allocation fix.
    // ---------------------------------------------------------------------

    @Test
    fun `remembered markers produce the same progress as the one-shot calculation`() {
        val markerBookmarks = listOf(
            BookmarkEntity(
                id = 1,
                bookId = bookId,
                chapterIndex = 0,
                chapterTitle = chapters[0].title,
                timestampSeconds = 90,
                note = "",
                createdAt = 1_700_000_000_000L
            ),
            BookmarkEntity(
                id = 2,
                bookId = bookId,
                chapterIndex = 2,
                chapterTitle = chapters[2].title,
                timestampSeconds = 120,
                note = "",
                createdAt = 1_700_000_001_000L
            ),
            // Out of range: the old contract dropped it, the prefix-sum path
            // must not place it at the end of the book.
            BookmarkEntity(
                id = 3,
                bookId = bookId,
                chapterIndex = 9,
                chapterTitle = "Зайвий розділ",
                timestampSeconds = 10,
                note = "",
                createdAt = 1_700_000_002_000L
            )
        )
        val bookTotal = chapters.sumOf { it.durationSeconds }

        val markers = calculatePlayerMarkers(
            chapters = chapters,
            currentChapterIndex = 1,
            currentChapterDurationMs = chapters[1].durationSeconds * 1_000L,
            bookmarks = markerBookmarks,
            bookTotalDurationSeconds = bookTotal
        )
        val oneShot = calculatePlayerProgress(
            chapters = chapters,
            currentChapterIndex = 1,
            currentPositionMs = 300_000L,
            currentChapterDurationMs = chapters[1].durationSeconds * 1_000L,
            bookmarks = markerBookmarks,
            bookTotalDurationSeconds = bookTotal
        )
        val reused = calculatePlayerProgress(
            chapters = chapters,
            currentChapterIndex = 1,
            currentPositionMs = 300_000L,
            currentChapterDurationMs = chapters[1].durationSeconds * 1_000L,
            bookmarks = markerBookmarks,
            bookTotalDurationSeconds = bookTotal,
            markers = markers
        )

        assertEquals(oneShot, reused)
        assertEquals(oneShot.chapterMarkers, markers.chapterMarkers)
        assertEquals(oneShot.bookmarkMarkers, markers.bookmarkMarkers)
    }

    // ---------------------------------------------------------------------
    // #1733 — the chapter ruler, too, is remembered (once per chapter layout
    // and book total) and shared by the markers and the per-tick progress.
    // The cached path must render exactly the one-shot path, bookmark markers
    // included; otherwise the cache would be a behaviour change.
    // ---------------------------------------------------------------------

    @Test
    fun `remembered chapter ruler and markers produce the same progress as the one-shot path`() {
        val cachedBookmarks = listOf(
            BookmarkEntity(
                id = 1,
                bookId = bookId,
                chapterIndex = 0,
                chapterTitle = chapters[0].title,
                timestampSeconds = 90,
                note = "",
                createdAt = 1_700_000_000_000L
            ),
            BookmarkEntity(
                id = 2,
                bookId = bookId,
                chapterIndex = 2,
                chapterTitle = chapters[2].title,
                timestampSeconds = 120,
                note = "",
                createdAt = 1_700_000_001_000L
            )
        )
        val bookTotal = chapters.sumOf { it.durationSeconds }
        val chapterDurationMs = chapters[1].durationSeconds * 1_000L

        val oneShot = calculatePlayerProgress(
            chapters = chapters,
            currentChapterIndex = 1,
            currentPositionMs = 300_000L,
            currentChapterDurationMs = chapterDurationMs,
            bookmarks = cachedBookmarks,
            bookTotalDurationSeconds = bookTotal
        )
        val durations = effectiveChapterDurations(
            chapters = chapters,
            currentChapterIndex = 1,
            currentChapterDurationMs = chapterDurationMs,
            bookTotalDurationSeconds = bookTotal
        )
        val markers = calculatePlayerMarkers(
            chapters = chapters,
            currentChapterIndex = 1,
            currentChapterDurationMs = chapterDurationMs,
            bookmarks = cachedBookmarks,
            bookTotalDurationSeconds = bookTotal,
            effectiveDurations = durations
        )
        val reused = calculatePlayerProgress(
            chapters = chapters,
            currentChapterIndex = 1,
            currentPositionMs = 300_000L,
            currentChapterDurationMs = chapterDurationMs,
            bookmarks = cachedBookmarks,
            bookTotalDurationSeconds = bookTotal,
            markers = markers,
            effectiveDurations = durations
        )

        assertEquals(
            "the remembered chapter ruler must not change the rendered progress",
            oneShot,
            reused
        )
        assertEquals(oneShot.chapterMarkers, markers.chapterMarkers)
        assertEquals(
            "bookmark markers must survive the cached path unchanged",
            oneShot.bookmarkMarkers,
            reused.bookmarkMarkers
        )
        assertTrue("the fixture must produce bookmark markers", reused.bookmarkMarkers.isNotEmpty())
    }

    @Test
    fun `progress uses the supplied chapter ruler for the playing chapter`() {
        // Deliberately different from the row's 600 s: progress must read the
        // ruler it was handed, not rebuild effectiveChapterDurations.
        val supplied = listOf(1_000L, 660L, 720L)

        val result = calculatePlayerProgress(
            chapters = chapters,
            currentChapterIndex = 0,
            currentPositionMs = 300_000,
            currentChapterDurationMs = 600_000,
            bookmarks = emptyList(),
            effectiveDurations = supplied
        )

        assertEquals(0.3f, result.chapterFraction, 0.0001f)
        assertEquals(300L, result.bookPositionSeconds)
        // The BOOK ruler is still the chapters' own known sum (#1187); the
        // supplied list only places positions on the chapter ruler.
        assertEquals(1_980L, result.bookDurationSeconds)
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

    // ---------------------------------------------------------------------
    // #1187 — the CHAPTER/BOOK boundary of the player's two rulers.
    //
    // A chapter measured only by being played is placed on the timeline with
    // what is known about it (the chapter ruler: position in the chapter, the
    // chapter fraction, the markers). The BOOK's own length is a different
    // question and may NOT be that same sum: for a local book it is the count
    // of what has been heard so far, so the fraction read the third chapter as
    // the end of the book and the player said "100 %" while the library card
    // honestly said «Тривалість невідома». The book ruler is
    // knownBookTotalSeconds: a sum only when EVERY chapter is measured.
    // ---------------------------------------------------------------------

    /**
     * Three 300-second chapters: the first was played (measures 300), the second
     * was never heard (0), and the listener is 60 s into the third — whose
     * length is only measured up to now (60 s). The old book ruler summed those
     * to 360 and put the listener at its end.
     */
    private fun partiallyMeasuredBook() = listOf(300L, 0L, 0L).mapIndexed { index, duration ->
        chapter(index, duration)
    }

    private fun progressInPartiallyMeasuredBook() = calculatePlayerProgress(
        chapters = partiallyMeasuredBook(),
        currentChapterIndex = 2,
        currentPositionMs = 60_000,
        currentChapterDurationMs = 60_000,
        bookmarks = emptyList()
    )

    @Test
    fun `an unmeasured chapter leaves the book duration unknown`() {
        assertEquals(
            "a partial sum is not the book's length — unknown, not 360",
            0L,
            progressInPartiallyMeasuredBook().bookDurationSeconds
        )
    }

    @Test
    fun `an unmeasured chapter leaves the book fraction at zero`() {
        // The old ruler made this exactly 1f — the "100 %" of the ticket.
        assertEquals(
            "a partial sum must not read as the end of the book",
            0f,
            progressInPartiallyMeasuredBook().bookFraction
        )
    }

    @Test
    fun `an unmeasured book has no seek target`() {
        assertNull(
            "a book of unknown length has no seek target to pretend to know",
            calculateBookSeekTarget(partiallyMeasuredBook(), 2, 60_000, 0.5f)
        )
    }

    @Test
    fun `the chapter ruler keeps working while the book ruler is unknown`() {
        // Same partially measured book: the CHAPTER ruler still places the
        // playing chapter by its measured length (60 s of 120 s heard), while
        // the BOOK ruler refuses to invent a length. The boundary is exactly
        // this: the chapter — yes, the book — no.
        val result = calculatePlayerProgress(
            chapters = partiallyMeasuredBook(),
            currentChapterIndex = 2,
            currentPositionMs = 60_000,
            currentChapterDurationMs = 120_000,
            bookmarks = emptyList()
        )

        assertEquals(0.5f, result.chapterFraction, 0.0001f)
        assertEquals(0L, result.bookDurationSeconds)
        assertEquals(0f, result.bookFraction)
    }

    @Test
    fun `a fully measured local book keeps its numbers`() {
        // The other side of the boundary: every chapter is measured, so the
        // sum IS the book's length and nothing moves.
        val fullyMeasured = listOf(300L, 300L, 300L).mapIndexed { index, duration ->
            chapter(index, duration)
        }

        val result = calculatePlayerProgress(
            chapters = fullyMeasured,
            currentChapterIndex = 2,
            currentPositionMs = 60_000,
            currentChapterDurationMs = 300_000,
            bookmarks = emptyList()
        )

        assertEquals(900L, result.bookDurationSeconds)
        assertEquals(660L, result.bookPositionSeconds)
        assertEquals(660f / 900f, result.bookFraction, 0.0001f)

        val target = calculateBookSeekTarget(fullyMeasured, 2, 300_000, 0.5f)!!
        assertEquals(1, target.chapterIndex)
        assertEquals(150_000L, target.positionMs)
    }

    @Test
    fun `book seek with unmeasured chapters returns no target`() {
        // Was «book seek uses measured duration when imported metadata is
        // missing»: the old expectation (target 300 s into chapter 1) was the
        // book ruler reading a playing chapter's length as the whole book.
        val zeroDuration = chapters.map { it.copy(durationSeconds = 0L) }

        assertNull(calculateBookSeekTarget(zeroDuration, 1, 600_000L, 0.5f))
    }

    @Test
    fun `the player and the library card report the same book total`() {
        // The two screens disagreed — that IS the defect (#1187): the card said
        // «Тривалість невідома» while the player said "100 %". One honest total
        // (knownBookTotalSeconds) is what makes them agree; a partial sum in
        // either one breaks this again.
        val partiallyMeasured = listOf(300L, 0L, 0L).mapIndexed { index, duration ->
            chapter(index, duration)
        }
        val progressRow = PlaybackProgressEntity(
            editionId = "edition-1",
            bookId = bookId,
            currentChapterIndex = 2,
            currentPositionSeconds = 60L
        )

        val card = bookProgress(partiallyMeasured, progressRow, bookTotalDurationSeconds = 0L)
        val player = calculatePlayerProgress(
            chapters = partiallyMeasured,
            currentChapterIndex = 2,
            currentPositionMs = 60_000,
            currentChapterDurationMs = 60_000,
            bookmarks = emptyList()
        )

        assertEquals(0L, card.totalDurationSeconds)
        assertEquals(card.totalDurationSeconds, player.bookDurationSeconds)

        val fullyMeasured = listOf(300L, 300L, 300L).mapIndexed { index, duration ->
            chapter(index, duration)
        }
        assertEquals(
            900L,
            bookProgress(fullyMeasured, progressRow, bookTotalDurationSeconds = 0L).totalDurationSeconds
        )
        assertEquals(
            900L,
            calculatePlayerProgress(fullyMeasured, 2, 60_000, 300_000, emptyList()).bookDurationSeconds
        )
    }

    // ---------------------------------------------------------------------
    // #351 — the «повернутися до останньої закладки» target: the most
    // recently CREATED bookmark. One predictable target — the button always
    // leads to the same place.
    // ---------------------------------------------------------------------

    private fun bookmark(id: Long, createdAt: Long) = BookmarkEntity(
        id = id,
        bookId = bookId,
        chapterIndex = 0,
        chapterTitle = chapters[0].title,
        timestampSeconds = 30L * id,
        note = "",
        createdAt = createdAt
    )

    @Test
    fun `last created bookmark wins by creation time`() {
        val older = bookmark(1, createdAt = 1_000L)
        val newer = bookmark(2, createdAt = 2_000L)

        assertEquals(newer.id, lastCreatedBookmark(listOf(older, newer))?.id)
    }

    @Test
    fun `equal creation times break the tie by id`() {
        val earlier = bookmark(7, createdAt = 5_000L)
        val later = bookmark(9, createdAt = 5_000L)

        assertEquals(later.id, lastCreatedBookmark(listOf(earlier, later))?.id)
    }

    @Test
    fun `no bookmarks means no jump target`() {
        assertNull(lastCreatedBookmark(emptyList()))
    }
}
