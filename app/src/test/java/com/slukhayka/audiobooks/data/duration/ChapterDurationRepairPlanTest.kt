package com.slukhayka.audiobooks.data.duration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #528 — what the repair pass resets, and what it leaves alone. */
class ChapterDurationRepairPlanTest {

    @Test
    fun `the observed multi-chapter book is reset whole`() {
        val chapters = (0 until 15).associate { "ch$it" to 52L }
        assertEquals(
            chapters.keys.toList(),
            ChapterDurationRepairPlan.chaptersToReset(chapters, distinctTracks = 15)
        )
    }

    @Test
    fun `the observed single-chapter book is caught by size`() {
        val reset = ChapterDurationRepairPlan.chaptersToReset(
            chapters = mapOf("ch0" to 52L),
            distinctTracks = 1,
            contentLengths = mapOf("ch0" to 51_021_112L)
        )
        assertEquals(listOf("ch0"), reset)
    }

    @Test
    fun `an honest book is left completely alone`() {
        val chapters = mapOf("a" to 1701L, "b" to 1200L, "c" to 900L)
        assertTrue(
            ChapterDurationRepairPlan.chaptersToReset(
                chapters = chapters,
                distinctTracks = 3,
                contentLengths = mapOf("a" to 51_021_112L, "b" to 36_000_000L, "c" to 27_000_000L)
            ).isEmpty()
        )
    }

    @Test
    fun `a chapter with no known size is never reset on size alone`() {
        assertTrue(
            ChapterDurationRepairPlan.chaptersToReset(
                chapters = mapOf("only" to 52L),
                distinctTracks = 1,
                contentLengths = emptyMap()
            ).isEmpty()
        )
    }

    @Test
    fun `an empty book plans nothing`() {
        assertTrue(ChapterDurationRepairPlan.chaptersToReset(emptyMap(), distinctTracks = 0).isEmpty())
    }

    @Test
    fun `the observed mixed state resets only the poisoned chapters`() {
        // Measured on device 2026-09-13: fourteen chapters held 52 s and ONE
        // held its true 3188 s. The old «ALL equal» rule saw no trace and
        // repaired nothing; the dominant rule must reset the fourteen and
        // leave the honestly measured chapter alone.
        val chapters = (0 until 14).associate { "ch$it" to 52L } + ("good" to 3188L)
        val reset = ChapterDurationRepairPlan.chaptersToReset(chapters, distinctTracks = 15)
        assertEquals(14, reset.size)
        assertFalse("the correctly measured chapter must survive", reset.contains("good"))
    }

    @Test
    fun `two equal chapters are not a trace`() {
        // Two identical durations over distinct files happen in real books.
        val chapters = mapOf("a" to 300L, "b" to 300L, "c" to 900L)
        assertTrue(ChapterDurationRepairPlan.chaptersToReset(chapters, distinctTracks = 3).isEmpty())
    }
}
