package com.slukhayka.audiobooks.data.collective

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1238 — the shared lease contract: one owner at a time, expiry/takeover with
 * a bumped fence, a slow old owner unable to publish over the new one, and a
 * transaction that may be retried without duplicating the side effect.
 */
class CollectiveSharedLeaseTest {

    private val key = "soundbooks|COLLECTIONS|sound-books.net/collections/do-a"
    private var now = 1_000_000L

    private fun block(title: String, revision: Long = 0L) = CollectiveFeedBlock(
        blockKey = key,
        sourceId = "soundbooks",
        kind = CollectiveBlockKind.COLLECTIONS,
        name = "Добірка",
        provenanceUrl = "https://sound-books.net/collections/do-a",
        cards = listOf(CollectiveBlockCard("soundbooks", "https://sound-books.net/$title", title, "Автор")),
        fetchedAt = now,
        staleAfter = now + CollectiveBlockPolicy.DEFAULT_TTL_MS,
        version = 1L,
        lastAttempt = CollectiveAttempt(now, CollectiveAttemptStatus.SUCCESS),
        revision = revision
    )

    private fun lease(store: CollectiveSharedLeaseStore, token: String, ttlMs: Long = 1_000L) =
        CollectiveSharedLease(store = store, ownerToken = token, ttlMs = ttlMs, clock = { now })

    @Test
    fun `only one owner wins and the loser is told it is busy`() = runBlocking {
        val store = InMemoryCollectiveSharedLeaseStore()

        val first = lease(store, "device-a").acquire(key)
        val second = lease(store, "device-b").acquire(key)

        assertTrue(first is CollectiveLeaseAttempt.Granted)
        assertEquals(CollectiveLeaseAttempt.Busy, second)
        assertEquals(1L, (first as CollectiveLeaseAttempt.Granted).grant.fence)
    }

    @Test
    fun `the same live owner extends its lease without a new fence`() = runBlocking {
        val store = InMemoryCollectiveSharedLeaseStore()
        val owner = lease(store, "device-a")

        val first = owner.acquire(key) as CollectiveLeaseAttempt.Granted
        now += 400L
        val extended = owner.acquire(key) as CollectiveLeaseAttempt.Granted

        assertEquals(first.grant.fence, extended.grant.fence)
        assertEquals(now + 1_000L, extended.grant.expiresAt)
    }

    @Test
    fun `an expired lease is taken over with a higher fence`() = runBlocking {
        val store = InMemoryCollectiveSharedLeaseStore()
        val oldOwner = lease(store, "device-a")
        val newOwner = lease(store, "device-b")

        val old = oldOwner.acquire(key) as CollectiveLeaseAttempt.Granted
        now += 1_001L
        val takeover = newOwner.acquire(key) as CollectiveLeaseAttempt.Granted

        assertEquals(old.grant.fence + 1L, takeover.grant.fence)
        assertTrue(takeover.grant.fence > old.grant.fence)
    }

    @Test
    fun `a slow old owner never overwrites the result of the new one`() = runBlocking {
        val store = InMemoryCollectiveSharedLeaseStore()
        val oldOwner = lease(store, "device-a")
        val newOwner = lease(store, "device-b")

        val oldGrant = (oldOwner.acquire(key) as CollectiveLeaseAttempt.Granted).grant
        now += 1_001L
        val newGrant = (newOwner.acquire(key) as CollectiveLeaseAttempt.Granted).grant
        val fresh = newOwner.publish(block("Нова"), newGrant)

        // The old owner finally finishes and tries to publish its stale result.
        val stale = oldOwner.publish(block("Стара"), oldGrant)

        assertNull("the expired fence is dead", stale)
        assertEquals("Нова", fresh!!.cards.single().title)
        assertEquals("Нова", store.published(key)!!.cards.single().title)
        assertEquals(1L, store.published(key)!!.revision)
    }

    @Test
    fun `releasing a grant frees the lease for the next owner`() = runBlocking {
        val store = InMemoryCollectiveSharedLeaseStore()
        val owner = lease(store, "device-a")
        val grant = (owner.acquire(key) as CollectiveLeaseAttempt.Granted).grant

        owner.release(grant)
        val next = lease(store, "device-b").acquire(key)

        assertTrue(next is CollectiveLeaseAttempt.Granted)
        assertEquals(grant.fence + 1L, (next as CollectiveLeaseAttempt.Granted).grant.fence)
    }

