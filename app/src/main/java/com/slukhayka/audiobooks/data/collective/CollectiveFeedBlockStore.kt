package com.slukhayka.audiobooks.data.collective

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * #523 — the persisted active block of one Source-scoped collective block.
 * Best-effort by contract: a miss or a failure contributes nothing and never
 * throws.
 */
interface CollectiveFeedBlockStore {

    /** The active block, or null when none was ever activated. */
    suspend fun active(blockKey: String): CollectiveFeedBlock?

    /**
     * Atomically replaces the active block. False when the candidate is
     * invalid (no cards) — an empty snapshot must never become active.
     */
    suspend fun activate(block: CollectiveFeedBlock): Boolean

    /** Records the latest attempt WITHOUT touching the active cards. */
    suspend fun recordAttempt(blockKey: String, attempt: CollectiveAttempt)
}

/** #523 — the single-owner refresh lease with a hard expiry. */
interface CollectiveRefreshLease {

    /**
     * Acquire the lease for [leaseTtlMs]. True only for the ONE owner; an
     * expired lease is free for anyone, so an abandoned refresh is recoverable.
     */
    suspend fun acquire(blockKey: String, now: Long, leaseTtlMs: Long): Boolean

    /** Release after the refresh attempt (success or failure). */
    suspend fun release(blockKey: String)
}

/**
 * In-process store/lease used by tests and as the null-object default: the
 * production store persists the block, while this one keeps the exact
 * concurrency semantics (atomic activation, expiring single-owner lease).
 */
class InMemoryCollectiveFeedBlockStore : CollectiveFeedBlockStore {
    private val blocks = mutableMapOf<String, CollectiveFeedBlock>()
    private val mutex = Mutex()

    override suspend fun active(blockKey: String): CollectiveFeedBlock? =
        mutex.withLock { blocks[blockKey] }

    override suspend fun activate(block: CollectiveFeedBlock): Boolean = mutex.withLock {
        if (block.cards.isEmpty()) return@withLock false
        blocks[block.blockKey] = block
        true
    }

    override suspend fun recordAttempt(blockKey: String, attempt: CollectiveAttempt) =
        mutex.withLock {
            val current = blocks[blockKey] ?: return@withLock
            blocks[blockKey] = current.copy(lastAttempt = attempt)
        }
}

class InMemoryCollectiveRefreshLease : CollectiveRefreshLease {
    private val heldUntil = mutableMapOf<String, Long>()
    private val mutex = Mutex()

    override suspend fun acquire(blockKey: String, now: Long, leaseTtlMs: Long): Boolean =
        mutex.withLock {
            val until = heldUntil[blockKey]
            if (until != null && now < until) {
                false
            } else {
                heldUntil[blockKey] = now + leaseTtlMs
                true
            }
        }

    override suspend fun release(blockKey: String) {
        mutex.withLock { heldUntil.remove(blockKey) }
    }
}
