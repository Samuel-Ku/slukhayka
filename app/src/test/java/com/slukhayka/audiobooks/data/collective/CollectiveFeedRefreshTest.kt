package com.slukhayka.audiobooks.data.collective

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #523 — the collective block's stale-while-revalidate contract: a fresh block
 * never touches the source, a stale one triggers exactly ONE refresh behind a
 * lease while everyone else renders the last good block, failures keep that
 * block, the lease expires, and an empty answer never activates.
 */
class CollectiveFeedRefreshTest {

    private val key = "soundbooks|new-arrivals"
    private var now = 1_000_000L

    private fun card(title: String) = CollectiveBlockCard(
        sourceId = "soundbooks",
        sourceUrl = "https://sound-books.net/$title",
        title = title,
        author = "Автор"
    )

    private fun block(
        cards: List<CollectiveBlockCard>,
        fetchedAt: Long,
        version: Long = 1L,
        kind: CollectiveBlockKind = CollectiveBlockKind.NEW_ARRIVALS
    ) = CollectiveFeedBlock(
        blockKey = key,
        sourceId = "soundbooks",
        kind = kind,
        name = "Новинки Sound-Books",
        provenanceUrl = "https://sound-books.net/new",
        cards = cards,
        fetchedAt = fetchedAt,
        staleAfter = fetchedAt + CollectiveBlockPolicy.ttlMillisFor(kind),
        version = version,
        lastAttempt = CollectiveAttempt(fetchedAt, CollectiveAttemptStatus.SUCCESS)
    )

    @Test
    fun `a fresh block answers without a source request`() = runBlocking {
        val store = InMemoryCollectiveFeedBlockStore()
        val seeded = block(listOf(card("А")), fetchedAt = now)
        store.activate(seeded)
        var calls = 0
        val refresh = CollectiveFeedRefresh(
            store = store,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = { calls++; CollectiveRefreshOutcome.Empty },
            clock = { now }
        )

        val rendered = refresh.read(key)

        assertEquals(seeded, rendered)
        assertEquals(0, calls)
    }

    @Test
    fun `a stale block refreshes once and bumps the version`() = runBlocking {
        val store = InMemoryCollectiveFeedBlockStore()
        store.activate(block(listOf(card("Стара")), fetchedAt = now - 7L * 60 * 60 * 1000))
        var calls = 0
        val refresh = CollectiveFeedRefresh(
            store = store,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = {
                calls++
                CollectiveRefreshOutcome.Success(block(listOf(card("Нова")), fetchedAt = 0L))
            },
            clock = { now }
        )

        val rendered = refresh.read(key)

        assertEquals(1, calls)
        assertEquals(listOf("Нова"), rendered!!.cards.map { it.title })
        assertEquals(2L, rendered.version)
        assertEquals(now, rendered.fetchedAt)
        assertEquals(now + CollectiveBlockPolicy.NEW_ARRIVALS_TTL_MS, rendered.staleAfter)
        assertEquals(CollectiveAttemptStatus.SUCCESS, rendered.lastAttempt.status)
    }

    @Test
    fun `concurrent clients give one owner and one source refresh`() = runBlocking {
        val store = InMemoryCollectiveFeedBlockStore()
        store.activate(block(listOf(card("Стара")), fetchedAt = now - 7L * 60 * 60 * 1000))
        val gate = CompletableDeferred<Unit>()
        val fetchStarted = CompletableDeferred<Unit>()
        var calls = 0
        val refresh = CollectiveFeedRefresh(
            store = store,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = {
                calls++
                fetchStarted.complete(Unit)
                gate.await()
                CollectiveRefreshOutcome.Success(block(listOf(card("Нова")), fetchedAt = 0L))
            },
            clock = { now }
        )

        val owner = async { refresh.read(key) }
        fetchStarted.await()
        // While the owner is mid-fetch, another client renders the last good
        // block immediately, without a request of its own.
        val other = refresh.read(key)
        assertEquals(listOf("Стара"), other!!.cards.map { it.title })

        gate.complete(Unit)
        val owned = owner.await()
        assertEquals(1, calls)
        assertEquals(2L, owned!!.version)
    }

