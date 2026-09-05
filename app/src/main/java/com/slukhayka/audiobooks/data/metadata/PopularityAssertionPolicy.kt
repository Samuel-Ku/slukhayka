package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.db.PopularityAssertionEntity
import com.slukhayka.audiobooks.data.merge.MergeKey

/**
 * #485 — the pure policy for source-signal Metadata Assertions: identity,
 * provenance shape and the expiry rule. The assertions persist in Room
 * (`popularity_assertions`, v26→v27) beneath the live collection shelves —
 * the shelves themselves are untouched; this layer only records what a
 * source claimed about a book, when, and from where, so the signals survive
 * the session and are computable outside it.
 *
 * Identity is stable per (kind, source, merge key) — a re-observation
 * REPLACES its row and refreshes the timestamp, never accumulates. The
 * merge key is the SAME normalized `title|author` rule the catalog union
 * merges on, so the assertions join the Work corpus without a second
 * identity scheme.
 */
object PopularityAssertionPolicy {

    /**
     * Popularity expires in a week: the «ТОП-100» / «Популярне зараз» shape
     * churns fast, an older observation says nothing about "now". The row
     * stays for provenance — only its freshness verdict flips.
     */
    const val POPULARITY_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

    /**
     * A claimed rating decays slower: a source's average rating moves on the
     * scale of months, not days.
     */
    const val RATING_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** The Work-level join key: the catalog union's own merge rule. */
    fun popularityMergeKey(title: String, author: String): String =
        MergeKey.keyFor(title, author)

    /** Stable assertion id for one rank signal: `pop:<source>:<mergeKey>`. */
    fun popularityAssertionId(sourceId: String, mergeKey: String): String =
        "pop:$sourceId:$mergeKey"

    /** Stable assertion id for one rating signal: `rating:<source>:<mergeKey>`. */
    fun ratingAssertionId(sourceId: String, mergeKey: String): String =
        "rating:$sourceId:$mergeKey"

    /**
     * The Metadata-Assertions freshness rule: an observation is fresh while
     * `now - observedAt < ttl`. `observedAt <= 0` (the legacy sentinel) is
     * never fresh, and a clock skew (now before observedAt) counts as fresh
     * — a future-dated observation is still an observation.
     */
    fun isFresh(observedAt: Long, nowMs: Long, ttlMs: Long = POPULARITY_TTL_MS): Boolean =
        observedAt > 0 && nowMs - observedAt < ttlMs

    /**
     * One rank assertion: the book sat at 1-based [rank] in a source's live
     * collection at [observedAt]. Null for a blank merge key — a signal that
     * joins no Work is noise.
     */
    fun rankRecord(
        mergeKey: String,
        sourceId: String,
        observedAt: Long,
        rank: Int = 0
    ): PopularityAssertionEntity? {
        if (mergeKey.isBlank()) return null
        return PopularityAssertionEntity(
            id = popularityAssertionId(sourceId, mergeKey),
            kind = PopularityAssertionEntity.KIND_RANK,
            mergeKey = mergeKey,
            rawValue = if (rank > 0) rank.toString() else "",
            sourceId = sourceId,
            observedAt = observedAt
        )
    }

    /**
     * One rating assertion: the source claimed [rating] for the book at
     * [observedAt]. Null when there is no claim (absent or non-positive) —
     * a missing rating is never recorded as a zero.
     */
    fun ratingRecord(
        mergeKey: String,
        sourceId: String,
        rating: Double?,
        observedAt: Long
    ): PopularityAssertionEntity? {
        if (mergeKey.isBlank() || rating == null || rating <= 0.0) return null
        return PopularityAssertionEntity(
            id = ratingAssertionId(sourceId, mergeKey),
            kind = PopularityAssertionEntity.KIND_RATING,
            mergeKey = mergeKey,
            rawValue = rating.toString(),
            sourceId = sourceId,
            observedAt = observedAt
        )
    }

    /** Parses a stored rating claim back to its double; null for anything else. */
    fun ratingValue(rawValue: String): Double? = rawValue.toDoubleOrNull()

    /**
     * #486 — the human-facing source name behind a rank assertion's stored
     * list id (the badge on a «джерело радить» card). Known live collections
     * get their short names; anything else shows the raw id — never a guess.
     */
    fun sourceLabel(sourceId: String): String = when (sourceId) {
        "soundbooks-top" -> "sound-books"
        "sluhayua-popular" -> "sluhay"
        "live-trending" -> "openlibrary"
        else -> sourceId
    }
}
