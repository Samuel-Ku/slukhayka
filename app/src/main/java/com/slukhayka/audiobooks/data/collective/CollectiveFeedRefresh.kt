package com.slukhayka.audiobooks.data.collective

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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
 * #1238 adds the CROSS-DEVICE half behind the same contract: the local lease
 * decides which coroutine may refresh, the [sharedLease] decides which DEVICE
 * may. The device that wins fetches once and publishes under its fence; a
 * follower loses the shared lease, waits a bounded moment for that shared
 * result and mirrors it — no second source request. A backend failure answers
 * [CollectiveLeaseAttempt.Unavailable] and the client degrades to the local
 * lane, keeping the last good Room block.
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
     *
     * #1238 — this unconditional publish is used ONLY when no shared lease is
     * configured at all. With a lease, an unconditional write could overwrite
     * the result of the owner that took over, which is exactly what the fence
     * exists to prevent.
     */
    private val onActivated: (suspend (CollectiveFeedBlock) -> Unit)? = null,
    /** #1238 — the cross-device lease; null keeps the pre-#1238 local behaviour. */
    private val sharedLease: CollectiveSharedLease? = null,
    /**
     * #1238 — how long a follower waits for the owner's shared result before
     * answering with the last good block. Bounded on purpose: a read never
     * waits on the network forever, and the next read retries.
     */
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

        // Stale (or never fetched): only the local lease owner may refresh.
        if (!lease.acquire(blockKey, now, leaseTtlMs)) return active

        var grant: CollectiveLeaseGrant? = null
        var follower = false
        try {
            when (val attempt = sharedLease?.acquire(blockKey)) {
                is CollectiveLeaseAttempt.Granted -> grant = attempt.grant
                CollectiveLeaseAttempt.Busy -> follower = true
                CollectiveLeaseAttempt.Unavailable, null -> Unit // local-only, degraded
            }
            if (follower) return awaitSharedResult(blockKey, active)
            val outcome = fetchOutcome(blockKey)
            return applyOutcome(blockKey, active, outcome, grant)
        } finally {
            grant?.let { sharedLease?.release(it) }
            lease.release(blockKey)
        }
    }

    /**
     * #528 — an EXPLICIT listener action (they opened a category): the page is
     * fetched NOW and, when it is a valid non-empty candidate, activated and
     * shared. Neither the TTL nor the local lease applies — the listener asked,
     * so the action is its own owner — but a failure still keeps the previous
     * block and only records the attempt. The caller passes the fetch it needs
     * (a category path + that action's cursor).
     *
     * #1238 — sharing still goes through the shared fence: when another device
     * owns the lease, the listener's page is activated locally but never
     * published over the owner's newer row.
     *
     * @return the block now active for the key, or null when none ever was.
     */
    suspend fun observeExplicit(
        blockKey: String,
        fetch: suspend (String) -> CollectiveRefreshOutcome = this.fetch
    ): CollectiveFeedBlock? {
        val active = store.active(blockKey)
        var grant: CollectiveLeaseGrant? = null
        val attempt = sharedLease?.acquire(blockKey)
        if (attempt is CollectiveLeaseAttempt.Granted) grant = attempt.grant
        try {
            val outcome = fetchOutcome(blockKey, fetch)
            return applyOutcome(blockKey, active, outcome, grant)
        } finally {
            grant?.let { sharedLease?.release(it) }
        }
    }

    /**
     * #1238 — a follower's bounded wait for the owner's shared result. It
     * returns as soon as a row newer than [active] appears; on timeout it
     * answers the last good block, never an error.
     */
    private suspend fun awaitSharedResult(
        blockKey: String,
        active: CollectiveFeedBlock?
    ): CollectiveFeedBlock? {
        val shared = sharedLease ?: return active
        var waited = 0L
        while (true) {
            val published = shared.published(blockKey)
            if (published != null && published.isNewerThan(active)) {
                return if (store.activate(published)) published else store.active(blockKey)
            }
            if (waited >= sharedWaitTimeoutMs) return active
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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.TIMEOUT)
    }

    /** The ONE activation path: a valid non-empty candidate wins, everything else keeps the block. */
    private suspend fun applyOutcome(
        blockKey: String,
        active: CollectiveFeedBlock?,
        outcome: CollectiveRefreshOutcome,
        grant: CollectiveLeaseGrant?
    ): CollectiveFeedBlock? {
        val at = clock()
        return when (outcome) {
            is CollectiveRefreshOutcome.Success -> {
                val candidate = outcome.block
                // The requested key is the identity the caller reads; a producer
                // that named a different context is a bug, not a new row here.
                val observed = candidate.copy(
                    blockKey = blockKey,
                    revision = 0L,
                    fetchedAt = at,
                    staleAfter = at + CollectiveBlockPolicy.ttlMillisFor(candidate.kind),
                    version = (active?.version ?: 0L) + 1L,
                    lastAttempt = CollectiveAttempt(at, CollectiveAttemptStatus.SUCCESS)
                )
                if (!store.activate(observed)) {
                    store.recordAttempt(blockKey, CollectiveAttempt(at, CollectiveAttemptStatus.EMPTY))
                    return store.active(blockKey)
                }
                // #1238 — the publish runs AFTER the local activation and outside
                // any retried server transaction: a backend failure can lose the
                // share, never the listener's last good block.
                val published = grant?.let { sharedLease?.publish(observed, it) }
                if (published != null) {
                    store.recordRevision(blockKey, published.revision, observed.version)
                } else if (sharedLease == null) {
                    // #527 — pre-lease behaviour only: share unconditionally.
                    runCatching { onActivated?.invoke(observed) }
                }
                observed.copy(revision = published?.revision ?: 0L)
            }

            CollectiveRefreshOutcome.Empty -> {
                store.recordAttempt(blockKey, CollectiveAttempt(at, CollectiveAttemptStatus.EMPTY))
                store.active(blockKey)
            }

            is CollectiveRefreshOutcome.Failure -> {
                store.recordAttempt(blockKey, CollectiveAttempt(at, outcome.status))
                store.active(blockKey)
            }
        }
    }

    companion object {
        /** Short by design: a refresh is one page, and an abandoned one frees up. */
        const val DEFAULT_LEASE_TTL_MS: Long = 60_000L

        /** Long enough for one page, short enough that a screen never hangs. */
        const val DEFAULT_SHARED_WAIT_MS: Long = 3_000L

        const val DEFAULT_SHARED_POLL_MS: Long = 200L
    }
}

/**
 * #1238 — the ONE rule for "is this shared block newer than what the device
 * already shows": the server revision orders the shared lane, and a strictly
 * newer observation wins only within the same revision (the pre-#1238 rows
 * that all carry revision 0). A local observation that was never published
 * (revision 0) is still compared by its honest [CollectiveFeedBlock.fetchedAt].
 */
fun CollectiveFeedBlock.isNewerThan(existing: CollectiveFeedBlock?): Boolean {
    if (existing == null) return true
    if (revision != existing.revision) return revision > existing.revision
    return fetchedAt > existing.fetchedAt
}
