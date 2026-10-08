package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.data.listening.BookProgress
import com.slukhayka.audiobooks.data.listening.isBookFinished
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec-52 US28 / #1174 — the pure rules of «покинути книгу».
 *
 * Two halves are pinned here, both without a screen or a database: WHICH books
 * the book page offers the action for (the AC's edges — a finished book, a book
 * without progress, and the book that is playing its LAST SECONDS), and what
 * the mark does to the pass itself. The completion verdict is the app's ONE
 * rule, shared with the library card and the abandon door, so its own
 * boundaries are pinned here too.
 */
class AbandonBookPolicyTest {

    private fun started(positionSeconds: Long = 120L, totalSeconds: Long = 3_600L) =
        BookProgress(
            cumulativePositionSeconds = positionSeconds,
            totalDurationSeconds = totalSeconds,
            completedManually = false
        )

    // --- the ONE completion rule ---------------------------------------------

    @Test
    fun `the manual flag finishes a book, whatever the position says`() {
        assertTrue(
            isBookFinished(
                completedManually = true,
                cumulativePositionSeconds = 0L,
                totalDurationSeconds = 3_600L
            )
        )
    }

    @Test
    fun `a position at the known end finishes a book`() {
        assertTrue(
            isBookFinished(
                completedManually = false,
                cumulativePositionSeconds = 3_600L,
                totalDurationSeconds = 3_600L
            )
        )
    }

    @Test
    fun `an unknown total never finishes a book by position`() {
        assertFalse(
            isBookFinished(
                completedManually = false,
                cumulativePositionSeconds = 9_999L,
                totalDurationSeconds = 0L
            )
        )
    }

    // --- the offer (AC: «лише книги з реальним поступом, не завершені») -------

    @Test
    fun `a started book that is not finished is offered the abandon`() {
        assertEquals(
            AbandonOffer.ABANDON,
            AbandonBookPolicy.offer(
                storedAbandoned = false,
                hasListeningState = true,
                bookProgress = started()
            )
        )
    }

    @Test
    fun `a book playing its last seconds is never offered the abandon`() {
        assertEquals(
            "the position already reached the book's end — the library calls it finished",
            AbandonOffer.NONE,
            AbandonBookPolicy.offer(
                storedAbandoned = false,
                hasListeningState = true,
                bookProgress = started(positionSeconds = 3_600L)
            )
        )
    }

    @Test
    fun `a manually finished book is never offered the abandon`() {
        assertEquals(
            AbandonOffer.NONE,
            AbandonBookPolicy.offer(
                storedAbandoned = false,
                hasListeningState = true,
                bookProgress = started().copy(completedManually = true)
            )
        )
    }

    @Test
    fun `a book without a listening position is never offered the abandon`() {
        assertEquals(
            AbandonOffer.NONE,
            AbandonBookPolicy.offer(
                storedAbandoned = false,
                hasListeningState = false,
                bookProgress = started(positionSeconds = 0L)
            )
        )
    }

    @Test
    fun `a marked book offers the way back instead of the abandon`() {
        assertEquals(
            AbandonOffer.CANCEL,
            AbandonBookPolicy.offer(
                storedAbandoned = true,
                hasListeningState = true,
                bookProgress = started()
            )
        )
    }

    @Test
    fun `completion wins over the mark - a finished book is not offered the cancel`() {
        assertEquals(
            AbandonOffer.NONE,
            AbandonBookPolicy.offer(
                storedAbandoned = true,
                hasListeningState = true,
                bookProgress = started(positionSeconds = 3_600L)
            )
        )
    }

    // --- the badge rule ------------------------------------------------------

    @Test
    fun `the mark is live only while the book is not finished`() {
        assertTrue(AbandonBookPolicy.markIsLive(abandoned = true, isFinished = false))
        assertFalse(AbandonBookPolicy.markIsLive(abandoned = true, isFinished = true))
        assertFalse(AbandonBookPolicy.markIsLive(abandoned = false, isFinished = false))
    }

    // --- the pass itself -----------------------------------------------------

    private fun pass(): Readthrough = ReadthroughPolicy.start(
        id = "rt-audio-entry-1",
        libraryEntryId = "entry-1",
        workId = "work-1",
        format = ReadingFormat.AUDIO,
        startedAt = 1_000L,
        editionId = "edition-1"
    )!!

    @Test
    fun `abandoning moves ONLY the state - the pass keeps its moment and units`() {
        val recorded = ReadthroughPolicy.recordProgress(pass(), at = 2_000L, value = 300)!!
        val marked = ReadthroughPolicy.abandon(recorded)!!

        assertEquals(ReadingState.ABANDONED, marked.state)
        assertEquals("the journal is history and stays untouched", recorded.journal, marked.journal)
        assertEquals(recorded.units, marked.units)
        assertEquals(recorded.startedAt, marked.startedAt)
        assertEquals(recorded.editionId, marked.editionId)
    }

    @Test
    fun `an already marked pass is not marked twice`() {
        val marked = ReadthroughPolicy.abandon(pass())!!

        assertEquals(marked, ReadthroughPolicy.abandon(marked))
    }

    @Test
    fun `a finished pass is history and is never marked`() {
        val finished = ReadthroughPolicy.finish(pass(), at = 5_000L)!!

        assertNull(ReadthroughPolicy.abandon(finished))
    }

    @Test
    fun `cancelling restores exactly what the mark took away`() {
        val marked = ReadthroughPolicy.abandon(pass())!!

        assertEquals(
            "a pass that was PLANNED before the mark comes back PLANNED",
            ReadingState.PLANNED,
            ReadthroughPolicy.restore(
                marked,
                previousState = ReadingState.PLANNED,
                hasListeningProgress = true
            )!!.state
        )
        assertEquals(
            ReadingState.IN_PROGRESS,
            ReadthroughPolicy.restore(
                marked,
                previousState = ReadingState.IN_PROGRESS,
                hasListeningProgress = true
            )!!.state
        )
    }

    @Test
    fun `a lost note falls back to the state the evidence proves`() {
        val marked = ReadthroughPolicy.abandon(pass())!!

        assertEquals(
            ReadingState.IN_PROGRESS,
            ReadthroughPolicy.restore(
                marked,
                previousState = null,
                hasListeningProgress = true
            )!!.state
        )
        assertEquals(
            "nothing proves a start, so nothing claims one",
            ReadingState.PLANNED,
            ReadthroughPolicy.restore(
                marked,
                previousState = null,
                hasListeningProgress = false
            )!!.state
        )
    }

    @Test
    fun `a note that names the mark or history is not trusted`() {
        val marked = ReadthroughPolicy.abandon(pass())!!

        assertEquals(
            ReadingState.IN_PROGRESS,
            ReadthroughPolicy.restore(
                marked,
                previousState = ReadingState.ABANDONED,
                hasListeningProgress = true
            )!!.state
        )
        assertEquals(
            ReadingState.IN_PROGRESS,
            ReadthroughPolicy.restore(
                marked,
                previousState = ReadingState.FINISHED,
                hasListeningProgress = true
            )!!.state
        )
    }

    @Test
    fun `cancelling a pass that carries no mark is refused`() {
        assertNull(
            ReadthroughPolicy.restore(pass(), previousState = null, hasListeningProgress = true)
        )
        assertNull(
            "a finished pass is not restored either",
            ReadthroughPolicy.restore(
                ReadthroughPolicy.finish(pass(), at = 5_000L)!!,
                previousState = ReadingState.IN_PROGRESS,
                hasListeningProgress = true
            )
        )
    }
}
