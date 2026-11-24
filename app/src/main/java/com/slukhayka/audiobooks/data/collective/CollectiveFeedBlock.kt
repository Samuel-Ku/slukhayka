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

    /**
     * The old parsers cached URL slugs and comment links instead of book
     * cards. A time-fresh shared block can therefore outlive the parser fix.
     * Match only those known broken shapes; a genuinely absent cover alone
     * must not turn every screen open into a source request.
     */
    fun requiresSourceRefresh(block: CollectiveFeedBlock): Boolean =
        block.kind == CollectiveBlockKind.NEW_ARRIVALS && block.cards.any { card ->
            card.coverUrl.isNullOrBlank() && when (card.sourceId) {
                "lihtar" -> card.author.isBlank() && card.title.equals(
                    card.sourceUrl.substringBefore('?').trimEnd('/').substringAfterLast('/').replace('-', ' '),
                    ignoreCase = true
                )
                "audiobookmp3" -> card.title.isNotBlank() && card.title == card.author
                else -> false
            }
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

/**
 * #523/#1238 — the Source, the kind and the canonical provenance context a
 * stable block key names.
 *
 * The context is empty only for keys written before #1238 (the #527 wire docs
 * and Room rows already in the wild). A non-empty context is what keeps two
 * related collections of ONE Source apart: «до A» and «до B» are different
 * blocks, not the same row rewritten.
 */
data class CollectiveBlockRef(
    val sourceId: String,
    val kind: CollectiveBlockKind,
    val provenanceContext: String = LEGACY_BLOCK_CONTEXT
)

/** The context of a key written before #1238 (or of a block without provenance). */
const val LEGACY_BLOCK_CONTEXT: String = ""

/**
 * #1238 — the canonical provenance context of one URL: the observation page
 * itself, without the transport noise that would make one page look like two
 * identities.
 *
 * Canonical means deterministic and total: scheme and fragment are dropped,
 * the authority is lowercased, a default port and a trailing slash go away,
 * and the key separator `|` is escaped. The path and the query stay — they are
 * exactly what distinguishes related page A from related page B. A blank URL
 * yields a blank context (the legacy identity), never an exception.
 */
fun canonicalProvenanceContext(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return ""
    val withoutFragment = trimmed.substringBefore('#')
    val schemeEnd = withoutFragment.indexOf("://")
    val rest = if (schemeEnd >= 0) withoutFragment.substring(schemeEnd + 3) else withoutFragment
    val boundary = rest.indexOfAny(charArrayOf('?', '/'))
    var authority = (if (boundary >= 0) rest.substring(0, boundary) else rest).lowercase()
    val pathAndQuery = (if (boundary >= 0) rest.substring(boundary) else "").trimEnd('/')
    authority = when {
        authority.endsWith(":80") -> authority.dropLast(3)
        authority.endsWith(":443") -> authority.dropLast(4)
        else -> authority
    }
    if (authority.isEmpty()) return ""
    return (authority + pathAndQuery).replace("|", "%7C")
}

/**
 * #523 — the legacy, Source-scoped block identity for a producer that has no
 * provenance URL to name. Kept because the #527 wire docs and Room rows
 * already use it; new producers should use the provenance-bearing key below.
 */
fun collectiveBlockKey(sourceId: String, kind: CollectiveBlockKind): String =
    "$sourceId|${kind.name}"

/**
 * #1238 — the block identity of one observed provenance URL. This is the key
 * every new producer should use: the canonical context travels inside the
 * identity, so a related collection never overwrites its sibling.
 */
fun collectiveBlockKey(
    sourceId: String,
    kind: CollectiveBlockKind,
    provenanceUrl: String
): String {
    val context = canonicalProvenanceContext(provenanceUrl)
    return if (context.isEmpty()) {
        collectiveBlockKey(sourceId, kind)
    } else {
        "$sourceId|${kind.name}|$context"
    }
}

/** Parses a block key back to its Source, kind and provenance context. */
fun parseCollectiveBlockKey(blockKey: String): CollectiveBlockRef? {
    val parts = blockKey.split('|', limit = 3)
    if (parts.size < 2) return null
    val sourceId = parts[0]
    if (sourceId.isEmpty()) return null
    val kind = CollectiveBlockKind.entries.firstOrNull { it.name == parts[1] } ?: return null
    val context = if (parts.size == 3) parts[2] else LEGACY_BLOCK_CONTEXT
    if (parts.size == 3 && context.isEmpty()) return null
    return CollectiveBlockRef(sourceId, kind, context)
}

/** The key of [ref]; the inverse of [parseCollectiveBlockKey]. */
fun collectiveBlockKey(ref: CollectiveBlockRef): String =
    if (ref.provenanceContext.isEmpty()) {
        collectiveBlockKey(ref.sourceId, ref.kind)
    } else {
        "${ref.sourceId}|${ref.kind.name}|${ref.provenanceContext}"
    }

/** #523 — the `feed_snapshots` feed key of a source-scoped block. */
fun collectiveFeedKey(kind: CollectiveBlockKind): String = "collective-${kind.name.lowercase()}"

/**
 * #1238 — the `feed_snapshots` feed key of one identity. A context-bearing
 * block gets a stable short-hash suffix, so its row never collides with its
 * sibling; the legacy context keeps the #527 key, so an upgraded install still
 * reads its last good block.
 */
fun collectiveFeedKey(ref: CollectiveBlockRef): String =
    if (ref.provenanceContext.isEmpty()) {
        collectiveFeedKey(ref.kind)
    } else {
        "${collectiveFeedKey(ref.kind)}-${stableContextHash(ref.provenanceContext)}"
    }

/**
 * #1238 — the shared document id of one identity. Firestore document ids are
 * bounded and cannot carry a URL, so the context rides as a stable hash; the
 * legacy context keeps the #527 id, so existing shared rows stay addressable.
 */
fun collectiveBlockDocumentId(ref: CollectiveBlockRef): String =
    if (ref.provenanceContext.isEmpty()) {
        "${ref.sourceId}-${ref.kind.name.lowercase()}"
    } else {
        "${ref.sourceId}-${ref.kind.name.lowercase()}-${stableContextHash(ref.provenanceContext)}"
    }

/**
 * #1238 — a deterministic, bounded FNV-1a (64 bit) digest of the canonical
 * context. It is NOT a security boundary: it only gives a URL a stable row id.
 */
fun stableContextHash(context: String): String {
    var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    for (byte in context.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xff)
        hash *= 0x100000001b3L
    }
    return java.lang.Long.toHexString(hash).padStart(16, '0')
}

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
    val lastAttempt: CollectiveAttempt,
    /**
     * #1238 — the server revision of the shared row. `0` means "observed
     * locally, not published yet": [fetchedAt] stays the honest time the
     * listener's device saw the page, while this number is the SERVER order of
     * the shared delta, assigned by the conditional publish (never by a device
     * clock). Legacy #527 rows decode as 0.
     */
    val revision: Long = 0L
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
