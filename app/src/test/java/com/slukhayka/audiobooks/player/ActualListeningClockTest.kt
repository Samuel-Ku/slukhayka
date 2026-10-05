package com.slukhayka.audiobooks.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ActualListeningClockTest {
    @Test fun `buffer pause seek and repeated stop never manufacture time`() {
        var now = 100L
        val durations = mutableListOf<Long>()
        val clock = ActualListeningClock({ now }, durations::add)
        clock.update(false)
        now = 200L; clock.flush()
        clock.update(true)
        now = 850L; clock.flush() // seek flush; keep playing
        now = 1000L; clock.update(false)
        now = 3000L; clock.update(false); clock.flush()
        assertEquals(listOf(650L, 150L), durations)
    }
    @Test fun `restart starts a fresh session and repeated playing callbacks do not reset its start`() {
        var now = 0L
        val durations = mutableListOf<Long>()
        val first = ActualListeningClock({ now }, durations::add)
        first.update(true)
        now = 700L; first.update(true)
        now = 900L; first.update(false)
        now = 8000L
        val restarted = ActualListeningClock({ now }, durations::add)
        restarted.update(true)
        now = 8123L; restarted.update(false)
        assertEquals(listOf(900L, 123L), durations)
    }
}
