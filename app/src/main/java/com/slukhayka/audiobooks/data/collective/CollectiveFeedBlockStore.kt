package com.slukhayka.audiobooks.data.collective

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * #523 — the persisted active block of one Source-scoped collective block.
 * Reads are best-effort; observation reports committed local changes and
 * propagates subscription failure and cancellation to its owner.
 */
interface CollectiveFeedBlockStore {

    /** The active block, or null when none was ever activated. */
    suspend fun active(blockKey: String): CollectiveFeedBlock?

    /** Cold local observation, including the initial state and same-key payload changes. */
    fun observeChanges(blockKeys: List<String>): Flow<Unit>

    /**
     * Atomically replaces the active block. False when the candidate is
     * invalid (no cards) — an empty snapshot must never become active.
     */
    suspend fun activate(block: CollectiveFeedBlock): Boolean

    /** Replaces only the observed block; a concurrent committed change rejects the candidate. */
    suspend fun activateIfUnchanged(expected: CollectiveFeedBlock?, block: CollectiveFeedBlock): Boolean

    /**
     * Atomically admits a non-empty block only when its observed fetchedAt is
     * strictly newer. Keeps the incoming whole block; local version is not
     * shared freshness. A missing active block accepts the candidate.
     */
    suspend fun activateIfNewer(block: CollectiveFeedBlock): Boolean

    /** Records the latest attempt WITHOUT touching the active cards. */
    suspend fun recordAttempt(blockKey: String, attempt: CollectiveAttempt)
}

/** An opaque acquisition identity; an expired owner cannot release its successor. */
class CollectiveRefreshLeaseToken internal constructor(
    internal val blockKey: String,
    internal val expiresAt: Long
)

/** #523 — the single-owner refresh lease with a hard expiry. */
interface CollectiveRefreshLease {
    suspend fun acquire(blockKey: String, now: Long, leaseTtlMs: Long): CollectiveRefreshLeaseToken?

    /** Releases only this acquisition, including when another owner has taken over. */
    suspend fun release(token: CollectiveRefreshLeaseToken)

    /**
     * Admits a local commit only for the current, unexpired owner. The clock is
     * sampled under the lease lock; takeover cannot interleave with the commit.
     * The action must contain local storage only, without re-entering this lease.
     * Null means ownership was lost; a successful action returns a non-null value.
     */
    suspend fun <T : Any> applyIfOwned(
        token: CollectiveRefreshLeaseToken, clock: () -> Long, action: suspend () -> T
    ): T?
}

/**
 * In-process store/lease used by tests and as the null-object default: the
 * production store persists the block, while this one keeps the exact
 * concurrency semantics (atomic activation, expiring single-owner lease).
 */
class InMemoryCollectiveFeedBlockStore : CollectiveFeedBlockStore {
    private val blocks = MutableStateFlow<Map<String, CollectiveFeedBlock>>(emptyMap())
    private val mutex = Mutex()

    override suspend fun active(blockKey: String): CollectiveFeedBlock? =
        mutex.withLock { blocks.value[blockKey] }

    override fun observeChanges(blockKeys: List<String>): Flow<Unit> =
        blocks.map { snapshot -> blockKeys.associateWith { snapshot[it] } }
            .distinctUntilChanged()
            .map { Unit }

    override suspend fun activate(block: CollectiveFeedBlock): Boolean = mutex.withLock {
        if (block.cards.isEmpty()) return@withLock false
        blocks.value = blocks.value + (block.blockKey to block)
        true
    }

    override suspend fun activateIfUnchanged(
        expected: CollectiveFeedBlock?, block: CollectiveFeedBlock
    ): Boolean = mutex.withLock {
        if (block.cards.isEmpty() || blocks.value[block.blockKey] != expected) return@withLock false
        blocks.value = blocks.value + (block.blockKey to block)
        true
    }

    override suspend fun activateIfNewer(block: CollectiveFeedBlock): Boolean = mutex.withLock {
        if (block.cards.isEmpty()) return@withLock false
        val current = blocks.value[block.blockKey]
        if (current != null && block.fetchedAt <= current.fetchedAt) return@withLock false
        blocks.value = blocks.value + (block.blockKey to block)
        true
    }

    override suspend fun recordAttempt(blockKey: String, attempt: CollectiveAttempt) =
        mutex.withLock {
            val current = blocks.value[blockKey] ?: return@withLock
            blocks.value = blocks.value + (blockKey to current.copy(lastAttempt = attempt))
        }
}

class InMemoryCollectiveRefreshLease : CollectiveRefreshLease {
    private val held = mutableMapOf<String, CollectiveRefreshLeaseToken>()
    private val mutex = Mutex()

    override suspend fun acquire(
        blockKey: String, now: Long, leaseTtlMs: Long
    ): CollectiveRefreshLeaseToken? = mutex.withLock {
        val current = held[blockKey]
        if (current != null && now < current.expiresAt) return@withLock null
        CollectiveRefreshLeaseToken(blockKey, now + leaseTtlMs).also { held[blockKey] = it }
    }

    override suspend fun release(token: CollectiveRefreshLeaseToken) {
        mutex.withLock {
            if (held[token.blockKey] === token) held.remove(token.blockKey)
        }
    }

    override suspend fun <T : Any> applyIfOwned(
        token: CollectiveRefreshLeaseToken, clock: () -> Long, action: suspend () -> T
    ): T? = mutex.withLock {
        if (held[token.blockKey] !== token || clock() >= token.expiresAt) return@withLock null
        action()
    }
}
