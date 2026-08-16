package com.slukhayka.audiobooks.data.db

import kotlin.math.abs

/**
 * Capture-time noise filter for the playback event log (spec-16 T2): which
 * transitions are worth recording at all. Pure functions of the player's
 * timestamps — the player asks before it records, so periodic position ticks
 * (they never call this) and sub-threshold seeks never touch the log.
 *
 * Rules:
 * - A seek is worth recording only when it jumped at least the 5-minute
 *   threshold (the same one SeekHistory uses, wayfinder #25).
 * - A pause is worth recording only after a listening segment of at least a
 *   minute — quick play/pause toggles are noise, not history.
 * - A resume is worth recording only after a break of at least a minute, or
 *   when there was no pause at all (a fresh start).
 */
object PlaybackEventFilter {

    /** A play/pause cycle shorter than this is a toggle, not a session. */
    const val MIN_LISTENING_SEGMENT_MS: Long = 60_000L

    /** Big jump = the SeekHistory threshold (one constant, no divergence). */
    fun shouldRecordSeek(fromPositionMs: Long, toPositionMs: Long): Boolean =
        abs(toPositionMs - fromPositionMs) >= PlaybackEventPolicy.SEEK_JUMP_THRESHOLD_MS

    /** Pause after a listening segment of ≥ 1 minute. */
    fun shouldRecordPause(segmentStartMs: Long?, nowMs: Long): Boolean =
        segmentStartMs != null && nowMs - segmentStartMs >= MIN_LISTENING_SEGMENT_MS

    /** Resume after a break of ≥ 1 minute, or a fresh start (no prior pause). */
    fun shouldRecordResume(lastPauseMs: Long?, nowMs: Long): Boolean =
        lastPauseMs == null || nowMs - lastPauseMs >= MIN_LISTENING_SEGMENT_MS
}
