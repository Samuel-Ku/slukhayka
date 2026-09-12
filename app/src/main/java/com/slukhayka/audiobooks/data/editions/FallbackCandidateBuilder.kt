package com.slukhayka.audiobooks.data.editions

import com.slukhayka.audiobooks.data.source.SourceAccessMode

/**
 * #530 — the per-Source facts the candidate builder needs. Each Source keeps
 * its OWN identity ([sourceId]) and access mode; [sameEdition] is
 * [EditionMatchPolicy]'s verdict for this Source against the Edition the
 * listener is in, and [chapterMappingSafe] says whether its chapters map onto
 * that Edition without guessing.
 */
data class SourceAvailabilityFacts(
    val sourceId: String,
    val accessMode: SourceAccessMode,
    val sameEdition: Boolean = false,
    val chapterMappingSafe: Boolean = true,
    /** The audio is already on the device (a local import). */
    val isLocal: Boolean = false
)

/**
 * #530 — builds the ordered fallback candidates of #519's action from the
 * Source rows that actually exist. Sources that are skipped (a refused source
 * or one inside its cooldown window) are left OUT — never deleted, just not
 * offered — and every remaining source is classified honestly:
 *
 * - a local rendition is [FallbackCandidateKind.LOCAL];
 * - the Source currently in use is [FallbackCandidateKind.CURRENT_DIRECT];
 * - another DIRECT Source of the SAME Edition is
 *   [FallbackCandidateKind.ALTERNATE_DIRECT_SAME_EDITION] (it may continue the
 *   chapter and position);
 * - a browser-gated Source is [FallbackCandidateKind.BROWSER] (always asks);
 * - anything else is [FallbackCandidateKind.CONFIRMED_OTHER_EDITION].
 */
object FallbackCandidateBuilder {

    fun build(
        facts: List<SourceAvailabilityFacts>,
        currentSourceId: String?,
        skippedSourceIds: Set<String> = emptySet()
    ): List<FallbackCandidate> {
        val candidates = facts
            .filter { it.sourceId.isNotBlank() && it.sourceId !in skippedSourceIds }
            .distinctBy { it.sourceId }
            .map { source -> candidateOf(source, currentSourceId) }
        return FallbackCandidateOrder.ordered(candidates)
    }

    private fun candidateOf(
        source: SourceAvailabilityFacts,
        currentSourceId: String?
    ): FallbackCandidate {
        val kind = when {
            source.isLocal -> FallbackCandidateKind.LOCAL
            source.sourceId == currentSourceId &&
                source.accessMode == SourceAccessMode.DIRECT -> FallbackCandidateKind.CURRENT_DIRECT
            source.accessMode == SourceAccessMode.BROWSER -> FallbackCandidateKind.BROWSER
            source.accessMode == SourceAccessMode.DIRECT && source.sameEdition ->
                FallbackCandidateKind.ALTERNATE_DIRECT_SAME_EDITION
            else -> FallbackCandidateKind.CONFIRMED_OTHER_EDITION
        }
        val verdict = when {
            kind == FallbackCandidateKind.ALTERNATE_DIRECT_SAME_EDITION ||
                kind == FallbackCandidateKind.LOCAL ||
                kind == FallbackCandidateKind.CURRENT_DIRECT -> EditionMatchVerdict.SAME_EDITION
            kind == FallbackCandidateKind.CONFIRMED_OTHER_EDITION -> EditionMatchVerdict.OTHER_EDITION
            else -> EditionMatchVerdict.SAME_EDITION
        }
        return FallbackCandidate(
            sourceId = source.sourceId,
            kind = kind,
            editionVerdict = verdict,
            chapterMappingSafe = source.chapterMappingSafe
        )
    }

    /**
     * The candidate #519's action may start by itself, or null when every
     * option needs the listener's confirmation. Skipped sources never appear.
     */
    fun autoStartable(
        facts: List<SourceAvailabilityFacts>,
        currentSourceId: String?,
        skippedSourceIds: Set<String> = emptySet()
    ): FallbackCandidate? = FallbackCandidateOrder.autoStartable(
        build(facts, currentSourceId, skippedSourceIds)
    )
}
