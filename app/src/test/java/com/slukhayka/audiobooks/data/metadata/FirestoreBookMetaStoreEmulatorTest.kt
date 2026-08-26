package com.slukhayka.audiobooks.data.metadata

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Real Android Firestore transaction coverage; run by the Emulator matrix. */
@RunWith(RobolectricTestRunner::class)
class FirestoreBookMetaStoreEmulatorTest {

    private lateinit var app: FirebaseApp
    private lateinit var firestore: FirebaseFirestore

    @Before
    fun connectToEmulator() {
        val endpoint = System.getenv(EMULATOR_HOST_ENV).orEmpty()
        assumeTrue("$EMULATOR_HOST_ENV must be set by the rules matrix", endpoint.isNotBlank())
        val (host, portText) = endpoint.split(':', limit = 2)
        val options = FirebaseOptions.Builder()
            .setProjectId(PROJECT_ID)
            .setApplicationId("1:0:android:duration-contract")
            .setApiKey("emulator-only")
            .build()
        app = FirebaseApp.initializeApp(
            ApplicationProvider.getApplicationContext<Context>(),
            options,
            "duration-contract-${System.nanoTime()}"
        )
        firestore = FirebaseFirestore.getInstance(app)
        firestore.useEmulator(host, portText.toInt())
    }

    @After
    fun disconnect() {
        if (::firestore.isInitialized) runWithMainLooperDrain {
            firestore.terminate().awaitResult()
        }
        if (::app.isInitialized) app.delete()
    }

    @Test
    fun `transaction creates once preserves canonical and deduplicates conflict`() {
        runWithMainLooperDrain {
            runBlocking {
                val store = FirestoreBookMetaStore(firestore)
                val editionId = "store-contract-edition"
                val canonicalProvenance = DurationProvenance("4read", 1_700_000_000_000L)
                val conflictProvenance = DurationProvenance(
                    source = "4read",
                    derivedAt = 1_700_000_001_000L,
                    method = DurationProvenance.METHOD_TECHNICAL_PROBE
                )

                store.putDuration(editionId, 7_200L, canonicalProvenance)
                store.putDuration(editionId, 7_200L, canonicalProvenance)
                store.putDuration(editionId, 8_000L, conflictProvenance)
                store.putDuration(
                    editionId,
                    8_000L,
                    conflictProvenance.copy(derivedAt = 1_800_000_000_000L)
                )

                val canonical = firestore.collection("book_durations")
                    .document(editionId)
                    .get()
                    .awaitResult()
                assertEquals(7_200L, SharedDurationCodec.fromMap(canonical.data.orEmpty()))

                val conflicts = firestore.collection("book_duration_conflicts")
                    .whereEqualTo("editionId", editionId)
                    .get()
                    .awaitResult()
                assertEquals(1, conflicts.size())
                val conflict = conflicts.documents.single()
                assertEquals("$editionId|8000|technical_probe", conflict.id)
                assertEquals(8_000L, DurationConflictCodec.fromMap(conflict.data.orEmpty())?.candidateSeconds)
                assertFalse(conflict.data.orEmpty().containsKey("uid"))
            }
        }
    }

    @Test
    fun `facet tracer creates reads updates and pages through the real store`() {
        runWithMainLooperDrain {
            runBlocking {
                val store = FirestoreBookMetaStore(firestore)
                val now = System.currentTimeMillis()
                val work = FacetAssertion.Work(
                    workId = "emulator-work|author",
                    sourceId = "emulator-source",
                    author = FacetPerson("author-emulator", "Автор"),
                    genres = listOf(FacetGenre("fantasy", "Фентезі")),
                    observedAt = now,
                    updatedAt = now
                )
                val edition = FacetAssertion.Edition(
                    editionId = "emulator-edition",
                    workId = work.workId,
                    sourceId = work.sourceId,
                    narrator = FacetPerson("narrator-emulator", "Оповідач"),
                    language = "uk",
                    durationRef = "emulator-edition",
                    durationBucket = FacetDurationBucket.FIVE_TO_TEN_HOURS,
                    chapterCount = 20,
                    completeness = FacetCompleteness.FULL,
                    availability = FacetAvailability(true, now, 86_400L),
                    observedAt = now,
                    updatedAt = now + 2L
                )

                store.putFacet(work)
                assertEquals(
                    work,
                    store.getFacet(FacetAssertionKey(work.kind, work.workId, work.sourceId))
                )

                val updatedWork = work.copy(
                    genres = listOf(
                        FacetGenre("fantasy", "Фентезі"),
                        FacetGenre("drama", "Драма")
                    ),
                    updatedAt = now + 1L
                )
                store.putFacet(updatedWork)
                store.putFacet(edition)

                assertEquals(
                    updatedWork,
                    store.getFacet(FacetAssertionKey(work.kind, work.workId, work.sourceId))
                )
                assertEquals(
                    listOf(updatedWork, edition),
                    store.getFacetPage(FacetCursor(now - 1L, "a"), 10).assertions
                )
            }
        }
    }

    /** Firestore's Android Task listeners target main; PAUSED Robolectric needs an explicit drain. */
    private fun runWithMainLooperDrain(block: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        thread(name = "firestore-emulator-contract") {
            runCatching(block).onFailure(failure::set)
            done.countDown()
        }
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!done.await(10, TimeUnit.MILLISECONDS)) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadlineNanos) { "Firestore store contract timed out" }
        }
        shadowOf(Looper.getMainLooper()).idle()
        failure.get()?.let { throw it }
    }

    private fun <T> Task<T>.awaitResult(): T {
        val latch = CountDownLatch(1)
        addOnCompleteListener { latch.countDown() }
        check(latch.await(20, TimeUnit.SECONDS)) { "Firebase Emulator task timed out" }
        exception?.let { throw it }
        return result
    }

    private companion object {
        const val EMULATOR_HOST_ENV = "SLUKHAYKA_FIRESTORE_EMULATOR_HOST"
        const val PROJECT_ID = "spec40-matrix"
    }
}
