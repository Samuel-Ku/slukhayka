package com.slukhayka.audiobooks.data.editions

/** #530 — what kind of alternative one candidate is. */
enum class FallbackCandidateKind {
    /** The audio is already on the device (local files) — the safest start. */
    LOCAL,

    /** The Direct Source currently in use. */
    CURRENT_DIRECT,

    /** Another Direct Source that serves the SAME Edition (same voice). */
    ALTERNATE_DIRECT_SAME_EDITION,

    /** A browser-gated Source: usable only through the live session door. */
    BROWSER,

    /** A different narration the listener must confirm first. */
    CONFIRMED_OTHER_EDITION
}

/**
 * #530 — one ordered fallback candidate for #519's availability action.
 * [editionVerdict] is [EditionMatchPolicy]'s verdict and [chapterMappingSafe]
 * says whether the candidate's chapters map onto the Edition the listener is
 * in without guessing.
 */
data class FallbackCandidate(
    val sourceId: String,
    val kind: FallbackCandidateKind,
    val editionVerdict: EditionMatchVerdict,
    val chapterMappingSafe: Boolean = true
) {
    /**
     * #530 — a candidate may start WITHOUT asking only when it is the same
     * Edition AND its chapters map safely. A browser door, another narration,
     * an unproven edition match or an unsafe mapping always asks first — the
     * app never switches the listener's narration silently.
     */
    val requiresConfirmation: Boolean
        get() = when (kind) {
            FallbackCandidateKind.LOCAL,
            FallbackCandidateKind.CURRENT_DIRECT -> !chapterMappingSafe
            FallbackCandidateKind.ALTERNATE_DIRECT_SAME_EDITION ->
                editionVerdict != EditionMatchVerdict.SAME_EDITION || !chapterMappingSafe
            FallbackCandidateKind.BROWSER,
            FallbackCandidateKind.CONFIRMED_OTHER_EDITION -> true
        }
}

/**
 * #530 — the ONE candidate order of #519's action:
 * local → current Direct → alternate Direct same Edition → browser →
 * confirmed other Edition. Ties keep the caller's order (a stable sort), so
 * the registry's own preference survives.
 */
object FallbackCandidateOrder {

    fun rank(candidate: FallbackCandidate): Int = when (candidate.kind) {
        FallbackCandidateKind.LOCAL -> 0
        FallbackCandidateKind.CURRENT_DIRECT -> 1
        FallbackCandidateKind.ALTERNATE_DIRECT_SAME_EDITION -> 2
        FallbackCandidateKind.BROWSER -> 3
        FallbackCandidateKind.CONFIRMED_OTHER_EDITION -> 4
    }

    fun ordered(candidates: List<FallbackCandidate>): List<FallbackCandidate> =
        candidates.sortedBy { rank(it) }

    /**
     * The candidate the action may start by itself, or null when every option
     * needs the listener's confirmation (the honest "ask, never guess" path).
     */
    fun autoStartable(candidates: List<FallbackCandidate>): FallbackCandidate? =
        ordered(candidates).firstOrNull { !it.requiresConfirmation }
}
