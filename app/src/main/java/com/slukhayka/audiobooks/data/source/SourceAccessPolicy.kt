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
    private val directSources = setOf("soundbooks", "audiobookmp3", "lihtar", "sluhayua")

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

    /** Stable ordering: capability first, then visible name, then id and URL. */
    fun order(candidates: Iterable<SourceAccessCandidate>): List<SourceAccessCandidate> =
        candidates.sortedWith(
            compareBy<SourceAccessCandidate> { priority(it) }
                .thenComparator { left, right ->
                    String.CASE_INSENSITIVE_ORDER.compare(left.sourceName, right.sourceName)
                }
                .thenBy { it.sourceId }
                .thenBy { it.url }
        )

    /**
     * True when every source on a search card is browser-gated: the shared
     * import budget can never fetch such a card, so the tap must surface the
     * honest refusal with the explicit browser door instead of doing nothing.
     */
    fun needsBrowserImport(sourceIds: Collection<String>): Boolean =
        sourceIds.isNotEmpty() && sourceIds.all { modeFor(it) == SourceAccessMode.BROWSER }
}
