package com.slukhayka.audiobooks.player

import androidx.media3.datasource.HttpDataSource

/**
 * Spec-32 T4 (#234) — the pure self-healing decision for a failed stream:
 * a 404/403 during playback means the source page moved or the CDN blocked
 * the file, so the player re-fetches the page ONCE, swaps in the fresh URL
 * and retries; any other status (server error, network, timeouts) surfaces
 * the honest failure immediately, and an exhausted heal budget never retries
 * a second time in the same chapter prepare.
 *
 * The 404/403 → refetch → retry decision is deliberately a pure function
 * (JVM-tested in isolation, spec-32 Testing Decisions): [shouldHeal] decides
 * from the status + the attempt counter, and [responseCodeOf] extracts the
 * status from the PlaybackException cause chain (the real ExoPlayer failure
 * is wrapped several layers deep — `PlaybackException` →
 * `HttpDataSourceException` → `InvalidResponseCodeException`).
 */
object StreamHealPolicy {

    /** One retry with the fresh URL per chapter prepare — no heal loops. */
    const val MAX_HEAL_ATTEMPTS = 1

    /** HTTP Forbidden — the CDN refused the stream (blocked/moved file). */
    const val HTTP_FORBIDDEN = 403

    /** HTTP Not Found — the stream file moved or was deleted. */
    const val HTTP_NOT_FOUND = 404

    /**
     * The HTTP status of the stream failure, or null when the error chain
     * carries none (timeouts, network failures, decoder errors). Walks the
     * whole cause chain so a wrapped ExoPlayer failure still yields its code.
     */
    fun responseCodeOf(error: Throwable?): Int? {
        var current = error
        while (current != null) {
            if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
            current = current.cause
        }
        return null
    }

    /**
     * Whether a stream failure heals: only a 404/403 — a moved or blocked
     * file — and only while the heal budget for the current chapter prepare
     * is not exhausted.
     */
    fun shouldHeal(responseCode: Int?, healAttempts: Int): Boolean =
        (responseCode == HTTP_FORBIDDEN || responseCode == HTTP_NOT_FOUND) &&
            healAttempts < MAX_HEAL_ATTEMPTS

    /**
     * The mirror of [shouldHeal] for the honest failure path: a 404/403 that
     * already spent the whole budget is a dead file — the player reports
     * «book unavailable» instead of a generic stream error. Kept next to
     * [shouldHeal] so the two can never drift apart (the budget is one).
     */
    fun budgetExhausted(responseCode: Int?, healAttempts: Int): Boolean =
        (responseCode == HTTP_FORBIDDEN || responseCode == HTTP_NOT_FOUND) &&
            healAttempts >= MAX_HEAL_ATTEMPTS
}