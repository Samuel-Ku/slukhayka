package com.slukhayka.audiobooks.data.collective

/** What a collective Огляд block shows. */
enum class CollectiveBlockKind {
    /** Freshly observed arrivals — the short 6 h TTL. */
    NEW_ARRIVALS,

    /** The source's recommendations — the 24 h TTL. */
    RECOMMENDATIONS,

    /** The source's curated collections — the 24 h TTL. */
    COLLECTIONS
}

/**
 * #523 — the block TTLs: новинки move fast (6 h), recommendations and
 * curated collections move slowly (24 h). One owner refreshes after the TTL;
 * everyone else keeps the last good block.
 */
object CollectiveBlockPolicy {
    const val NEW_ARRIVALS_TTL_MS: Long = 6L * 60 * 60 * 1000
    const val DEFAULT_TTL_MS: Long = 24L * 60 * 60 * 1000

    fun ttlMillisFor(kind: CollectiveBlockKind): Long = when (kind) {
        CollectiveBlockKind.NEW_ARRIVALS -> NEW_ARRIVALS_TTL_MS
        CollectiveBlockKind.RECOMMENDATIONS,
        CollectiveBlockKind.COLLECTIONS -> DEFAULT_TTL_MS
    }
}

/** One card of a collective block, in the source's own order. */
data class CollectiveBlockCard(
    val sourceId: String,
    val sourceUrl: String,
    val title: String,
    val author: String,
    val coverUrl: String? = null
)

/** #523 — the Source+kind a stable block key names. */
data class CollectiveBlockRef(
    val sourceId: String,
    val kind: CollectiveBlockKind
)

/** #523 — the ONE stable, Source-scoped block identity. */
fun collectiveBlockKey(sourceId: String, kind: CollectiveBlockKind): String =
    "$sourceId|${kind.name}"

/** Parses a block key back to its Source+kind; null for anything else. */
fun parseCollectiveBlockKey(blockKey: String): CollectiveBlockRef? {
    val separator = blockKey.lastIndexOf('|')
    if (separator <= 0) return null
    val sourceId = blockKey.substring(0, separator)
    val kind = CollectiveBlockKind.entries.firstOrNull { it.name == blockKey.substring(separator + 1) }
        ?: return null
    return CollectiveBlockRef(sourceId, kind)
}

/** #523 — the `feed_snapshots` feed key one block persists under. */
fun collectiveFeedKey(kind: CollectiveBlockKind): String = "collective-${kind.name.lowercase()}"

/** The status of the latest refresh attempt — honest, never a guess. */
enum class CollectiveAttemptStatus {
    SUCCESS,
    TIMEOUT,
    FORBIDDEN,
    NOT_FOUND,
    CHALLENGE,
    PARSE_FAILURE,
    EMPTY
}

data class CollectiveAttempt(
    val at: Long,
    val status: CollectiveAttemptStatus
)

/**
 * #523 / ADR-0041 — ONE collective Огляд block with stale-while-revalidate.
 *
 * Identity is Source-scoped and stable ([blockKey]); [cards] keep the source's
 * own order; [fetchedAt]/[staleAfter] carry the honesty window; [version]
 * increments only when a valid non-empty snapshot is activated; [lastAttempt]
 * records the latest try (including the ones that kept the previous good
 * block). A block with no cards is never active.
 */
data class CollectiveFeedBlock(
    val blockKey: String,
    val sourceId: String,
    val kind: CollectiveBlockKind,
    val name: String,
    val provenanceUrl: String,
    val cards: List<CollectiveBlockCard>,
    val fetchedAt: Long,
    val staleAfter: Long,
    val version: Long,
    val lastAttempt: CollectiveAttempt
) {
    fun isStale(now: Long): Boolean = now >= staleAfter

    /** The next version of this block after a successful refresh. */
    fun refreshed(
        cards: List<CollectiveBlockCard>,
        fetchedAt: Long,
        attempt: CollectiveAttempt
    ): CollectiveFeedBlock = copy(
        cards = cards,
        fetchedAt = fetchedAt,
        staleAfter = fetchedAt + CollectiveBlockPolicy.ttlMillisFor(kind),
        version = version + 1,
        lastAttempt = attempt
    )
}

/**
 * #528 — the result of ONE listener genre action: the candidate refresh
 * outcome plus the cursor of the NEXT page (null = last page). Passing the
 * cursor back is a separate action, so pagination is always listener-driven.
 */
data class CollectiveGenreFetch(
    val outcome: CollectiveRefreshOutcome,
    val nextCursor: String? = null
)

/** The result of one attempted source refresh. */
sealed interface CollectiveRefreshOutcome {
    /**
     * A non-empty page from the source (at most one page per open). The block
     * carries the source-scoped identity the page revealed; the orchestrator
     * stamps the honest time window and bumps the version.
     */
    data class Success(val block: CollectiveFeedBlock) : CollectiveRefreshOutcome

    /** An empty answer is a failure, never an activated empty block. */
    data object Empty : CollectiveRefreshOutcome

    data class Failure(val status: CollectiveAttemptStatus) : CollectiveRefreshOutcome
}

/**
 * #523 — the honest status of a failed one-page fetch. The adapters themselves
 * fail closed to an empty list (their transport returns "" on any non-200), so
 * a source-side 403/404/challenge surfaces as [CollectiveAttemptStatus.EMPTY];
 * an exception the transport DID throw is classified here rather than guessed.
 */
fun classifyCollectiveFailure(error: Throwable): CollectiveAttemptStatus = when (error) {
    is java.net.SocketTimeoutException -> CollectiveAttemptStatus.TIMEOUT
    is java.net.UnknownHostException -> CollectiveAttemptStatus.TIMEOUT
    is java.net.ConnectException -> CollectiveAttemptStatus.TIMEOUT
    is java.io.IOException -> CollectiveAttemptStatus.TIMEOUT
    else -> CollectiveAttemptStatus.PARSE_FAILURE
}
