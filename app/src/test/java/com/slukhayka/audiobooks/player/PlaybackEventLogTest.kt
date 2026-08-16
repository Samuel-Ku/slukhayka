package com.slukhayka.audiobooks.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEventLogTest {

    @Test
    fun `records events in order with a timestamp prefix`() {
        val log = PlaybackEventLog(capacity = 3)

        log.record("PREPARE ch0 url")
        log.record("FAIL TEST_CODE url")

        val events = log.recent()
        assertEquals(2, events.size)
        assertTrue(events[0].matches(Regex("""^\d{2}:\d{2}:\d{2}\.\d{3} FAIL TEST_CODE url$""")))
        assertTrue(events[0].endsWith("FAIL TEST_CODE url"))
        assertEquals("PREPARE ch0 url", events[1].substringAfter(" "))
    }

    @Test
    fun `ring buffer drops the oldest events beyond capacity`() {
        val log = PlaybackEventLog(capacity = 3)

        repeat(5) { log.record("event-$it") }

        assertEquals(3, log.size())
        val recent = log.recent()
        assertEquals("event-4", recent[0].substringAfter(" "))
        assertEquals("event-2", recent.last().substringAfter(" "))
    }

    @Test
    fun `export joins the whole buffer oldest first`() {
        val log = PlaybackEventLog(capacity = 10)
        log.record("a")
        log.record("b")

        val exported = log.export().lines()
        assertEquals(2, exported.size)
        assertTrue(exported[0].endsWith("a"))
        assertTrue(exported[1].endsWith("b"))
    }

    @Test
    fun `recent limits the number of events`() {
        val log = PlaybackEventLog(capacity = 10)
        repeat(5) { log.record("e$it") }

        assertEquals(2, log.recent(max = 2).size)
    }
}