package com.slukhayka.audiobooks.data.collective

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * #1238 — the SHARED lease of one collective block: who may refresh it right
 * now, and which publication is the newest.
 *
 * The lease is cooperative and cross-device: [CollectiveBlockRef] ties it to
 * the same identity as the block row, so a related collection has its own
 * lease. [fence] is a monotonic number assigned by whoever takes the lease
 * over. Every publication must present the exact grant it was issued, so an
 * owner that stalled past its expiry can never overwrite the result of the
 * owner that took over — the fence, not a device clock, is the authority.
 */
data class CollectiveLeaseGrant(
    val blockKey: String,
    val ownerToken: String,
    val fence: Long,
    val expiresAt: Long
)

/** What one attempt to take the shared lease produced. */
sealed interface CollectiveLeaseAttempt {
    /** This client owns the refresh; [grant] must be presented to publish. */
    data class Granted(val grant: CollectiveLeaseGrant) : CollectiveLeaseAttempt

    /** Another live owner holds it; read its published result instead. */
    data object Busy : CollectiveLeaseAttempt

    /** The shared lane is absent or unreachable; degrade to local-only. */
    data object Unavailable : CollectiveLeaseAttempt
}

/**
 * #1238 — the compare-and-set seam of the shared lane.
 *
 * Implementations MUST run every decision atomically (the Firestore adapter
 * uses `runTransaction`, which may re-run a decision after a conflict) and the
 * decision itself is pure: no source request, no Room write and no UI call
 * belongs inside [acquire]/[publish]. Side effects happen after the call
 * returns, never inside a retried transaction callback.
 *
 * Every method is best-effort: a transport failure is answered by the caller
 * as [CollectiveLeaseAttempt.Unavailable], never by a crash.
 */
interface CollectiveSharedLeaseStore {

    /**
     * Atomically takes or extends the lease. Only one owner wins; an expired
     * lease is free, and a takeover bumps [CollectiveLeaseGrant.fence].
     * Returns [CollectiveLeaseAttempt.Granted] or
     * [CollectiveLeaseAttempt.Busy]; a thrown transport failure is the
     * protocol's [CollectiveLeaseAttempt.Unavailable], never a third answer
     * here.
     */
    suspend fun acquire(
        blockKey: String,
        ownerToken: String,
        now: Long,
        ttlMs: Long
    ): CollectiveLeaseAttempt

    /**
     * Atomically publishes [block] ONLY when [grant] is still the live lease
     * (same owner token, same fence, not expired). The server assigns the next
     * revision. Returns the published block, or null when this owner lost —
     * a stalled old owner's result is discarded, never written over a newer.
     */
    suspend fun publish(
        block: CollectiveFeedBlock,
        grant: CollectiveLeaseGrant,
        now: Long
    ): CollectiveFeedBlock?

    /** Atomically frees the lease only when [grant] still holds it. */
    suspend fun release(grant: CollectiveLeaseGrant, now: Long) = Unit

    /** The newest block published for [blockKey], or null when none exists. */
    suspend fun published(blockKey: String): CollectiveFeedBlock? = null
}

/**
 * #1238 — the cross-device lease/publish protocol over a possibly absent
 * transport. A null store (no Firebase configuration) and a throwing store
 * both answer [CollectiveLeaseAttempt.Unavailable], so the caller degrades to
 * the local stale-while-revalidate behaviour instead of failing the screen.
 *
 * The protocol object is the only place that mints the owner token and reads
 * the device clock; the store only sees the values, which keeps the Firestore
 * callback pure and makes the whole contract JVM-testable.
 */
