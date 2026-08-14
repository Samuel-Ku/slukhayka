package com.example.data.recommend

/**
 * The background embedding pass (spec-19 Q7, US5/US7): given the catalogue
 * and the [EmbeddingCache], returns the id → vector map, computing and
 * persisting it only when the catalogue version is new. Embeddings are
 * derived data — deterministic, recomputable, keyed by catalogue version —
 * so a catalogue sync that adds books simply misses and recomputes; a user
 * browsing is never blocked (the pass runs on an idle dispatcher, and the
 * ranking reads the cached map afterwards).
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
     * persists the map for the next run.
     */
    fun vectorsFor(
        catalog: List<RecommendationEngine.Candidate>,
        embedder: TextEmbedder
    ): Map<String, FloatArray> {
        if (catalog.isEmpty()) return emptyMap()
        val version = EmbeddingCache.catalogVersion(catalog)
        cache.load(version)?.let { return it }
        val computed = catalog.associate { it.id to embedder.embed(it.text) }
        cache.save(version, computed)
        return computed
    }
}
