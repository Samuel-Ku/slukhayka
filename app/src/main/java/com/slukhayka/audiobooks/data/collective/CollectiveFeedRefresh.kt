package com.slukhayka.audiobooks.data.collective

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * #523 / ADR-0041 — stale-while-revalidate for ONE collective Огляд block.
 *
 * [read] always answers from the active block: while it is fresh nobody
 * touches the source; once stale, exactly ONE client takes the short refresh
 * lease and opens ONE source page, while every other client gets the last
 * good block immediately. A timeout, 403/404, challenge, parse failure or an
 * empty page never erases that block — it only records the attempt status. An
 * abandoned lease expires, so a later client can take over.
 *
 * The clock and the fetch/lease/store seams are injected, so the whole
 * concurrency contract is JVM-testable without a network or an emulator.
 */
class CollectiveFeedRefresh(
    private val store: CollectiveFeedBlockStore,
    private val lease: CollectiveRefreshLease,
    private val fetch: suspend (blockKey: String) -> CollectiveRefreshOutcome,
    private val clock: () -> Long = System::currentTimeMillis,
    private val leaseTtlMs: Long = DEFAULT_LEASE_TTL_MS,
    /**
     * #527 — called with the block a refresh just ACTIVATED, so the one owner
     * that observed it can share it (the collective lane). Best-effort: a
     * failing publish never changes what the local listener sees.
     */
    private val onActivated: (suspend (CollectiveFeedBlock) -> Unit)? = null,
    /** The shared write is optional; its deadline never encloses local activation. */
    private val publicationTimeoutMs: Long = DEFAULT_PUBLICATION_TIMEOUT_MS,
    private val sharedLease: CollectiveSharedLease? = null,
    private val sharedWaitTimeoutMs: Long = DEFAULT_SHARED_WAIT_MS,
    private val sharedPollMs: Long = DEFAULT_SHARED_POLL_MS,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {

    /** The block to render: the last good one, refreshed at most once per TTL. */
    suspend fun read(blockKey: String): CollectiveFeedBlock? {
        val now = clock()
        val active = store.active(blockKey)
        if (active != null && !active.isStale(now) && !CollectiveBlockPolicy.requiresSourceRefresh(active)) {
            return active
        }

        val token = lease.acquire(blockKey, clock(), leaseTtlMs) ?: return active
        var grant: CollectiveLeaseGrant? = null
        try {
            val applied = when (val result = readWithLocalLease(blockKey, token) { grant = it }) {
                is LocalRead.Direct -> return result.block
                is LocalRead.Refreshed -> result.applied
            }
            currentCoroutineContext().ensureActive()
            if (applied == null) return store.active(blockKey)
            return finishOutcome(applied, grant)
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(publicationTimeoutMs) { grant?.let { sharedLease?.release(it) } }
            }
        }
    }

    private sealed interface LocalRead {
        data class Direct(val block: CollectiveFeedBlock?) : LocalRead
        data class Refreshed(val applied: AppliedBlock?) : LocalRead
    }

    /** Local cleanup has its own coroutine frame; shared cleanup stays with [read]. */
    private suspend fun readWithLocalLease(
        blockKey: String,
        token: CollectiveRefreshLeaseToken,
        onSharedGrant: (CollectiveLeaseGrant) -> Unit
    ): LocalRead = try {
        // A preceding commit may have completed while acquisition waited.
        val current = store.active(blockKey)
        if (current != null && !current.isStale(clock()) && !CollectiveBlockPolicy.requiresSourceRefresh(current)) {
            currentCoroutineContext().ensureActive()
            LocalRead.Direct(current)
        } else {
            val attempt = sharedLease?.acquire(blockKey)
            if (attempt is CollectiveLeaseAttempt.Busy) {
                LocalRead.Direct(awaitSharedResult(blockKey, current))
            } else {
                if (attempt is CollectiveLeaseAttempt.Granted) onSharedGrant(attempt.grant)
                val outcome = fetchOutcome(blockKey)
                LocalRead.Refreshed(lease.applyIfOwned(token, clock) { commitOutcome(blockKey, current, outcome) })
            }
        }
    } finally {
        withContext(NonCancellable) { lease.release(token) }
    }

    /**
     * #528 — an EXPLICIT listener action (they opened a category): the page is
     * fetched NOW and, when it is a valid non-empty candidate, activated and
     * shared. Neither the TTL nor the local lease applies — the listener asked, so
     * the action is its own owner — but a failure still keeps the previous
     * block and only records the attempt. The caller passes the fetch it needs
     * (a category path + that action's cursor).
     *
     * @return the block now active for the key, or null when none ever was.
     */
    suspend fun observeExplicit(
        blockKey: String,
        fetch: suspend (String) -> CollectiveRefreshOutcome = this.fetch
    ): CollectiveFeedBlock? {
        val active = store.active(blockKey)
        var grant: CollectiveLeaseGrant? = null
        try {
            val attempt = sharedLease?.acquire(blockKey)
            if (attempt is CollectiveLeaseAttempt.Granted) grant = attempt.grant
            val outcome = fetchOutcome(blockKey, fetch)
            return finishOutcome(commitOutcome(blockKey, active, outcome), grant)
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(publicationTimeoutMs) { grant?.let { sharedLease?.release(it) } }
            }
        }
    }

    /** A shared follower waits boundedly and never issues its own source request. */
    private suspend fun awaitSharedResult(blockKey: String, active: CollectiveFeedBlock?): CollectiveFeedBlock? {
        val shared = sharedLease ?: return active
        var waited = 0L
        while (true) {
            val published = shared.published(blockKey)
            if (published != null && published.isNewerThan(active)) {
                store.activateIfNewer(published)
                return store.active(blockKey)
            }
            if (waited >= sharedWaitTimeoutMs) return store.active(blockKey)
            val step = minOf(sharedPollMs, sharedWaitTimeoutMs - waited).coerceAtLeast(1L)
            sleep(step)
            waited += step
        }
    }

    private suspend fun fetchOutcome(
        blockKey: String,
        fetch: suspend (String) -> CollectiveRefreshOutcome = this.fetch
    ): CollectiveRefreshOutcome = try {
        fetch(blockKey)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.TIMEOUT)
    }

    private data class AppliedBlock(
        val active: CollectiveFeedBlock?,
        val newlyActivated: CollectiveFeedBlock? = null
    )

    /** Local admission only; shared publication never holds the lease mutex. */
    private suspend fun commitOutcome(
        blockKey: String,
        active: CollectiveFeedBlock?,
        outcome: CollectiveRefreshOutcome
    ): AppliedBlock {
        currentCoroutineContext().ensureActive()
        val at = clock()
        return when (outcome) {
            is CollectiveRefreshOutcome.Success -> {
                val candidate = outcome.block
                if (candidate.cards.isEmpty()) {
                    store.recordAttempt(blockKey, CollectiveAttempt(at, CollectiveAttemptStatus.EMPTY))
                    AppliedBlock(store.active(blockKey))
                } else {
                    val activated = candidate.copy(
                        blockKey = blockKey,
                        revision = 0L,
                        fetchedAt = at,
                        staleAfter = at + CollectiveBlockPolicy.ttlMillisFor(candidate.kind),
                        version = (active?.version ?: 0L) + 1L,
                        lastAttempt = CollectiveAttempt(at, CollectiveAttemptStatus.SUCCESS)
                    )
                    if (store.activateIfUnchanged(active, activated)) {
                        AppliedBlock(activated, activated)
                    } else {
                        // An intervening commit wins; conflict is not EMPTY.
                        AppliedBlock(store.active(blockKey))
                    }
                }
            }
            CollectiveRefreshOutcome.Empty -> {
                store.recordAttempt(blockKey, CollectiveAttempt(at, CollectiveAttemptStatus.EMPTY))
                AppliedBlock(store.active(blockKey))
            }
            is CollectiveRefreshOutcome.Failure -> {
                store.recordAttempt(blockKey, CollectiveAttempt(at, outcome.status))
                AppliedBlock(store.active(blockKey))
            }
        }
    }

    private suspend fun finishOutcome(applied: AppliedBlock, grant: CollectiveLeaseGrant?): CollectiveFeedBlock? {
        val activated = applied.newlyActivated ?: return applied.active
        var result = activated
        currentCoroutineContext().ensureActive()
        try {
            withTimeoutOrNull(publicationTimeoutMs) {
                val published = grant?.let { sharedLease?.publish(activated, it) }
                if (published != null) {
                    store.recordRevision(activated.blockKey, published.revision, activated.version)
                    result = activated.copy(revision = published.revision)
                } else if (sharedLease == null) {
                    // An unconditional publish must never bypass a configured shared fence.
                    onActivated?.invoke(activated)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A shared write failure leaves the committed local block intact.
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    companion object {
        /** A pending shared write cannot hold an already committed block indefinitely. */
        const val DEFAULT_PUBLICATION_TIMEOUT_MS: Long = 1_000L

        /** Short by design: a refresh is one page, and an abandoned one frees up. */
        const val DEFAULT_LEASE_TTL_MS: Long = 60_000L
        const val DEFAULT_SHARED_WAIT_MS: Long = 3_000L
        const val DEFAULT_SHARED_POLL_MS: Long = 200L
    }
}

/** Server revisions order shared snapshots; observation time breaks equal-revision ties. */
fun CollectiveFeedBlock.isNewerThan(existing: CollectiveFeedBlock?): Boolean {
    if (existing == null) return true
    if (revision != existing.revision) return revision > existing.revision
    return fetchedAt > existing.fetchedAt
}
