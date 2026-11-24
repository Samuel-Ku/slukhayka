package com.slukhayka.audiobooks.ui

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.EmbeddingVectorEntity
import com.slukhayka.audiobooks.data.db.PopularityAssertionEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkFacts
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import com.slukhayka.audiobooks.data.recommend.EmbeddingContext
import com.slukhayka.audiobooks.data.recommend.RecommendationBackendLoader
import com.slukhayka.audiobooks.data.recommend.RecommendationBackendStatus
import com.slukhayka.audiobooks.data.recommend.RecommendationPersonalization
import com.slukhayka.audiobooks.data.recommend.TextEmbedder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = App::class)
class MainViewModelRecommendationFactsRoomTest {
    @Test(timeout = 120_000)
    fun `facts-only description update rewarms the accepted recommendation frame without retry`() = runBlocking {
        val probe = ExternalBoundary()
        val store = ViewModelStore()
        val observers = SupervisorJob()
        val observationScope = CoroutineScope(observers + Dispatchers.Default)
        var primaryFailure: Throwable? = null
        try {
            withTimeout(75_000) {
                val app = ApplicationProvider.getApplicationContext<App>()
                val dao = app.audiobookDao
                val db = AudiobookDatabase.getDatabase(app)
                assertSame("SETUP: transaction and App DAO belong to one Room instance", db.audiobookDao(), dao)
                val modelDir = File(app.filesDir, "models/e5")
                assertFalse("SETUP_NOT_REACHED: no downloaded model inventory", modelDir.exists())
                app.recommendationPreferences.setLocalEnabled(true)
                db.withTransaction { seed(dao) }
                assertEquals(listOf(WorkFacts(ALPHA, "", FIRST_DESCRIPTION)), dao.observeWorkFacts().first())
                assertTrue(dao.popularityAssertions(PopularityAssertionEntity.KIND_RANK).isEmpty())
                assertTrue(dao.popularityAssertions(PopularityAssertionEntity.KIND_RATING).isEmpty())
                val originalBook = checkNotNull(dao.getAudiobookById(ALPHA_BOOK))
                val originalEntry = checkNotNull(dao.libraryEntryById(ALPHA_BOOK))
                val originalWorks = listOf(ALPHA, BETA, GAMMA).map { checkNotNull(dao.getWorkById(it)) }
                val originalSources = originalWorks.flatMap { dao.getWorkSourcesForWorkSync(it.id) }

                val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        require(modelClass == MainViewModel::class.java)
                        return modelClass.cast(MainViewModel(app, recommendationBackendLoader = probe.loader))
                    }
                })[MainViewModel::class.java]
                val publicCatalog = AtomicReference<List<WorkEntity>>(emptyList())
                observationScope.launch { vm.recommendationContent.collect {} }
                observationScope.launch { vm.libraryBooks.collect {} }
                observationScope.launch { vm.sourceCatalog.discoverableWorks.collect { publicCatalog.set(it) } }
                awaitControl("the three discoverable Works and favourite Alpha") {
                    publicCatalog.get().map { it.id }.toSet() == setOf(ALPHA, BETA, GAMMA) &&
                        vm.libraryBooks.value.let { it.size == 1 && it.single().book.id == ALPHA_BOOK && it.single().book.isFavorite }
                }
                assertTrue(vm.recommendationSettings.value.localPersonalizationEnabled)
                assertEquals(RecommendationPersonalization.ScoreWeights(), vm.recommendationSettings.value.weights)

                awaitControl("the subscribed public row settles as ready Beta") {
                    vm.recommendationContent.value.let {
                        it.ready && it.backendStatus is RecommendationBackendStatus.E5 &&
                            it.books.map { row -> row.candidate.id } == listOf(BETA)
                    }
                }
                probe.assertHealthy()
                assertEquals(
                    "R1_FACTS_MUST_REACH_INITIAL_WARM_TEXT",
                    listOf(ALPHA_FIRST_TEXT),
                    probe.alphaTexts(),
                )
                assertEquals(originalBook, dao.getAudiobookById(ALPHA_BOOK))
                assertEquals(originalEntry, dao.libraryEntryById(ALPHA_BOOK))
                assertEquals(listOf(sentinel()), dao.embeddingVectors(listOf(SENTINEL)))

                val unchangedCandidateCache = dao.embeddingVectors(listOf(BETA, GAMMA))
                assertEquals(2, unchangedCandidateCache.size)
                val originalAlphaCache = dao.embeddingVectors(listOf(ALPHA)).single()
                assertArrayEquals(byteArrayOf(0, 0, -128, 63, 0, 0, 0, 0), originalAlphaCache.vector)
                val factoryCallsBeforeFacts = probe.factoryCalls.get()
                val closesBeforeFacts = probe.closeCalls.get()

