package com.example.player

import android.content.Context

class PlaybackSettings(private val context: Context?) {
    var defaultSpeed: Float = 1.0f
}

class PlaybackEventLog {
    private val logList = mutableListOf<String>()
    
    fun log(event: String) {
        synchronized(logList) {
            logList.add(event)
            if (logList.size > 200) logList.removeAt(0)
        }
    }

    fun recent(count: Int = 10): List<String> {
        return synchronized(logList) { logList.takeLast(count) }
    }

    fun export(): String {
        return synchronized(logList) { logList.joinToString("\n") }
    }
    
    fun getLog(): List<String> {
        return synchronized(logList) { logList.toList() }
    }

    fun recordAttempt(bookId: String) {
        log("ATTEMPT: $bookId")
    }

    fun record(event: Any, positionSeconds: Long = 0L) {
        log("EVENT: $event @ ${positionSeconds}s")
    }
}

class PlaybackMetrics {
    var successCount: Int = 0
        private set
    var failureCount: Int = 0
        private set

    fun recordAttempt() {}
    fun recordSuccess() { successCount++ }
    fun recordFailure(bookId: String = "", error: String = "") { failureCount++ }
    fun record(event: Any) {
        successCount++
    }

    fun export(): String {
        return "Successes: $successCount, Failures: $failureCount"
    }
}

data class SeekJump(
    val fromPositionMs: Long,
    val targetPositionMs: Long,
    val chapterIndex: Int
) {
    val toPositionMs: Long get() = targetPositionMs
}

class SeekHistory {
    private var _lastJump: SeekJump? = null
    val lastJump: SeekJump? get() = _lastJump

    fun recordSeek(fromMs: Long, targetMs: Long, chapterIdx: Int) {
        if (kotlin.math.abs(targetMs - fromMs) >= 10_000L) {
            push(SeekJump(fromMs, targetMs, chapterIdx))
        }
    }

    fun canUndo(): Boolean = _lastJump != null
    fun restore(jump: SeekJump) { _lastJump = jump }
    fun pop(): SeekJump? {
        val j = _lastJump
        _lastJump = null
        return j
    }
    fun push(jump: SeekJump) { _lastJump = jump }
    fun clear() { _lastJump = null }
    fun consumeUndo(): SeekJump? = pop()
}

object SmartRewind {
    fun computeRewindSeconds(pauseDurationMs: Long): Long {
        if (pauseDurationMs < 10_000L) return 0L
        if (pauseDurationMs < 60_000L) return 3L
        if (pauseDurationMs < 300_000L) return 10L
        return 20L
    }
}
