package com.slukhayka.audiobooks.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #700 (T2) — the cap must not evict the facts an AWARD is built on.
 *
 * «Друге дихання» needs two `COMPLETED` rows of one book to exist side by side,
 * but a bucket holds only [PlaybackEventPolicy.DEFAULT_EVENTS_PER_BOOK_SOURCE]
 * rows and the second pass fills it with its own `CHAPTER_CHANGE` rows. These
 * tests pin the exemption and — just as important — that the cap for every other
 * kind is exactly what it was.
 */
class PlaybackEventCompletionRetentionTest {

    private val cap = PlaybackEventPolicy.DEFAULT_EVENTS_PER_BOOK_SOURCE

    private fun event(
        id: Long,
        kind: String,
        timestamp: Long,
        bookId: String = "b1",
        sourceKey: String = ""
    ) = PlaybackEventEntity(
        id = id,
        bookId = bookId,
        sourceKey = sourceKey,
        kind = kind,
        timestamp = timestamp
    )

    /** Fresh enough that age pruning can never be the reason for a prune. */
    private val now = 10_000_000L

    @Test
    fun `two newest completions survive fifty newer events`() {
        // Two completions first, then a whole second pass on top of them.
        val events = listOf(
            event(1, PlaybackEventKind.COMPLETED, timestamp = 1L),
            event(2, PlaybackEventKind.COMPLETED, timestamp = 2L)
        ) + (3L..52L).map { event(it, PlaybackEventKind.CHAPTER_CHANGE, timestamp = it) }

        val prune = PlaybackEventPolicy.pruneIds(events, cap = cap, nowMs = now)

        assertFalse("перша COMPLETED не має витіснятись", 1L in prune)
        assertFalse("друга COMPLETED не має витіснятись", 2L in prune)
        assertEquals("бакет тримає ковпак плюс два винятки", cap + 2, events.size - prune.size)
    }

    @Test
    fun `the rest of the bucket is still pruned to the cap`() {
        // 60 ordinary events on top of the two completions: the bucket must
        // still settle at `cap + 2`, so exactly the ten oldest RESUME go.
        val events = listOf(
            event(1, PlaybackEventKind.COMPLETED, timestamp = 1L),
            event(2, PlaybackEventKind.COMPLETED, timestamp = 2L)
        ) + (3L..62L).map { event(it, PlaybackEventKind.RESUME, timestamp = it) }

        val prune = PlaybackEventPolicy.pruneIds(events, cap = cap, nowMs = now)

        assertEquals("старі RESUME ріжуться як і раніше", (3L..12L).toList(), prune.sorted())
        assertEquals("бакет тримає ковпак плюс два винятки", cap + 2, events.size - prune.size)
    }

    @Test
    fun `an older completion is still pruned`() {
        // Three completions: the newest pair must stay, the third is ordinary
        // history and goes with the rest of what is beyond the cap.
        val events = (1L..3L).map { event(it, PlaybackEventKind.COMPLETED, timestamp = it) } +
            (4L..58L).map { event(it, PlaybackEventKind.PAUSE, timestamp = it) }

        val prune = PlaybackEventPolicy.pruneIds(events, cap = cap, nowMs = now)

        assertTrue("третя (найстаріша) COMPLETED мусить піти", 1L in prune)
        assertFalse("друга COMPLETED лишається", 2L in prune)
        assertFalse("третя за новизною COMPLETED лишається", 3L in prune)
    }

    @Test
    fun `a lone completion is not evicted either`() {
        // Fewer than the protected two: whatever exists must survive, since the
        // award is the reason the row is worth keeping at all.
        val events = listOf(event(1, PlaybackEventKind.COMPLETED, timestamp = 1L)) +
            (2L..56L).map { event(it, PlaybackEventKind.PAUSE, timestamp = it) }

        val prune = PlaybackEventPolicy.pruneIds(events, cap = cap, nowMs = now)

        assertFalse("єдина COMPLETED лишається", 1L in prune)
        assertEquals("решта бакета — рівно ковпак", cap + 1, events.size - prune.size)
    }

    @Test
    fun `the exemption is two rows, not a growing window`() {
        // A long log with many completions: only the newest pair may escape the
        // cap, so the bucket is `cap + 2` and never more.
        val events = (1L..5L).map { event(it, PlaybackEventKind.COMPLETED, timestamp = it) } +
            (6L..205L).map { event(it, PlaybackEventKind.CHAPTER_CHANGE, timestamp = it) }

        val prune = PlaybackEventPolicy.pruneIds(events, cap = cap, nowMs = now)

        assertEquals("рівно два винятки", cap + 2, events.size - prune.size)
        assertFalse("найновіша COMPLETED лишається", 4L in prune)
        assertFalse("друга за новизною COMPLETED лишається", 5L in prune)
        assertEquals("решта COMPLETED ріжуться", listOf(1L, 2L, 3L), prune.filter { it in 1L..5L }.sorted())
    }
}
