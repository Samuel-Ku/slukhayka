package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1240 — the shared start seam: one place resets the failure and sets recovery. */
class ExplicitPlaybackAttemptsTest {

    private val gate = AutomaticPlaybackRecoveryGate()
    private var cleared = 0
    private val attempts = ExplicitPlaybackAttempts(gate) { cleared++ }

    @Test
    fun `every kind clears the previous failure exactly once`() {
        attempts.begin("book", ExplicitPlaybackAttempts.Kind.RESUME)
        attempts.begin("book", ExplicitPlaybackAttempts.Kind.RELISTEN)
        attempts.begin("book", ExplicitPlaybackAttempts.Kind.BOOKMARK)
        assertEquals(3, cleared)
    }

    @Test
    fun `resume supersedes the previous recovery allowance`() {
        gate.arm("book")
        attempts.begin("book", ExplicitPlaybackAttempts.Kind.RESUME)
        assertFalse("resume must not inherit the old allowance", gate.claimFailure("book"))
    }

    @Test
    fun `relisten and bookmark arm recovery up front`() {
        attempts.begin("book", ExplicitPlaybackAttempts.Kind.RELISTEN)
        assertTrue(gate.claimFailure("book"))

        attempts.begin("book", ExplicitPlaybackAttempts.Kind.BOOKMARK)
        assertTrue(gate.claimFailure("book"))
    }

    @Test
    fun `only the latest attempt is current`() {
        val first = attempts.begin("book", ExplicitPlaybackAttempts.Kind.RESUME)
        val second = attempts.begin("book", ExplicitPlaybackAttempts.Kind.BOOKMARK)
        assertFalse(attempts.isCurrent(first))
        assertTrue(attempts.isCurrent(second))
    }

    @Test
    fun `recovery is never claimed for a superseded attempt`() {
        val first = attempts.begin("book", ExplicitPlaybackAttempts.Kind.BOOKMARK)
        attempts.begin("book", ExplicitPlaybackAttempts.Kind.BOOKMARK)
        assertFalse(attempts.claimAutomaticRecovery(first))
    }

    @Test
    fun `supersede ends the current attempt`() {
        val attempt = attempts.begin("book", ExplicitPlaybackAttempts.Kind.RESUME)
        attempts.supersede()
        assertFalse(attempts.isCurrent(attempt))
    }
}
