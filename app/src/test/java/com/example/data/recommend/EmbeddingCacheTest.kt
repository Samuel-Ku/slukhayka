package com.example.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure JVM tests for [EmbeddingCache] and [CatalogEmbeddingService]
 * (spec-19 Q7 / US10). Only external behaviour: round-trip, version-keyed
 * hit/miss, catalogue-version determinism, and the service's load-or-compute
 * pass.
 */
class EmbeddingCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun candidate(id: String, title: String, genre: String = "") =
        RecommendationEngine.Candidate(id = id, title = title, author = "Автор", genre = genre)

    @Test
    fun `round-trips vectors through a file`() {
        val cache = EmbeddingCache(tmp.newFolder())
        val vectors = mapOf(
            "c1" to floatArrayOf(0.1f, 0.2f, 0.3f),
            "c2" to floatArrayOf(1.0f, 0.0f, -0.5f)
        )
        cache.save("v1", vectors)
        val loaded = cache.load("v1")
        assertNotNull(loaded)
        assertTrue(loaded!!["c1"]!!.contentEquals(vectors["c1"]))
        assertTrue(loaded["c2"]!!.contentEquals(vectors["c2"]))
    }

    @Test
    fun `different catalogue versions miss the cache`() {
        val cache = EmbeddingCache(tmp.newFolder())
        cache.save("v1", mapOf("c1" to floatArrayOf(0.1f)))
        assertNull(cache.load("v2"))
        // A corrupted / missing file never throws, returns null.
        assertNull(cache.load("v3"))
    }

    @Test
    fun `catalogue version changes when the catalogue changes and is stable when it does not`() {
        val a = listOf(candidate("c1", "Кобзар", "Класика"), candidate("c2", "Лісова пісня", "Класика"))
        val sameOrder = listOf(candidate("c2", "Лісова пісня", "Класика"), candidate("c1", "Кобзар", "Класика"))
        val changed = listOf(candidate("c1", "Кобзар", "Класика"), candidate("c2", "Лісова пісня", "Драма"))

        assertEquals(EmbeddingCache.catalogVersion(a), EmbeddingCache.catalogVersion(sameOrder))
        assertTrue(EmbeddingCache.catalogVersion(a) != EmbeddingCache.catalogVersion(changed))
    }

    @Test
    fun `service computes once, caches the version, and reuses on the next run`() {
        val cache = EmbeddingCache(tmp.newFolder())
        val service = CatalogEmbeddingService(cache)
        val catalog = listOf(candidate("c1", "Кобзар", "Класика"), candidate("c2", "Гайдамаки", "Класика"))

        val first = service.vectorsFor(catalog, KeywordEmbedder())
        assertEquals(2, first.size)

        // Second run on the same catalogue: the file cache serves it without
        // re-embedding — a fresh service instance with the same dir hits.
        val second = CatalogEmbeddingService(cache).vectorsFor(catalog, KeywordEmbedder())
        assertEquals(first.keys, second.keys)
        assertTrue(first["c1"]!!.contentEquals(second["c1"]))

        // A changed catalogue (new book) recomputes and grows the cache.
        val grown = CatalogEmbeddingService(cache).vectorsFor(
            catalog + candidate("c3", "Сто років самотності", "Магічний реалізм"),
            KeywordEmbedder()
        )
        assertEquals(3, grown.size)
    }

    @Test
    fun `empty catalogue yields empty vectors without touching the cache`() {
        val service = CatalogEmbeddingService(EmbeddingCache(tmp.newFolder()))
        assertEquals(0, service.vectorsFor(emptyList(), KeywordEmbedder()).size)
    }
}
