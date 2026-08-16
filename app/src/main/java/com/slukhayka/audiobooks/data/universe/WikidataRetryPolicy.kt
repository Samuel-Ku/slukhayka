package com.slukhayka.audiobooks.data.universe

import kotlin.random.Random

/**
 * Spec-26 T3 (#177) — the Wikidata 429 retry policy. Pure JVM, no network:
 * [WikidataSeriesProvider] owns the retry loop, this object owns the
 * decisions — so the policy is pinned by fast table-driven JVM tests.
 */
object WikidataRetryPolicy {

    /** HTTP 429 Too Many Requests. */
    const val HTTP_TOO_MANY_REQUESTS = 429

    /**
     * Whether another attempt is allowed after the [attempt]-th response
     * (0-based). Retry ONLY rate-limited responses, and only up to
     * [maxAttempts] total tries — any other status (success, 5xx, 404,
     * connection failure) or an exhausted limit does NOT retry, so a
     * genuinely failing backend never amplifies into a retry storm.
     */
    fun shouldRetry(attempt: Int, maxAttempts: Int, statusCode: Int): Boolean =
        statusCode == HTTP_TOO_MANY_REQUESTS && attempt < maxAttempts - 1

    /**
     * Exponential backoff with jitter: `initialDelayMs * 2^attempt` (capped
     * so a long 429 series never overflows), plus up to `base` more of
     * random jitter — the jitter desynchronises parallel resolvers so they
     * do not all hammer Wikidata on the same schedule. The [random] is
     * injectable so tests can pin the jitter band deterministically.
     */
    fun backoffDelayMs(
        attempt: Int,
        initialDelayMs: Long = 250,
        random: Random = Random.Default
    ): Long {
        val base = initialDelayMs * (1L shl attempt.coerceAtMost(6))
        return base + if (base == 0L) 0L else random.nextLong(0, base)
    }
}
