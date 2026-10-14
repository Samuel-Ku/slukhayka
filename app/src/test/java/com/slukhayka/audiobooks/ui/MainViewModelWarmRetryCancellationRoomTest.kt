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
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import com.slukhayka.audiobooks.data.recommend.EmbeddingContext
import com.slukhayka.audiobooks.data.recommend.RecommendationBackendLoader
import com.slukhayka.audiobooks.data.recommend.RecommendationBackendStatus
import com.slukhayka.audiobooks.data.recommend.TextEmbedder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** New fixture on the approved public VM/loader/retry/Room seam, not recovered old bytes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = App::class)
class MainViewModelWarmRetryCancellationRoomTest {
    @Test(timeout = 120_000)
    fun `retry cancels the old warm after its current embed and starts an honest new backend`() = runBlocking {
        val probe = ExternalBoundary()
        val store = ViewModelStore()
        val observers = SupervisorJob()
        val observationScope = CoroutineScope(observers + Dispatchers.Default)
        var primaryFailure: Throwable? = null
        try {
            withTimeout(60_000) {
                val app = ApplicationProvider.getApplicationContext<App>()
                val db = AudiobookDatabase.getDatabase(app)
                val dao = app.audiobookDao
                val modelDir = File(app.filesDir, "models/e5")
                assertFalse("SETUP_NOT_REACHED: isolated App has no downloaded E5 files", modelDir.exists())
                app.recommendationPreferences.setLocalEnabled(false)
                db.withTransaction { seedCatalogAndSentinel(dao) }
                val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        require(modelClass == MainViewModel::class.java)
                        return modelClass.cast(MainViewModel(app, probe.loader))
                    }
                })[MainViewModel::class.java]
                observationScope.launch { vm.recommendationContent.collect {} }
                observationScope.launch { vm.libraryBooks.collect {} }
                val publicCatalog = AtomicReference<List<WorkEntity>>(emptyList())
                observationScope.launch { vm.sourceCatalog.discoverableWorks.collect { publicCatalog.set(it) } }

                // This no-download retry may initially capture an empty pool. Its actual
                // Keyword frame and public catalogue are positive bootstrap controls.
                vm.retryEmbeddingModel()
                awaitControl("actual bootstrap Keyword frame") {
                    vm.recommendationContent.value.let { it.ready && it.backendStatus is RecommendationBackendStatus.Keyword }
                }
                awaitControl("Gamma in the public discoverable pool") {
                    publicCatalog.get().map { it.id } == listOf(GAMMA)
                }
                assertEquals(1, probe.factoryCalls.get())
                assertEquals(1, probe.bundledCalls.get())
                assertSentinel(dao)

                // Add the library only after bootstrap. No hidden cache delete, SQL,
                // model inventory trick or ordinary refresh/download call is used.
                db.withTransaction { seedLibrarySignals(dao) }
                awaitControl("public favorite Alpha and Beta library rows") {
                    val rows = vm.libraryBooks.value
                    rows.map { it.book.id } == listOf(ALPHA_BOOK, BETA_BOOK) && rows.all { it.book.isFavorite }
                }
                assertTrue("SETUP_NOT_REACHED: bootstrap did not embed later library signals", dao.embeddingVectors(listOf(ALPHA, BETA)).isEmpty())
                vm.retryEmbeddingModel()
                awaitControl("controlled A reaches Gamma and holds library Alpha") { probe.alphaEntered.count == 0L }
                assertEquals(1, probe.gammaCalls.get())
                assertEquals(1, probe.alphaCalls.get())
                assertEquals(0, probe.betaCalls.get())
                assertEquals(listOf(GAMMA_TEXT, ALPHA_TEXT), probe.textSnapshot())
                assertEquals(0, probe.closeCalls.get())
                assertEquals(1, probe.activeEmbeds.get())
                probe.assertNoBoundaryFailure()
                val gammaBeforeRetry = dao.embeddingVectors(listOf(GAMMA)).single()
                assertArrayEquals(byteArrayOf(0, 0, -128, 63, 0, 0, 0, 0), gammaBeforeRetry.vector)
                assertTrue(dao.embeddingVectors(listOf(ALPHA, BETA)).isEmpty())
                assertSentinel(dao)

                vm.retryEmbeddingModel()
                awaitControl("live VM publishes the invalidated empty NotLoaded frame") {
                    vm.recommendationContent.value.let {
                        it.backendStatus == RecommendationBackendStatus.NotLoaded && !it.ready && it.books.isEmpty()
                    }
                }
                assertEquals("An in-use external handle stays open", 0, probe.closeCalls.get())
                assertEquals(1, probe.activeEmbeds.get())
                assertEquals("Single-flight keeps backend B queued while Alpha is held", 2, probe.factoryCalls.get())
                probe.releaseAlpha.countDown()
                awaitControl("old pass finishes and backend B reaches its external factory") { probe.bFactoryEntered.count == 0L }
                probe.assertNoBoundaryFailure()

