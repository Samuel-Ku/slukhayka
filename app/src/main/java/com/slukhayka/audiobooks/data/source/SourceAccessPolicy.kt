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
    private val browserSources = setOf("4read", "sluhay", "sluhayknigi")
    // Spec-45 (#405) T2 (#490): librivox streams from archive.org over plain
    // HTTPS — a direct source like the other server-fetch adapters.
    private val directSources = setOf("soundbooks", "audiobookmp3", "lihtar", "sluhayua", "librivox")

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
