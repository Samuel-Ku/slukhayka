package com.example.player

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of recent playback events (wayfinder #52) for the
 * diagnostic overlay and post-mortems. Never persisted; the durable ledger of
 * *failures* lives in Room via [ListeningStateStore.recordPlaybackFailure].
 * Pure JVM so it is unit-testable without Robolectric.
 */
class PlaybackEventLog(private val capacity: Int = DEFAULT_CAPACITY) {

    private val events = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    @Synchronized
    fun record(message: String) {
        val stamped = "${clock.format(Date())} $message"
        events.addLast(stamped)
        while (events.size > capacity) events.removeFirst()
    }

    /** Most recent events first, at most [max]. */
    @Synchronized
    fun recent(max: Int = capacity): List<String> =
        events.toList().takeLast(max).reversed()

    /** Whole ring buffer, oldest first — the paste-into-bug-report payload. */
    @Synchronized
    fun export(): String = events.joinToString("\n")

    @Synchronized
    fun size(): Int = events.size

    companion object {
        const val DEFAULT_CAPACITY = 50
    }
}