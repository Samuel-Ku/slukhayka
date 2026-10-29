package com.slukhayka.audiobooks.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1571 — contract of the pure scrub decision: a fraction maps to a chapter
 * position, the preview label reads the same position in seconds, and the
 * boundaries are clamped rather than trusted.
 */
class SeekSessionTest {

    private val chapterDurationMs = 660_000L

    @Test
    fun startOfChapterMapsToZeroInBothForms() {
        assertEquals(0L, SeekSession.commitPositionMs(0f, chapterDurationMs))
        assertEquals(0L, SeekSession.previewSeconds(0f, chapterDurationMs))
    }

    @Test
    fun endOfChapterMapsToTheChapterLength() {
        assertEquals(chapterDurationMs, SeekSession.commitPositionMs(1f, chapterDurationMs))
        assertEquals(660L, SeekSession.previewSeconds(1f, chapterDurationMs))
    }

    @Test
    fun previewSecondsAreTheCommitTruncatedToTheSecond() {
        val middle = 0.5f
        assertEquals(330_000L, SeekSession.commitPositionMs(middle, chapterDurationMs))
        assertEquals(330L, SeekSession.previewSeconds(middle, chapterDurationMs))

        val awkward = 0.123456f
        assertEquals(
            SeekSession.commitPositionMs(awkward, chapterDurationMs) / 1_000L,
            SeekSession.previewSeconds(awkward, chapterDurationMs)
        )
    }

    @Test
    fun outOfRangeFractionsAreClamped() {
        assertEquals(0L, SeekSession.commitPositionMs(-0.5f, chapterDurationMs))
        assertEquals(0L, SeekSession.previewSeconds(-0.5f, chapterDurationMs))
        assertEquals(chapterDurationMs, SeekSession.commitPositionMs(1.5f, chapterDurationMs))
        assertEquals(660L, SeekSession.previewSeconds(1.5f, chapterDurationMs))
    }

    @Test
    fun unknownDurationAnswersZeroInsteadOfGuessing() {
        assertEquals(0L, SeekSession.commitPositionMs(0.5f, 0L))
        assertEquals(0L, SeekSession.previewSeconds(0.5f, 0L))
        assertEquals(0L, SeekSession.commitPositionMs(0.5f, -1L))
        assertEquals(0L, SeekSession.previewSeconds(0.5f, -1L))
    }
}
