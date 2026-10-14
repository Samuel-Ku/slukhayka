package com.slukhayka.audiobooks.data.catalog

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.source.SourceBook
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #620: cancellation before a real Room commit must not become a fresh memory answer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class FeedSnapshotCancellationPersistenceTest {
    @Test(timeout = 90_000L)
    fun `last waiter cancellation before Room persistence cannot become a fresh memory snapshot`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val databaseName = "q1-feed-cancellation-${UUID.randomUUID()}.db"
        val clock = AtomicLong(1_700_000_000_000L)
        val ownerJob = SupervisorJob()
        val ownerScope = CoroutineScope(ownerJob + Dispatchers.Default)
        var database: AudiobookDatabase? = null
        var module: FeedSnapshotRefresh? = null
        var waiter: Job? = null
        var bodyFailure: Throwable? = null
        try {
            assertEquals(Application::class.java, context.javaClass)
            val room = Room.databaseBuilder(context, AudiobookDatabase::class.java, databaseName).build()
            database = room
            val store = FeedSnapshotStore(room.audiobookDao(), clock::get)
            // Independent source payloads; unknown language stays unknown through the existing codec.
            val booksA = listOf(SourceBook(
                title = "Тарас Бульба", author = "Микола Гоголь", narrator = "Оповідач A",
                url = "https://sluhay.com.ua/q1-cancellation-a", coverImageUrl = "https://fixture.invalid/a.jpg",
                seriesTitle = "Збірка A", seriesIndex = 1, genre = "Повість", totalDurationSeconds = 3_600L,
                sourceId = "sluhayua", language = ""
            ))
            val booksB = listOf(SourceBook(
                title = "Лісова пісня", author = "Леся Українка", narrator = "Оповідач B",
                url = "https://sluhay.com.ua/q1-cancellation-b", coverImageUrl = "https://fixture.invalid/b.jpg",
                seriesTitle = "Збірка B", seriesIndex = 2, genre = "Драма", totalDurationSeconds = 7_200L,
                sourceId = "sluhayua", language = ""
            ))
            val booksC = listOf(SourceBook(
                title = "Кобзар", author = "Тарас Шевченко", narrator = "Оповідач C",
                url = "https://sluhay.com.ua/q1-cancellation-c", coverImageUrl = "https://fixture.invalid/c.jpg",
                seriesTitle = "Збірка C", seriesIndex = 3, genre = "Поезія", totalDurationSeconds = 10_800L,
                sourceId = "sluhayua", language = ""
            ))
            val seed = PersistedFeedSnapshot("sluhayua", "new-arrivals", booksA, 1_700_000_000_000L, "limit=60")
            assertTrue(withTimeout(15_000L) { store.saveSnapshot(seed) })
            assertEquals(seed, withTimeout(15_000L) { store.snapshot("sluhayua", "new-arrivals") })
            assertTrue("the snapshot uses an actual database file", context.getDatabasePath(databaseName).isFile)

            val writeEntered = CompletableDeferred<PersistedFeedSnapshot>()
            val writePermission = CompletableDeferred<Unit>()
            val writeCancelled = CompletableDeferred<Unit>()
            val delegatedWrites = CopyOnWriteArrayList<PersistedFeedSnapshot>()
            val fetched = CopyOnWriteArrayList<String>()
            val calls = AtomicInteger()
            val refresh = FeedSnapshotRefresh(
                nowMillis = clock::get,
                readPersisted = store::snapshot,
                writePersisted = { snapshot ->
                    if (snapshot.books == booksB) {
                        // The public persistence port is held BEFORE any actual Room transaction starts.
                        writeEntered.complete(snapshot)
                        try {
                            writePermission.await()
                        } catch (cancelled: CancellationException) {
                            writeCancelled.complete(Unit)
                            throw cancelled
                        }
                    }
                    delegatedWrites += snapshot
                    store.saveSnapshot(snapshot)
                },
                scope = ownerScope
            )
            module = refresh
            // Exactly six hours after A: A cannot explain a cache hit on the next ordinary refresh.
            clock.set(1_700_021_600_000L)
            val acceptedFetch = async {
                refresh.refresh("sluhayua", "new-arrivals", parameters = "limit=60", forceRefresh = true) {
                    calls.incrementAndGet()
                    fetched += "B"
                    booksB
                }
            }
            waiter = acceptedFetch
            val held = withTimeout(15_000L) { writeEntered.await() }
            assertEquals(PersistedFeedSnapshot("sluhayua", "new-arrivals", booksB, 1_700_021_600_000L, "limit=60"), held)
            assertEquals(listOf("B"), fetched.toList())
            assertEquals(1, calls.get())
            assertTrue(delegatedWrites.isEmpty())
            withTimeout(15_000L) { acceptedFetch.cancelAndJoin() }
            assertTrue("only the sole caller was cancelled", acceptedFetch.isCancelled)
            withTimeout(15_000L) {
                writeCancelled.await()
                // Public Job ownership: finish the actually cancelled child without cancelling its owner.
                ownerJob.children.toList().forEach { it.join() }
            }
            assertTrue("the provided refresh owner remains alive", ownerJob.isActive)
            assertTrue(delegatedWrites.isEmpty())
            assertEquals("Room retains the entire last good snapshot and its original stamp", seed,
                withTimeout(15_000L) { store.snapshot("sluhayua", "new-arrivals") })

            val next = withTimeout(15_000L) {
                refresh.refresh("sluhayua", "new-arrivals", parameters = "limit=60") {
                    calls.incrementAndGet()
                    fetched += "C"
                    booksC
                }
            }
            // FIRST decisive oracle. Nothing below gets credit when this assertion fails.
            assertEquals("Q1_POSTFETCH_CANCEL_FIRST: cancelled B must not answer the next ordinary refresh",
                FeedRefreshOutcome.Data(booksC, 1_700_021_600_000L), next)
            assertEquals(listOf("B", "C"), fetched.toList())
            assertEquals(2, calls.get())
            val committedC = PersistedFeedSnapshot("sluhayua", "new-arrivals", booksC, 1_700_021_600_000L, "limit=60")
            assertEquals(listOf(committedC), delegatedWrites.toList())
            assertEquals(committedC, withTimeout(15_000L) { store.snapshot("sluhayua", "new-arrivals") })
            room.close()
            database = null
            val reopened = Room.databaseBuilder(context, AudiobookDatabase::class.java, databaseName).build()
            database = reopened
            assertEquals("a reopened real file retains C without the cancelled payload", committedC,
                withTimeout(15_000L) { FeedSnapshotStore(reopened.audiobookDao(), clock::get).snapshot("sluhayua", "new-arrivals") })
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(action: suspend () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    val prior = bodyFailure ?: cleanupFailure
                    if (prior == null) cleanupFailure = failure else prior.addSuppressed(failure)
                }
            }
            withContext(NonCancellable) {
                cleanup { withTimeout(15_000L) { waiter?.cancelAndJoin() } }
                cleanup {
                    module?.close()
                }
                cleanup { withTimeout(15_000L) { ownerJob.cancelAndJoin() } }
                cleanup { database?.close() }
                cleanup {
                    if (context.getDatabasePath(databaseName).exists()) {
                        assertTrue("only the owned fixture database is removed", context.deleteDatabase(databaseName))
                    }
                }
            }
            if (bodyFailure == null) cleanupFailure?.let { throw it }
        }
    }
}
