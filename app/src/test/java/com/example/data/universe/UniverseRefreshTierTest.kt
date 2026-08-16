package com.example.data.universe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-26 T7 (#181) — the tiered refresh rule: hot (~7d) for a young series
 * at the chain tail, warm (~30d) for tail-but-old or fresh-in-the-middle,
 * cold (~180d floor) for the rest — so every cached membership eventually
 * re-resolves and spreads Wikidata fixes.
 */
class UniverseRefreshTierTest {

    private val nowYear = 2026

    @Test
    fun `a tail series younger than three years is hot`() {
        // Tail + young (2024 → 2 years back) → 7-day tier.
        assertEquals(UniverseRefreshTier.HOT_MILLIS, UniverseRefreshTier.tierTtlMillis(true, 2024, nowYear))
    }

    @Test
    fun `a tail series three years old is no longer hot but still warm`() {
        // Exactly 3 years back → not young → tail keeps it warm.
        assertEquals(UniverseRefreshTier.WARM_MILLIS, UniverseRefreshTier.tierTtlMillis(true, 2023, nowYear))
    }

    @Test
    fun `an old tail series is warm - the tail alone buys warm`() {
        assertEquals(UniverseRefreshTier.WARM_MILLIS, UniverseRefreshTier.tierTtlMillis(true, 2000, nowYear))
    }

    @Test
    fun `a young series in the middle is warm - youth buys warm`() {
        assertEquals(UniverseRefreshTier.WARM_MILLIS, UniverseRefreshTier.tierTtlMillis(false, 2025, nowYear))
    }

    @Test
    fun `a middle series that is old is cold - the 180-day floor`() {
        assertEquals(UniverseRefreshTier.COLD_MILLIS, UniverseRefreshTier.tierTtlMillis(false, 2000, nowYear))
    }

    @Test
    fun `an unknown-age series is never hot - tail unknown is warm, middle unknown is cold`() {
        assertEquals(UniverseRefreshTier.WARM_MILLIS, UniverseRefreshTier.tierTtlMillis(true, null, nowYear))
        assertEquals(UniverseRefreshTier.COLD_MILLIS, UniverseRefreshTier.tierTtlMillis(false, null, nowYear))
    }

    @Test
    fun `the tiers are ordered - hot then warm then cold`() {
        assertTrue(UniverseRefreshTier.HOT_MILLIS < UniverseRefreshTier.WARM_MILLIS)
        assertTrue(UniverseRefreshTier.WARM_MILLIS < UniverseRefreshTier.COLD_MILLIS)
        assertEquals(7L * 24 * 60 * 60 * 1000, UniverseRefreshTier.HOT_MILLIS)
        assertEquals(30L * 24 * 60 * 60 * 1000, UniverseRefreshTier.WARM_MILLIS)
        assertEquals(180L * 24 * 60 * 60 * 1000, UniverseRefreshTier.COLD_MILLIS)
    }

    @Test
    fun `isYoung flips at the three-year boundary`() {
        assertTrue(UniverseRefreshTier.isYoung(2025, nowYear))
        assertTrue(UniverseRefreshTier.isYoung(2024, nowYear))
        assertFalse(UniverseRefreshTier.isYoung(2023, nowYear))
        assertFalse(UniverseRefreshTier.isYoung(null, nowYear))
    }

    @Test
    fun `epochYear yields the calendar year of an instant`() {
        // 2023-11-14 UTC and a year-boundary instant.
        assertEquals(2023, UniverseRefreshTier.epochYear(1_700_000_000_000L))
        assertEquals(2020, UniverseRefreshTier.epochYear(1_577_836_800_000L)) // 2020-01-01 UTC
        assertEquals(2021, UniverseRefreshTier.epochYear(1_609_459_200_000L)) // 2021-01-01 UTC
    }
}
