package com.slukhayka.audiobooks.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-32 T4 (#234) — the pure self-healing decision: only a 404/403 stream
 * failure heals (one background re-fetch + one retry with the fresh URL);
 * every other status or an exhausted heal budget surfaces the honest failure
 * immediately. The cause-chain walk ([StreamHealPolicy.responseCodeOf]) is
 * covered by the Robolectric player wiring tests, where a real
 * InvalidResponseCodeException can be built.
 */
class StreamHealPolicyTest {

    @Test
    fun `a 404 stream failure heals`() {
        assertTrue(StreamHealPolicy.shouldHeal(404, healAttempts = 0))
    }

    @Test
    fun `a 403 stream failure heals`() {
        assertTrue(StreamHealPolicy.shouldHeal(403, healAttempts = 0))
    }

    @Test
    fun `a moved file (301) never heals`() {
        assertFalse(StreamHealPolicy.shouldHeal(301, healAttempts = 0))
        assertFalse(StreamHealPolicy.shouldHeal(302, healAttempts = 0))
    }

    @Test
    fun `a server error never heals`() {
        assertFalse(StreamHealPolicy.shouldHeal(500, healAttempts = 0))
        assertFalse(StreamHealPolicy.shouldHeal(503, healAttempts = 0))
    }

    @Test
    fun `an unknown status never heals`() {
        assertFalse(StreamHealPolicy.shouldHeal(200, healAttempts = 0))
        assertFalse(StreamHealPolicy.shouldHeal(429, healAttempts = 0))
    }

    @Test
    fun `a missing status never heals`() {
        assertFalse(StreamHealPolicy.shouldHeal(null, healAttempts = 0))
    }

    @Test
    fun `the heal budget is one retry per chapter prepare`() {
        assertTrue(StreamHealPolicy.shouldHeal(404, healAttempts = 0))
        assertFalse("one heal already used -> no second retry", StreamHealPolicy.shouldHeal(404, healAttempts = 1))
        assertFalse(StreamHealPolicy.shouldHeal(403, healAttempts = 1))
        assertFalse(StreamHealPolicy.shouldHeal(404, healAttempts = 2))
    }

    @Test
    fun `the exhaustion mirror fires exactly when the budget is spent`() {
        assertTrue(StreamHealPolicy.budgetExhausted(404, healAttempts = 1))
        assertTrue(StreamHealPolicy.budgetExhausted(403, healAttempts = 2))
        assertFalse(StreamHealPolicy.budgetExhausted(404, healAttempts = 0))
        assertFalse("a server error is never a spent heal budget", StreamHealPolicy.budgetExhausted(500, healAttempts = 5))
        assertFalse(StreamHealPolicy.budgetExhausted(null, healAttempts = 5))
    }
}