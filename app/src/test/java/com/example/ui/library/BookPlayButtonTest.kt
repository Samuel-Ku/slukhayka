package com.example.ui.library

import com.example.data.db.ChapterEntity
import com.example.data.db.PlaybackProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #40 decision 1 — the book page's main button: pure state → label mapping
 * (unstarted / in progress / finished / currently playing) and the
 * cumulative-position rule it runs on.
 */
class BookPlayButtonTest {

    private val bookId = "book-detail-button"
    private val chapters = listOf(600L, 660L, 720L).mapIndexed { index, duration ->
        ChapterEntity("ch-$index", bookId, index, "Глава ${index + 1}", duration, "https://example.invalid/$index.mp3")
    }

    private fun progress(chapterIndex: Int, positionSeconds: Long) = PlaybackProgressEntity(
        bookId = bookId,
        currentChapterIndex = chapterIndex,
        currentPositionSeconds = positionSeconds
    )

    private fun label(state: BookPlayState): String = bookPlayLabel(state) { seconds ->
        val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
        "%d:%02d:%02d".format(h, m, s)
    }

    // ------------------------------------------------------------------
    // State mapping
    // ------------------------------------------------------------------

    @Test
    fun `no progress shows unstarted`() {
        assertEquals(
            BookPlayState.Unstarted,
            bookPlayState(isPlayingThisBook = false, progress = null, cumulativePositionSeconds = 0L, totalDurationSeconds = 6000L)
        )
    }

    @Test
    fun `progress row at position zero is still unstarted`() {
        assertEquals(
            BookPlayState.Unstarted,
            bookPlayState(false, progress(0, 0L), cumulativePositionSeconds = 0L, totalDurationSeconds = 6000L)
        )
    }

    @Test
    fun `progress inside the book resumes with the cumulative position`() {
        // Chapter 1 (index 1) at 300 s of 660 → cumulative 600 + 300 = 900.
        assertEquals(
            BookPlayState.InProgress(900L),
            bookPlayState(false, progress(1, 300L), cumulativePositionSeconds = 900L, totalDurationSeconds = 6000L)
        )
    }

    @Test
    fun `position at the very end is finished`() {
        assertEquals(
            BookPlayState.Finished,
            bookPlayState(false, progress(2, 720L), cumulativePositionSeconds = 6000L, totalDurationSeconds = 6000L)
        )
    }

    @Test
    fun `playing wins over every stored state`() {
        assertEquals(
            BookPlayState.Playing,
            bookPlayState(true, progress(0, 120L), cumulativePositionSeconds = 120L, totalDurationSeconds = 6000L)
        )
        assertEquals(
            BookPlayState.Playing,
            bookPlayState(true, null, cumulativePositionSeconds = 0L, totalDurationSeconds = 0L)
        )
    }

    @Test
    fun `unknown total never counts as finished`() {
        assertEquals(
            BookPlayState.InProgress(900L),
            bookPlayState(false, progress(1, 300L), cumulativePositionSeconds = 900L, totalDurationSeconds = 0L)
        )
    }

    // ------------------------------------------------------------------
    // Labels
    // ------------------------------------------------------------------

    @Test
    fun `labels cover all four states`() {
        assertEquals("Слухати", label(BookPlayState.Unstarted))
        assertEquals("Продовжити з 0:15:00", label(BookPlayState.InProgress(900L)))
        assertEquals("Почати спочатку", label(BookPlayState.Finished))
        assertEquals("Playing", label(BookPlayState.Playing))
    }

    // ------------------------------------------------------------------
    // Position + total (spec-16 T4 rule, shared with the library card)
    // ------------------------------------------------------------------

    @Test
    fun `cumulative position sums chapters before the current one`() {
        val (cumulative, total) = bookPositionAndTotal(chapters, progress(1, 300L), bookTotalDurationSeconds = 0L)
        assertEquals(900L, cumulative)
        assertEquals((600 + 660 + 720).toLong(), total)
    }

    @Test
    fun `authoritative book total wins over the chapter sum`() {
        val (_, total) = bookPositionAndTotal(chapters, progress(0, 10L), bookTotalDurationSeconds = 16_000L)
        assertEquals(16_000L, total)
    }

    @Test
    fun `sparse known durations spread into the authoritative total`() {
        // Only chapter 0 was played (real duration); the rest unknown.
        val sparse = listOf(1205L, 0L, 0L, 1038L).mapIndexed { index, duration ->
            ChapterEntity("ch-$index", bookId, index, "Глава ${index + 1}", duration, "")
        }
        val bookTotal = 60_071L
        val (cumulative, total) = bookPositionAndTotal(sparse, progress(3, 300L), bookTotalDurationSeconds = bookTotal)
        assertEquals(bookTotal, total)
        // Each unknown chapter gets (60071 - 2243) / 2 seconds.
        val spread = (bookTotal - 2243L) / 2
        assertEquals(1205L + spread + spread + 300L, cumulative)
    }

    @Test
    fun `no progress yields zero position and total`() {
        assertEquals(0L to 0L, bookPositionAndTotal(chapters, null, bookTotalDurationSeconds = 6000L))
    }

    @Test
    fun `last chapter at its end lands exactly on the total`() {
        val (cumulative, total) = bookPositionAndTotal(chapters, progress(2, 720L), bookTotalDurationSeconds = 0L)
        assertEquals(total, cumulative)
        assertEquals(total, (600 + 660 + 720).toLong())
    }
}