                assertEquals(
                    "R1_RUNTIME_RETRY_MUST_CANCEL_OLD_WARM_BEFORE_BETA",
                    0,
                    probe.betaCalls.get(),
                )
                assertEquals(1, probe.alphaReturned.get())
                assertEquals(0, probe.activeEmbeds.get())
                assertEquals("Retired A closes exactly once after its active input", 1, probe.closeCalls.get())
                assertEquals("Native handle is never closed while embed is active", 0, probe.closedWhileActive.get())
                assertEquals(listOf(GAMMA_TEXT, ALPHA_TEXT), probe.textSnapshot())
                assertTrue("Invalidated A does not persist Alpha or Beta", dao.embeddingVectors(listOf(ALPHA, BETA)).isEmpty())
                assertEquals("Gamma was persisted before retry and remains exact", listOf(gammaBeforeRetry), dao.embeddingVectors(listOf(GAMMA)))
                assertSentinel(dao)
                assertEquals(3, probe.factoryCalls.get())
                assertEquals("B has not yet reached bundled fallback", 1, probe.bundledCalls.get())
                assertEquals(RecommendationBackendStatus.NotLoaded, vm.recommendationContent.value.backendStatus)
                assertFalse(vm.recommendationContent.value.ready)

                probe.releaseBFactory.countDown()
                awaitControl("actual fresh Keyword backend publishes ready on the live VM") {
                    vm.recommendationContent.value.let { it.ready && it.backendStatus is RecommendationBackendStatus.Keyword }
                }
                assertEquals(1, probe.bFactoryExited.get())
                assertEquals(2, probe.bundledCalls.get())
                assertEquals(3, probe.factoryCalls.get())
                assertEquals(0, probe.betaCalls.get())
                assertEquals(1, probe.closeCalls.get())
                assertEquals(0, probe.closedWhileActive.get())
                assertTrue("Disabled personalization keeps recommendations empty", vm.recommendationContent.value.books.isEmpty())
                assertSentinel(dao)
                assertFalse("Runtime retries do not install or download E5", modelDir.exists())
                probe.assertNoBoundaryFailure()
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            probe.releaseAlpha.countDown()
            probe.releaseBFactory.countDown()
            try {
                withContext(NonCancellable) {
                    withTimeout(20_000) {
                        store.clear()
                        awaitControl("all owned external work closes after public Store.clear") {
                            probe.activeEmbeds.get() == 0 &&
                                (probe.factoryCalls.get() < 2 || probe.closeCalls.get() == 1) &&
                                (probe.bFactoryEntered.count != 0L || probe.bFactoryExited.get() == 1)
                        }
                        observers.cancelAndJoin()
                        // Store/flow cancellation can post its last Main dispatcher cleanup.
                        shadowOf(Looper.getMainLooper()).idle()
                        probe.assertNoBoundaryFailure()
                    }
                }
            } catch (cleanupFailure: Throwable) {
                val failure = primaryFailure
                if (failure != null) failure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
            // App owns its shared Room DB and startup modules. Do not close that DB
            // underneath those modules; isolated Robolectric/worker teardown owns it.
        }
    }

    private suspend fun awaitControl(label: String, condition: () -> Boolean) {
        withTimeout(10_000) {
            while (!condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        assertTrue("SETUP_NOT_REACHED: $label", condition())
    }

    private suspend fun seedCatalogAndSentinel(dao: AudiobookDao) {
        dao.upsertWorkWithSource(
            WorkEntity(GAMMA, GAMMA, "Gamma", AUTHOR, addedAt = 30L),
            WorkSourceEntity("runtime-retry-gamma-claim", GAMMA, "sound-books", "https://example.test/runtime-retry/gamma", addedAt = 30L),
        )
        dao.upsertEmbeddingVectors(listOf(sentinel()))
    }

    private suspend fun seedLibrarySignals(dao: AudiobookDao) {
        for ((bookId, workId, title, addedAt) in listOf(
            LibraryLiteral(ALPHA_BOOK, ALPHA, "Alpha", 10L),
            LibraryLiteral(BETA_BOOK, BETA, "Beta", 20L),
        )) {
            dao.upsertWork(WorkEntity(workId, workId, title, AUTHOR, addedAt = addedAt))
            dao.insertAudiobooks(listOf(AudiobookEntity(
                id = bookId, title = title, author = AUTHOR, narrator = "Runtime Narrator",
                description = "", coverDrawableRes = 0, genre = "", sourceUrl = "",
            )))
            dao.upsertLibraryEntry(bookId, workId, isFavorite = true, createdAt = addedAt, downloadProgress = 0f)
        }
    }

    private data class LibraryLiteral(val bookId: String, val workId: String, val title: String, val addedAt: Long)

    private suspend fun assertSentinel(dao: AudiobookDao) {
        val rows = dao.embeddingVectors(listOf(SENTINEL))
        assertEquals(listOf(sentinel()), rows)
        assertArrayEquals(byteArrayOf(0, 0, -128, 63, 0, 0, 0, 0), rows.single().vector)
    }

    private fun sentinel() = EmbeddingVectorEntity(
        SENTINEL, "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        byteArrayOf(0, 0, -128, 63, 0, 0, 0, 0), 1_234L,
    )

    private class ExternalBoundary {
        val alphaEntered = CountDownLatch(1)
        val releaseAlpha = CountDownLatch(1)
        val bFactoryEntered = CountDownLatch(1)
        val releaseBFactory = CountDownLatch(1)
        val factoryCalls = AtomicInteger()
        val bundledCalls = AtomicInteger()
        val gammaCalls = AtomicInteger()
        val alphaCalls = AtomicInteger()
        val alphaReturned = AtomicInteger()
        val betaCalls = AtomicInteger()
        val activeEmbeds = AtomicInteger()
        val closeCalls = AtomicInteger()
        val closedWhileActive = AtomicInteger()
        val bFactoryExited = AtomicInteger()
        private val texts = Collections.synchronizedList(mutableListOf<String>())
        private val boundaryFailure = AtomicReference<String?>(null)
        private val a = object : TextEmbedder, AutoCloseable {
            override val cacheContext = EmbeddingContext("runtime-warm-retry-external-A-v1", 2)
            override fun embed(text: String): FloatArray {
                activeEmbeds.incrementAndGet()
                texts += text
                try {
                    return when (text) {
                        GAMMA_TEXT -> { gammaCalls.incrementAndGet(); floatArrayOf(1f, 0f) }
                        ALPHA_TEXT -> {
                            alphaCalls.incrementAndGet()
                            alphaEntered.countDown()
                            if (!releaseAlpha.await(45, TimeUnit.SECONDS)) {
                                boundaryFailure.compareAndSet(null, "Alpha external wait exceeded its budget")
                                error("SETUP_NOT_REACHED: Alpha release")
                            }
                            alphaReturned.incrementAndGet()
                            floatArrayOf(.6f, .8f)
                        }
                        BETA_TEXT -> { betaCalls.incrementAndGet(); floatArrayOf(0f, 1f) }
                        else -> {
                            boundaryFailure.compareAndSet(null, "Unexpected external A text")
                            error("SETUP_NOT_REACHED: external text")
                        }
                    }
                } finally {
                    activeEmbeds.decrementAndGet()
                }
            }
            override fun close() {
                if (activeEmbeds.get() != 0) closedWhileActive.incrementAndGet()
                closeCalls.incrementAndGet()
            }
        }
        val loader = RecommendationBackendLoader(
            installedFactory = {
                when (factoryCalls.incrementAndGet()) {
                    1 -> null
                    2 -> a
                    3 -> {
                        bFactoryEntered.countDown()
                        if (!releaseBFactory.await(45, TimeUnit.SECONDS)) {
                            boundaryFailure.compareAndSet(null, "B external factory wait exceeded its budget")
                            error("SETUP_NOT_REACHED: B factory release")
                        }
                        bFactoryExited.incrementAndGet()
                        null
                    }
                    else -> {
                        boundaryFailure.compareAndSet(null, "Unexpected extra backend load")
                        null
                    }
                }
            },
            bundledFactory = { bundledCalls.incrementAndGet(); null },
        )
        fun textSnapshot(): List<String> = synchronized(texts) { texts.toList() }
        fun assertNoBoundaryFailure() = assertEquals("External boundary positive controls", null, boundaryFailure.get())
    }

    private companion object {
        const val AUTHOR = "Runtime Author"
        const val GAMMA = "runtime-retry-gamma"
        const val ALPHA = "runtime-retry-alpha"
        const val BETA = "runtime-retry-beta"
        const val ALPHA_BOOK = "runtime-retry-alpha-book"
        const val BETA_BOOK = "runtime-retry-beta-book"
        const val SENTINEL = "runtime-retry-cache-C"
        const val GAMMA_TEXT = "Gamma\nRuntime Author"
        const val ALPHA_TEXT = "Alpha\nRuntime Author"
        const val BETA_TEXT = "Beta\nRuntime Author"
    }
}
