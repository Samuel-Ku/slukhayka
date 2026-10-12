package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.db.EmbeddingVectorEntity
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingBackendCacheTest {
    private val book = RecommendationEngine.Candidate("book", "Book", "Author")
    private fun backend(context: EmbeddingContext?, onEmbed: () -> Unit = {}) = object : TextEmbedder {
        override val cacheContext = context
        override fun embed(text: String): FloatArray {
            onEmbed()
            return FloatArray(context?.dimension ?: 2) { if (it == 0) 1f else 0f }
        }
    }

    @Test fun `same backend and text survive cache restart without another embed`() = runBlocking {
        val dao = FakeAudiobookDao(emptyList())
        var calls = 0
        val context = EmbeddingContext("model-A:processor-v2", 2)
        CatalogEmbeddingService(RoomEmbeddingCache(dao)).vectorsFor(listOf(book), backend(context) { calls++ })
        val served = CatalogEmbeddingService(RoomEmbeddingCache(dao)).vectorsFor(listOf(book), backend(context) { calls++ })
        assertEquals(1, calls)
        assertEquals(2, served.getValue(book.id).size)
    }

    @Test fun `backend dimension transition recomputes instead of mixing spaces`() = runBlocking {
        val dao = FakeAudiobookDao(emptyList())
        val service = CatalogEmbeddingService(RoomEmbeddingCache(dao))
        service.vectorsFor(listOf(book), backend(EmbeddingContext("keyword", 512)))
        val semantic = service.vectorsFor(listOf(book), backend(EmbeddingContext("E5:processor-v2", 384)))
        assertEquals(384, semantic.getValue(book.id).size)
    }

    @Test fun `same dimensionality with changed preprocessing identity is a miss`() = runBlocking {
        val service = CatalogEmbeddingService(RoomEmbeddingCache(FakeAudiobookDao(emptyList())))
        var calls = 0
        service.vectorsFor(listOf(book), backend(EmbeddingContext("model-A:processor-v1", 2)) { calls++ })
        service.vectorsFor(listOf(book), backend(EmbeddingContext("model-A:processor-v2", 2)) { calls++ })
        assertEquals(2, calls)
    }

    @Test fun `legacy text-only rows never serve a named backend`() = runBlocking {
        val dao = FakeAudiobookDao(emptyList())
        dao.upsertEmbeddingVectors(listOf(EmbeddingVectorEntity(book.id, VectorCodec.textHash(book.text),
            VectorCodec.encode(floatArrayOf(1f, 0f)), 1L)))
        var calls = 0
        CatalogEmbeddingService(RoomEmbeddingCache(dao)).vectorsFor(listOf(book),
            backend(EmbeddingContext("model-A:processor-v2", 2)) { calls++ })
        assertEquals(1, calls)
    }

    @Test fun `unidentified custom backends do not persist or reuse derived vectors`() = runBlocking {
        val dao = FakeAudiobookDao(emptyList())
        val service = CatalogEmbeddingService(RoomEmbeddingCache(dao))
        var calls = 0
        service.vectorsFor(listOf(book), backend(null) { calls++ })
        service.vectorsFor(listOf(book), backend(null) { calls++ })
        assertEquals(2, calls)
        assertTrue(dao.allEmbeddingVectors().isEmpty())
    }

    @Test fun `bad dimensions nonfinite degenerate and malformed persisted blobs are misses`() = runBlocking {
        val context = EmbeddingContext("model-A:processor-v2", 2)
        for (blob in listOf(VectorCodec.encode(floatArrayOf(1f)), VectorCodec.encode(floatArrayOf(Float.NaN, 1f)),
            VectorCodec.encode(floatArrayOf(0f, 0f)), byteArrayOf(1, 2, 3))) {
            val dao = FakeAudiobookDao(emptyList())
            dao.upsertEmbeddingVectors(listOf(EmbeddingVectorEntity(book.id,
                VectorCodec.contextHash(book.text, context), blob, 1L)))
            assertTrue(RoomEmbeddingCache(dao).loadFresh(mapOf(book.id to book.text), context).isEmpty())
        }
    }
}
