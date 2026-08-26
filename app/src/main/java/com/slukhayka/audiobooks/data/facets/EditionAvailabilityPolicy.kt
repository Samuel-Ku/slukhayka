package com.slukhayka.audiobooks.data.facets

/** Pure shared/local handoff for deciding whether an Edition availability assertion is current. */
object EditionAvailabilityPolicy {
    const val MAX_TTL_SECONDS = 2_592_000L

    fun isFresh(observedAtMillis: Long, ttlSeconds: Long, nowMillis: Long): Boolean {
        if (observedAtMillis < 0 || ttlSeconds !in 1L..MAX_TTL_SECONDS || nowMillis < observedAtMillis) {
            return false
        }
        return nowMillis - observedAtMillis < ttlSeconds * 1_000
    }
}
