package com.example.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the playback-event log policy (spec-16 T1, wayfinder #53). */
class PlaybackEventPolicyTest {

    private val fiveMinMs = PlaybackEventPolicy.SEEK_JUMP_THRESHOLD_MS
    private val fiveMinSec = fiveMinMs / 1000L

    private fun event(
        id: Long,
        kind: String,
        positionSeconds: Long = 0L,
        fromPositionSeconds: Long? = null,
        timestamp: Long = 0L,
        bookId: String = "b1",
        sourceKey: String = ""
    ) = PlaybackEventEntity(
        id = id,
        bookId = bookId,
        sourceKey = sourceKey,
        kind = kind,
        positionSeconds = positionSeconds,
        fromPositionSeconds = fromPositionSeconds,
        timestamp = timestamp
    )

    // --- Stable kinds ------------------------------------------------------

    @Test
    fun `kinds are the stable strings the log stores`() {
        assertEquals("RESUME", PlaybackEventKind.RESUME)
        assertEquals("PAUSE", PlaybackEventKind.PAUSE)
        assertEquals("SEEK", PlaybackEventKind.SEEK)
        assertEquals("CHAPTER_CHANGE", PlaybackEventKind.CHAPTER_CHANGE)
        assertEquals("TIMER_STOP", PlaybackEventKind.TIMER_STOP)
        assertEquals("COMPLETED", PlaybackEventKind.COMPLETED)
        assertEquals("RELISTEN", PlaybackEventKind.RELISTEN)
        assertEquals("SOURCE_SWITCH", PlaybackEventKind.SOURCE_SWITCH)
    }

    // --- Undo candidates ---------------------------------------------------

    @Test
    fun `seek of five minutes or more is an undo candidate`() {
        val atThreshold = event(1, PlaybackEventKind.SEEK, positionSeconds = fiveMinSec, fromPositionSeconds = 0L)
        val beyond = event(2, PlaybackEventKind.SEEK, positionSeconds = fiveMinSec + 1, fromPositionSeconds = 0L)

        assertTrue(PlaybackEventPolicy.isUndoCandidate(atThreshold))
        assertTrue(PlaybackEventPolicy.isUndoCandidate(beyond))
    }

    @Test
    fun `sub-threshold seek is not an undo candidate`() {
        val small = event(1, PlaybackEventKind.SEEK, positionSeconds = fiveMinSec - 1, fromPositionSeconds = 0L)
        val tiny = event(2, PlaybackEventKind.SEEK, positionSeconds = 30L, fromPositionSeconds = 0L)

        assertFalse(PlaybackEventPolicy.isUndoCandidate(small))
        assertFalse(PlaybackEventPolicy.isUndoCandidate(tiny))
    }

    @Test
    fun `only seek-like kinds with a from-position can be undo candidates`() {
        val sourceSwitch = event(1, PlaybackEventKind.SOURCE_SWITCH, positionSeconds = 400L, fromPositionSeconds = 60L)
        val resume = event(2, PlaybackEventKind.RESUME, positionSeconds = 400L, fromPositionSeconds = 60L)
        val seekWithoutFrom = event(3, PlaybackEventKind.SEEK, positionSeconds = 400L, fromPositionSeconds = null)

        assertTrue(PlaybackEventPolicy.isUndoCandidate(sourceSwitch))
        assertFalse(PlaybackEventPolicy.isUndoCandidate(resume))
        assertFalse(PlaybackEventPolicy.isUndoCandidate(seekWithoutFrom))
    }

    // --- Compaction --------------------------------------------------------

    @Test
    fun `events beyond the cap are pruned newest-first`() {
        val events = (1..55L).map { event(it, PlaybackEventKind.RESUME, timestamp = it) }

        val prune = PlaybackEventPolicy.pruneIds(events, nowMs = 1000L)

        // The 5 oldest (timestamps 1..5) go; the newest 50 stay.
        assertEquals((1L..5L).toList(), prune.sorted())
    }

    @Test
    fun `stale undo candidates are pruned even inside the cap`() {
        val now = 10_000L
        val stale = event(1, PlaybackEventKind.SEEK, timestamp = now - 25 * 60 * 60 * 1000L) // 25 h old
        val fresh = event(2, PlaybackEventKind.SEEK, timestamp = now - 1_000L)

        val prune = PlaybackEventPolicy.pruneIds(listOf(stale, fresh), nowMs = now)

        assertEquals(listOf(1L), prune)
    }

    @Test
    fun `recent candidates and non-candidate kinds are never stale-pruned`() {
        val now = 10_000L
        val recentSeek = event(1, PlaybackEventKind.SEEK, timestamp = now - 60_000L)
        val oldResume = event(2, PlaybackEventKind.RESUME, timestamp = now - 10 * 24 * 60 * 60 * 1000L)

        val prune = PlaybackEventPolicy.pruneIds(listOf(recentSeek, oldResume), nowMs = now)

        assertTrue("nothing may be pruned", prune.isEmpty())
    }

    @Test
    fun `prune ids are deduplicated when an event is both stale and beyond cap`() {
        val events = (1..55L).map { event(it, PlaybackEventKind.SEEK, timestamp = it) }
        // Newest 50 have timestamps 6..55 (all fresh); only cap pruning applies.
        val prune = PlaybackEventPolicy.pruneIds(events, nowMs = 1000L)

        assertEquals(5, prune.size)
        assertEquals(5, prune.distinct().size)
    }

    @Test
    fun `stale prune and cap prune results are the same set`() {
        // 55 seek candidates, the 5 oldest are both stale-eligible (kind) and
        // beyond cap — the pruned set is exactly the 5 oldest, deduplicated.
        val now = 1000L
        val events = (1..55L).map { event(it, PlaybackEventKind.SEEK, timestamp = it) }

        val prune = PlaybackEventPolicy.pruneIds(events, nowMs = now)

        assertEquals((1L..5L).toList(), prune.sorted())
    }

    // --- Restart offer (spec-16 T3) ----------------------------------------

    @Test
    fun `undo candidate is stale only when seek-like and older than a day`() {
        val now = 100_000L
        val oldSeek = event(1, PlaybackEventKind.SEEK, timestamp = now - 25 * 60 * 60 * 1000L)
        val freshSeek = event(2, PlaybackEventKind.SEEK, timestamp = now - 60_000L)
        val oldResume = event(3, PlaybackEventKind.RESUME, timestamp = now - 10 * 24 * 60 * 60 * 1000L)

        assertTrue(PlaybackEventPolicy.isStaleUndoCandidate(oldSeek, now))
        assertFalse(PlaybackEventPolicy.isStaleUndoCandidate(freshSeek, now))
        assertFalse("non-candidate kinds are never stale", PlaybackEventPolicy.isStaleUndoCandidate(oldResume, now))
    }

    @Test
    fun `listener is at the landing position within the tolerance`() {
        val jump = event(1, PlaybackEventKind.SEEK, positionSeconds = 360L, fromPositionSeconds = 0L)

        assertTrue(PlaybackEventPolicy.isAtUndoPosition(jump, positionSeconds = 360L))
        assertTrue(PlaybackEventPolicy.isAtUndoPosition(jump, positionSeconds = 400L)) // within 60 s
        assertFalse(PlaybackEventPolicy.isAtUndoPosition(jump, positionSeconds = 0L))
        assertFalse(PlaybackEventPolicy.isAtUndoPosition(jump, positionSeconds = 1_800L))
    }

    @Test
    fun `sorted input or not the newest cap is kept by timestamp then id`() {
        // Two events share a timestamp; the higher id wins the newest slot.
        val a = event(1, PlaybackEventKind.RESUME, timestamp = 5L)
        val b = event(2, PlaybackEventKind.RESUME, timestamp = 5L)
        val older = event(3, PlaybackEventKind.RESUME, timestamp = 1L)

        val prune = PlaybackEventPolicy.pruneIds(listOf(a, b, older), cap = 2, nowMs = 1000L)

        assertEquals(listOf(3L), prune)
    }

    @Test
    fun `the state row is not part of the policy input`() {
        // The policy only ever returns event ids; the progress row is a
        // different table and cannot be produced by pruneIds.
        val events = (1..55L).map { event(it, PlaybackEventKind.RESUME, timestamp = it) }

        val prune = PlaybackEventPolicy.pruneIds(events, nowMs = 1000L)

        assertTrue(prune.none { it == 0L })
    }
}
