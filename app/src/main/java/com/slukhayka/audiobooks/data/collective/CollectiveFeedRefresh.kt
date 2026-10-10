package com.slukhayka.audiobooks.data.collective

import kotlinx.coroutines.CancellationException
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
    private val publicationTimeoutMs: Long = DEFAULT_PUBLICATION_TIMEOUT_MS
) {

    /** The block to render: the last good one, refreshed at most once per TTL. */
    suspend fun read(blockKey: String): CollectiveFeedBlock? {
        val now = clock()
        val active = store.active(blockKey)
        if (active != null && !active.isStale(now) && !CollectiveBlockPolicy.requiresSourceRefresh(active)) {
            return active
        }

        val token = lease.acquire(blockKey, clock(), leaseTtlMs) ?: return active
        val applied = try {
            // A preceding commit may have completed while acquisition waited.
            val current = store.active(blockKey)
            if (current != null && !current.isStale(clock()) && !CollectiveBlockPolicy.requiresSourceRefresh(current)) {
                currentCoroutineContext().ensureActive()
                return current
            }
            val outcome = try {
                fetch(blockKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.TIMEOUT)
            }
            lease.applyIfOwned(token, clock) { commitOutcome(blockKey, current, outcome) }
        } finally {
            withContext(NonCancellable) { lease.release(token) }
        }
        currentCoroutineContext().ensureActive()
        if (applied == null) return store.active(blockKey)
        return finishOutcome(applied)
    }

    /**
     * #528 — an EXPLICIT listener action (they opened a category): the page is
     * fetched NOW and, when it is a valid non-empty candidate, activated and
     * shared. Neither the TTL nor the lease applies — the listener asked, so
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
        val outcome = try {
            fetch(blockKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.TIMEOUT)
        }
        return finishOutcome(commitOutcome(blockKey, active, outcome))
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

    private suspend fun finishOutcome(applied: AppliedBlock): CollectiveFeedBlock? {
        if (applied.newlyActivated != null) {
            currentCoroutineContext().ensureActive()
            try {
                withTimeoutOrNull(publicationTimeoutMs) { onActivated?.invoke(applied.newlyActivated) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A shared write failure leaves the committed local block intact.
            }
            currentCoroutineContext().ensureActive()
        }
        return applied.active
    }

    companion object {
        /** A pending shared write cannot hold an already committed block indefinitely. */
        const val DEFAULT_PUBLICATION_TIMEOUT_MS: Long = 1_000L

        /** Short by design: a refresh is one page, and an abandoned one frees up. */
        const val DEFAULT_LEASE_TTL_MS: Long = 60_000L
    }
}
