package com.slukhayka.audiobooks.data.availability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-56 T3 (#730) — the priority queue of availability checks: visible
 * cards first, the backlog after, each key queued once, insertion order as
 * the tie-break, and the daily scan due at most once per interval.
 */
class AvailabilityCheckQueueTest {

    @Test
    fun `an empty queue yields nothing`() {
        val queue = AvailabilityCheckQueue()
        assertTrue(queue.isEmpty())
        assertNull(queue.next())
    }

    @Test
    fun `visible cards are served before the backlog`() {
        val queue = AvailabilityCheckQueue()
        queue.requestBacklog(listOf("backlog-1", "backlog-2"))
        queue.requestVisible(listOf("visible-1"))

        assertEquals("visible-1", queue.next())
        assertEquals("backlog-1", queue.next())
        assertEquals("backlog-2", queue.next())
        assertNull(queue.next())
    }

    @Test
    fun `a key is queued at most once`() {
        val queue = AvailabilityCheckQueue()
        queue.requestVisible(listOf("key", "key"))
        queue.requestBacklog(listOf("key"))

        assertEquals(1, queue.size)
        assertEquals("key", queue.next())
        assertNull(queue.next())
    }

    @Test
    fun `a visible request promotes a pending backlog entry`() {
        val queue = AvailabilityCheckQueue()
        queue.requestBacklog(listOf("backlog-1", "shared", "backlog-2"))
        queue.requestVisible(listOf("shared"))

        assertEquals("shared", queue.next())
        assertEquals("backlog-1", queue.next())
        assertEquals("backlog-2", queue.next())
    }

    @Test
    fun `insertion order breaks ties inside one priority`() {
        val queue = AvailabilityCheckQueue()
        queue.requestBacklog(listOf("first", "second", "third"))

        assertEquals("first", queue.next())
        assertEquals("second", queue.next())
        assertEquals("third", queue.next())
    }

    @Test
    fun `blank keys are never queued`() {
        val queue = AvailabilityCheckQueue()
        queue.requestVisible(listOf("", "  ", "real"))
        assertEquals(1, queue.size)
        assertEquals("real", queue.next())
    }

    @Test
    fun `clear drops everything`() {
        val queue = AvailabilityCheckQueue()
        queue.requestVisible(listOf("key"))
        queue.clear()
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `the daily scan is due on a fresh install and after the interval`() {
        assertTrue(AvailabilityDailyScan.isDue(lastScanAtMs = 0L, nowMs = 1_000L))
        assertFalse(
            AvailabilityDailyScan.isDue(
                lastScanAtMs = 1_000L,
                nowMs = 1_000L + AvailabilityDailyScan.INTERVAL_MS - 1L
            )
        )
        assertTrue(
            AvailabilityDailyScan.isDue(
                lastScanAtMs = 1_000L,
                nowMs = 1_000L + AvailabilityDailyScan.INTERVAL_MS
            )
        )
    }
}