    @Test(timeout = 10_000L)
    fun `an expired refresh cannot replace the newer block from its successor`() = runTest {
        val store = InMemoryCollectiveFeedBlockStore()
        store.activate(block(listOf(card("Стара")), fetchedAt = now - 7L * 60 * 60 * 1000))
        val firstStarted = CompletableDeferred<Unit>()
        val returnFirst = CompletableDeferred<Unit>()
        var calls = 0
        val refresh = CollectiveFeedRefresh(
            store = store,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = {
                when (++calls) {
                    1 -> {
                        firstStarted.complete(Unit)
                        returnFirst.await()
                        CollectiveRefreshOutcome.Success(block(listOf(card("Запізніла A")), fetchedAt = 0L))
                    }
                    2 -> CollectiveRefreshOutcome.Success(block(listOf(card("Нова B")), fetchedAt = 0L))
                    else -> error("A fresh successor must not trigger another source request")
                }
            },
            clock = { now }
        )

        val first = async { refresh.read(key) }
        firstStarted.await()
        now += CollectiveFeedRefresh.DEFAULT_LEASE_TTL_MS + 1L
        val successor = refresh.read(key)
        assertEquals(listOf("Нова B"), successor!!.cards.map { it.title })
        returnFirst.complete(Unit)

        assertEquals("The expired owner must return the committed successor", successor, first.await())
        assertEquals(successor, store.active(key))
        assertEquals(successor, refresh.read(key))
        assertEquals(2, calls)
    }

    @Test(timeout = 10_000L)
    fun `an expired owner cannot release the successor lease to admit another request`() = runTest {
        val store = InMemoryCollectiveFeedBlockStore()
        store.activate(block(listOf(card("Стара")), fetchedAt = now - 7L * 60 * 60 * 1000))
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val returnFirst = CompletableDeferred<Unit>()
        val returnSecond = CompletableDeferred<Unit>()
        var calls = 0
        val refresh = CollectiveFeedRefresh(
            store = store,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = {
                when (++calls) {
                    1 -> {
                        firstStarted.complete(Unit)
                        returnFirst.await()
                        CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.TIMEOUT)
                    }
                    2 -> {
                        secondStarted.complete(Unit)
                        returnSecond.await()
                        CollectiveRefreshOutcome.Success(block(listOf(card("Нова B")), fetchedAt = 0L))
                    }
                    else -> CollectiveRefreshOutcome.Empty
                }
            },
            clock = { now }
        )

        val first = async { refresh.read(key) }
        firstStarted.await()
        now += CollectiveFeedRefresh.DEFAULT_LEASE_TTL_MS + 1L
        val successor = async { refresh.read(key) }
        secondStarted.await()
        returnFirst.complete(Unit)
        first.await()
        val concurrentReader = refresh.read(key)
        returnSecond.complete(Unit)
        val committed = successor.await()

