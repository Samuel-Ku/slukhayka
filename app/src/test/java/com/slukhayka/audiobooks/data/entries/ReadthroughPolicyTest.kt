package com.slukhayka.audiobooks.data.entries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0046 / #863 — the pure Readthrough rules, before any Room row exists. */
class ReadthroughPolicyTest {

    private fun audio() = ReadthroughPolicy.start(
        id = "rt-audio-1",
        libraryEntryId = "entry-1",
        workId = "work-1",
        format = ReadingFormat.AUDIO,
        startedAt = 1_000L,
        editionId = "edition-1",
        value = 300
    )!!

    private fun paper() = ReadthroughPolicy.start(
        id = "rt-paper-1",
        libraryEntryId = "entry-1",
        workId = "work-1",
        format = ReadingFormat.PAPER,
        startedAt = 1_000L,
        value = 12
    )!!

    @Test
    fun `only audio names an edition - and audio must name one`() {
        assertTrue(ReadthroughPolicy.editionAllowed(ReadingFormat.AUDIO))
        assertTrue(!ReadthroughPolicy.editionAllowed(ReadingFormat.PAPER))
        assertTrue(!ReadthroughPolicy.editionAllowed(ReadingFormat.EBOOK))

        assertNull(
            "an audio pass without an Edition is not a pass",
            ReadthroughPolicy.start("rt", "entry-1", "work-1", ReadingFormat.AUDIO, 1L, editionId = null)
        )
        assertNull(
            "a paper pass never names an Edition",
            ReadthroughPolicy.start("rt", "entry-1", "work-1", ReadingFormat.PAPER, 1L, editionId = "edition-1")
        )
        assertNotNull(paper())
        assertEquals(null, paper().editionId)
    }

    @Test
    fun `each format keeps its OWN units - nothing is converted`() {
        assertEquals(ReadingUnit.SECONDS, audio().units.unit)
        assertEquals(ReadingUnit.PAGES, paper().units.unit)
        assertEquals(
            ReadingUnit.PERCENT,
            ReadthroughPolicy.unitFor(ReadingFormat.EBOOK)
        )
        // The journal records the observed unit, never a common percentage.
        val progressed = ReadthroughPolicy.recordProgress(paper(), at = 2_000L, value = 40)!!
        assertEquals(ReadingUnit.PAGES, progressed.units.unit)
        assertTrue(progressed.journal.all { it.units.unit == ReadingUnit.PAGES })
    }

    @Test
    fun `finishing belongs to one readthrough only`() {
        val audioPass = audio()
        val finished = ReadthroughPolicy.finish(audioPass, at = 5_000L)!!

        assertEquals(ReadingState.FINISHED, finished.state)
        assertEquals(5_000L, finished.finishedAt)
        assertEquals("the pass being finished is the only one changed", ReadingState.IN_PROGRESS, audioPass.state)
        assertNull("a finished pass is not finished twice", ReadthroughPolicy.finish(finished, at = 6_000L))
        assertNull(
            "progress goes to a new pass, not to a finished one",
            ReadthroughPolicy.recordProgress(finished, at = 6_000L, value = 1)
        )
    }

    @Test
    fun `a re-read is a NEW pass and the previous one stays in history`() {
        val first = ReadthroughPolicy.finish(audio(), at = 5_000L)!!
        val second = ReadthroughPolicy.restart(first, newId = "rt-audio-2", startedAt = 9_000L)!!

        assertEquals("rt-audio-2", second.id)
        assertEquals(ReadingState.IN_PROGRESS, second.state)
        assertEquals(null, second.finishedAt)
        assertEquals(0, second.units.value)
        assertTrue(second.journal.isEmpty())
        assertEquals("the previous pass is untouched", ReadingState.FINISHED, first.state)
        assertEquals(5_000L, first.finishedAt)
        assertEquals(
            "same Work and format: a re-read, not another book",
            first.workId to first.format,
            second.workId to second.format
        )
        assertNull("the id must be a NEW one", ReadthroughPolicy.restart(first, "rt-audio-1", 9_000L))
    }

    @Test
    fun `an abandoned pass takes no further progress`() {
        val abandoned = paper().copy(state = ReadingState.ABANDONED)

        assertNull(ReadthroughPolicy.recordProgress(abandoned, at = 3_000L, value = 50))
    }

    @Test
    fun `nonsense input is refused, never guessed`() {
        assertNull(ReadthroughPolicy.start("", "entry-1", "work-1", ReadingFormat.PAPER, 1L, value = 1))
        assertNull(ReadthroughPolicy.start("rt", "entry-1", "work-1", ReadingFormat.PAPER, 0L, value = 1))
        assertNull(
            "a blank Edition is not an Edition",
            ReadthroughPolicy.start("rt", "entry-1", "work-1", ReadingFormat.AUDIO, 1L, editionId = "  ")
        )
        assertNull(ReadthroughPolicy.recordProgress(paper(), at = 0L, value = 1))
        assertNull(ReadthroughPolicy.finish(paper(), at = 0L))
    }
}
