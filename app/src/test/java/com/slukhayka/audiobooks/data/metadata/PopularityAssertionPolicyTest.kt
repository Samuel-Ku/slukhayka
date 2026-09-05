package com.slukhayka.audiobooks.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #485 — the pure policy layer beneath the live collection shelves:
 * identity (stable per assertion), provenance (source, raw observation,
 * observedAt) and the Metadata-Assertions expiry rule. Pure JVM — no Room,
 * no Android.
 */
class PopularityAssertionPolicyTest {

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Test
    fun `popularity assertion id is stable per source and merge key`() {
        assertEquals(
            PopularityAssertionPolicy.popularityAssertionId("soundbooks-top", "кобзар|шевченко"),
            PopularityAssertionPolicy.popularityAssertionId("soundbooks-top", "кобзар|шевченко")
        )
        assertEquals(
            "pop:sluhayua-popular:кобзар|шевченко",
            PopularityAssertionPolicy.popularityAssertionId("sluhayua-popular", "кобзар|шевченко")
        )
    }

    @Test
    fun `rating assertion id is stable per source and merge key`() {
        assertEquals(
            "rating:4read:кобзар|шевченко",
            PopularityAssertionPolicy.ratingAssertionId("4read", "кобзар|шевченко")
        )
    }

    // ------------------------------------------------------------------
    // Expiry (Metadata Assertions rules)
    // ------------------------------------------------------------------

    @Test
    fun `popularity signal is fresh within its ttl and expired after`() {
        val observedAt = 1_000_000L
        assertTrue(
            PopularityAssertionPolicy.isFresh(observedAt, nowMs = observedAt + PopularityAssertionPolicy.POPULARITY_TTL_MS - 1)
        )
        assertFalse(
            PopularityAssertionPolicy.isFresh(observedAt, nowMs = observedAt + PopularityAssertionPolicy.POPULARITY_TTL_MS)
        )
    }

    @Test
    fun `rating signal keeps its own ttl`() {
        val observedAt = 1_000_000L
        assertTrue(
            PopularityAssertionPolicy.isFresh(
                observedAt,
                nowMs = observedAt + PopularityAssertionPolicy.RATING_TTL_MS - 1,
                ttlMs = PopularityAssertionPolicy.RATING_TTL_MS
            )
        )
        assertFalse(
            PopularityAssertionPolicy.isFresh(
                observedAt,
                nowMs = observedAt + PopularityAssertionPolicy.RATING_TTL_MS,
                ttlMs = PopularityAssertionPolicy.RATING_TTL_MS
            )
        )
    }

    @Test
    fun `expired signals are not dropped but excluded from freshness`() {
        // The assertion is provenance: an expired row stays queryable, only
        // its freshness verdict flips — the reader decides what to do.
        assertFalse(PopularityAssertionPolicy.isFresh(observedAt = 0L, nowMs = PopularityAssertionPolicy.POPULARITY_TTL_MS))
    }

    // ------------------------------------------------------------------
    // Record shape
    // ------------------------------------------------------------------

    @Test
    fun `a rank signal records merge key, source and observation time`() {
        val record = PopularityAssertionPolicy.rankRecord(
            mergeKey = "кобзар|шевченко",
            sourceId = "soundbooks-top",
            observedAt = 42L
        )!!
        assertEquals("кобзар|шевченко", record.mergeKey)
        assertEquals("soundbooks-top", record.sourceId)
        assertEquals(42L, record.observedAt)
    }

    @Test
    fun `a rating signal records the claimed value with provenance`() {
        val record = PopularityAssertionPolicy.ratingRecord(
            mergeKey = "кобзар|шевченко",
            sourceId = "4read",
            rating = 4.5,
            observedAt = 42L
        )
        assertEquals(4.5, PopularityAssertionPolicy.ratingValue(record!!.rawValue)!!, 0.0)
        assertEquals("4read", record.sourceId)
    }

    @Test
    fun `a blank rating is never recorded`() {
        val record = PopularityAssertionPolicy.ratingRecord(
            mergeKey = "кобзар|шевченко",
            sourceId = "4read",
            rating = null,
            observedAt = 42L
        )
        assertNull(record)
    }

    @Test
    fun `a rank assertion's source gets a human label for the badge`() {
        assertEquals("sound-books", PopularityAssertionPolicy.sourceLabel("soundbooks-top"))
        assertEquals("sluhay", PopularityAssertionPolicy.sourceLabel("sluhayua-popular"))
        assertEquals("openlibrary", PopularityAssertionPolicy.sourceLabel("live-trending"))
        // An unknown list id shows itself — never a fabricated name.
        assertEquals("mystery-list", PopularityAssertionPolicy.sourceLabel("mystery-list"))
    }
}
