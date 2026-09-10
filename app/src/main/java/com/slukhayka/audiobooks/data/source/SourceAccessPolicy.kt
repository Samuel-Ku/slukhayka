package com.slukhayka.audiobooks.data.source

/**
 * The stable access order used whenever one Work exposes more than one
 * physical source. Local copies win, then sources that can be opened with a
 * direct HTTP request, then legacy/unknown sources, and browser-only sources
 * are last. This is deliberately a capability order, not a health score: a
 * transient 403 must not permanently demote a source.
 */
enum class SourceAccessMode { DIRECT, UNKNOWN, BROWSER }

data class SourceAccessCandidate(
    val sourceId: String,
    val sourceName: String = sourceDisplayName(sourceId),
    val url: String = "",
    val localAvailable: Boolean = false,
    val accessMode: SourceAccessMode = SourceAccessPolicy.modeFor(sourceId)
)

object SourceAccessPolicy {
    // ADR-0036 (spec-48 T1): the browser family is the set of declared
    // Browser Recovery Profiles — one registry, not a second list to forget
    // when the next browser source connects.
    private val browserSources = BrowserRecoveryProfiles.orderedSourceIds.toSet()
    // Spec-45 (#405) T2 (#490): librivox streams from archive.org over plain
    // HTTPS — a direct source like the other server-fetch adapters.
    // Spec-47 T2: audiobook.co.ua is server-fetch too (T1 spike verdict PASS;
    // audio rides archive.org with ranges).
    // Spec-47 T3: chytaylo.com.ua is server-fetch (Next.js SSR, T1 verdict
    // PASS; audio `/api/audio-local/…mp3` serves ranges directly).
    // Spec-47 T5: ukrainianaudiobooks.com is NOT here — Cloudflare-gated
    // (T1 GATED), it resolves to BROWSER through its recovery profile below.
    private val directSources = setOf(
        "soundbooks", "audiobookmp3", "lihtar", "sluhayua", "librivox",
        "audiobookcoua", "chytaylo"
    )

    /**
     * Deterministic sub-order inside the DIRECT capability tier (#465):
     * soundbooks → sluhayua → audiobookmp3 → lihtar — parity with the web
     * worker's SOURCE_PRIORITY (`web/src/worker/workFeed.ts`). Direct sources
     * absent from a list keep this relative order; a direct id not listed here
     * (a future adapter) falls after the known ones and then ties by name.
     */
    private val directOrder = listOf("soundbooks", "sluhayua", "audiobookmp3", "lihtar")

    fun modeFor(sourceId: String): SourceAccessMode = when {
        sourceId in browserSources -> SourceAccessMode.BROWSER
        sourceId in directSources -> SourceAccessMode.DIRECT
        sourceId == "local" -> SourceAccessMode.DIRECT
        else -> SourceAccessMode.UNKNOWN
    }

    fun priority(candidate: SourceAccessCandidate): Int = when {
        candidate.localAvailable || candidate.sourceId == "local" -> 0
        candidate.accessMode == SourceAccessMode.DIRECT -> 1
        candidate.accessMode == SourceAccessMode.UNKNOWN -> 2
        else -> 3
    }

    /**
     * Stable ordering: capability first, then within DIRECT the deterministic
     * [directOrder] sub-order, then visible name, then id and URL.
     */
    fun order(candidates: Iterable<SourceAccessCandidate>): List<SourceAccessCandidate> =
        candidates.sortedWith(
            compareBy<SourceAccessCandidate> { priority(it) }
                .thenComparator { left, right ->
                    val leftRank = directSubOrder(left)
                    val rightRank = directSubOrder(right)
                    if (leftRank != rightRank) leftRank - rightRank
                    else String.CASE_INSENSITIVE_ORDER.compare(left.sourceName, right.sourceName)
                }
                .thenBy { it.sourceId }
                .thenBy { it.url }
        )

    private fun directSubOrder(candidate: SourceAccessCandidate): Int {
        val rank = directOrder.indexOf(candidate.sourceId)
        return if (candidate.accessMode == SourceAccessMode.DIRECT && rank >= 0) rank else directOrder.size
    }

    /**
     * True when every source on a search card is browser-gated: the shared
     * import budget can never fetch such a card, so the tap must surface the
     * honest refusal with the explicit browser door instead of doing nothing.
     */
    fun needsBrowserImport(sourceIds: Collection<String>): Boolean =
        sourceIds.isNotEmpty() && sourceIds.all { modeFor(it) == SourceAccessMode.BROWSER }
}
