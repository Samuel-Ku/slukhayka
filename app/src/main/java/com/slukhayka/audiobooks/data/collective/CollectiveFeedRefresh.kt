package com.slukhayka.audiobooks.data.collective

import kotlinx.coroutines.CancellationException

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
    private val leaseTtlMs: Long = DEFAULT_LEASE_TTL_MS
) {

    /** The block to render: the last good one, refreshed at most once per TTL. */
    suspend fun read(blockKey: String): CollectiveFeedBlock? {
        val now = clock()
        val active = store.active(blockKey)
        if (active != null && !active.isStale(now)) return active

        // Stale (or never fetched): only the lease owner may hit the source.
        if (!lease.acquire(blockKey, now, leaseTtlMs)) return active
        val outcome = try {
            fetch(blockKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.TIMEOUT)
        } finally {
            lease.release(blockKey)
        }

        val at = clock()
        return when (outcome) {
            is CollectiveRefreshOutcome.Success -> {
                val candidate = outcome.block
                val activated = candidate.copy(
                    fetchedAt = at,
                    staleAfter = at + CollectiveBlockPolicy.ttlMillisFor(candidate.kind),
                    version = (active?.version ?: 0L) + 1L,
                    lastAttempt = CollectiveAttempt(at, CollectiveAttemptStatus.SUCCESS)
                )
                if (store.activate(activated)) {
                    activated
                } else {
                    store.recordAttempt(blockKey, CollectiveAttempt(at, CollectiveAttemptStatus.EMPTY))
                    store.active(blockKey)
                }
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
    }
}
