package com.slukhayka.audiobooks.data.collections

/**
 * Spec-51 (#692/#693) — the ONE ordering behind every public collection
 * surface: the book-page block, the «Добірки слухачів» rail and a curator's
 * profile all show the same top list, so quality reads the same everywhere.
 *
 * Order: the real average (descending — a collection with no votes sorts
 * below one with votes), then the number of votes, then the newest. The
 * tie-breaks are deterministic, so two surfaces never disagree about order.
 */
object CollectionRanking {

    /** A rail/block is a shelf, not an archive. */
    const val DEFAULT_LIMIT: Int = 10

    fun top(
        collections: List<PublishedCollection>,
        limit: Int = DEFAULT_LIMIT
    ): List<PublishedCollection> {
        if (limit <= 0) return emptyList()
        return collections
            .sortedWith(
                compareByDescending<PublishedCollection> {
                    CollectionRating.average(it.ratingSum, it.ratingCount) ?: -1.0
                }
                    .thenByDescending { it.ratingCount }
                    .thenByDescending { it.publishedAt }
                    .thenBy { it.documentId }
            )
            .take(limit)
    }
}
