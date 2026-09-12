package com.slukhayka.audiobooks.data.collective

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
