package com.slukhayka.audiobooks.player

import com.slukhayka.audiobooks.data.db.PlaybackEventPolicy
import kotlin.math.abs

/** A recorded potentially-accidental jump: the position the listener was at before it. */
data class SeekJump(
    val fromPositionMs: Long,
    val toPositionMs: Long,
    val chapterIndex: Int
)

/**
 * Position history for the "Повернутися" (undo) action (wayfinder #25): when a
 * seek jumps more than [jumpThresholdMs], the pre-jump position is remembered
 * so the UI can offer to jump back. Only the latest jump is kept — the
 * listener can always undo once.
 */
class SeekHistory(
    private val jumpThresholdMs: Long = DEFAULT_JUMP_THRESHOLD_MS
) {

    private var jump: SeekJump? = null

    /** The latest recorded jump, or null when there is nothing to undo. */
    val lastJump: SeekJump? get() = jump

    /** Whether an undoable jump exists right now. */
    fun canUndo(): Boolean = jump != null

    /**
     * Records a seek if it was big enough to be a plausible accident
     * ([jumpThresholdMs] or more). Smaller seeks are left alone.
     */
    fun recordSeek(fromPositionMs: Long, toPositionMs: Long, chapterIndex: Int) {
        if (abs(toPositionMs - fromPositionMs) >= jumpThresholdMs) {
            jump = SeekJump(fromPositionMs, toPositionMs, chapterIndex)
        }
    }

    /** Returns and clears the recorded jump, if any. */
    fun consumeUndo(): SeekJump? {
        val found = jump
        jump = null
        return found
    }

    /**
     * Seeds a jump from the persisted event log (spec-16 T3): a restart finds
     * its undo candidate again. Unconditional — the caller (the player) has
     * already vetted the candidate (threshold, staleness, landing position).
     */
    fun restore(jump: SeekJump) {
        this.jump = jump
    }

    /** Drops any remembered jump — a fresh listening cycle starts clean. */
    fun clear() {
        jump = null
    }

    companion object {
        /**
         * A seek of 5+ minutes is treated as a potentially accidental jump.
         * ADR-0003: reads the canonical threshold from the data layer
         * ([PlaybackEventPolicy]) — the player never owns the number.
         */
        const val DEFAULT_JUMP_THRESHOLD_MS: Long = PlaybackEventPolicy.SEEK_JUMP_THRESHOLD_MS
    }
}
