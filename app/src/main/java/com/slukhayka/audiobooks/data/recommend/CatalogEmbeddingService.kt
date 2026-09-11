package com.slukhayka.audiobooks.data.recommend

/**
 * The background embedding pass (#482): given a catalogue and the Room-backed
 * [RoomEmbeddingCache], returns the id → vector map, computing ONLY the books
 * whose text hash is new or changed and persisting them. Embeddings are
 * derived data; the per-book hash means an unchanged book is never computed
 * twice even when the ephemeral union churns.
 *
 * Failure-safe by contract (spec-19 T2): it never throws. A book whose
 * embedding fails is skipped (its missing vector drops it from the ranking,
 * never fabricates a score), and a cache write that fails leaves the
 * in-memory result usable. The caller picks the dispatcher.
 */
class CatalogEmbeddingService(
    private val cache: RoomEmbeddingCache
) {
    /**
     * The vectors for [catalog]: fresh cache hits serve without the embedder;
     * only new/changed books are computed and persisted. Never throws.
     */
    suspend fun vectorsFor(
        catalog: List<RecommendationEngine.Candidate>,
        embedder: TextEmbedder
    ): Map<String, FloatArray> {
        if (catalog.isEmpty()) return emptyMap()
        val texts = catalog.associate { it.id to it.text }
        val cached = cache.loadFresh(texts)
        val missing = catalog.filter { it.id !in cached }
        if (missing.isEmpty()) return cached

        val computed = LinkedHashMap(cached)
        val persist = LinkedHashMap<String, Pair<String, FloatArray>>()
        for (candidate in missing) {
            try {
                val vector = embedder.embed(candidate.text)
                computed[candidate.id] = vector
                persist[candidate.id] = candidate.text to vector
            } catch (e: Exception) {
                // One broken embed (or a throwing embedder in tests) must not
                // take the whole row down — the candidate simply misses.
            }
        }
        cache.save(persist)
        return computed
    }
}
