package com.slukhayka.audiobooks.widget

/** Why the playback sampler would repaint the widget; [NONE] keeps the frame. */
internal enum class WidgetUpdateKind { NONE, DISCRETE, PROGRESS }

/**
 * #1749 — decides when a playback observation deserves a widget repaint.
 *
 * The old sampler repainted every 2 s, which is expensive and still never
 * restarted `provideGlance` inside a live session. Here the discrete moments a
 * listener notices (play/pause, buffering, book/chapter change, seek) repaint
 * immediately, while an unchanged playing stream is allowed at most one
 * progress repaint per [progressIntervalMs].
 *
 * Every observation advances the comparison baseline, whether or not it was
 * published; otherwise 1 s position ticks would look like a 15 s seek.
 */
internal class WidgetUpdateCadence(
    private val progressIntervalMs: Long = DEFAULT_PROGRESS_INTERVAL_MS
) {

    private var previous: GlanceWidgetState? = null
    private var previousObservedAtMs: Long = 0L
    private var lastPublishedAtMs: Long = 0L

    fun decide(next: GlanceWidgetState, nowMs: Long): WidgetUpdateKind {
        val before = previous
        val elapsedMs = if (before == null) 0L else (nowMs - previousObservedAtMs).coerceAtLeast(0L)
        previous = next
        previousObservedAtMs = nowMs

        if (before == null) return publish(nowMs, WidgetUpdateKind.DISCRETE)
        if (hasDiscreteTransition(before, next)) return publish(nowMs, WidgetUpdateKind.DISCRETE)
        if (isSeek(before, next, elapsedMs)) return publish(nowMs, WidgetUpdateKind.DISCRETE)

        val sincePublish = (nowMs - lastPublishedAtMs).coerceAtLeast(0L)
        val isPlaying = next.hasActiveBook && next.isPlaying && !next.isBuffering
        if (isPlaying && sincePublish >= progressIntervalMs) {
            return publish(nowMs, WidgetUpdateKind.PROGRESS)
        }
        return WidgetUpdateKind.NONE
    }

    private fun publish(nowMs: Long, kind: WidgetUpdateKind): WidgetUpdateKind {
        lastPublishedAtMs = nowMs
        return kind
    }

    private fun hasDiscreteTransition(
        before: GlanceWidgetState,
        after: GlanceWidgetState
    ): Boolean =
        before.hasActiveBook != after.hasActiveBook ||
            before.bookId != after.bookId ||
            before.title != after.title ||
            before.author != after.author ||
            before.chapterTitle != after.chapterTitle ||
            before.isPlaying != after.isPlaying ||
            before.isBuffering != after.isBuffering ||
            before.durationSeconds != after.durationSeconds

    /**
     * A jump the progress tracker cannot explain. Playback speed is capped at
     * 3x, so anything past `elapsed * 3 + slack` is a seek; a backwards jump
     * past the jitter allowance is one too.
     */
    private fun isSeek(
        before: GlanceWidgetState,
        after: GlanceWidgetState,
        elapsedMs: Long
    ): Boolean {
        val deltaSeconds = after.currentPositionSeconds - before.currentPositionSeconds
        if (deltaSeconds <= -SEEK_BACK_SLACK_SECONDS) return true
        val forwardAllowance = elapsedMs / 1000L * MAX_EXPECTED_SPEED + SEEK_FORWARD_SLACK_SECONDS
        return deltaSeconds > forwardAllowance
    }

    companion object {
        /** Inside the 10–30 s band from the audit: cheaper than 2 s, still live. */
        const val DEFAULT_PROGRESS_INTERVAL_MS = 15_000L
        private const val SEEK_BACK_SLACK_SECONDS = 3L
        private const val SEEK_FORWARD_SLACK_SECONDS = 5L
        private const val MAX_EXPECTED_SPEED = 3L
    }
}