    @Test
    fun `a stale release cannot free the new owner's lease`() = runBlocking {
        val store = InMemoryCollectiveSharedLeaseStore()
        val oldOwner = lease(store, "device-a")
        val newOwner = lease(store, "device-b")

        val oldGrant = (oldOwner.acquire(key) as CollectiveLeaseAttempt.Granted).grant
        now += 1_001L
        val newGrant = (newOwner.acquire(key) as CollectiveLeaseAttempt.Granted).grant

        oldOwner.release(oldGrant)
        val third = lease(store, "device-c").acquire(key)

        assertEquals("the new owner still holds it", CollectiveLeaseAttempt.Busy, third)
        assertEquals(newGrant.fence, oldGrant.fence + 1L)
    }

    @Test
    fun `the server assigns a monotonic revision on each publish`() = runBlocking {
        val store = InMemoryCollectiveSharedLeaseStore()
        val owner = lease(store, "device-a")
        var grant = (owner.acquire(key) as CollectiveLeaseAttempt.Granted).grant

        val first = owner.publish(block("Перша"), grant)!!
        owner.release(grant)
        grant = (owner.acquire(key) as CollectiveLeaseAttempt.Granted).grant
        val second = owner.publish(block("Друга"), grant)!!

        assertEquals(1L, first.revision)
        assertEquals(2L, second.revision)
        assertEquals(2L, store.published(key)!!.revision)
    }

    @Test
    fun `a retried transaction decision does not duplicate the write`() = runBlocking {
        // The in-memory store re-runs the pure decision [transactionRetries]
        // times, exactly like Firestore after a conflict. The block is written
        // once, with one revision step — the side effect lives outside the
        // retried part.
        val store = InMemoryCollectiveSharedLeaseStore(transactionRetries = 2)
        val owner = lease(store, "device-a")

        val grant = (owner.acquire(key) as CollectiveLeaseAttempt.Granted).grant
        val published = owner.publish(block("Одна"), grant)!!

        assertTrue("the decision really was retried", store.decisionRuns >= 4)
        assertEquals(1L, published.revision)
        assertEquals(1L, store.published(key)!!.revision)
        assertEquals("Одна", store.published(key)!!.cards.single().title)
    }

    @Test
    fun `an absent transport degrades to unavailable, never a crash`() = runBlocking {
        val noTransport = leaseOrNull()

        assertEquals(CollectiveLeaseAttempt.Unavailable, noTransport.acquire(key))
        assertNull(noTransport.published(key))
    }

    private fun leaseOrNull(): CollectiveSharedLease = CollectiveSharedLease(store = null)

    @Test
    fun `a throwing transport degrades to unavailable`() = runBlocking {
        val throwing = object : CollectiveSharedLeaseStore {
            override suspend fun acquire(
                blockKey: String,
                ownerToken: String,
                now: Long,
                ttlMs: Long
            ): CollectiveLeaseAttempt = throw java.io.IOException("offline")

            override suspend fun publish(
                block: CollectiveFeedBlock,
                grant: CollectiveLeaseGrant,
                now: Long
            ): CollectiveFeedBlock? = throw java.io.IOException("offline")

            override suspend fun published(blockKey: String): CollectiveFeedBlock? =
                throw java.io.IOException("offline")
        }

        val protocol = CollectiveSharedLease(throwing, ownerToken = "device-a", clock = { now })

        assertEquals(CollectiveLeaseAttempt.Unavailable, protocol.acquire(key))
        assertNull(protocol.publish(block("X"), CollectiveLeaseGrant(key, "device-a", 1L, now + 1L)))
        assertNull(protocol.published(key))
    }

    @Test
    fun `a lease survives a client restart until its expiry`() = runBlocking {
        val store = InMemoryCollectiveSharedLeaseStore()
        val beforeRestart = lease(store, "device-a")
        val oldGrant = (beforeRestart.acquire(key) as CollectiveLeaseAttempt.Granted).grant

        // A new protocol object with a new token is the app after a restart.
        val afterRestart = lease(store, "device-a-restarted")
        assertEquals(CollectiveLeaseAttempt.Busy, afterRestart.acquire(key))

        now += 1_001L
        val takeover = afterRestart.acquire(key) as CollectiveLeaseAttempt.Granted
        assertEquals(oldGrant.fence + 1L, takeover.grant.fence)
        assertFalse(takeover.grant.ownerToken == oldGrant.ownerToken)
    }
}
