package com.slukhayka.audiobooks.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #504 — the pure playback-fallback decision: a proven remote 403/404 may
 * swap the chapter to a direct source once per prepare; everything else —
 * and any second attempt — stays on the honest path (the #479 answer: the
 * fallback can never mask a local bug because local failures never qualify).
 */
class PlaybackFallbackPolicyTest {

    @Test
    fun `a 403 stream failure attempts once`() {
        assertTrue(PlaybackFallbackPolicy.shouldAttempt(403, fallbackAttempts = 0))
    }

    @Test
    fun `a 404 stream failure attempts once`() {
        assertTrue(PlaybackFallbackPolicy.shouldAttempt(404, fallbackAttempts = 0))
    }

    @Test
    fun `a spent budget never attempts again`() {
        assertFalse(PlaybackFallbackPolicy.shouldAttempt(403, fallbackAttempts = 1))
        assertFalse(PlaybackFallbackPolicy.shouldAttempt(404, fallbackAttempts = 1))
        assertFalse(PlaybackFallbackPolicy.shouldAttempt(403, fallbackAttempts = 2))
    }

    @Test
    fun `a network error without a code never attempts`() {
        assertFalse(PlaybackFallbackPolicy.shouldAttempt(null, fallbackAttempts = 0))
    }

    @Test
    fun `server errors and redirects never attempt`() {
        assertFalse(PlaybackFallbackPolicy.shouldAttempt(500, fallbackAttempts = 0))
        assertFalse(PlaybackFallbackPolicy.shouldAttempt(503, fallbackAttempts = 0))
        assertFalse(PlaybackFallbackPolicy.shouldAttempt(301, fallbackAttempts = 0))
        assertFalse(PlaybackFallbackPolicy.shouldAttempt(429, fallbackAttempts = 0))
    }
}
