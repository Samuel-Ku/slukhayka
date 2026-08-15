package com.example.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackMetricsTest {

    @Test
    fun `starts empty with zero rate`() {
        val metrics = PlaybackMetrics()

        assertEquals(0, metrics.attempts())
        assertEquals(0, metrics.failures())
        assertEquals(0f, metrics.failureRate())
        assertEquals(emptyMap<String, Int>(), metrics.failureByCode())
    }

    @Test
    fun `failure rate is failures over attempts`() {
        val metrics = PlaybackMetrics()
        metrics.recordAttempt()
        metrics.recordAttempt()
        metrics.recordAttempt()
        metrics.recordAttempt()
        metrics.recordFailure("ERROR_CODE_IO_UNSPECIFIED")

        assertEquals(0.25f, metrics.failureRate())
    }

    @Test
    fun `error codes histogram counts per code`() {
        val metrics = PlaybackMetrics()
        metrics.recordFailure("ERROR_CODE_IO_UNSPECIFIED")
        metrics.recordFailure("ERROR_CODE_IO_UNSPECIFIED")
        metrics.recordFailure("PREPARE_TIMEOUT")

        assertEquals(2, metrics.failureByCode()["ERROR_CODE_IO_UNSPECIFIED"])
        assertEquals(1, metrics.failureByCode()["PREPARE_TIMEOUT"])
    }

    @Test
    fun `export summarizes counters and codes`() {
        val metrics = PlaybackMetrics()
        metrics.recordAttempt()
        metrics.recordFailure("PREPARE_TIMEOUT")

        val line = metrics.export()
        assertEquals("attempts=1 failures=1 rate=100% | PREP:1", line)
    }
}