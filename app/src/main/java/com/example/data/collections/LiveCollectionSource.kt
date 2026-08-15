package com.example.data.collections

/**
 * Spec-16 follow-up — the LIVE collection seam: a network-backed provider of
 * [CollectionList]s (bestsellers, trending, shelves), the counterpart of the
 * static JSON assets. Live lists feed the SAME [CollectionMatcher] against
 * the same catalog union as the static ones — a collection is a collection,
 * whatever its provenance.
 *
 * A live source is best-effort by contract: any failure (network, parsing,
 * a changed upstream shape) yields an empty list — it contributes no
 * collection, never breaks the union refresh, and non-matches are hidden by
 * the matcher as always. Fetching goes through the shared [com.example.data.source.HttpFetcher]
 * (ADR-0006: one HTTP transport), never a raw connection of its own.
 */
interface LiveCollectionSource {

    /** Stable id of this live source (cache key + diagnostics). */
    val sourceId: String

    /**
     * Fetches the current live lists. Must never throw; returns an empty
     * list when the source is unreachable or the payload is unusable.
     */
    suspend fun fetchLiveCollections(): List<CollectionList>
}
