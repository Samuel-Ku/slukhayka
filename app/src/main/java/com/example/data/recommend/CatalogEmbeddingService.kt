package com.example.data.recommend

/**
 * The background embedding pass (spec-19 Q7, US5/US7): given the catalogue
 * and the [EmbeddingCache], returns the id → vector map, computing and
 * persisting it only when the catalogue version is new. Embeddings are
 * derived data — deterministic, recomputable, keyed by catalogue version —
 * so a catalogue sync that adds books simply misses and recomputes; a user
 * browsing is never blocked (the pass runs on an idle dispatcher chosen by
 * the caller, and the ranking reads the cached map afterwards).
 *
 * The pass is failure-safe by contract (spec-19 T2): it never throws. A
 * book whose embedding fails is skipped (its missing vector makes the
 * ranking drop it, never fabricate a score), a cache write that fails
 * degrades to the in-memory result, and a corrupted cache is a miss. The
 * worst case is an empty vector map — an empty recommendation row, never a
 * crash.
 *
 * Pure JVM: the embedder is the [TextEmbedder] seam (baseline today, ONNX
 * later), the cache is the file, and the caller decides the dispatcher.
 */
class CatalogEmbeddingService(
    private val cache: EmbeddingCache
) {
    /**
     * The vectors for [catalog]: a cache hit by version returns the stored
     * map without touching the embedder; a miss embeds every candidate and
     * persists the map for the next run. Never throws — see the class doc.
     */
    fun vectorsFor(
        catalog: List<RecommendationEngine.Candidate>,
        embedder: TextEmbedder
    ): Map<String, FloatArray> {
        if (catalog.isEmpty()) return emptyMap()
        val version = EmbeddingCache.catalogVersion(catalog)
        cache.load(version)?.let { return it }
        val computed = LinkedHashMap<String, FloatArray>()
        for (candidate in catalog) {
            try {
                computed[candidate.id] = embedder.embed(candidate.text)
            } catch (e: Exception) {
                // One broken embed (or a throwing embedder in tests) must not
                // take the whole row down — the candidate simply misses.
            }
        }
        if (computed.isNotEmpty()) {
            try {
                cache.save(version, computed)
            } catch (e: Exception) {
                // Cache write is best-effort: return the computed map anyway.
            }
        }
        return computed
    }
}
