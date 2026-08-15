package com.example.player

/**
 * Session counters for playback health (wayfinder #52): how many prepares
 * happened, how many failed, and the error-code histogram. Pure JVM so it is
 * unit-testable without Robolectric. Feeds the debug overlay and the
 * exported journal.
 */
class PlaybackMetrics {

    private var attemptCount = 0
    private var failureCount = 0
    private val failureByCode = LinkedHashMap<String, Int>()

    @Synchronized
    fun recordAttempt() {
        attemptCount++
    }

    @Synchronized
    fun recordFailure(errorCodeName: String) {
        failureCount++
        failureByCode[errorCodeName] = (failureByCode[errorCodeName] ?: 0) + 1
    }

    @Synchronized
    fun attempts(): Int = attemptCount

    @Synchronized
    fun failures(): Int = failureCount

    /** Failure rate 0f..1f; 0 when nothing was attempted yet. */
    @Synchronized
    fun failureRate(): Float =
        if (attemptCount == 0) 0f else failureCount.toFloat() / attemptCount

    @Synchronized
    fun failureByCode(): Map<String, Int> = failureByCode.toMap()

    /** One-line summary, e.g. `attempts=12 failures=3 rate=25% | IO:2 TIMEOUT:1`. */
    @Synchronized
    fun export(): String {
        val codes = failureByCode.entries.joinToString(" ") { "${it.key.substringBefore("_").take(4)}:${it.value}" }
        val rate = ((failureRate() * 100)).toInt()
        return "attempts=$attemptCount failures=$failureCount rate=$rate%" + if (codes.isNotEmpty()) " | $codes" else ""
    }
}