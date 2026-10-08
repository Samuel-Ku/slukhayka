package com.slukhayka.audiobooks.data.entries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec-52 US28 / #1174 — the pure rules of «покинути книгу».
 *
 * Two halves are pinned here, both without a screen or a database: WHICH books
 * the book page offers the action for (the AC's two edges — a finished book and
 * a book without progress), and what the mark does to the pass itself.
 */
class AbandonBookPolicyTest {

    // --- the offer (AC: «лише книги з реальним поступом, не завершені») -------

    @Test
    fun `a started book that is not finished is offered the abandon`() {
        assertEquals(
            AbandonOffer.ABANDON,
            AbandonBookPolicy.offer(storedAbandoned = false, hasListeningState = true, isCompleted = false)
        )
    }

    @Test
    fun `a finished book is never offered the abandon`() {
        assertEquals(
            AbandonOffer.NONE,
            AbandonBookPolicy.offer(storedAbandoned = false, hasListeningState = true, isCompleted = true)
        )
    }

    @Test
    fun `a book without a listening position is never offered the abandon`() {
        assertEquals(
            AbandonOffer.NONE,
            AbandonBookPolicy.offer(storedAbandoned = false, hasListeningState = false, isCompleted = false)
        )
    }

    @Test
    fun `a marked book offers the way back instead of the abandon`() {
        assertEquals(
            AbandonOffer.CANCEL,
            AbandonBookPolicy.offer(storedAbandoned = true, hasListeningState = true, isCompleted = false)
        )
    }

    @Test
    fun `completion wins over the mark - a finished book is not offered the cancel`() {
        assertEquals(
            AbandonOffer.NONE,
            AbandonBookPolicy.offer(storedAbandoned = true, hasListeningState = true, isCompleted = true)
        )
    }

    // --- the badge rule ------------------------------------------------------

    @Test
    fun `the mark is live only while the book is not finished`() {
        assertTrue(AbandonBookPolicy.markIsLive(abandoned = true, isCompleted = false))
        assertFalse(AbandonBookPolicy.markIsLive(abandoned = true, isCompleted = true))
        assertFalse(AbandonBookPolicy.markIsLive(abandoned = false, isCompleted = false))
    }

    // --- the pass itself -----------------------------------------------------

    private fun started(): Readthrough = ReadthroughPolicy.start(
        id = "rt-audio-entry-1",
        libraryEntryId = "entry-1",
        workId = "work-1",
        format = ReadingFormat.AUDIO,
        startedAt = 1_000L,
        editionId = "edition-1"
    )!!

    @Test
    fun `abandoning moves ONLY the state - the pass keeps its moment and units`() {
        val pass = ReadthroughPolicy.recordProgress(started(), at = 2_000L, value = 300)!!
        val marked = ReadthroughPolicy.abandon(pass)!!

        assertEquals(ReadingState.ABANDONED, marked.state)
        assertEquals("the journal is history and stays untouched", pass.journal, marked.journal)
        assertEquals(pass.units, marked.units)
        assertEquals(pass.startedAt, marked.startedAt)
        assertEquals(pass.editionId, marked.editionId)
    }

    @Test
    fun `an already marked pass is not marked twice`() {
        val marked = ReadthroughPolicy.abandon(started())!!

        assertEquals(marked, ReadthroughPolicy.abandon(marked))
    }

    @Test
    fun `a finished pass is history and is never marked`() {
        val finished = ReadthroughPolicy.finish(started(), at = 5_000L)!!

        assertNull(ReadthroughPolicy.abandon(finished))
    }

    @Test
    fun `cancelling returns the state the evidence proves`() {
        val marked = ReadthroughPolicy.abandon(started())!!

        val startedAgain = ReadthroughPolicy.reopen(marked, hasListeningProgress = true)!!
        assertEquals(ReadingState.IN_PROGRESS, startedAgain.state)

        val neverStarted = ReadthroughPolicy.reopen(marked, hasListeningProgress = false)!!
        assertEquals(
            "nothing proves a start, so nothing claims one",
            ReadingState.PLANNED,
            neverStarted.state
        )
    }

    @Test
    fun `cancelling a pass that carries no mark is refused`() {
        assertNull(ReadthroughPolicy.reopen(started(), hasListeningProgress = true))
        assertNull(
            "a finished pass is not reopened either",
            ReadthroughPolicy.reopen(ReadthroughPolicy.finish(started(), at = 5_000L)!!, true)
        )
    }
}
