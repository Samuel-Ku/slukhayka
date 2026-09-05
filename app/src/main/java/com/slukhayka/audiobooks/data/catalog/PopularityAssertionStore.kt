package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.collections.CollectionList
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.metadata.PopularityAssertionPolicy

/**
 * #485 — the write side of the persistent source-signal layer. Records
 * provenance-bearing Metadata Assertions (rank signals from the live
 * collection shelves, rating claims from resolved pages) into
 * `popularity_assertions`. Best-effort by contract — a failing write never
 * breaks the fetch or the import that triggered it. The live shelves and the
 * stored rating column are untouched: this layer only persists what a
 * source claimed, when, and from where.
 */
class PopularityAssertionStore(private val dao: AudiobookDao) {

    /**
     * Records one observation set from a source's live collections: each
     * titled entry becomes a rank assertion (1-based position in the list),
     * keyed by the SAME merge rule the catalog union merges on. Null for a
     * blank merge key — a signal that joins no Work is noise.
     */
    suspend fun recordRankSignals(lists: List<CollectionList>, observedAt: Long) {
        val records = lists.flatMap { list ->
            list.entries.mapIndexedNotNull { index, entry ->
                PopularityAssertionPolicy.rankRecord(
                    mergeKey = PopularityAssertionPolicy.popularityMergeKey(entry.title.orEmpty(), entry.author),
                    sourceId = list.id,
                    observedAt = observedAt,
                    rank = index + 1
                )
            }
        }
        if (records.isEmpty()) return
        runCatching { dao.upsertPopularityAssertions(records) }
    }

    /**
     * Records one rating claim a source made on a resolved page. Null when
     * there is no claim — a missing rating is never recorded as a zero.
     */
    suspend fun recordRatingSignal(
        mergeKey: String,
        sourceId: String,
        rating: Double?,
        observedAt: Long
    ) {
        val record = PopularityAssertionPolicy.ratingRecord(mergeKey, sourceId, rating, observedAt) ?: return
        runCatching { dao.upsertPopularityAssertions(listOf(record)) }
    }
}
