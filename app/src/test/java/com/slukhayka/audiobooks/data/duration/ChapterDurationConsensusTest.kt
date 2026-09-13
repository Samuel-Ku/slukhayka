package com.slukhayka.audiobooks.data.duration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #528 — the corroboration that lets the playback guard trust a duration.
 *
 * The asymmetry these tests pin down is the whole reason the guard may not
 * act on a chapter's own stored duration alone: a poisoned row must be
 * uncorroborated, so a substituted body can never make us refuse an honest
 * file.
 */
class ChapterDurationConsensusTest {

    @Test
    fun `a chapter agreeing with a sibling is corroborated`() {
        // «горобець»: 1701 s beside 3188 s — the same book's chapters.
        assertTrue(ChapterDurationConsensus.corroborated(1_701L, listOf(3_188L, 1_701L)))
    }

    @Test
    fun `a poisoned lone row is not corroborated by long chapters`() {
        // The case that must never justify a refusal: 52 s stored, 28-minute
        // siblings. Nothing agrees with it, so the guard stays out.
        assertFalse(ChapterDurationConsensus.corroborated(52L, listOf(1_701L, 3_188L, 1_700L)))
        assertNull(ChapterDurationConsensus.trustedSeconds(52L, listOf(1_701L, 3_188L)))
    }

    @Test
    fun `a lone chapter with no siblings is never corroborated`() {
        // «Планета туману»: one chapter. There is nothing to agree with, so
        // the size rule repairs metadata but never gates playback.
        assertFalse(ChapterDurationConsensus.corroborated(52L, emptyList()))
        assertFalse(ChapterDurationConsensus.corroborated(1_701L, listOf(0L, 0L)))
    }

    @Test
    fun `the honest spread inside one book is corroborated`() {
        // A book may legitimately mix a 27-minute chapter with a 53-minute
        // one; that must not read as an outlier.
        assertTrue(ChapterDurationConsensus.corroborated(1_701L, listOf(3_188L)))
        assertTrue(ChapterDurationConsensus.corroborated(3_188L, listOf(1_701L)))
    }

    @Test
    fun `a genuinely short intro is not corroborated by long chapters`() {
        assertFalse(ChapterDurationConsensus.corroborated(40L, listOf(1_800L, 1_820L)))
    }

    @Test
    fun `a uniformly short book corroborates itself`() {
        // If every chapter really is ~52 s, the row is honest and the body
        // that implies 52 s is honest too.
        assertTrue(ChapterDurationConsensus.corroborated(52L, listOf(52L, 51L, 53L)))
    }

    @Test
    fun `a duration is judged by what it means, not by itself`() {
        // The chapter's own row repeats in the sibling list; it must not be
        // able to corroborate itself.
        assertFalse(ChapterDurationConsensus.corroborated(52L, listOf(52L)))
    }

    @Test
    fun `trusted seconds pass only for a corroborated duration`() {
        assertEquals(
            1_701L,
            ChapterDurationConsensus.trustedSeconds(1_701L, listOf(3_188L, 1_701L))
        )
        assertNull(ChapterDurationConsensus.trustedSeconds(0L, listOf(3_188L)))
    }
}
