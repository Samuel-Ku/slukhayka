package com.example.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Position-history undo behavior (wayfinder #25). */
class SeekHistoryTest {

    private val history = SeekHistory()

    @Test
    fun `starts with nothing to undo`() {
        assertFalse(history.canUndo())
        assertNull(history.consumeUndo())
    }

    @Test
    fun `small seeks are not recorded`() {
        history.recordSeek(fromPositionMs = 60_000L, toPositionMs = 90_000L, chapterIndex = 0)
        history.recordSeek(fromPositionMs = 90_000L, toPositionMs = 90_000L, chapterIndex = 0)
        assertFalse(history.canUndo())
    }

    @Test
    fun `a big seek is recorded with the pre-jump position`() {
        // 6 minutes is past the 5-minute threshold (delta 300001 >= 300000).
        history.recordSeek(fromPositionMs = 60_000L, toPositionMs = 6 * 60 * 1000L + 1L, chapterIndex = 1)
        assertTrue(history.canUndo())
        val jump = history.consumeUndo()
        assertEquals(60_000L, jump?.fromPositionMs)
        assertEquals(6 * 60 * 1000L + 1L, jump?.toPositionMs)
        assertEquals(1, jump?.chapterIndex)
        assertFalse("consume clears the jump", history.canUndo())
    }

    @Test
    fun `only the latest jump survives`() {
        history.recordSeek(fromPositionMs = 1_000L, toPositionMs = 30 * 60 * 1000L, chapterIndex = 0)
        history.recordSeek(fromPositionMs = 30 * 60 * 1000L, toPositionMs = 60 * 60 * 1000L, chapterIndex = 0)
        val jump = history.consumeUndo()
        assertEquals(30 * 60 * 1000L, jump?.fromPositionMs)
    }

    // --- Spec-16 T3: the log facade (restart restores the candidate) -------

    @Test
    fun `restore seeds a jump that survived a restart`() {
        // The player restores a candidate read back from the event log.
        history.restore(SeekJump(fromPositionMs = 60_000L, toPositionMs = 6 * 60 * 1000L, chapterIndex = 2))
        assertTrue(history.canUndo())
        val jump = history.consumeUndo()
        assertEquals(60_000L, jump?.fromPositionMs)
        assertEquals(6 * 60 * 1000L, jump?.toPositionMs)
        assertEquals(2, jump?.chapterIndex)
    }

    @Test
    fun `clear drops any remembered jump for a fresh cycle`() {
        history.recordSeek(fromPositionMs = 1_000L, toPositionMs = 30 * 60 * 1000L, chapterIndex = 0)
        history.clear()
        assertFalse(history.canUndo())
        assertNull(history.consumeUndo())
    }
}
