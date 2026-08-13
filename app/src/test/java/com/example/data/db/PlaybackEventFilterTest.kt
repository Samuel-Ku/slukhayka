package com.example.data.db

import com.example.player.SeekHistory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the capture-time noise filter (spec-16 T2). */
class PlaybackEventFilterTest {

    private val thresholdMs = SeekHistory.DEFAULT_JUMP_THRESHOLD_MS
    private val minuteMs = PlaybackEventFilter.MIN_LISTENING_SEGMENT_MS

    // --- Seeks -------------------------------------------------------------

    @Test
    fun `seek at the five-minute threshold is recorded`() {
        assertTrue(PlaybackEventFilter.shouldRecordSeek(0L, thresholdMs))
        assertTrue(PlaybackEventFilter.shouldRecordSeek(thresholdMs, 0L))
        assertTrue(PlaybackEventFilter.shouldRecordSeek(1_000L, thresholdMs + 1_000L))
    }

    @Test
    fun `sub-threshold and zero seeks are noise`() {
        assertFalse(PlaybackEventFilter.shouldRecordSeek(0L, thresholdMs - 1))
        assertFalse(PlaybackEventFilter.shouldRecordSeek(30_000L, 45_000L))
        assertFalse(PlaybackEventFilter.shouldRecordSeek(100L, 100L))
    }

    // --- Pauses ------------------------------------------------------------

    @Test
    fun `pause without a known segment start is noise`() {
        assertFalse(PlaybackEventFilter.shouldRecordPause(segmentStartMs = null, nowMs = 60_000L))
    }

    @Test
    fun `pause before a minute of listening is noise`() {
        assertFalse(PlaybackEventFilter.shouldRecordPause(segmentStartMs = 0L, nowMs = minuteMs - 1))
    }

    @Test
    fun `pause after a minute of listening is recorded`() {
        assertTrue(PlaybackEventFilter.shouldRecordPause(segmentStartMs = 0L, nowMs = minuteMs))
        assertTrue(PlaybackEventFilter.shouldRecordPause(segmentStartMs = 0L, nowMs = minuteMs + 1))
    }

    // --- Resumes -----------------------------------------------------------

    @Test
    fun `fresh start with no prior pause is recorded`() {
        assertTrue(PlaybackEventFilter.shouldRecordResume(lastPauseMs = null, nowMs = 0L))
    }

    @Test
    fun `resume after a quick toggle is noise`() {
        assertFalse(PlaybackEventFilter.shouldRecordResume(lastPauseMs = 0L, nowMs = minuteMs - 1))
    }

    @Test
    fun `resume after a minute break is recorded`() {
        assertTrue(PlaybackEventFilter.shouldRecordResume(lastPauseMs = 0L, nowMs = minuteMs))
        assertTrue(PlaybackEventFilter.shouldRecordResume(lastPauseMs = 0L, nowMs = minuteMs + 1))
    }
}
