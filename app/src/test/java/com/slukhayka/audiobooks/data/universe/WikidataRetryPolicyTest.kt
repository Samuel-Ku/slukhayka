package com.slukhayka.audiobooks.data.universe

import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-26 T3 (#177) — pure JVM table tests for [WikidataRetryPolicy]: retry
 * ONLY on 429 and ONLY within the attempt limit; the backoff grows
 * exponentially with jitter within its band (prior art: SmartRewindTest's
 * table-driven style).
 */
class WikidataRetryPolicyTest {

    @Test
    fun `only a 429 retries and only within the attempt limit`() {
        // 429 inside the limit → retry.
        assertTrue(WikidataRetryPolicy.shouldRetry(attempt = 0, maxAttempts = 3, statusCode = 429))
        assertTrue(WikidataRetryPolicy.shouldRetry(attempt = 1, maxAttempts = 3, statusCode = 429))
        // The last attempt is returned as-is — no retry past the limit.
        assertFalse(WikidataRetryPolicy.shouldRetry(attempt = 2, maxAttempts = 3, statusCode = 429))
        // A single-attempt policy never retries.
        assertFalse(WikidataRetryPolicy.shouldRetry(attempt = 0, maxAttempts = 1, statusCode = 429))
        // Any other status — success, 5xx, 404, connection failure — never retries.
        assertFalse(WikidataRetryPolicy.shouldRetry(attempt = 0, maxAttempts = 3, statusCode = 200))
        assertFalse(WikidataRetryPolicy.shouldRetry(attempt = 0, maxAttempts = 3, statusCode = 500))
        assertFalse(WikidataRetryPolicy.shouldRetry(attempt = 0, maxAttempts = 3, statusCode = 404))
        assertFalse(WikidataRetryPolicy.shouldRetry(attempt = 0, maxAttempts = 3, statusCode = 0))
    }

    @Test
    fun `the backoff grows exponentially with jitter within its band`() {
        val fixed = Random(42)
        val d0 = WikidataRetryPolicy.backoffDelayMs(0, initialDelayMs = 250, random = fixed)
        val d1 = WikidataRetryPolicy.backoffDelayMs(1, initialDelayMs = 250, random = fixed)
        val d2 = WikidataRetryPolicy.backoffDelayMs(2, initialDelayMs = 250, random = fixed)
        // base 250 / 500 / 1000, jitter in [0, base) — each delay stays in
        // its exponential band, so the policy never degenerates into a
        // retry storm.
        assertTrue(d0 in 250L until 500L)
        assertTrue(d1 in 500L until 1000L)
        assertTrue(d2 in 1000L until 2000L)
    }

    @Test
    fun `the backoff caps so a long series never overflows`() {
        // The cap at 6 doublings keeps the delay finite for any attempt.
        val d10 = WikidataRetryPolicy.backoffDelayMs(10, initialDelayMs = 250, random = Random(1))
        val d20 = WikidataRetryPolicy.backoffDelayMs(20, initialDelayMs = 250, random = Random(1))
        assertTrue(d10 > 0)
        assertTrue(d20 > 0)
    }
}
