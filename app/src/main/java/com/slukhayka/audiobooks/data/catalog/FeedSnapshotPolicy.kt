package com.slukhayka.audiobooks.data.catalog

/**
 * Spec #462 Implementation Decision 6 (#467) — the pure feed-snapshot
 * freshness decision: how long one persisted feed snapshot (feed_snapshots,
 * #467) may answer the Огляд feed WITHOUT a network call.
 *
 * Deliberately a pure function (the StreamHealPolicy convention, ADR-0019):
 * [needsNetwork] decides from the feed key, the snapshot's fetched-at stamp,
 * the current clock and the explicit-refresh flag — nothing here touches
 * Room, HTTP or the system clock, so the JVM test suite pins the decision
 * with a fake clock.
 *
 * TTLs (spec #462): новинки / new arrivals — 6 hours; catalog feeds —
 * 24 hours. A snapshot is stale at the EXACT expiry boundary (the same
 * honest convention as the Edition Availability Assertion): `now -
 * fetchedAt < ttl` — equal means stale.
 */
object FeedSnapshotPolicy {

    /** «Новинки» / new-arrivals feeds: six hours fresh. */
    const val NEW_ARRIVALS_TTL_MS: Long = 6L * 60 * 60 * 1000

    /** Catalog feeds (catalogue enumeration, 4read homepage sections): 24 hours. */
    const val CATALOG_TTL_MS: Long = 24L * 60 * 60 * 1000

    /** Feed key of the per-source new-arrivals feed («Новинки»). */
    const val FEED_NEW_ARRIVALS: String = "new-arrivals"

    /** Feed key of a source's catalogue enumeration (the union input). */
    const val FEED_CATALOG: String = "catalog"

    /** Feed key of the 4read homepage sections («Новинки»/«Цикли»/«Популярне»). */
    const val FEED_HOMEPAGE_SECTIONS: String = "homepage-sections"

    /** The TTL of one feed kind. Unknown feed keys are catalog-grade (24 h). */
    fun ttlMillisFor(feedKey: String): Long =
        if (feedKey == FEED_NEW_ARRIVALS) NEW_ARRIVALS_TTL_MS else CATALOG_TTL_MS

    /** Fresh strictly INSIDE the TTL — stale at the exact expiry boundary. */
    fun isFresh(fetchedAt: Long, nowMillis: Long, ttlMillis: Long): Boolean =
        nowMillis - fetchedAt < ttlMillis

    /**
     * Whether the network must be hit: an explicit user refresh always
     * fetches; otherwise only a missing or stale snapshot does. A fresh
     * snapshot answers the feed entirely from the database.
     */
    fun needsNetwork(
        feedKey: String,
        fetchedAt: Long?,
        nowMillis: Long,
        forceRefresh: Boolean = false
    ): Boolean = when {
        forceRefresh -> true
        fetchedAt == null -> true
        else -> !isFresh(fetchedAt, nowMillis, ttlMillisFor(feedKey))
    }
}
