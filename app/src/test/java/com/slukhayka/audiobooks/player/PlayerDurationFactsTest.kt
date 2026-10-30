package com.slukhayka.audiobooks.player

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * #1647 — the player state's chapters are a snapshot; Room keeps learning the
 * real durations. This pins the display overlay that makes the player show
 * those facts without switching a chapter.
 */
class PlayerDurationFactsTest {

    private val book = AudiobookEntity(
        id = "b1",
        title = "Доктор Сон",
        author = "Стівен Кінг",
        narrator = "Олег Фомін",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = "https://4read.org/doctor-sleep",
        isDownloaded = false,
        totalDurationSeconds = 0L,
        totalChapters = 3
    )

    private fun chapter(index: Int, duration: Long, id: String = "c$index") = ChapterEntity(
        id = id,
        bookId = "b1",
        chapterIndex = index,
        title = "Розділ ${index + 1}",
        durationSeconds = duration
    )

    private fun state(
        chapters: List<ChapterEntity> = listOf(chapter(0, 0L), chapter(1, 0L)),
        currentBook: AudiobookEntity = book
    ) = PlayerState(
        currentBook = currentBook,
        chapters = chapters,
        currentChapterIndex = 0,
        isPlaying = true,
        currentPositionMs = 0L,
        durationMs = 0L
    )

    @Test
    fun `a probed duration replaces the unknown snapshot value`() {
        val merged = state().withLiveDurationFacts(
            liveBook = null,
            liveChapters = listOf(chapter(0, 2_448L), chapter(1, 0L))
        )

        assertEquals(listOf(2_448L, 0L), merged.chapters.map { it.durationSeconds })
    }

    @Test
    fun `a known snapshot duration is never replaced by a zero live row`() {
        val known = chapter(0, 600L)
        val merged = state(chapters = listOf(known)).withLiveDurationFacts(
            liveBook = null,
            liveChapters = listOf(chapter(0, 0L))
        )

        assertSame("nothing changed, so the snapshot instance is reused", known, merged.chapters.single())
    }

    @Test
    fun `a live chapter row that is absent from the snapshot does not invent a chapter`() {
        val merged = state(chapters = listOf(chapter(0, 0L))).withLiveDurationFacts(
            liveBook = null,
            liveChapters = listOf(chapter(0, 300L), chapter(9, 500L))
        )

        assertEquals(1, merged.chapters.size)
        assertEquals(300L, merged.chapters.single().durationSeconds)
    }

    @Test
    fun `the live book row replaces the stale book so a probed total is visible`() {
        val live = book.copy(totalDurationSeconds = 1_980L)
        val merged = state().withLiveDurationFacts(
            liveBook = live,
            liveChapters = emptyList()
        )

        assertEquals(1_980L, merged.currentBook?.totalDurationSeconds)
    }

    @Test
    fun `equal live rows are a no-op too - the steady state stays the same instance`() {
        // #1650 (review of #1649): the review's mutation that dropped the
        // equality guard survived, because only EMPTY live lists were pinned.
        // A realistic steady state is live rows that repeat the snapshot with
        // the same positive durations; it must not allocate/copy either.
        val chapters = listOf(chapter(0, 600L), chapter(1, 900L))
        val original = state(chapters = chapters)
        val repeated = listOf(chapter(0, 600L), chapter(1, 900L))
        val sameBook = book.copy(totalDurationSeconds = 1_500L)

        val withNewTotal = original.copy(currentBook = sameBook)
        assertSame(
            "live rows that repeat the snapshot must not produce a new state",
            withNewTotal,
            withNewTotal.withLiveDurationFacts(liveBook = null, liveChapters = repeated)
        )
        assertSame(original, original.withLiveDurationFacts(liveBook = null, liveChapters = repeated))
    }

    @Test
    fun `with nothing live the state is returned untouched`() {
        val original = state()

        assertSame(original, original.withLiveDurationFacts(liveBook = null, liveChapters = emptyList()))
    }

    @Test
    fun `the snapshot order and identity are the player's, durations ride along`() {
        val display = listOf(chapter(1, 0L, id = "second"), chapter(0, 0L, id = "first"))
        val merged = state(chapters = display).withLiveDurationFacts(
            liveBook = null,
            liveChapters = listOf(chapter(0, 120L, id = "first"), chapter(1, 240L, id = "second"))
        )

        assertEquals(listOf("second", "first"), merged.chapters.map { it.id })
        assertEquals(listOf(240L, 120L), merged.chapters.map { it.durationSeconds })
    }
}
