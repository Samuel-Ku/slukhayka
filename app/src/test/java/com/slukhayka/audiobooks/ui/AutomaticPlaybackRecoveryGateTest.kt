package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticPlaybackRecoveryGateTest {

    @Test
    fun `one user play permits one automatic recovery`() {
        val gate = AutomaticPlaybackRecoveryGate()

        gate.arm("book")

        assertTrue(gate.claimFailure("book"))
        assertFalse(gate.claimFailure("book"))
    }

    @Test
    fun `failure without a play gesture cannot open recovery`() {
        val gate = AutomaticPlaybackRecoveryGate()

        assertFalse(gate.claimFailure("book"))
    }

    @Test
    fun `actual playback rearms recovery for a later network drop`() {
        val gate = AutomaticPlaybackRecoveryGate()
        gate.arm("book")
        assertTrue(gate.claimFailure("book"))

        gate.arm("book")

        assertTrue(gate.claimFailure("book"))
    }

    @Test
    fun `books have independent recovery attempts`() {
        val gate = AutomaticPlaybackRecoveryGate()
        gate.arm("one")
        gate.arm("two")

        assertTrue(gate.claimFailure("one"))
        assertTrue(gate.claimFailure("two"))
    }

    @Test
    fun `stale failure cannot spend recovery while a new play is resolving`() {
        val gate = AutomaticPlaybackRecoveryGate()
        gate.arm("book") // the previous playing session

        gate.beginAttempt("book")

        assertFalse(gate.claimFailure("book"))
        gate.arm("book") // the new attempt has now reached the player
        assertTrue(gate.claimFailure("book"))
    }

    @Test
    fun `starting another book invalidates every previous session arm`() {
        val gate = AutomaticPlaybackRecoveryGate()
        gate.arm("old-book")

        gate.beginAttempt("new-book")

        assertFalse(gate.claimFailure("old-book"))
    }
}
