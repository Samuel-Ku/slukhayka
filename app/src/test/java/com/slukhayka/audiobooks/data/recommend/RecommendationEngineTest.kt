package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [RecommendationEngine] and [KeywordEmbedder]
 * (spec-19 Track A). Only external behaviour: cosine similarity, exclusion
 * of known books, top-N ordering, the reason chip, and the deterministic
 * baseline embedder.
 */
class RecommendationEngineTest {

    private val embedder = KeywordEmbedder()

    private fun candidate(id: String, title: String, author: String = "", genre: String = "") =
        RecommendationEngine.Candidate(id = id, title = title, author = author, genre = genre)

    @Test
    fun `cosine of identical vectors is one and orthogonal is zero`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(1f, 0f, 0f)
        val c = floatArrayOf(0f, 1f, 0f)
        assertEquals(1.0, RecommendationEngine.cosine(a, b), 1e-9)
        assertEquals(0.0, RecommendationEngine.cosine(a, c), 1e-9)
    }

    @Test
    fun `cosine of empty or mismatched vectors is zero, never throws`() {
        assertEquals(0.0, RecommendationEngine.cosine(FloatArray(0), FloatArray(0)), 1e-9)
        assertEquals(0.0, RecommendationEngine.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)), 1e-9)
        assertEquals(0.0, RecommendationEngine.cosine(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f)), 1e-9)
    }

    @Test
    fun `keyword embedder is deterministic and stop words are ignored`() {
        val v1 = embedder.embed("Кобзар Тарас Шевченко")
        val v2 = embedder.embed("Кобзар Тарас Шевченко")
        assertTrue(v1.contentEquals(v2))
        // Same tokens, different order → same vector (bag of tokens).
        val v3 = embedder.embed("Тарас Шевченко Кобзар")
        assertTrue(v1.contentEquals(v3))
        // The generic word «книга» is a stop word: «Кобзар» alone equals
        // «Книга Кобзар».
        val bare = embedder.embed("Кобзар")
        val withStop = embedder.embed("Книга Кобзар")
        assertTrue(bare.contentEquals(withStop))
    }

    @Test
    fun `recommend returns the most similar candidate first with a reason chip`() {
        val recommendations = RecommendationEngine.recommend(
            candidates = listOf(
                // The exact match (same title+author) must rank first.
                candidate("c2", "Кобзар", "Тарас Шевченко", "Класика"),
                // A same-author, different-title book shares tokens → positive
                // cosine but below the exact match.
                candidate("c1", "Гайдамаки", "Тарас Шевченко", "Класика"),
                // No token overlap with the signal → cosine 0 → dropped.
                candidate("c3", "Сто років самотності", "Габрієль Маркес", "Магічний реалізм")
            ),
            signals = listOf(
                RecommendationEngine.Signal(id = "s1", title = "Кобзар", author = "Тарас Шевченко", genre = "Класика", weight = 1.0)
            ),
            embedder = embedder,
            excludeIds = emptySet(),
            topN = 3
        )
        assertEquals(2, recommendations.size)
        assertEquals("c2", recommendations.first().candidate.id)
        assertEquals("Кобзар", recommendations.first().reasonTitle)
        assertTrue(recommendations[0].score > recommendations[1].score)
    }

    @Test
    fun `known books are excluded from the row`() {
        val recommendations = RecommendationEngine.recommend(
            candidates = listOf(
                candidate("c1", "Кобзар", "Тарас Шевченко"),
                candidate("c2", "Гайдамаки", "Тарас Шевченко")
            ),
            signals = listOf(
                RecommendationEngine.Signal(id = "s1", title = "Кобзар", author = "Тарас Шевченко", weight = 1.0)
            ),
            embedder = embedder,
            excludeIds = setOf("c1"),
            topN = 10
        )
        assertEquals(listOf("c2"), recommendations.map { it.candidate.id })
    }

    @Test
    fun `weighted signals rank favourite above recent for the same candidate`() {
        // A candidate close to both signals: the favourite's weight wins.
        val recommendations = RecommendationEngine.recommend(
            candidates = listOf(candidate("c1", "Лісова пісня", "Леся Українка")),
            signals = listOf(
                RecommendationEngine.Signal(id = "fav", title = "Лісова пісня", author = "Леся Українка", weight = 1.0),
                RecommendationEngine.Signal(id = "recent", title = "Лісова пісня", author = "Леся Українка", weight = 0.6)
            ),
            embedder = embedder,
            excludeIds = emptySet()
        )
        assertEquals(1, recommendations.size)
        // The weight multiplies the same cosine, so the score is the 1.0 signal's.
        assertEquals(1.0, recommendations.first().score, 1e-6)
    }

    @Test
    fun `no signals means no recommendations`() {
        val recommendations = RecommendationEngine.recommend(
            candidates = listOf(candidate("c1", "Кобзар")),
            signals = emptyList(),
            embedder = embedder,
            excludeIds = emptySet()
        )
        assertTrue(recommendations.isEmpty())
    }

    @Test
    fun `topN caps the row size`() {
        val candidates = (1..20).map { candidate("c$it", "Книга $it", "Автор") }
        val recommendations = RecommendationEngine.recommend(
            candidates = candidates,
            signals = listOf(
                RecommendationEngine.Signal(id = "s1", title = "Книга 1", author = "Автор", weight = 1.0)
            ),
            embedder = embedder,
            excludeIds = emptySet(),
            topN = 5
        )
        assertEquals(5, recommendations.size)
    }
}
