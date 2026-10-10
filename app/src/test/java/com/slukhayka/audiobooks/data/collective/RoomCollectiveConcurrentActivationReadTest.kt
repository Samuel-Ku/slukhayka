package com.slukhayka.audiobooks.data.collective

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

/** A valid local refresh cannot replace an independently committed Room block. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomCollectiveConcurrentActivationReadTest {

    @Test(timeout = 60_000L)
    fun `an active refresh preserves a newer shared block committed before its source response`() = runBlocking {
        var ownedDatabase: AudiobookDatabase? = null
        var ownedRead: Deferred<CollectiveFeedBlock?>? = null
        var bodyFailure: Throwable? = null
        val returnSource = CompletableDeferred<Unit>()
        try {
            withTimeout(45_000L) {
                val context = ApplicationProvider.getApplicationContext<Application>()
                assertEquals(Application::class.java, context.javaClass)
                val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
                    .build().also { ownedDatabase = it }
                val store = RoomCollectiveFeedBlockStore(database.audiobookDao())
                val key = collectiveBlockKey("soundbooks", CollectiveBlockKind.NEW_ARRIVALS)
                val seeded = CollectiveFeedBlock(
                    blockKey = key,
                    sourceId = "soundbooks",
                    kind = CollectiveBlockKind.NEW_ARRIVALS,
                    name = "Попередні новинки",
                    provenanceUrl = "https://sound-books.net/new",
                    cards = listOf(CollectiveBlockCard("soundbooks", "https://sound-books.net/old", "Попередня", "Автор")),
                    fetchedAt = 1_699_974_800_000L,
                    staleAfter = 1_699_996_400_000L,
                    version = 7L,
                    lastAttempt = CollectiveAttempt(1_699_974_800_000L, CollectiveAttemptStatus.SUCCESS)
                )
                assertTrue(store.activate(seeded))
                assertEquals(seeded, store.active(key))

                val sourceStarted = CompletableDeferred<Unit>()
                val sourceCalls = AtomicInteger()
                val publications = AtomicInteger()
                val refresh = CollectiveFeedRefresh(
                    store = store,
                    lease = InMemoryCollectiveRefreshLease(),
                    fetch = { requestedKey ->
                        assertEquals(key, requestedKey)
                        sourceCalls.incrementAndGet()
                        sourceStarted.complete(Unit)
                        returnSource.await()
                        CollectiveRefreshOutcome.Success(seeded.copy(
                            name = "Запізнілий локальний результат A",
                            cards = listOf(CollectiveBlockCard("soundbooks", "https://sound-books.net/local-a", "Локальна A", "Автор A"))
                        ))
                    },
                    // A's lease stays valid: the independent writer does not acquire it.
                    clock = { 1_700_000_000_000L },
                    onActivated = { publications.incrementAndGet(); Unit }
                )
                val read = async { refresh.read(key) }.also { ownedRead = it }
                sourceStarted.await()

                val shared = CollectiveFeedBlock(
                    blockKey = key,
                    sourceId = "soundbooks",
                    kind = CollectiveBlockKind.NEW_ARRIVALS,
                    name = "Отримані спільні новинки B",
                    provenanceUrl = "https://sound-books.net/shared-new",
                    cards = listOf(
                        CollectiveBlockCard("soundbooks", "https://sound-books.net/shared-b-first", "Спільна B перша", "Автор B", "https://sound-books.net/b-first.jpg"),
                        CollectiveBlockCard("soundbooks", "https://sound-books.net/shared-b-second", "Спільна B друга", "Інший автор B", "https://sound-books.net/b-second.jpg")
                    ),
                    fetchedAt = 1_699_999_999_000L,
                    staleAfter = 1_700_021_599_000L,
                    version = 8L,
                    lastAttempt = CollectiveAttempt(1_699_999_999_000L, CollectiveAttemptStatus.SUCCESS)
                )
                // Independent local activation persists B while A is fetching.
                assertTrue(store.activate(shared))
                assertEquals("B is persisted while A is still in its source fetch", shared, store.active(key))

                returnSource.complete(Unit)
                assertEquals("the valid lease owner must render the intervening block", shared, read.await())
                assertEquals("the complete Room block B must survive A's candidate", shared, store.active(key))
                assertEquals("a later open reuses B without another fetch", shared, refresh.read(key))
                assertEquals("only A requested the source; B arrived independently", 1, sourceCalls.get())
                assertEquals("a rejected local candidate must not be published", 0, publications.get())
            }
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            returnSource.complete(Unit)
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(action: suspend () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    val primary = bodyFailure ?: cleanupFailure
                    if (primary == null) cleanupFailure = failure else primary.addSuppressed(failure)
                }
            }
            withContext(NonCancellable) {
                cleanup { withTimeout(10_000L) { ownedRead?.cancelAndJoin() } }
                cleanup { withContext(Dispatchers.IO) { ownedDatabase?.close() } }
            }
            if (bodyFailure == null) cleanupFailure?.let { throw it }
        }
    }
}
