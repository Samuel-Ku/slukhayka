package com.slukhayka.audiobooks.data.collective

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1238 — the cross-device half of stale-while-revalidate: one device fetches,
 * the follower receives the shared result without a second source request; an
 * expired or slow owner cannot overwrite the new one; a broken backend never
 * erases the last good block.
 */
class CollectiveSharedRefreshTest {

    private val key = newArrivalsBlockKey("soundbooks")
    private val provenance = "https://sound-books.net"
    private var now = 1_000_000L

    private fun card(title: String) = CollectiveBlockCard(
        sourceId = "soundbooks",
        sourceUrl = "https://sound-books.net/$title",
        title = title,
        author = "Автор"
    )

    private fun block(
        title: String,
        fetchedAt: Long,
        version: Long = 1L,
        revision: Long = 0L
    ) = CollectiveFeedBlock(
        blockKey = key,
        sourceId = "soundbooks",
        kind = CollectiveBlockKind.NEW_ARRIVALS,
        name = "Новинки Sound-Books",
        provenanceUrl = provenance,
        cards = listOf(card(title)),
        fetchedAt = fetchedAt,
        staleAfter = fetchedAt + CollectiveBlockPolicy.NEW_ARRIVALS_TTL_MS,
        version = version,
        lastAttempt = CollectiveAttempt(fetchedAt, CollectiveAttemptStatus.SUCCESS),
        revision = revision
    )

    private suspend fun staleLocal(title: String = "Стара"): InMemoryCollectiveFeedBlockStore {
        val store = InMemoryCollectiveFeedBlockStore()
        store.activate(block(title, fetchedAt = now - 7L * 60 * 60 * 1000))
        return store
    }

    @Test
    fun `two devices refresh one stale block with one source request and one shared result`() = runBlocking {
        val shared = InMemoryCollectiveSharedLeaseStore()
        val localOwner = staleLocal()
        val localFollower = staleLocal()
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var ownerCalls = 0
        var followerCalls = 0

        val owner = CollectiveFeedRefresh(
            store = localOwner,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = {
                ownerCalls++
                fetchStarted.complete(Unit)
                releaseFetch.await()
                CollectiveRefreshOutcome.Success(block("Спільна", fetchedAt = 0L))
            },
            clock = { now },
            sharedLease = CollectiveSharedLease(shared, ownerToken = "device-a", clock = { now })
        )
        val follower = CollectiveFeedRefresh(
            store = localFollower,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = {
                followerCalls++
                CollectiveRefreshOutcome.Success(block("Своя", fetchedAt = 0L))
            },
            clock = { now },
            sharedLease = CollectiveSharedLease(shared, ownerToken = "device-b", clock = { now }),
            sharedWaitTimeoutMs = 2_000L,
            sharedPollMs = 10L
        )

        val ownerResult = async(Dispatchers.Default) { owner.read(key) }
        fetchStarted.await()
        val followerResult = async(Dispatchers.Default) { follower.read(key) }
        // Give the follower time to lose the shared lease and enter its bounded
        // wait while the owner still holds the fence.
        delay(100L)
        releaseFetch.complete(Unit)

        val owned = withTimeout(5_000L) { ownerResult.await() }
        val followed = withTimeout(5_000L) { followerResult.await() }

        assertEquals("exactly one source request", 1, ownerCalls)
        assertEquals("the follower never hits the source", 0, followerCalls)
        assertEquals(listOf("Спільна"), owned!!.cards.map { it.title })
        assertEquals(listOf("Спільна"), followed!!.cards.map { it.title })
        assertEquals(1L, followed.revision)
        assertEquals(listOf("Спільна"), localFollower.active(key)!!.cards.map { it.title })
    }

    @Test
    fun `a broken backend degrades to the local lane and keeps the new observation`() = runBlocking {
        val offline = object : CollectiveSharedLeaseStore {
            override suspend fun acquire(
                blockKey: String,
                ownerToken: String,
                now: Long,
                ttlMs: Long
            ): CollectiveLeaseAttempt = throw IOException("offline")

            override suspend fun publish(
                block: CollectiveFeedBlock,
                grant: CollectiveLeaseGrant,
                now: Long
            ): CollectiveFeedBlock? = throw IOException("offline")
        }
        val local = staleLocal()
        val refresh = CollectiveFeedRefresh(
            store = local,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = { CollectiveRefreshOutcome.Success(block("Локальна", fetchedAt = 0L)) },
            clock = { now },
            sharedLease = CollectiveSharedLease(offline, ownerToken = "device-a", clock = { now })
        )

        val result = refresh.read(key)

        assertEquals(listOf("Локальна"), result!!.cards.map { it.title })
        assertEquals(0L, result.revision)
        assertEquals(listOf("Локальна"), local.active(key)!!.cards.map { it.title })
    }