class CollectiveSharedLease(
    private val store: CollectiveSharedLeaseStore?,
    private val ownerToken: String = UUID.randomUUID().toString(),
    private val ttlMs: Long = DEFAULT_LEASE_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun acquire(blockKey: String): CollectiveLeaseAttempt {
        val store = store ?: return CollectiveLeaseAttempt.Unavailable
        return runCatching {
            store.acquire(blockKey, ownerToken, clock(), ttlMs)
        }.getOrDefault(CollectiveLeaseAttempt.Unavailable)
    }

    /** @return the block with its server-assigned revision, or null when the fence was lost. */
    suspend fun publish(block: CollectiveFeedBlock, grant: CollectiveLeaseGrant): CollectiveFeedBlock? {
        val store = store ?: return null
        return runCatching { store.publish(block, grant, clock()) }.getOrNull()
    }

    suspend fun release(grant: CollectiveLeaseGrant) {
        val store = store ?: return
        runCatching { store.release(grant, clock()) }
    }

    /** The shared result, for a follower that lost the race; null when absent/unreachable. */
    suspend fun published(blockKey: String): CollectiveFeedBlock? {
        val store = store ?: return null
        return runCatching { store.published(blockKey) }.getOrNull()
    }

    companion object {
        /** Short by design: one source page, and an abandoned lease frees up. */
        const val DEFAULT_LEASE_TTL_MS: Long = 60_000L
    }
}

/**
 * #1238 — the in-memory double of the shared lane: the same atomic semantics
 * as the Firestore transaction, without Firebase. [transactionRetries] makes
 * [acquire]/[publish] re-run their pure decision the way a conflicted server
 * transaction does, so a test can prove that side effects live OUTSIDE the
 * callback (they are counted once while [decisionRuns] grows).
 */
class InMemoryCollectiveSharedLeaseStore(
    private val transactionRetries: Int = 0
) : CollectiveSharedLeaseStore {

    private data class Row(
        val ownerToken: String,
        val fence: Long,
        val expiresAt: Long
    )

    private val rows = mutableMapOf<String, Row>()
    private val blocks = mutableMapOf<String, CollectiveFeedBlock>()
    private val mutex = Mutex()

    /** How many times the pure transaction decision ran (retries included). */
    var decisionRuns: Int = 0
        private set

    override suspend fun acquire(
        blockKey: String,
        ownerToken: String,
        now: Long,
        ttlMs: Long
    ): CollectiveLeaseAttempt = mutex.withLock {
        repeat(transactionRetries + 1) { decisionRuns++ }
        val current = rows[blockKey]
        val sameOwner = current?.ownerToken == ownerToken
        val liveOtherOwner = current != null && !sameOwner && now < current.expiresAt
        if (liveOtherOwner) return@withLock CollectiveLeaseAttempt.Busy
        val fence = when {
            current == null -> 1L
            sameOwner && now < current.expiresAt -> current.fence
            else -> current.fence + 1L
        }
        val next = Row(ownerToken = ownerToken, fence = fence, expiresAt = now + ttlMs)
        rows[blockKey] = next
        CollectiveLeaseAttempt.Granted(
            CollectiveLeaseGrant(blockKey, ownerToken, next.fence, next.expiresAt)
        )
    }

    override suspend fun publish(
        block: CollectiveFeedBlock,
        grant: CollectiveLeaseGrant,
        now: Long
    ): CollectiveFeedBlock? = mutex.withLock {
        val current = rows[grant.blockKey] ?: return@withLock null
        val stillMine = current.ownerToken == grant.ownerToken &&
            current.fence == grant.fence &&
            now < current.expiresAt
        if (!stillMine) return@withLock null
        repeat(transactionRetries + 1) { decisionRuns++ }
        val revision = (blocks[grant.blockKey]?.revision ?: 0L) + 1L
        val published = block.copy(revision = revision)
        blocks[grant.blockKey] = published
        published
    }

    override suspend fun release(grant: CollectiveLeaseGrant, now: Long) {
        mutex.withLock {
            val current = rows[grant.blockKey] ?: return@withLock
            if (current.ownerToken != grant.ownerToken || current.fence != grant.fence) return@withLock
            rows[grant.blockKey] = current.copy(ownerToken = "", expiresAt = 0L)
        }
    }

    override suspend fun published(blockKey: String): CollectiveFeedBlock? =
        mutex.withLock { blocks[blockKey] }
}
