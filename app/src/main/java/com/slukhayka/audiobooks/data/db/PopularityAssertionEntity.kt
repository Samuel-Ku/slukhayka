package com.slukhayka.audiobooks.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * #485 — one provenance-bearing source signal (a Metadata Assertion): a
 * book's popularity rank in a source's live collection, or the source's
 * claimed rating on a resolved page. Persistent beneath the live shelves —
 * the rows survive the session and are available for ranking outside it.
 * Never replaces the live collections and never rewrites the
 * audiobooks.rating display column; it is the raw provenance layer.
 *
 * [mergeKey] is the SAME normalized `title|author` rule the catalog union
 * merges on ([com.slukhayka.audiobooks.data.merge.MergeKey.keyFor]), so the
 * signals join the Work corpus without a second identity.
 */
@Entity(
    tableName = "popularity_assertions",
    primaryKeys = ["id"],
    indices = [Index("mergeKey"), Index("sourceId"), Index("kind")]
)
data class PopularityAssertionEntity(
    val id: String,
    val kind: String,
    val mergeKey: String,
    val rawValue: String,
    val sourceId: String,
    val observedAt: Long
) {
    companion object {
        /** The book ranked in a source's live collection ([rawValue] = 1-based rank). */
        const val KIND_RANK: String = "rank"

        /** The source's claimed rating on a resolved page ([rawValue] = the double). */
        const val KIND_RATING: String = "rating"
    }
}
