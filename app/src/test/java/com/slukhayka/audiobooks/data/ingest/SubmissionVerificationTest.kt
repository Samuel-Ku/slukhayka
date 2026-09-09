package com.slukhayka.audiobooks.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the ADR-0035 / #604 submission verification gate: only
 * a REAL playback verdict (the player's actual `playing` event) verifies a
 * submitted source — a prepared-but-never-played probe never does.
 */
class SubmissionVerificationTest {

    @Test
    fun `only a real playing verdict verifies`() {
        var now = 1_000L
        val verification = SubmissionVerification { now }

        verification.record("yt-1", actualPlaybackStarted = false)
        assertFalse("prepare alone is never a verdict", verification.isVerified("yt-1"))
        assertNull(verification.verifiedAt("yt-1"))

        now = 2_000L
        verification.record("yt-1", actualPlaybackStarted = true)
        assertTrue(verification.isVerified("yt-1"))
        assertEquals(2_000L, verification.verifiedAt("yt-1"))
    }

    @Test
    fun `a negative signal never clears a positive verdict`() {
        val verification = SubmissionVerification { 5_000L }

        verification.record("yt-2", actualPlaybackStarted = true)
        verification.record("yt-2", actualPlaybackStarted = false)

        assertTrue("once played, always verified for the process", verification.isVerified("yt-2"))
        assertEquals(5_000L, verification.verifiedAt("yt-2"))
    }

    @Test
    fun `sources are isolated`() {
        val verification = SubmissionVerification()

        verification.record("yt-a", actualPlaybackStarted = true)

        assertFalse(verification.isVerified("yt-b"))
        assertTrue(verification.isVerified("yt-a"))
    }
}