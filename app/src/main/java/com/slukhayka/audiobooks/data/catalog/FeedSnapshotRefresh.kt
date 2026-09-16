package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.source.SourceBook
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Spec-620 (#622) — one persisted feed snapshot: the cards and the moment they
 * were really observed. The stamp travels WITH the cards, so a snapshot read
 * back from storage keeps its original age instead of looking freshly taken.
 */
data class PersistedFeedSnapshot(
    val sourceId: String,
    val feedKey: String,
    val books: List<SourceBook>,
    val observedAt: Long
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
 * Spec-620 (#622) — the ONE refresh module behind the per-source feeds:
 * memory → Room → Source, decided by a single injected clock and the feed's
 * TTL ([FeedSnapshotPolicy]).
 *
 * It exists because the previous arrangement could turn a network failure into
 * a fresh empty snapshot: the exception was swallowed into `emptyList()`, the
 * empty list was cached with a fresh timestamp, and the Огляд stayed empty for
 * the rest of the TTL. Here the rules are explicit:
 *
 * - only a NON-EMPTY live result is remembered (memory and storage) and only
 *   it advances the observed-at stamp;
 * - [FeedRefreshOutcome.Failure] and [FeedRefreshOutcome.Empty] neither write
 *   memory/storage nor touch the previous snapshot;
 * - a snapshot promoted from storage keeps its ORIGINAL observed-at, so
 *   reading it never extends the TTL;
 * - a stamp in the future is never treated as fresh (a bad clock cannot freeze
 *   the feed forever);
 * - an explicit refresh bypasses memory and storage entirely;
 * - a failed storage write never hides the successful live result.
 *
 * The module owns no Android, Room or HTTP type: persistence is two suspend
 * lambdas, so the whole decision is pinned by plain JVM tests with a fake
 * clock (the StreamHealPolicy/FeedSnapshotPolicy convention).
 */
class FeedSnapshotRefresh(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** The stored snapshot of one feed (cards + its own observed-at), or null. */
    private val readPersisted: suspend (sourceId: String, feedKey: String) -> PersistedFeedSnapshot? =
        { _, _ -> null },
    /** Persists one successful live snapshot; false when the write failed. */
    private val writePersisted: suspend (snapshot: PersistedFeedSnapshot) -> Boolean = { true }
) {

    private data class MemoryEntry(val books: List<SourceBook>, val observedAt: Long)

    private val memory = ConcurrentHashMap<String, MemoryEntry>()

    /**
     * @param forceRefresh an explicit listener refresh: bypasses every cache.
     * @param skipCache a session-bound source's feed is never reusable.
     * @param fetch the live half; it may throw, and the throw becomes
     *   [FeedRefreshOutcome.Failure]. An empty list is a real empty answer.
     */
    suspend fun refresh(
        sourceId: String,
        feedKey: String,
        forceRefresh: Boolean = false,
        skipCache: Boolean = false,
        fetch: suspend () -> List<SourceBook>
    ): FeedRefreshOutcome {
        val cacheKey = "$sourceId|$feedKey"
        val ttl = FeedSnapshotPolicy.ttlMillisFor(feedKey)
        if (!forceRefresh && !skipCache) {
            memory[cacheKey]?.let { entry ->
                if (FeedSnapshotPolicy.isFresh(entry.observedAt, nowMillis(), ttl)) {
                    return FeedRefreshOutcome.Data(entry.books, entry.observedAt)
                }
            }
            val persisted = readPersisted(sourceId, feedKey)
            if (persisted != null &&
                persisted.books.isNotEmpty() &&
                FeedSnapshotPolicy.isFresh(persisted.observedAt, nowMillis(), ttl)
            ) {
                // Promotion keeps the ORIGINAL stamp: a read is not a refresh.
                memory[cacheKey] = MemoryEntry(persisted.books, persisted.observedAt)
                return FeedRefreshOutcome.Data(persisted.books, persisted.observedAt)
            }
        }

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

        val observedAt = nowMillis()
        memory[cacheKey] = MemoryEntry(books, observedAt)
        val snapshot = PersistedFeedSnapshot(sourceId, feedKey, books, observedAt)
        runCatching { writePersisted(snapshot) }
        return FeedRefreshOutcome.Data(books, observedAt)
    }
}
