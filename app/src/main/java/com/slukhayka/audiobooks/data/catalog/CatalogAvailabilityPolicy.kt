package com.slukhayka.audiobooks.data.catalog

/** Canonical Android limits for Edition availability and Source playback attempts. */
object CatalogAvailabilityPolicy {
    const val POSITIVE_TTL_MS = 6L * 60 * 60 * 1_000
    const val NEGATIVE_TTL_MS = 15L * 60 * 1_000
    const val VERIFIED_PROFILE_TTL_MS = 24L * 60 * 60 * 1_000
    const val SOURCE_BUDGET_MS = 8_000L
    const val MAX_PARALLEL_SOURCES = 2

    fun isFresh(available: Boolean, observedAtMillis: Long, nowMillis: Long): Boolean =
        isWithin(observedAtMillis, nowMillis, if (available) POSITIVE_TTL_MS else NEGATIVE_TTL_MS)

    fun isVerifiedProfileFresh(observedAtMillis: Long, nowMillis: Long): Boolean =
        isWithin(observedAtMillis, nowMillis, VERIFIED_PROFILE_TTL_MS)

    private fun isWithin(observedAtMillis: Long, nowMillis: Long, ttlMillis: Long): Boolean =
        observedAtMillis >= 0L && nowMillis >= observedAtMillis && nowMillis - observedAtMillis < ttlMillis
}
