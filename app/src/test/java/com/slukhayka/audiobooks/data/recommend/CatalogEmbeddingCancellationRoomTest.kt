package com.slukhayka.audiobooks.data.recommend

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.EmbeddingVectorEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CatalogEmbeddingCancellationRoomTest {
    @Test(timeout = 90_000)
    fun `catalog cancellation propagates unchanged and does not persist the interrupted pass`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            withTimeout(45_000) {
                val dao = db.audiobookDao()
                val preservedC = EmbeddingVectorEntity(
                    workId = "C",
                    textHash = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                    vector = byteArrayOf(0, 0, -128, 63, 0, 0, 0, 0),
                    updatedAt = 1_234L,
                )
                dao.upsertEmbeddingVectors(listOf(preservedC))
                val before = dao.allEmbeddingVectors()
                assertEquals(listOf(preservedC), before)
                assertArrayEquals(byteArrayOf(0, 0, -128, 63, 0, 0, 0, 0), before.single().vector)

                val cancellation = CancellationException("catalog pass cancelled at the external embedding boundary")
                val observedTexts = mutableListOf<String>()
                var firstReturned = false
                var secondThrew = false
                val externalEmbedder = object : TextEmbedder {
                    override val cacheContext = EmbeddingContext("catalog-cancellation-external-v1", 2)
                    override fun embed(text: String): FloatArray {
                        observedTexts += text
                        return when (text) {
                            "Alpha" -> {
                                firstReturned = true
                                floatArrayOf(0.6f, 0.8f)
                            }
                            "Beta" -> {
                                secondThrew = true
                                throw cancellation
                            }
                            else -> error("Unexpected input at the external embedding boundary")
                        }
                    }
                }
                val candidates = listOf(
                    RecommendationEngine.Candidate(id = "A", title = "Alpha"),
                    RecommendationEngine.Candidate(id = "B", title = "Beta"),
                )
                var observedCancellation: CancellationException? = null
                var returnedNormally = false
                try {
                    CatalogEmbeddingService(RoomEmbeddingCache(dao))
                        .vectorsFor(candidates, externalEmbedder, limit = 2)
                    returnedNormally = true
                } catch (cancelled: CancellationException) {
                    observedCancellation = cancelled
                }

                assertTrue("A returned the usable literal vector before cancellation", firstReturned)
                assertTrue("B threw the supplied external cancellation", secondThrew)
                assertEquals(listOf("Alpha", "Beta"), observedTexts)
                assertSame("Cancellation must escape the catalog pass unchanged", cancellation, observedCancellation)
                assertFalse("A successful result cannot replace cancellation", returnedNormally)
                val after = dao.allEmbeddingVectors()
                assertEquals(listOf(preservedC), after)
                assertArrayEquals(byteArrayOf(0, 0, -128, 63, 0, 0, 0, 0), after.single().vector)
            }
        } finally {
            db.close()
        }
    }
}
