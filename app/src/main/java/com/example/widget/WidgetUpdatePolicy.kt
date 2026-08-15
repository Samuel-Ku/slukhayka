package com.example.widget

/**
 * Spec-17 (#110): the widget's refresh policy — pure logic, pinned by JVM tests.
 *
 * Battery guarantee (spec: "the progress bar refreshes on a 15-second cadence
 * only while playing; paused/stopped schedules no updates"):
 *  - state changes (play/pause/track) refresh immediately;
 *  - while playing the progress refreshes on [PROGRESS_REFRESH_INTERVAL_MS];
 *  - while paused/stopped (or without a book) nothing is scheduled.
 */
object WidgetUpdatePolicy {

    /** Progress refresh cadence while playing. */
    const val PROGRESS_REFRESH_INTERVAL_MS = 15_000L

    /** The fields whose change means "state change" for refresh purposes. */
    fun hasStateChanged(previous: WidgetModel, current: WidgetModel): Boolean =
        previous.title != current.title ||
            previous.isPlaying != current.isPlaying ||
            previous.hasBook != current.hasBook

    /**
     * Decision function from the spec: (widget state, last refresh, now) →
     * refresh or not.
     */
    fun shouldRefresh(
        previous: WidgetModel?,
        current: WidgetModel,
        lastRefreshMs: Long,
        nowMs: Long,
    ): Boolean =
        previous == null ||
            hasStateChanged(previous, current) ||
            (current.isPlaying && current.hasBook &&
                nowMs - lastRefreshMs >= PROGRESS_REFRESH_INTERVAL_MS)

    /**
     * How long until the next refresh, or null when nothing should be
     * scheduled (paused/stopped/no book).
     */
    fun nextRefreshDelayMs(
        current: WidgetModel,
        lastRefreshMs: Long,
        nowMs: Long,
    ): Long? {
        if (!current.isPlaying || !current.hasBook) return null
        val elapsed = nowMs - lastRefreshMs
        return if (elapsed >= PROGRESS_REFRESH_INTERVAL_MS) 0L
        else PROGRESS_REFRESH_INTERVAL_MS - elapsed
    }
}