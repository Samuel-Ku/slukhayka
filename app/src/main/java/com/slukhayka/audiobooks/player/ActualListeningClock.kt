package com.slukhayka.audiobooks.player

/** Monotonic wall time from actual playback, independent of position and speed. */
class ActualListeningClock(private val now: () -> Long, private val record: (Long) -> Unit) {
    private var startedAt: Long? = null
    fun update(isPlaying: Boolean) {
        if (isPlaying) {
            if (startedAt == null) startedAt = now()
        } else {
            flush()
            startedAt = null
        }
    }
    /** Checkpoint a running interval without counting it twice. */
    fun flush() {
        val start = startedAt ?: return
        val end = now()
        startedAt = end
        val duration = end - start
        if (duration > 0L) record(duration)
    }
}
