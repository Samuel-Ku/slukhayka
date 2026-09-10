package com.slukhayka.audiobooks.data.source

/**
 * The stable access order used whenever one Work exposes more than one
 * physical source. Local copies win, then sources that can be opened with a
 * direct HTTP request, then legacy/unknown sources, and browser-only sources
 * are last. This is deliberately a capability order, not a health score: a
 * transient 403 must not permanently demote a source.
 *
 * ADR-0038 — the facts are the [SourceRegistry] (`sources.json`): the mode
 * comes from each entry's `accessMode`, the within-tier order from its
 * `order`. The tier RULE (LOCAL < DIRECT < UNKNOWN < BROWSER) stays code.
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

    /**
     * Deterministic sub-order inside the DIRECT capability tier: the
     * registry's `order` list (soundbooks → sluhayua → audiobookmp3 →
     * lihtar → librivox → audiobookcoua → chytaylo). Direct sources absent
     * from the registry fall after the known ones and then tie by name.
     */
    private val directOrder: List<String> =
        SourceRegistry.entries
            .filter { it.accessMode == SourceAccessMode.DIRECT && it.id != "local" }
            .sortedBy { it.order }
            .map { it.id }

    fun modeFor(sourceId: String): SourceAccessMode = SourceRegistry.modeFor(sourceId)

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
