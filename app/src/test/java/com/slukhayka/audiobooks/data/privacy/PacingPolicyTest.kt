package com.slukhayka.audiobooks.data.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Spec-38 T1 (#253) — the human-rhythm pacing policy that lives behind the
 * privacy door: random pauses inside a configured range and a burst limit per
 * domain. Deterministic through a seeded generator (spec-38 Testing
 * Decisions), so tests pin exact bounds without sleeps.
 */
class PacingPolicyTest {

    private val params = PacingParams(
        minPauseMillis = 1_000,
        maxPauseMillis = 3_000,
        burstLimit = 3,
        burstWindowMillis = 10_000
    )

    // --- pause jitter ---

    @Test
    fun `pauses stay inside the configured range`() {
        val policy = PacingPolicy(params, Random(42))
        repeat(500) {
            val pause = policy.nextPauseMillis()
            assertTrue(pause in params.minPauseMillis..params.maxPauseMillis)
        }
    }

    @Test
    fun `the same seed produces the same pause sequence`() {
        val a = PacingPolicy(params, Random(7))
        val b = PacingPolicy(params, Random(7))
        repeat(50) { assertEquals(a.nextPauseMillis(), b.nextPauseMillis()) }
    }

    @Test
    fun `different seeds produce different sequences`() {
        val a = PacingPolicy(params, Random(1))
        val b = PacingPolicy(params, Random(2))
        val seqA = (1..20).map { a.nextPauseMillis() }
        val seqB = (1..20).map { b.nextPauseMillis() }
        assertFalse(seqA == seqB)
    }

    @Test
    fun `jitter actually varies`() {
        val policy = PacingPolicy(params, Random(9))
        val pauses = (1..100).map { policy.nextPauseMillis() }.toSet()
        assertTrue("one value across 100 draws means no jitter", pauses.size > 10)
    }

    // --- burst limiting ---

    @Test
    fun `requests up to the burst limit pass`() {
        val policy = PacingPolicy(params, Random(0))
        repeat(params.burstLimit) {
            assertTrue(policy.allowsRequest("sluhay.com", nowMillis = 1_000))
        }
    }

    @Test
    fun `the request over the limit is refused`() {
        val policy = PacingPolicy(params, Random(0))
        repeat(params.burstLimit) { policy.allowsRequest("sluhay.com", 1_000) }
        assertFalse(policy.allowsRequest("sluhay.com", 2_000))
    }

    @Test
    fun `the window sliding out frees the budget`() {
        val policy = PacingPolicy(params, Random(0))
        repeat(params.burstLimit) { policy.allowsRequest("sluhay.com", 1_000) }
        // Just past the window the old hits expire.
        assertTrue(policy.allowsRequest("sluhay.com", 11_001))
    }

    @Test
    fun `domains are limited independently`() {
        val policy = PacingPolicy(params, Random(0))
        repeat(params.burstLimit) { policy.allowsRequest("sluhay.com", 1_000) }
        assertTrue(policy.allowsRequest("4read.org", 1_001))
    }
}