    @Test
    fun `a failed fetch never erases the last good block`() = runBlocking {
        val local = staleLocal()
        val refresh = CollectiveFeedRefresh(
            store = local,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = { CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.TIMEOUT) },
            clock = { now },
            sharedLease = CollectiveSharedLease(
                InMemoryCollectiveSharedLeaseStore(),
                ownerToken = "device-a",
                clock = { now }
            )
        )

        val result = refresh.read(key)

        assertEquals(listOf("Стара"), result!!.cards.map { it.title })
        assertEquals(CollectiveAttemptStatus.TIMEOUT, result.lastAttempt.status)
        assertEquals(listOf("Стара"), local.active(key)!!.cards.map { it.title })
    }

    @Test
    fun `a slow old owner keeps its local observation but never overwrites the new shared one`() = runBlocking {
        val shared = InMemoryCollectiveSharedLeaseStore()
        val localOld = staleLocal()
        val localNew = staleLocal()
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseOldFetch = CompletableDeferred<Unit>()

        val oldOwner = CollectiveFeedRefresh(
            store = localOld,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = {
                fetchStarted.complete(Unit)
                releaseOldFetch.await()
                CollectiveRefreshOutcome.Success(block("Повільна", fetchedAt = 0L))
            },
            clock = { now },
            sharedLease = CollectiveSharedLease(shared, ownerToken = "device-old", clock = { now })
        )
        val newOwner = CollectiveFeedRefresh(
            store = localNew,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = { CollectiveRefreshOutcome.Success(block("Нова", fetchedAt = 0L)) },
            clock = { now },
            sharedLease = CollectiveSharedLease(shared, ownerToken = "device-new", clock = { now })
        )

        val oldResult = async(Dispatchers.Default) { oldOwner.read(key) }
        fetchStarted.await()
        // The old owner stalls past its lease; the new one takes over.
        now += 1_001L
        val fresh = newOwner.read(key)
        releaseOldFetch.complete(Unit)
        val stale = withTimeout(5_000L) { oldResult.await() }

        assertEquals(listOf("Нова"), fresh!!.cards.map { it.title })
        assertEquals(1L, fresh.revision)
        assertEquals(listOf("Нова"), shared.published(key)!!.cards.map { it.title })
        assertEquals(1L, shared.published(key)!!.revision)
        // The slow owner's local observation is honest and stays local — it is
        // NOT erased, and it is not published over the winner either.
        assertEquals(listOf("Повільна"), stale!!.cards.map { it.title })
        assertEquals(0L, stale.revision)
        assertEquals(listOf("Повільна"), localOld.active(key)!!.cards.map { it.title })
    }

    @Test
    fun `a follower that times out answers the last good block`() = runBlocking {
        val shared = InMemoryCollectiveSharedLeaseStore()
        // Another device holds a live lease but never publishes.
        val holder = CollectiveSharedLease(shared, ownerToken = "device-a", clock = { now })
        assertTrue(holder.acquire(key) is CollectiveLeaseAttempt.Granted)
        val local = staleLocal()
        var calls = 0
        val refresh = CollectiveFeedRefresh(
            store = local,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = { calls++; CollectiveRefreshOutcome.Empty },
            clock = { now },
            sharedLease = CollectiveSharedLease(shared, ownerToken = "device-b", clock = { now }),
            sharedWaitTimeoutMs = 50L,
            sharedPollMs = 10L
        )

        val result = refresh.read(key)

        assertEquals(listOf("Стара"), result!!.cards.map { it.title })
        assertEquals(0, calls)
        assertNull(shared.published(key))
    }

    @Test
    fun `an explicit action shares through the fence and never bypasses a live owner`() = runBlocking {
        val shared = InMemoryCollectiveSharedLeaseStore()
        val holder = CollectiveSharedLease(shared, ownerToken = "device-a", clock = { now })
        assertTrue(holder.acquire(key) is CollectiveLeaseAttempt.Granted)
        val local = InMemoryCollectiveFeedBlockStore()
        val refresh = CollectiveFeedRefresh(
            store = local,
            lease = InMemoryCollectiveRefreshLease(),
            fetch = { CollectiveRefreshOutcome.Empty },
            clock = { now },
            sharedLease = CollectiveSharedLease(shared, ownerToken = "device-b", clock = { now })
        )

        val block = refresh.observeExplicit(key) {
            CollectiveRefreshOutcome.Success(block("Явна", fetchedAt = 0L))
        }

        // The listener's page is local, but the owner's shared row is untouched.
        assertEquals(listOf("Явна"), block!!.cards.map { it.title })
        assertEquals(listOf("Явна"), local.active(key)!!.cards.map { it.title })
        assertNull(shared.published(key))
    }
}
