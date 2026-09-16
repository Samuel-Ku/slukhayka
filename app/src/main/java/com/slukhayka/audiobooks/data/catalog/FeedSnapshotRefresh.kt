package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.source.SourceBook
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Spec-620 (#622) — one persisted feed snapshot: the cards and the moment they
 * were really observed. The stamp travels WITH the cards, so a snapshot read
 * back from storage keeps its original age instead of looking freshly taken.
 */
data class PersistedFeedSnapshot(
    val sourceId: String,
    val feedKey: String,
    val books: List<SourceBook>,
    val observedAt: Long,
    /**
     * Spec-620 (#625) — the fetch identity that produced this snapshot (e.g.
     * `limit=60`). A snapshot answers only the SAME request: a different limit
     * is never served from a snapshot it does not provably cover. It rides the
     * existing free-text snapshot slot, so no schema migration is needed.
     */
    val parameters: String = ""
)

/**
 * Spec-620 (#622) — the honest outcome of one refresh. The three states are
 * deliberately distinct: [Data] is a real answer (live or cached), [Empty] is
 * a source that really served nothing, [Failure] is a source that could not be
 * reached. Cancellation is NOT one of them — it propagates as
 * [CancellationException] so a cancelled refresh never publishes a fake empty.
 */
sealed interface FeedRefreshOutcome {

    /** Real cards, with the moment they were observed. */
    data class Data(val books: List<SourceBook>, val observedAt: Long) : FeedRefreshOutcome

    /** The source really answered with nothing — honest, and never cached. */
    data object Empty : FeedRefreshOutcome

    /** The source could not be reached — the previous snapshot is untouched. */
    data object Failure : FeedRefreshOutcome
}

/**
 * Spec-620 (#622/#625) — the ONE refresh module behind every Огляд feed
 * (new arrivals AND the catalogue): memory → Room → Source, decided by a single
 * injected clock and the feed's TTL ([FeedSnapshotPolicy]).
 *
 * #622 rules (unchanged here):
 * - only a NON-EMPTY live result is remembered (memory and storage) and only
 *   it advances the observed-at stamp;
 * - [FeedRefreshOutcome.Failure] and [FeedRefreshOutcome.Empty] neither write
 *   memory/storage nor touch the previous snapshot;
 * - a snapshot promoted from storage keeps its ORIGINAL observed-at, so
 *   reading it never extends the TTL;
 * - a stamp in the future is never treated as fresh;
 * - an explicit refresh bypasses memory and storage;
 * - a failed storage write never hides the successful live result.
 *
 * #625 additions:
 * - EQUIVALENT concurrent refreshes share ONE live fetch. The in-flight key is
 *   Source × feed kind × fetch parameters × browser-session generation, so two
 *   different limits, feed kinds or sessions never mix;
 * - one waiter cancelling does not stop the others: the fetch runs on [scope],
 *   independent of any single caller, and is cancelled only when the LAST
 *   waiter leaves;
 * - an explicit refresh may still JOIN an equivalent active live fetch;
 * - a session-bound refresh always bypasses the cache, and a NEWER session
 *   generation makes an older in-flight result unusable (it is neither
 *   persisted nor published);
 * - a fetch-parameter change (e.g. the limit) is a different cache cohort:
 *   a snapshot written for one request never answers another, so a non-default
 *   limit is never served from a snapshot that does not provably cover it;
 *
 * The module owns no Android, Room or HTTP type: persistence is two suspend
 * lambdas and the fetch scope is injected, so the whole decision is pinned by
 * plain JVM tests with a fake clock.
 */
class FeedSnapshotRefresh(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** The stored snapshot of one feed (cards + its own observed-at), or null. */
    private val readPersisted: suspend (sourceId: String, feedKey: String) -> PersistedFeedSnapshot? =
        { _, _ -> null },
    /** Persists one successful live snapshot; false when the write failed. */
    private val writePersisted: suspend (snapshot: PersistedFeedSnapshot) -> Boolean = { true },
    /**
     * The scope the shared live fetch runs in. It must OUTLIVE one waiter so
     * cancellation is a per-waiter decision; the app gives the module its own
     * supervisor scope, tests inject theirs.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private data class MemoryEntry(
        val books: List<SourceBook>,
        val observedAt: Long,
        val parameters: String
    )

    private data class InFlight(
        val deferred: Deferred<FeedRefreshOutcome>,
        val waiters: AtomicInteger
    )

    private val memory = ConcurrentHashMap<String, MemoryEntry>()

    /** One key per fetch identity: source × feed × parameters × session. */
    private val inFlight = ConcurrentHashMap<String, InFlight>()

    /**
     * The newest browser-session generation seen per feed. An older in-flight
     * result that lands after a newer session started is unusable.
     */
    private val latestGeneration = ConcurrentHashMap<String, Long>()

    /** Releases the shared fetch scope (tests / teardown). */
    fun close() {
        scope.cancel()
    }

    /**
     * @param parameters the fetch identity beyond source/feed (e.g. `limit=60`);
     *   a snapshot answers only the identical parameters.
     * @param sessionGeneration a session-bound source's browser-session epoch.
     * @param forceRefresh an explicit listener refresh: bypasses every cache.
     * @param skipCache a session-bound source's feed is never reusable.
     * @param fetch the live half; it may throw, and the throw becomes
     *   [FeedRefreshOutcome.Failure]. An empty list is a real empty answer.
     */
    suspend fun refresh(
        sourceId: String,
        feedKey: String,
        parameters: String = "",
        sessionGeneration: Long = 0L,
        forceRefresh: Boolean = false,
        skipCache: Boolean = false,
        fetch: suspend () -> List<SourceBook>
    ): FeedRefreshOutcome {
        val sourceFeedKey = "$sourceId|$feedKey"
        latestGeneration.merge(sourceFeedKey, sessionGeneration, ::maxOf)
        val allowCache = !forceRefresh && !skipCache
        val identity = "$sourceFeedKey|$parameters|$sessionGeneration"
        return coalesced(
            identity = identity,
            sourceId = sourceId,
            feedKey = feedKey,
            parameters = parameters,
            sessionGeneration = sessionGeneration,
            allowCache = allowCache,
            fetch = fetch
        )
    }

    private suspend fun coalesced(
        identity: String,
        sourceId: String,
        feedKey: String,
        parameters: String,
        sessionGeneration: Long,
        allowCache: Boolean,
        fetch: suspend () -> List<SourceBook>
    ): FeedRefreshOutcome {
        while (true) {
            if (allowCache) {
                cachedOrNull(sourceId, feedKey, parameters)?.let { return it }
            }
            val existing = inFlight[identity]
            if (existing != null) {
                existing.waiters.incrementAndGet()
                return try {
                    existing.deferred.await()
                } finally {
                    // The fetch dies only when the LAST waiter has left; a
                    // single cancelled surface never breaks another's refresh.
                    if (existing.waiters.decrementAndGet() == 0) existing.deferred.cancel()
                }
            }
            val deferred = scope.async(start = CoroutineStart.LAZY) {
                runFetch(sourceId, feedKey, parameters, sessionGeneration, fetch)
            }
            val holder = InFlight(deferred, AtomicInteger(1))
            if (inFlight.putIfAbsent(identity, holder) != null) {
                // Lost the race: drop ours and join the winner.
                deferred.cancel()
                continue
            }
            deferred.invokeOnCompletion { inFlight.remove(identity, holder) }
            return try {
                deferred.await()
            } finally {
                if (holder.waiters.decrementAndGet() == 0) deferred.cancel()
            }
        }
    }

    private suspend fun cachedOrNull(
        sourceId: String,
        feedKey: String,
        parameters: String
    ): FeedRefreshOutcome.Data? {
        val ttl = FeedSnapshotPolicy.ttlMillisFor(feedKey)
        val sourceFeedKey = "$sourceId|$feedKey"
        memory[sourceFeedKey]?.let { entry ->
            if (entry.parameters == parameters &&
                FeedSnapshotPolicy.isFresh(entry.observedAt, nowMillis(), ttl)
            ) {
                return FeedRefreshOutcome.Data(entry.books, entry.observedAt)
            }
        }
        val persisted = readPersisted(sourceId, feedKey)
        if (persisted != null &&
            persisted.books.isNotEmpty() &&
            persisted.parameters == parameters &&
            FeedSnapshotPolicy.isFresh(persisted.observedAt, nowMillis(), ttl)
        ) {
            // Promotion keeps the ORIGINAL stamp: a read is not a refresh.
            memory[sourceFeedKey] = MemoryEntry(persisted.books, persisted.observedAt, parameters)
            return FeedRefreshOutcome.Data(persisted.books, persisted.observedAt)
        }
        return null
    }

    private suspend fun runFetch(
        sourceId: String,
        feedKey: String,
        parameters: String,
        sessionGeneration: Long,
        fetch: suspend () -> List<SourceBook>
    ): FeedRefreshOutcome {
        val books = try {
            fetch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return FeedRefreshOutcome.Failure
        }
        // A successful empty answer belongs to THIS caller only: caching it
        // would freeze the emptiness for a whole TTL and hide the previous
        // good snapshot that is still there.
        if (books.isEmpty()) return FeedRefreshOutcome.Empty

        val sourceFeedKey = "$sourceId|$feedKey"
        val newest = latestGeneration[sourceFeedKey] ?: sessionGeneration
        if (sessionGeneration < newest) {
            // A newer browser session already started: an old session's
            // success is not publishable, let alone cacheable.
            return FeedRefreshOutcome.Failure
        }

        val observedAt = nowMillis()
        memory[sourceFeedKey] = MemoryEntry(books, observedAt, parameters)
        runCatching {
            writePersisted(PersistedFeedSnapshot(sourceId, feedKey, books, observedAt, parameters))
        }
        return FeedRefreshOutcome.Data(books, observedAt)
    }
}
