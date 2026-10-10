package com.slukhayka.audiobooks.data.collective

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Public feed reads preserve a real Room successor after an expired refresh returns. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomCollectiveExpiredOwnerReadTest {

    @Test(timeout = 60_000L)
    fun `an expired source response preserves the successor committed in Room without a third fetch`() = runBlocking {
        var ownedDatabase: AudiobookDatabase? = null
        var firstRead: Deferred<CollectiveFeedBlock?>? = null
        var bodyFailure: Throwable? = null
        val returnFirst = CompletableDeferred<Unit>()
        try {
            withTimeout(45_000L) {
                val context = ApplicationProvider.getApplicationContext<Application>()
                assertEquals(Application::class.java, context.javaClass)
                val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
                    .build().also { ownedDatabase = it }
                val store = RoomCollectiveFeedBlockStore(database.audiobookDao())
                val key = collectiveBlockKey("soundbooks", CollectiveBlockKind.NEW_ARRIVALS)
                assertEquals("soundbooks|NEW_ARRIVALS", key)
                val now = AtomicLong(1_700_000_000_000L)
                val seeded = block(key, "Попередня", 1_699_974_800_000L, 7L)
                assertTrue(store.activate(seeded))
                assertEquals(seeded, store.active(key))

                val firstStarted = CompletableDeferred<Unit>()
                val calls = AtomicInteger()
                val refresh = CollectiveFeedRefresh(
                    store = store,
                    lease = InMemoryCollectiveRefreshLease(),
                    fetch = { requestedKey ->
                        assertEquals(key, requestedKey)
                        when (calls.incrementAndGet()) {
                            1 -> {
                                firstStarted.complete(Unit)
                                returnFirst.await()
                                CollectiveRefreshOutcome.Success(block(key, "Запізніла A", 0L, 1L))
                            }
                            2 -> CollectiveRefreshOutcome.Success(block(key, "Нова B", 0L, 1L))
                            else -> CollectiveRefreshOutcome.Empty
                        }
                    },
                    clock = now::get
                )

                val first = async { refresh.read(key) }.also { firstRead = it }
                firstStarted.await()
                // A remains in fetch; its expired lease now allows successor B.
                now.addAndGet(60_001L)
                val successor = refresh.read(key)
                assertNotNull(successor)
                assertEquals(listOf("Нова B"), successor!!.cards.map { it.title })
                assertEquals(8L, successor.version)
                assertEquals(1_700_000_060_001L, successor.fetchedAt)
                assertEquals(CollectiveAttemptStatus.SUCCESS, successor.lastAttempt.status)
                assertEquals("B is committed before A returns", successor, store.active(key))

                returnFirst.complete(Unit)
                assertEquals("the expired read renders the committed successor", successor, first.await())
                assertEquals("the complete persisted successor survives A", successor, store.active(key))
                assertEquals("a later feed open reuses the fresh successor", successor, refresh.read(key))
                assertEquals("A and B are the only source fetches", 2, calls.get())
            }
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            returnFirst.complete(Unit)
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
                cleanup { withTimeout(10_000L) { firstRead?.cancelAndJoin() } }
                cleanup { withContext(Dispatchers.IO) { ownedDatabase?.close() } }
            }
            if (bodyFailure == null) cleanupFailure?.let { throw it }
        }
    }

    private fun block(key: String, title: String, fetchedAt: Long, version: Long) = CollectiveFeedBlock(
        blockKey = key,
        sourceId = "soundbooks",
        kind = CollectiveBlockKind.NEW_ARRIVALS,
        name = "Новинки Sound-Books",
        provenanceUrl = "https://sound-books.net/new",
        cards = listOf(CollectiveBlockCard("soundbooks", "https://sound-books.net/$version", title, "Автор")),
        fetchedAt = fetchedAt,
        staleAfter = fetchedAt + 21_600_000L,
        version = version,
        lastAttempt = CollectiveAttempt(fetchedAt, CollectiveAttemptStatus.SUCCESS)
    )
}
