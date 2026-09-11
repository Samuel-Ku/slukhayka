package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #482 — the Room per-book embedding cache: content-hash hit/miss, delta
 * update (only new/changed texts embed), and restart survival (a new cache
 * over the same database still serves). The service never throws.
 */
class RoomEmbeddingCacheTest {

    private fun candidate(id: String, title: String, genre: String = "") =
        RecommendationEngine.Candidate(id = id, title = title, author = "Автор", genre = genre)

    private fun countingEmbedder(counter: () -> Unit) = object : TextEmbedder {
        override fun embed(text: String): FloatArray {
            counter()
            return floatArrayOf(text.length.toFloat(), 1f)
        }
    }

    @Test
    fun `a cached vector survives a new cache over the same database`() = runBlocking {
        val dao = FakeAudiobookDao(books = emptyList())
        RoomEmbeddingCache(dao).save(mapOf("c1" to ("Кобзар" to floatArrayOf(1f, 2f))))

        val served = RoomEmbeddingCache(dao).loadFresh(mapOf("c1" to "Кобзар"))

        assertTrue(served["c1"]!!.contentEquals(floatArrayOf(1f, 2f)))
    }

    @Test
    fun `a changed text misses the cache`() = runBlocking {
        val dao = FakeAudiobookDao(books = emptyList())
        val cache = RoomEmbeddingCache(dao)
        cache.save(mapOf("c1" to ("old text" to floatArrayOf(1f))))

        assertTrue(cache.loadFresh(mapOf("c1" to "new text")).isEmpty())
    }

    @Test
    fun `the service embeds only new or changed books`() = runBlocking {
        val dao = FakeAudiobookDao(books = emptyList())
        val cache = RoomEmbeddingCache(dao)
        var embeds = 0
        val embedder = countingEmbedder { embeds++ }
        val service = CatalogEmbeddingService(cache)

        val first = listOf(candidate("c1", "Кобзар", "Класика"), candidate("c2", "Лісова пісня", "Класика"))
        service.vectorsFor(first, embedder)
        assertEquals(2, embeds)

        // Same catalogue → a pure cache read, no new embeds.
        service.vectorsFor(first, embedder)
        assertEquals(2, embeds)

        // Only the changed book re-embeds.
        val changed = listOf(candidate("c1", "Кобзар", "Драма"), candidate("c2", "Лісова пісня", "Класика"))
        service.vectorsFor(changed, embedder)
        assertEquals(3, embeds)
    }

    @Test
    fun `a failing embed is skipped and never throws`() = runBlocking {
        val dao = FakeAudiobookDao(books = emptyList())
        val service = CatalogEmbeddingService(RoomEmbeddingCache(dao))
        val throwing = object : TextEmbedder {
            override fun embed(text: String): FloatArray = error("boom")
        }

        val vectors = service.vectorsFor(listOf(candidate("c1", "Кобзар")), throwing)

        assertTrue(vectors.isEmpty())
    }
}