                dao.updateBookDescription(ALPHA_BOOK, SECOND_DESCRIPTION)
                assertEquals(listOf(WorkFacts(ALPHA, "", SECOND_DESCRIPTION)), dao.observeWorkFacts().first())
                awaitControl("a facts-only update starts the new Alpha external input") { probe.secondAlphaEntered.count == 0L }
                awaitControl("changed facts expose an empty not-ready frame while the new embed is held") {
                    vm.recommendationContent.value.let { !it.ready && it.books.isEmpty() }
                }
                assertEquals(1, probe.secondAlphaCalls.get())
                assertEquals(factoryCallsBeforeFacts, probe.factoryCalls.get())
                assertEquals(closesBeforeFacts, probe.closeCalls.get())
                assertEquals(1, probe.activeEmbeds.get())
                assertEquals(listOf(ALPHA_FIRST_TEXT, ALPHA_SECOND_TEXT), probe.alphaTexts())
                assertEquals(originalAlphaCache, dao.embeddingVectors(listOf(ALPHA)).single())
                assertEquals(unchangedCandidateCache, dao.embeddingVectors(listOf(BETA, GAMMA)))
                assertEquals(listOf(sentinel()), dao.embeddingVectors(listOf(SENTINEL)))

                probe.releaseSecondAlpha.countDown()
                awaitControl("the new accepted frame contains Gamma instead of Beta") {
                    vm.recommendationContent.value.let {
                        it.ready && it.backendStatus is RecommendationBackendStatus.E5 &&
                            it.books.map { row -> row.candidate.id } == listOf(GAMMA)
                    }
                }
                assertEquals(1, probe.secondAlphaReturned.get())
                assertEquals(0, probe.activeEmbeds.get())
                assertEquals(factoryCallsBeforeFacts, probe.factoryCalls.get())
                assertEquals(closesBeforeFacts, probe.closeCalls.get())
                assertEquals(0, probe.bundledCalls.get())
                assertArrayEquals(
                    byteArrayOf(0, 0, 0, 0, 0, 0, -128, 63),
                    dao.embeddingVectors(listOf(ALPHA)).single().vector,
                )
                assertEquals(unchangedCandidateCache, dao.embeddingVectors(listOf(BETA, GAMMA)))
                assertEquals(originalBook.copy(description = SECOND_DESCRIPTION), dao.getAudiobookById(ALPHA_BOOK))
                assertEquals(originalEntry, dao.libraryEntryById(ALPHA_BOOK))
                assertEquals(originalWorks, listOf(ALPHA, BETA, GAMMA).map { dao.getWorkById(it) })
                assertEquals(originalSources, originalWorks.flatMap { dao.getWorkSourcesForWorkSync(it.id) })
                assertEquals(listOf(sentinel()), dao.embeddingVectors(listOf(SENTINEL)))
                assertFalse("A facts update does not install model files", modelDir.exists())
                probe.assertHealthy()
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            probe.releaseSecondAlpha.countDown()
            try {
                withContext(NonCancellable) {
                    withTimeout(20_000) {
                        store.clear()
                        awaitControl("all controlled handles close after Store.clear") {
                            probe.activeEmbeds.get() == 0 && probe.closeCalls.get() == probe.factoryCalls.get()
                        }
                        observers.cancelAndJoin()
                        shadowOf(Looper.getMainLooper()).idle()
                        probe.assertHealthy()
                    }
                }
            } catch (cleanupFailure: Throwable) {
                val failure = primaryFailure
                if (failure != null) failure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
            // App and the isolated Robolectric worker own their Room teardown.
        }
    }

    private suspend fun awaitControl(label: String, condition: () -> Boolean) {
        withTimeout(10_000) {
            while (!condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        assertTrue("SETUP_OR_BEHAVIOR_NOT_REACHED: $label", condition())
    }

    private suspend fun seed(dao: AudiobookDao) {
        for ((id, title, author, addedAt) in listOf(
            WorkLiteral(ALPHA, "Alpha", "Owner", 10L),
            WorkLiteral(BETA, "Beta", "Writer B", 20L),
            WorkLiteral(GAMMA, "Gamma", "Writer G", 30L),
        )) {
            dao.upsertWorkWithSource(
                WorkEntity(id, id, title, author, addedAt = addedAt),
                WorkSourceEntity("facts-$addedAt-claim", id, "sound-books", "https://example.test/facts/$addedAt", addedAt = addedAt),
            )
        }
        dao.insertAudiobooks(listOf(AudiobookEntity(
            id = ALPHA_BOOK, title = "Alpha", author = "Owner", narrator = "Facts Narrator",
            description = FIRST_DESCRIPTION, coverDrawableRes = 0, genre = "",
            sourceUrl = "https://example.test/facts/10",
        )))
        dao.upsertLibraryEntry(ALPHA_BOOK, ALPHA, isFavorite = true, createdAt = 10L, downloadProgress = 0f)
        dao.upsertEmbeddingVectors(listOf(sentinel()))
    }

    private fun sentinel() = EmbeddingVectorEntity(
        SENTINEL, "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        byteArrayOf(0, 0, -128, 63, 0, 0, 0, 0), 1_234L,
    )

    private data class WorkLiteral(val id: String, val title: String, val author: String, val addedAt: Long)

    private class ExternalBoundary {
        val secondAlphaEntered = CountDownLatch(1)
        val releaseSecondAlpha = CountDownLatch(1)
        val factoryCalls = AtomicInteger()
        val bundledCalls = AtomicInteger()
        val secondAlphaCalls = AtomicInteger()
        val secondAlphaReturned = AtomicInteger()
        val activeEmbeds = AtomicInteger()
        val closeCalls = AtomicInteger()
        private val texts = Collections.synchronizedList(mutableListOf<String>())
        private val failure = AtomicReference<String?>()
        val loader = RecommendationBackendLoader(
            installedFactory = { factoryCalls.incrementAndGet(); Session() },
            bundledFactory = { bundledCalls.incrementAndGet(); null },
        )

        private inner class Session : TextEmbedder, AutoCloseable {
            private val closed = AtomicBoolean(false)
            override val cacheContext = EmbeddingContext("r1-facts-controlled-v1", 2)

            override fun embed(text: String): FloatArray {
                if (closed.get()) failure.compareAndSet(null, "Embed used a closed controlled handle")
                activeEmbeds.incrementAndGet()
                texts += text
                try {
                    return when {
                        text == ALPHA_SECOND_TEXT -> {
                            secondAlphaCalls.incrementAndGet()
                            secondAlphaEntered.countDown()
                            if (!releaseSecondAlpha.await(30, TimeUnit.SECONDS)) {
                                failure.compareAndSet(null, "Second Alpha exceeded its external wait budget")
                                error("BEHAVIOR_NOT_REACHED: second Alpha release")
                            }
                            secondAlphaReturned.incrementAndGet()
                            floatArrayOf(0f, 1f)
                        }
                        // Legacy unenriched input gets a valid vector so RED is the literal-input assertion.
                        text.startsWith("Alpha\nOwner") -> floatArrayOf(1f, 0f)
                        text == BETA_TEXT -> floatArrayOf(1f, 0f)
                        text == GAMMA_TEXT -> floatArrayOf(0f, 1f)
                        else -> {
                            failure.compareAndSet(null, "Unexpected controlled embedding text")
                            floatArrayOf(1f, 0f)
                        }
                    }
                } finally {
                    activeEmbeds.decrementAndGet()
                }
            }

            override fun close() {
                if (!closed.compareAndSet(false, true)) failure.compareAndSet(null, "Controlled handle closed twice")
                if (activeEmbeds.get() != 0) failure.compareAndSet(null, "Controlled handle closed during active embed")
                closeCalls.incrementAndGet()
            }
        }

        fun alphaTexts(): List<String> = synchronized(texts) { texts.filter { it.startsWith("Alpha\nOwner") } }
        fun assertHealthy() = assertEquals("Controlled external boundary", null, failure.get())
    }

    private companion object {
        const val ALPHA = "alpha|owner"
        const val BETA = "beta|writer b"
        const val GAMMA = "gamma|writer g"
        const val ALPHA_BOOK = "facts-alpha-book"
        const val SENTINEL = "facts-unrelated-sentinel"
        const val FIRST_DESCRIPTION = "first focus"
        const val SECOND_DESCRIPTION = "second focus"
        const val ALPHA_FIRST_TEXT = "Alpha\nOwner\nfirst focus"
        const val ALPHA_SECOND_TEXT = "Alpha\nOwner\nsecond focus"
        const val BETA_TEXT = "Beta\nWriter B"
        const val GAMMA_TEXT = "Gamma\nWriter G"
    }
}
