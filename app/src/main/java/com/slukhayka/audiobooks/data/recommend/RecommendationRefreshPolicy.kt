package com.slukhayka.audiobooks.data.recommend

/**
 * Spec-19 / issue #481 — the pure "should the recommendations recompute?"
 * policy. The row re-ranks on real SIGNAL BOUNDARIES and inputs, never on an
 * intermediate playback-progress tick: a Work becomes a signal when the
 * listener starts it, finishes it or favourites it — not on every 5-second
 * position save.
 *
 * The policy is a pure JVM function so the whole trigger discipline is
 * testable with virtual time; the IO glue (the flow pipeline) is a separate
 * seam and is not part of this file.
 */
object RecommendationRefreshPolicy {

    /**
     * The refresh-relevant shape of one library Work. Deliberately excludes
     * the progress fraction and the last-listened timestamp: those change
     * every few seconds and must not move the key.
     */
    data class WorkSignal(
        val id: String,
        val isFavorite: Boolean,
        val started: Boolean,
        val completed: Boolean
    )

    /**
     * A stable key over the library's signal boundaries. Two calls that
     * differ only by intermediate progress produce the SAME key, so a
     * `distinctUntilChanged` gate drops the tick.
     */
    fun libraryKey(signals: List<WorkSignal>): String =
        signals.sortedBy { it.id }
            .joinToString(separator = "\n") { signal ->
                buildString {
                    append(signal.id)
                    append(':')
                    append(if (signal.isFavorite) '1' else '0')
                    append(if (signal.started) '1' else '0')
                    append(if (signal.completed) '1' else '0')
                }
            }
}
