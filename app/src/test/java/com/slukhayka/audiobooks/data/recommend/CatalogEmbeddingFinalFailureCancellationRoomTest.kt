package com.slukhayka.audiobooks.data.recommend

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.EmbeddingVectorEntity
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CatalogEmbeddingFinalFailureCancellationRoomTest {
    @Test(timeout = 90_000)
    fun `cancelled catalog caller propagates cancellation after its final embed throws an ordinary failure`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val alphaEntered = CountDownLatch(1)
        val releaseAlpha = CountDownLatch(1)
        val bodyFinished = CompletableDeferred<Unit>()
        var caller: Job? = null
        var primaryFailure: Throwable? = null
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

                val alphaCalls = AtomicInteger()
                val alphaThrew = AtomicInteger()
                val observedTexts = Collections.synchronizedList(mutableListOf<String>())
                val observedCancellation = AtomicReference<CancellationException?>(null)
                val returnedNormally = AtomicBoolean(false)
                val cancellation = CancellationException("catalog caller cancelled while its only Alpha embed is held")
                val externalEmbedder = object : TextEmbedder {
                    override val cacheContext = EmbeddingContext("catalog-final-failure-cancellation-external-v1", 2)
                    override fun embed(text: String): FloatArray {
                        observedTexts += text
                        return when (text) {
                            "Alpha" -> {
                                alphaCalls.incrementAndGet()
                                alphaEntered.countDown()
                                check(releaseAlpha.await(30, TimeUnit.SECONDS)) {
                                    "SETUP_NOT_REACHED: Alpha was not released within the external boundary budget"
                                }
                                alphaThrew.incrementAndGet()
                                throw IllegalStateException("ordinary external embedding failure after caller cancellation")
                            }
                            else -> error("Unexpected input at the external embedding boundary")
                        }
                    }
                }
                val candidates = listOf(
                    RecommendationEngine.Candidate(id = "A", title = "Alpha"),
                )
                val running = launch(Dispatchers.Default) {
                    try {
                        CatalogEmbeddingService(RoomEmbeddingCache(dao))
                            .vectorsFor(candidates, externalEmbedder, limit = 1)
                        returnedNormally.set(true)
                    } catch (cancelled: CancellationException) {
                        observedCancellation.set(cancelled)
                    } finally {
                        bodyFinished.complete(Unit)
                    }
                }
                caller = running
                assertTrue("SETUP_NOT_REACHED: Alpha entered the real catalog pass", alphaEntered.await(5, TimeUnit.SECONDS))
                assertEquals(1, alphaCalls.get())
                assertEquals(0, alphaThrew.get())
                assertEquals(listOf("Alpha"), synchronized(observedTexts) { observedTexts.toList() })
                running.cancel(cancellation)
                assertTrue("The public caller job is cancelled while Alpha is held", running.isCancelled)
                releaseAlpha.countDown()
                withTimeout(10_000) {
                    bodyFinished.await()
                    running.join()
                }

                assertEquals("Alpha threw its ordinary failure after public job cancellation", 1, alphaThrew.get())
                assertEquals(listOf("Alpha"), synchronized(observedTexts) { observedTexts.toList() })
                val escaped = observedCancellation.get()
                assertTrue(
                    "R1_FINAL_ORDINARY_FAILURE_AFTER_JOB_CANCEL_MUST_PROPAGATE_CANCELLATION",
                    escaped === cancellation || escaped?.cause === cancellation,
                )
                assertFalse("The catalog method must not return normally after caller cancellation", returnedNormally.get())
                val after = dao.allEmbeddingVectors()
                assertEquals(listOf(preservedC), after)
                assertArrayEquals(byteArrayOf(0, 0, -128, 63, 0, 0, 0, 0), after.single().vector)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            releaseAlpha.countDown()
            try {
                withContext(NonCancellable) {
                    withTimeout(20_000) {
                        caller?.cancelAndJoin()
                    }
                    // A live catalog caller must never outlast its Room database.
                    db.close()
                }
            } catch (cleanupFailure: Throwable) {
                val failure = primaryFailure
                if (failure != null) failure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }
}
