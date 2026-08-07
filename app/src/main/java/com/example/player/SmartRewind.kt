package com.example.player

/**
 * Smart rewind on resume (wayfinder #25): the longer the pause, the further
 * back playback rewinds so the listener never loses the thread.
 *
 * Tiers from the product vision — a short pause rewinds a couple of seconds,
 * a break of hours rewinds ~12 s, an overnight gap rewinds ~25 s. Sub-second
 * toggles rewind nothing (avoids punishing a quick play/pause double-tap).
 */
object SmartRewind {

    /** Pauses shorter than this rewind nothing. */
    const val NO_REWIND_BELOW_MS: Long = 2_000L

    /** Pauses below this are "short" (2–3 s rewind). */
    const val SHORT_PAUSE_MS: Long = 10 * 60 * 1000L

    /** Pauses below a day are "hours" (10–15 s rewind). */
    const val DAY_MS: Long = 24 * 60 * 60 * 1000L

    const val REWIND_SHORT_SECONDS: Long = 3L
    const val REWIND_MEDIUM_SECONDS: Long = 12L
    const val REWIND_LONG_SECONDS: Long = 25L

    /**
     * Seconds to rewind for a pause of [pauseDurationMs]. Pure and
     * deterministic — unit-tested directly.
     */
    fun computeRewindSeconds(pauseDurationMs: Long): Long = when {
        pauseDurationMs < NO_REWIND_BELOW_MS -> 0L
        pauseDurationMs < SHORT_PAUSE_MS -> REWIND_SHORT_SECONDS
        pauseDurationMs < DAY_MS -> REWIND_MEDIUM_SECONDS
        else -> REWIND_LONG_SECONDS
    }
}