        assertEquals("The expired owner must not admit request C while B holds the lease", 2, calls)
        assertEquals(listOf("Стара"), concurrentReader!!.cards.map { it.title })
        assertEquals(listOf("Нова B"), committed!!.cards.map { it.title })
        assertEquals(committed, store.active(key))
    }

    @Test(timeout = 10_000L)
    fun `a cancelled expired owner cannot continue after fetch swallowed cancellation`() = runTest {
        val store = InMemoryCollectiveFeedBlockStore()
        val seeded = block(listOf(card("Стара")), fetchedAt = now - 7L * 60 * 60 * 1000)
        store.activate(seeded)
        val refresh = CollectiveFeedRefresh(
            store = store,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = {
                currentCoroutineContext().cancel()
                now += CollectiveFeedRefresh.DEFAULT_LEASE_TTL_MS + 1L
                CollectiveRefreshOutcome.Empty
            },
            clock = { now }
        )
        var successfulContinuation = false
        val caller = async {
            refresh.read(key)
            successfulContinuation = true
        }
        caller.join()

        assertFalse("Cancelled read must not allow a successful caller continuation", successfulContinuation)
        assertTrue(caller.isCancelled)
        assertEquals(seeded, store.active(key))
    }

    @Test
    fun `every failure keeps the last good block and records the status`() = runBlocking {
        val failures = mapOf(
            CollectiveAttemptStatus.TIMEOUT to CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.TIMEOUT),
            CollectiveAttemptStatus.FORBIDDEN to CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.FORBIDDEN),
            CollectiveAttemptStatus.NOT_FOUND to CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.NOT_FOUND),
            CollectiveAttemptStatus.CHALLENGE to CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.CHALLENGE),
            CollectiveAttemptStatus.PARSE_FAILURE to CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.PARSE_FAILURE),
            CollectiveAttemptStatus.EMPTY to CollectiveRefreshOutcome.Empty
        )
        for ((status, outcome) in failures) {
            val store = InMemoryCollectiveFeedBlockStore()
            val good = block(listOf(card("Стара")), fetchedAt = now - 7L * 60 * 60 * 1000)
            store.activate(good)
            val refresh = CollectiveFeedRefresh(
                store = store,
                lease = InMemoryCollectiveRefreshLease(),
                fetch = { outcome },
                clock = { now }
            )

            val rendered = refresh.read(key)

            assertEquals("$status keeps the cards", good.cards, rendered!!.cards)
            assertEquals("$status keeps the version", 1L, rendered.version)
            assertEquals("$status is recorded", status, rendered.lastAttempt.status)
        }
    }

    @Test
    fun `an empty success never activates`() = runBlocking {
        val store = InMemoryCollectiveFeedBlockStore()
        val good = block(listOf(card("Стара")), fetchedAt = now - 7L * 60 * 60 * 1000)
        store.activate(good)
        val refresh = CollectiveFeedRefresh(
            store = store,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = { CollectiveRefreshOutcome.Success(block(emptyList(), fetchedAt = 0L)) },
            clock = { now }
        )

        val rendered = refresh.read(key)

        assertEquals(good.cards, rendered!!.cards)
        assertEquals(1L, rendered.version)
        assertEquals(CollectiveAttemptStatus.EMPTY, rendered.lastAttempt.status)
    }

    @Test
    fun `an abandoned lease expires and can be taken over`() = runBlocking {
        val store = InMemoryCollectiveFeedBlockStore()
        store.activate(block(listOf(card("Стара")), fetchedAt = now - 7L * 60 * 60 * 1000))
        val lease = InMemoryCollectiveRefreshLease()
        // Another client took the lease and died before releasing it.
        lease.acquire(key, now, 60_000L)
        var calls = 0
        val refresh = CollectiveFeedRefresh(
            store = store,
            lease = lease,
            fetch = { calls++; CollectiveRefreshOutcome.Success(block(listOf(card("Нова")), fetchedAt = 0L)) },
            clock = { now }
        )

        // Within the lease no one may refresh…
        refresh.read(key)
        assertEquals(0, calls)

        // …after it expires, the next client takes over.
        now += 60_000L + 1L
        val rendered = refresh.read(key)
        assertEquals(1, calls)
        assertEquals(2L, rendered!!.version)
    }

    @Test
    fun `a failure with no previous block stays honestly empty`() = runBlocking {
        val refresh = CollectiveFeedRefresh(
            store = InMemoryCollectiveFeedBlockStore(),
            lease = InMemoryCollectiveRefreshLease(),
            fetch = { CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.TIMEOUT) },
            clock = { now }
        )

        assertNull(refresh.read(key))
    }

    @Test
    fun `a once-fetched block then serves within its TTL`() = runBlocking {
        val store = InMemoryCollectiveFeedBlockStore()
        var calls = 0
        val refresh = CollectiveFeedRefresh(
            store = store,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = { calls++; CollectiveRefreshOutcome.Success(block(listOf(card("А")), fetchedAt = 0L)) },
            clock = { now }
        )

        refresh.read(key)
        now += 60_000L
        refresh.read(key)

        assertEquals("one page per open, then the TTL serves it", 1, calls)
    }

    @Test
    fun `block TTLs are six hours for arrivals and a day for the rest`() {
        assertEquals(6L * 60 * 60 * 1000, CollectiveBlockPolicy.ttlMillisFor(CollectiveBlockKind.NEW_ARRIVALS))
        assertEquals(24L * 60 * 60 * 1000, CollectiveBlockPolicy.ttlMillisFor(CollectiveBlockKind.RECOMMENDATIONS))
        assertEquals(24L * 60 * 60 * 1000, CollectiveBlockPolicy.ttlMillisFor(CollectiveBlockKind.COLLECTIONS))
    }

    @Test
    fun `stale is the exact expiry boundary`() {
        val value = block(listOf(card("А")), fetchedAt = 1_000L)
        assertTrue(!value.isStale(value.staleAfter - 1))
        assertTrue(value.isStale(value.staleAfter))
        assertNotNull(value.refreshed(listOf(card("Б")), fetchedAt = 2_000L, attempt = CollectiveAttempt(2_000L, CollectiveAttemptStatus.SUCCESS)))
    }

    @Test
    fun `an activated block is offered to the shared lane`() = runBlocking {
        val store = InMemoryCollectiveFeedBlockStore()
        store.activate(block(listOf(card("Стара")), fetchedAt = now - 7L * 60 * 60 * 1000))
        val published = mutableListOf<CollectiveFeedBlock>()
        val refresh = CollectiveFeedRefresh(
            store = store,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = { CollectiveRefreshOutcome.Success(block(listOf(card("Нова")), fetchedAt = 0L)) },
            clock = { now },
            onActivated = { published += it }
        )

        refresh.read(key)

        assertEquals(1, published.size)
        assertEquals(listOf("Нова"), published.single().cards.map { it.title })
        assertEquals(2L, published.single().version)
    }

    @Test
    fun `a failing publish never breaks the local read`() = runBlocking {
        val refresh = CollectiveFeedRefresh(
            store = InMemoryCollectiveFeedBlockStore(),
            lease = InMemoryCollectiveRefreshLease(),
            fetch = { CollectiveRefreshOutcome.Success(block(listOf(card("А")), fetchedAt = 0L)) },
            clock = { now },
            onActivated = { throw IllegalStateException("shared lane down") }
        )

        val rendered = refresh.read(key)

        assertEquals(listOf("А"), rendered!!.cards.map { it.title })
    }

    // --- #528 — an explicit listener action ---------------------------------

    @Test
    fun `an explicit action bypasses the TTL and shares the new block`() = runBlocking {
        val store = InMemoryCollectiveFeedBlockStore()
        // FRESH: a background read would never touch the source.
        store.activate(block(listOf(card("Стара")), fetchedAt = now))
        val published = mutableListOf<CollectiveFeedBlock>()
        val refresh = CollectiveFeedRefresh(
            store = store,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = { CollectiveRefreshOutcome.Empty },
            clock = { now },
            onActivated = { published += it }
        )

        val rendered = refresh.observeExplicit(key) {
            CollectiveRefreshOutcome.Success(block(listOf(card("Відкрита")), fetchedAt = 0L))
        }

        assertEquals(listOf("Відкрита"), rendered!!.cards.map { it.title })
        assertEquals(2L, rendered.version)
        assertEquals(1, published.size)
        assertEquals(listOf("Відкрита"), published.single().cards.map { it.title })
    }

    @Test
    fun `an explicit action wins over a held lease`() = runBlocking {
        val store = InMemoryCollectiveFeedBlockStore()
        val lease = InMemoryCollectiveRefreshLease()
        // Another client holds the background refresh lease right now.
        lease.acquire(key, now, 60_000L)
        var calls = 0
        val refresh = CollectiveFeedRefresh(
            store = store,
            lease = lease,
            fetch = { calls++; CollectiveRefreshOutcome.Empty },
            clock = { now }
        )

        val rendered = refresh.observeExplicit(key) {
            calls++
            CollectiveRefreshOutcome.Success(block(listOf(card("Моя дія")), fetchedAt = 0L))
        }

        assertEquals("the listener's action is its own owner", 1, calls)
        assertEquals(listOf("Моя дія"), rendered!!.cards.map { it.title })
    }

    @Test
    fun `an explicit failure keeps the previous block and records the status`() = runBlocking {
        val store = InMemoryCollectiveFeedBlockStore()
        val good = block(listOf(card("Стара")), fetchedAt = now)
        store.activate(good)
        val refresh = CollectiveFeedRefresh(
            store = store,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = { CollectiveRefreshOutcome.Empty },
            clock = { now }
        )

        val rendered = refresh.observeExplicit(key) {
            CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.CHALLENGE)
        }

        assertEquals(good.cards, rendered!!.cards)
        assertEquals(1L, rendered.version)
        assertEquals(CollectiveAttemptStatus.CHALLENGE, rendered.lastAttempt.status)
    }

    @Test
    fun `cancellation after the observed outcome keeps the previous active block and publication`() {
        val store = InMemoryCollectiveFeedBlockStore()
        val good = block(listOf(card("Попередня")), fetchedAt = now)
        runBlocking { store.activate(good) }
        var publications = 0
        val refresh = CollectiveFeedRefresh(store, InMemoryCollectiveRefreshLease(),
            fetch = { error("no source fetching") }, clock = { now }, onActivated = { publications++ })
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking {
                refresh.observeExplicit(key) {
                    currentCoroutineContext().cancel()
                    CollectiveRefreshOutcome.Success(block(listOf(card("Скасована")), fetchedAt = 0L))
                }
            }
        }
        assertEquals(good, runBlocking { store.active(key) })
        assertEquals(0, publications)
    }

    @Test
    fun `shared publication cancellation propagates after the local block is committed`() {
        val store = InMemoryCollectiveFeedBlockStore()
        runBlocking { store.activate(block(listOf(card("Попередня")), fetchedAt = now)) }
        val cancelled = kotlinx.coroutines.CancellationException("publication cancelled")
        val refresh = CollectiveFeedRefresh(store, InMemoryCollectiveRefreshLease(),
            fetch = { error("no source fetching") }, clock = { now }, onActivated = { throw cancelled })
        val thrown = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking {
                refresh.observeExplicit(key) {
                    CollectiveRefreshOutcome.Success(block(listOf(card("Відкрита")), fetchedAt = 0L))
                }
            }
        }
        assertTrue(
            "the publisher's cancellation must propagate directly or through stacktrace recovery",
            thrown === cancelled || thrown.cause === cancelled
        )
        assertEquals(cancelled.message, thrown.message)
        val committed = runBlocking { store.active(key) }!!
        assertEquals(listOf("Відкрита"), committed.cards.map { it.title })
        assertEquals(2L, committed.version)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `an unavailable publication returns the committed observation within one second`() = runTest {
        val store = InMemoryCollectiveFeedBlockStore()
        var publications = 0
        val refresh = CollectiveFeedRefresh(store, InMemoryCollectiveRefreshLease(),
            fetch = { error("no additional source request") }, clock = { now },
            onActivated = { publications++; awaitCancellation() })
        val result = async {
            refresh.observeExplicit(key) {
                CollectiveRefreshOutcome.Success(block(listOf(card("Відкрита")), fetchedAt = 0L))
            }
        }
        try {
            runCurrent()
            val committed = store.active(key)!!
            assertEquals(listOf("Відкрита"), committed.cards.map { it.title })
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue("an offline publication cannot hold the observed result", result.isCompleted)
            assertEquals(committed, result.await())
            assertEquals(committed, store.active(key))
            assertEquals(CollectiveAttemptStatus.SUCCESS, committed.lastAttempt.status)
            assertEquals(1, publications)
        } finally {
            result.cancelAndJoin()
        }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `the configured publication budget bounds only the shared callback`() = runTest {
        val store = InMemoryCollectiveFeedBlockStore()
        val refresh = CollectiveFeedRefresh(store, InMemoryCollectiveRefreshLease(),
            fetch = { error("no source request") }, clock = { now },
            onActivated = { awaitCancellation() }, publicationTimeoutMs = 25L)
        val result = async {
            refresh.observeExplicit(key) {
                CollectiveRefreshOutcome.Success(block(listOf(card("Відкрита")), fetchedAt = 0L))
            }
        }
        try {
            runCurrent()
            val committed = store.active(key)!!
            advanceTimeBy(24L)
            runCurrent()
            assertTrue("the configured budget has not expired", !result.isCompleted)
            advanceTimeBy(1L)
            runCurrent()
            assertTrue("the configured budget has expired", result.isCompleted)
            assertEquals(committed, result.await())
            assertEquals(committed, store.active(key))
        } finally {
            result.cancelAndJoin()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `external cancellation cannot become success when the publisher swallows it`() = runTest {
        val store = InMemoryCollectiveFeedBlockStore()
        var swallowed = false
        var returned = false
        val refresh = CollectiveFeedRefresh(store, InMemoryCollectiveRefreshLease(),
            fetch = { error("no source request") }, clock = { now }, onActivated = {
                try {
                    awaitCancellation()
                } catch (_: kotlinx.coroutines.CancellationException) {
                    swallowed = true
                }
            })
        val result = async {
            refresh.observeExplicit(key) {
                CollectiveRefreshOutcome.Success(block(listOf(card("Відкрита")), fetchedAt = 0L))
            }
            returned = true
        }
        runCurrent()
        val committed = store.active(key)!!
        result.cancelAndJoin()
        assertTrue("the external publisher swallowed its cancellation", swallowed)
        assertTrue("the observed call cannot continue as a success", !returned)
        assertTrue(result.isCancelled)
        assertEquals(committed, store.active(key))
    }
}
