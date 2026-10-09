package com.slukhayka.audiobooks.data.collections

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.slukhayka.audiobooks.data.achievements.ShowcaseAwardSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Real Firestore coverage for the PARTIAL writes on a published collection.
 *
 * This test exists because the in-memory fake cannot fail the way Firestore can.
 * [InMemorySharedCollections] stores whole immutable objects and `copy`s them,
 * so a partial update is a merge BY CONSTRUCTION — which is exactly why the
 * data-loss bug in #1150 was invisible to every unit test the repository had.
 * Only a real server (here: the emulator) can distinguish `set(fields)` from
 * `set(fields, SetOptions.merge())`, and that difference is whether a curator's
 * collection survives renaming themselves.
 *
 * Run by the rules matrix (`RUN_ANDROID_STORE_TEST=1 ./scripts/rules-matrix/run-all.sh`);
 * skipped, honestly, when no emulator host is configured.
 */
@RunWith(RobolectricTestRunner::class)
class FirestoreListenerCollectionsSharedStoreEmulatorTest {

    private lateinit var app: FirebaseApp
    private lateinit var firestore: FirebaseFirestore

    @Before
    fun connectToEmulator() {
        val endpoint = System.getenv(EMULATOR_HOST_ENV).orEmpty()
        assumeTrue("$EMULATOR_HOST_ENV must be set by the rules matrix", endpoint.isNotBlank())
        val (host, portText) = endpoint.split(':', limit = 2)
        val options = FirebaseOptions.Builder()
            .setProjectId(PROJECT_ID)
            .setApplicationId("1:0:android:curator-collections-contract")
            .setApiKey("emulator-only")
            .build()
        app = FirebaseApp.initializeApp(
            ApplicationProvider.getApplicationContext<Context>(),
            options,
            "curator-collections-${System.nanoTime()}"
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

    private fun collection(id: String) = ListenerCollection(
        id = id,
        title = "Магія",
        description = "про зорі",
        createdAt = 1L,
        items = listOf(
            ListenerCollectionItem("book-a", "бо атмосферно", 1L),
            ListenerCollectionItem("book-b", "бо тихо", 2L)
        )
    )

    /** Every field a published document must still carry after a partial write. */
    private val collectionFields = setOf(
        "authorId", "collectionId", "pseudonym", "title", "description",
        "bookIds", "reasons", "ratingSum", "ratingCount",
        "hidden", "reportCount", "items", "publishedAt"
    )

    private fun authorId(name: String) = CuratorIdentity.authorId(name)

    private suspend fun publish(
        store: FirestoreListenerCollectionsSharedStore,
        id: String,
        author: String
    ): String {
        val result = store.publish(
            collection = collection(id),
            authorId = authorId(author),
            pseudonym = "Старий",
            itemSnapshots = mapOf(
                "book-a" to PublishedCollectionFactory.ItemSnapshot("Книга А", "Автор А")
            )
        )
        assertEquals(PublishResult.Published, result)
        return "${authorId(author)}-$id"
    }

    /**
     * The document as the SERVER holds it, read back by raw field name rather
     * than through the codec — a codec would happily decode a document that had
     * lost half its fields into a smaller, still-valid collection, and the
     * whole point here is to notice that loss.
     */
    private fun rawDocument(documentId: String): Map<String, Any> =
        firestore.collection("curator_collections").document(documentId).get().awaitResult().data.orEmpty()

    @Test
    fun `renaming the pseudonym is a merge and never erases the collection`() {
        runWithMainLooperDrain {
            runBlocking {
                val store = FirestoreListenerCollectionsSharedStore(firestore)
                val author = authorId("rename-uid")
                val documentId = publish(store, "rename-c1", "rename-uid")

                assertEquals(PublishResult.Published, store.renameAuthor(author, "Новий"))

                val raw = rawDocument(documentId)
                assertTrue(
                    "a partial write must not drop the collection's own fields: " +
                        "missing ${collectionFields - raw.keys}",
                    raw.keys.containsAll(collectionFields)
                )
                assertEquals("Новий", raw["pseudonym"])
                assertEquals(listOf("book-a", "book-b"), raw["bookIds"])
                assertEquals("Магія", raw["title"])
                assertEquals("про зорі", raw["description"])
                assertEquals(2, (raw["reasons"] as List<*>).size)
                // Both books keep a display snapshot; the one we resolved at
                // publish time carries its title, the other stays an honest
                // empty rather than borrowing a neighbour's.
                assertEquals(2, (raw["items"] as List<*>).size)

                // And the store still reads it as the same collection.
                val readBack = store.publishedBy(author).single()
                assertEquals("Новий", readBack.pseudonym)
                assertEquals(listOf("book-a", "book-b"), readBack.bookIds)
            }
        }
    }

    @Test
    fun `publishing the showcase is a merge and never erases the collection`() {
        runWithMainLooperDrain {
            runBlocking {
                val store = FirestoreListenerCollectionsSharedStore(firestore)
                val author = authorId("showcase-uid")
                val documentId = publish(store, "showcase-c1", "showcase-uid")
                val awards = listOf(
                    ShowcaseAwardSnapshot("first_book", "Перша книга"),
                    ShowcaseAwardSnapshot("night_watch", "Нічний вартовий")
                )

                assertEquals(PublishResult.Published, store.publishShowcase(author, awards))

                val raw = rawDocument(documentId)
                assertTrue(
                    "the showcase must not cost the collection its own fields: " +
                        "missing ${collectionFields - raw.keys}",
                    raw.keys.containsAll(collectionFields)
                )
                assertEquals("Старий", raw["pseudonym"])
                assertEquals(listOf("book-a", "book-b"), raw["bookIds"])

                val readBack = store.publishedBy(author).single()
                assertEquals(awards, readBack.showcase)
                assertEquals(listOf("book-a", "book-b"), readBack.bookIds)
                assertEquals(listOf("бо атмосферно", "бо тихо"), readBack.reasons)
            }
        }
    }

    @Test
    fun `an empty showcase clears it and the collection survives`() {
        runWithMainLooperDrain {
            runBlocking {
                val store = FirestoreListenerCollectionsSharedStore(firestore)
                val author = authorId("clear-uid")
                val documentId = publish(store, "clear-c1", "clear-uid")
                store.publishShowcase(author, listOf(ShowcaseAwardSnapshot("first_book", "Перша книга")))

                assertEquals(PublishResult.Published, store.publishShowcase(author, emptyList()))

                val raw = rawDocument(documentId)
                assertTrue(raw.keys.containsAll(collectionFields))
                assertEquals(emptyList<Any>(), raw["showcase"])
                assertTrue(store.publishedBy(author).single().showcase.isEmpty())
            }
        }
    }

    @Test
    fun `a showcase without a published profile is refused and writes nothing`() {
        runWithMainLooperDrain {
            runBlocking {
                val store = FirestoreListenerCollectionsSharedStore(firestore)
                val author = authorId("no-profile-uid")

                assertEquals(
                    PublishResult.Refused("no-public-profile"),
                    store.publishShowcase(author, listOf(ShowcaseAwardSnapshot("first_book", "Перша книга")))
                )
                assertTrue(store.publishedBy(author).isEmpty())
            }
        }
    }

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
