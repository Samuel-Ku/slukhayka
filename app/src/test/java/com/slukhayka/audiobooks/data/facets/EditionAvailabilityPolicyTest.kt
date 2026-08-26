package com.slukhayka.audiobooks.data.facets

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditionAvailabilityPolicyTest {
    @Test
    fun `freshness rejects future observations and is stale at exact expiry`() {
        assertFalse(EditionAvailabilityPolicy.isFresh(observedAtMillis = 2_000, ttlSeconds = 1, nowMillis = 1_999))
        assertTrue(EditionAvailabilityPolicy.isFresh(observedAtMillis = 2_000, ttlSeconds = 1, nowMillis = 2_999))
        assertFalse(EditionAvailabilityPolicy.isFresh(observedAtMillis = 2_000, ttlSeconds = 1, nowMillis = 3_000))
        assertFalse(EditionAvailabilityPolicy.isFresh(observedAtMillis = 0, ttlSeconds = 0, nowMillis = 0))
        assertFalse(
            EditionAvailabilityPolicy.isFresh(
                observedAtMillis = 0,
                ttlSeconds = EditionAvailabilityPolicy.MAX_TTL_SECONDS + 1,
                nowMillis = 0
            )
        )
    }
}
