package com.slukhayka.audiobooks.data.duration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #528 — the signature a written-in constant leaves on a book. */
class ConstantChapterDurationDetectorTest {

    @Test
    fun `the observed case is caught`() {
        // «Темна матерія»: 15 chapters, all 52 s, fifteen distinct tracks.
        assertTrue(
            ConstantChapterDurationDetector.looksLikeAConstant(
                chapterDurations = List(15) { 52L },
                distinctTracks = 15
            )
        )
    }

    @Test
    fun `a real book is not touched`() {
        assertFalse(
            ConstantChapterDurationDetector.looksLikeAConstant(
                chapterDurations = listOf(1701L, 1200L, 900L),
                distinctTracks = 3
            )
        )
    }

    @Test
    fun `identical durations over one file are honest`() {
        // One track, many chapters: an equal split is legitimate, not a trace.
        assertFalse(
            ConstantChapterDurationDetector.looksLikeAConstant(
                chapterDurations = listOf(300L, 300L, 300L),
                distinctTracks = 1
            )
        )
    }

    @Test
    fun `unknown and single-chapter books are left alone`() {
        assertFalse(ConstantChapterDurationDetector.looksLikeAConstant(listOf(52L), 5))
        assertFalse(ConstantChapterDurationDetector.looksLikeAConstant(listOf(0L, 0L), 5))
    }
}
