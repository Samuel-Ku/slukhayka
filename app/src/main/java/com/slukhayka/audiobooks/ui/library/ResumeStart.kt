package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.player.SmartRewind

/**
 * Where a playback session starts: the chapter index and the position inside
 * it, in seconds.
 */
data class ResumeStart(
    val chapterIndex: Int,
    val positionSeconds: Long
)

/**
 * ADR-0008 — the ONE pure resume decision, shared by every start path.
 *
 * Given the user's explicit chapter request (null when resuming the book
 * normally), the persisted progress row and the current wall clock, decide
 * where playback starts:
 *
 *  - an explicit chapter request starts at its saved position only when the
 *    saved progress is in that same chapter, else at 0;
 *  - a normal resume starts at the saved chapter/position (0 when nothing was
 *    ever saved);
 *  - the ADR-0003 smart rewind then applies to any start: the longer the
 *    pause since the book was paused, the further back the position rewinds
 *    (clamped at zero). A row without a pause marker rewinds nothing.
 *
 * The caller owns the side effect that pairs with the rewind — clearing the
 * pause marker so the same pause never rewinds twice.
 */
fun computeResumeStart(
    requestedChapter: Int?,
    progress: PlaybackProgressEntity?,
    nowEpochMs: Long
): ResumeStart {
    val startChapter = requestedChapter ?: progress?.currentChapterIndex ?: 0
    val startPositionSec = when {
        requestedChapter != null ->
            if (progress != null && progress.currentChapterIndex == requestedChapter) {
                progress.currentPositionSeconds
            } else 0L
        else -> progress?.currentPositionSeconds ?: 0L
    }
    val rewound = progress?.lastPausedAtEpochMs?.let { pausedAt ->
        SmartRewind.rewoundPositionMs(
            startPositionSec * 1000L,
            nowEpochMs - pausedAt
        ) / 1000L
    } ?: startPositionSec
    return ResumeStart(startChapter, rewound)
}
