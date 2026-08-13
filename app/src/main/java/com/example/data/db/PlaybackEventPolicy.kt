package com.example.data.db

import com.example.player.SeekHistory
import kotlin.math.abs

/**
 * Pure rules of the playback event log (spec-16, wayfinder #53): what counts
 * as an undo candidate and when the log is pruned. Every rule here is a
 * function of the events — no I/O — so the JVM suite pins each one without
 * Robolectric.
 *
 * The state row ([PlaybackProgressEntity]) is never touched by these rules:
 * compaction prunes history only.
 */
object PlaybackEventPolicy {

    /** How many events one (book, source) may keep before FIFO pruning. */
    const val DEFAULT_EVENTS_PER_BOOK_SOURCE: Int = 50

    /** An undo candidate older than this is noise — pruning yesterday's jump. */
    const val UNDO_CANDIDATE_MAX_AGE_MS: Long = 24 * 60 * 60 * 1000L

    /**
     * A seek is undoable only if it jumped at least this far. Reuses the
     * SeekHistory threshold (wayfinder #25) — one constant, no divergence.
     */
    val SEEK_JUMP_THRESHOLD_MS: Long = SeekHistory.DEFAULT_JUMP_THRESHOLD_MS

    /** Kinds that carry a from-position and may therefore be undo candidates. */
    fun isUndoCandidateKind(kind: String): Boolean =
        kind == PlaybackEventKind.SEEK || kind == PlaybackEventKind.SOURCE_SWITCH

    /**
     * Whether [event] is a valid undo candidate: right kind, a known
     * from-position, and a jump of at least [thresholdMs]. Milliseconds vs
     * the stored seconds — the threshold is the SeekHistory constant.
     */
    fun isUndoCandidate(event: PlaybackEventEntity, thresholdMs: Long = SEEK_JUMP_THRESHOLD_MS): Boolean {
        if (!isUndoCandidateKind(event.kind)) return false
        val from = event.fromPositionSeconds ?: return false
        return abs(event.positionSeconds - from) * 1000L >= thresholdMs
    }

    /**
     * The ids to prune for one (book, source): everything beyond the newest
     * [cap] (FIFO by timestamp, then id) plus any undo-candidate older than
     * [maxAgeMs]. [events] is the bucket newest-first, as the DAO returns it.
     * The state row is not this function's concern.
     */
    fun pruneIds(
        events: List<PlaybackEventEntity>,
        cap: Int = DEFAULT_EVENTS_PER_BOOK_SOURCE,
        nowMs: Long,
        maxAgeMs: Long = UNDO_CANDIDATE_MAX_AGE_MS
    ): List<Long> {
        val ordered = events.sortedWith(compareByDescending<PlaybackEventEntity> { it.timestamp }.thenByDescending { it.id })
        val beyondCap = ordered.drop(cap).map { it.id }
        val stale = ordered.filter { isUndoCandidateKind(it.kind) && nowMs - it.timestamp > maxAgeMs }.map { it.id }
        return (beyondCap + stale).distinct()
    }
}
