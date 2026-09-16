package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.source.SourceBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spec-620 (#622) — the Feed Snapshot refresh module, pinned on its own
 * interface: one injected clock, one TTL per feed kind, memory and persistence
 * ageing from the SAME stamp, and distinct data/empty/failure outcomes.
 *
 * The mandatory scenario is the one that used to break: a failed fetch must
 * NOT become a fresh empty snapshot, and the very next refresh in the same
 * process must hit the source again and be able to publish success.
 */
class FeedSnapshotRefreshTest {

    private val key = FeedSnapshotPolicy.FEED_NEW_ARRIVALS
    private var now = 1_700_000_000_000L
    private val clock: () -> Long = { now }

    private class FakePersistence {
        val stored = mutableMapOf<String, PersistedFeedSnapshot>()
        var failWrites = false

        suspend fun read(sourceId: String, feedKey: String): PersistedFeedSnapshot? =
            stored["$sourceId|$feedKey"]

        suspend fun write(snapshot: PersistedFeedSnapshot): Boolean {
            if (failWrites) return false
            stored["${snapshot.sourceId}|${snapshot.feedKey}"] = snapshot
            return true
        }
    }

    private fun book(title: String) = SourceBook(
        title = title,
        author = "Автор",
        url = "https://example.com/$title",
        sourceId = "sluhayua"
    )

    private fun module(persistence: FakePersistence) = FeedSnapshotRefresh(
        nowMillis = clock,
        readPersisted = persistence::read,
        writePersisted = persistence::write
    )

    @Test
    fun `a live success is remembered and answers the next refresh without a fetch`() = runBlocking {
        val persistence = FakePersistence()
        val module = module(persistence)
        val calls = AtomicInteger(0)
        val live = listOf(book("Книга"))

        val first = module.refresh("sluhayua", key) { calls.incrementAndGet(); live }
        assertEquals(FeedRefreshOutcome.Data(live, now), first)
        assertEquals(1, calls.get())
        assertEquals(listOf(live), persistence.stored.values.map { it.books })

        val second = module.refresh("sluhayua", key) { calls.incrementAndGet(); live }
        assertEquals(FeedRefreshOutcome.Data(live, now), second)
        assertEquals("a fresh memory entry answers, the source is not hit", 1, calls.get())
    }

    @Test
    fun `a stored snapshot answers a cold module and keeps its original observed-at`() = runBlocking {
        val persistence = FakePersistence()
        val live = listOf(book("Книга"))
        module(persistence).refresh("sluhayua", key) { live }
        val storedAt = now

        // A brand-new module (a warm process restarted into the same store).
        now += FeedSnapshotPolicy.NEW_ARRIVALS_TTL_MS - 1
        val calls = AtomicInteger(0)
        val warm = module(persistence).refresh("sluhayua", key) { calls.incrementAndGet(); live }
        assertEquals(FeedRefreshOutcome.Data(live, storedAt), warm)
        assertEquals("inside the TTL the stored snapshot answers", 0, calls.get())

        // Reading it did not extend the TTL: one millisecond later it is stale.
        now += 1
        val refetched = module(persistence).refresh("sluhayua", key) {
            calls.incrementAndGet(); listOf(book("Новіша"))
        }
        assertEquals(FeedRefreshOutcome.Data(listOf(book("Новіша")), now), refetched)
        assertEquals("the exact TTL boundary is stale", 1, calls.get())
    }

    @Test
    fun `a snapshot stamped in the future is never fresh`() = runBlocking {
        val persistence = FakePersistence()
        persistence.stored["sluhayua|$key"] = PersistedFeedSnapshot(
            sourceId = "sluhayua",
            feedKey = key,
            books = listOf(book("З майбутнього")),
            observedAt = now + 3 * 60 * 60 * 1000L
        )
        val calls = AtomicInteger(0)
        val outcome = module(persistence).refresh("sluhayua", key) {
            calls.incrementAndGet(); listOf(book("Чесна"))
        }
        assertEquals(FeedRefreshOutcome.Data(listOf(book("Чесна")), now), outcome)
        assertEquals("a future stamp must not freeze the feed", 1, calls.get())
    }

    @Test
    fun `a network failure is never an empty snapshot and never touches the cache`() = runBlocking {
        val persistence = FakePersistence()
        val module = module(persistence)
        val live = listOf(book("Добра"))
        module.refresh("sluhayua", key) { live }
        val good = persistence.stored.values.single()

        // Age the snapshot past the TTL, then fail the network.
        now += FeedSnapshotPolicy.NEW_ARRIVALS_TTL_MS
        val failed = module.refresh("sluhayua", key) { throw java.io.IOException("no network") }
        assertEquals(FeedRefreshOutcome.Failure, failed)
        assertEquals("the previous snapshot is still there", good, persistence.stored.values.single())

        // The next attempt in the SAME process hits the source again and may succeed.
        val calls = AtomicInteger(0)
        val retried = module.refresh("sluhayua", key) {
            calls.incrementAndGet(); listOf(book("Після відмови"))
        }
        assertEquals(FeedRefreshOutcome.Data(listOf(book("Після відмови")), now), retried)
        assertEquals(1, calls.get())
        assertEquals(listOf(book("Після відмови")), persistence.stored.values.single().books)
    }

    @Test
    fun `a successful empty belongs to the caller but is not cached`() = runBlocking {
        val persistence = FakePersistence()
        val module = module(persistence)
        val live = listOf(book("Добра"))
        module.refresh("sluhayua", key) { live }
        val good = persistence.stored.values.single()

        now += FeedSnapshotPolicy.NEW_ARRIVALS_TTL_MS
        val calls = AtomicInteger(0)
        val empty = module.refresh("sluhayua", key) { calls.incrementAndGet(); emptyList() }
        assertEquals(FeedRefreshOutcome.Empty, empty)
        assertEquals("the previous good snapshot is not replaced", good, persistence.stored.values.single())
        assertEquals(1, calls.get())

        // A fresh module (cold memory) still retries: the empty never became a TTL cache.
        val afterEmpty = module(persistence).refresh("sluhayua", key) {
            calls.incrementAndGet(); listOf(book("Знову є"))
        }
        assertEquals(FeedRefreshOutcome.Data(listOf(book("Знову є")), now), afterEmpty)
        assertEquals(2, calls.get())
    }

    @Test
    fun `an explicit refresh bypasses a fresh cache`() = runBlocking {
        val persistence = FakePersistence()
        val module = module(persistence)
        val calls = AtomicInteger(0)
        module.refresh("sluhayua", key) { calls.incrementAndGet(); listOf(book("Стара")) }
        val forced = module.refresh("sluhayua", key, forceRefresh = true) {
            calls.incrementAndGet(); listOf(book("Свіжа"))
        }
        assertEquals(FeedRefreshOutcome.Data(listOf(book("Свіжа")), now), forced)
        assertEquals(2, calls.get())
    }

    @Test
    fun `a session-bound feed never reuses a snapshot`() = runBlocking {
        val persistence = FakePersistence()
        val module = module(persistence)
        val calls = AtomicInteger(0)
        module.refresh("cloudflare", key) { calls.incrementAndGet(); listOf(book("Сесія")) }
        val again = module.refresh("cloudflare", key, skipCache = true) {
            calls.incrementAndGet(); listOf(book("Нова сесія"))
        }
        assertEquals(FeedRefreshOutcome.Data(listOf(book("Нова сесія")), now), again)
        assertEquals(2, calls.get())
    }

    @Test
    fun `a failed storage write does not hide the live result`() = runBlocking {
        val persistence = FakePersistence().apply { failWrites = true }
        val module = module(persistence)
        val live = listOf(book("Жива"))
        val outcome = module.refresh("sluhayua", key) { live }
        assertEquals(FeedRefreshOutcome.Data(live, now), outcome)
        assertTrue(persistence.stored.isEmpty())
    }

    @Test
    fun `cancellation propagates and changes nothing`() = runBlocking {
        val persistence = FakePersistence()
        val module = module(persistence)
        val live = listOf(book("Добра"))
        module.refresh("sluhayua", key) { live }
        val good = persistence.stored.values.single()

        now += FeedSnapshotPolicy.NEW_ARRIVALS_TTL_MS
        val thrown = runCatching {
            module.refresh("sluhayua", key) { throw CancellationException("navigated away") }
        }.exceptionOrNull()
        assertTrue("cancellation is not swallowed into empty/failure", thrown is CancellationException)
        assertEquals(good, persistence.stored.values.single())
    }

    @Test
    fun `catalogue and new arrivals age on their own TTLs`() = runBlocking {
        val persistence = FakePersistence()
        val module = module(persistence)
        module.refresh("sluhayua", FeedSnapshotPolicy.FEED_CATALOG) { listOf(book("Каталог")) }
        now += FeedSnapshotPolicy.NEW_ARRIVALS_TTL_MS
        val calls = AtomicInteger(0)
        // The 24-hour catalogue snapshot is still fresh when new arrivals are not.
        module.refresh("sluhayua", FeedSnapshotPolicy.FEED_CATALOG) {
            calls.incrementAndGet(); listOf(book("Каталог"))
        }
        assertEquals(0, calls.get())
        assertNull(persistence.stored["sluhayua|${FeedSnapshotPolicy.FEED_NEW_ARRIVALS}"])
    }

    @Test
    fun `the returned books are the same instances that were fetched`() = runBlocking {
        val any = book("Той самий")
        val outcome = module(FakePersistence()).refresh("sluhayua", key) { listOf(any) }
        assertSame(any, (outcome as FeedRefreshOutcome.Data).books.single())
    }
    // ------------------------------------------------------------------
    // Spec-620 (#625) — coalescing, fetch parameters and session epochs
    // ------------------------------------------------------------------

    @Test
    fun `two equivalent refreshes share one live fetch`() = runBlocking {
        val module = module(FakePersistence())
        val calls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val live = listOf(book("Спільна"))

        val first = async {
            module.refresh("sluhayua", key, parameters = "limit=20") {
                calls.incrementAndGet(); gate.await(); live
            }
        }
        while (calls.get() == 0) delay(1)
        val second = async {
            module.refresh("sluhayua", key, parameters = "limit=20") {
                calls.incrementAndGet(); gate.await(); live
            }
        }
        delay(30)
        assertEquals("equivalent callers share the fetch", 1, calls.get())

        gate.complete(Unit)
        assertEquals(FeedRefreshOutcome.Data(live, now), first.await())
        assertEquals(FeedRefreshOutcome.Data(live, now), second.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun `different fetch parameters never share a fetch`() = runBlocking {
        val module = module(FakePersistence())
        val calls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val narrow = listOf(book("Двадцять"))
        val wide = listOf(book("Шістдесят"))

        val narrowJob = async {
            module.refresh("sluhayua", key, parameters = "limit=20") {
                calls.incrementAndGet(); gate.await(); narrow
            }
        }
        while (calls.get() == 0) delay(1)
        val wideJob = async {
            module.refresh("sluhayua", key, parameters = "limit=60") {
                calls.incrementAndGet(); gate.await(); wide
            }
        }
        while (calls.get() < 2) delay(1)
        gate.complete(Unit)
        assertEquals(FeedRefreshOutcome.Data(narrow, now), narrowJob.await())
        assertEquals(FeedRefreshOutcome.Data(wide, now), wideJob.await())
        assertEquals(2, calls.get())
    }

    @Test
    fun `an explicit refresh joins an equivalent active live fetch`() = runBlocking {
        val module = module(FakePersistence())
        val calls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val live = listOf(book("Спільна"))

        val normal = async {
            module.refresh("sluhayua", key, parameters = "limit=20") {
                calls.incrementAndGet(); gate.await(); live
            }
        }
        while (calls.get() == 0) delay(1)
        val forced = async {
            module.refresh("sluhayua", key, parameters = "limit=20", forceRefresh = true) {
                calls.incrementAndGet(); gate.await(); live
            }
        }
        delay(30)
        assertEquals("an explicit refresh joins the live fetch", 1, calls.get())
        gate.complete(Unit)
        assertEquals(FeedRefreshOutcome.Data(live, now), forced.await())
        assertEquals(FeedRefreshOutcome.Data(live, now), normal.await())
    }

    @Test
    fun `cancelling one waiter leaves the other waiting and completing`() = runBlocking {
        val module = module(FakePersistence())
        val calls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val live = listOf(book("Жива"))

        val keeper = async {
            module.refresh("sluhayua", key) { calls.incrementAndGet(); gate.await(); live }
        }
        while (calls.get() == 0) delay(1)
        val leaver = async {
            module.refresh("sluhayua", key) { calls.incrementAndGet(); gate.await(); live }
        }
        delay(30)
        leaver.cancel()
        gate.complete(Unit)
        assertEquals(FeedRefreshOutcome.Data(live, now), keeper.await())
        assertEquals("the shared fetch ran once and survived the leaver", 1, calls.get())
    }

    @Test
    fun `the shared fetch is cancelled when the last waiter leaves`() = runBlocking {
        val module = module(FakePersistence())
        val started = CompletableDeferred<Unit>()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val waiter = async {
            module.refresh("sluhayua", key) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.set(true)
                }
            }
        }
        started.await()
        waiter.cancel()
        waiter.join()
        delay(50)
        assertTrue("the fetch dies with its last waiter", cancelled.get())
    }

    @Test
    fun `a snapshot written for one limit never answers another`() = runBlocking {
        val persistence = FakePersistence()
        val module = module(persistence)
        val calls = AtomicInteger(0)

        module.refresh("sluhayua", key, parameters = "limit=60") {
            calls.incrementAndGet(); listOf(book("Шістдесят"))
        }
        assertEquals(1, calls.get())
        module.refresh("sluhayua", key, parameters = "limit=60") {
            calls.incrementAndGet(); listOf(book("Шістдесят"))
        }
        assertEquals("the same request is a cache hit", 1, calls.get())

        val narrow = module.refresh("sluhayua", key, parameters = "limit=20") {
            calls.incrementAndGet(); listOf(book("Двадцять"))
        }
        assertEquals("a different limit is a different request", 2, calls.get())
        assertEquals(FeedRefreshOutcome.Data(listOf(book("Двадцять")), now), narrow)
    }

    @Test
    fun `a newer session generation discards an older in-flight success`() = runBlocking {
        val persistence = FakePersistence()
        val module = module(persistence)
        val oldGate = CompletableDeferred<Unit>()
        val oldStarted = CompletableDeferred<Unit>()
        val old = async {
            module.refresh("cloudflare", key, sessionGeneration = 0L, skipCache = true) {
                oldStarted.complete(Unit)
                oldGate.await()
                listOf(book("Стара сесія"))
            }
        }
        oldStarted.await()

        val fresh = module.refresh("cloudflare", key, sessionGeneration = 1L, skipCache = true) {
            listOf(book("Нова сесія"))
        }
        assertEquals(FeedRefreshOutcome.Data(listOf(book("Нова сесія")), now), fresh)

        oldGate.complete(Unit)
        assertEquals(
            "an older session's success is not publishable",
            FeedRefreshOutcome.Failure,
            old.await()
        )
        assertEquals(listOf(book("Нова сесія")), persistence.stored.values.single().books)
    }
}
