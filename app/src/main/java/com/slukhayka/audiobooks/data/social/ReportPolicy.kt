package com.slukhayka.audiobooks.data.social

/**
 * #896 — reports, per `docs/specs/2026-09-16-social-layer.md` §4.
 *
 * The spec is explicit that the social layer does NOT invent a second
 * moderation: a report reuses the machinery that already works for published
 * collections — one person, one report per object; a counter of UNIQUE authors;
 * a hiding threshold; a one-sided `hidden` flag. This policy carries exactly
 * those rules, so the shared moderation queue receives the shape it already
 * understands.
 */
data class ReportState(
    /** Pseudonyms that reported this object — the unique-author counter. */
    val reporters: Set<String> = emptySet(),
    /** §4 — hidden for others, one-sided; never deleted. */
    val hidden: Boolean = false
) {
    val uniqueAuthors: Int get() = reporters.size
}

object ReportPolicy {

    /**
     * §4 — one person, one report per object: a second report by the same
     * pseudonym changes nothing (the counter must not inflate).
     */
    fun report(state: ReportState, reporter: String): ReportState =
        state.copy(reporters = state.reporters + reporter)

    /**
     * §4 — at the threshold the object is hidden for others. Hiding is not
     * deletion: the author keeps the record, and manual removal (tombstone +
     * blocklist) stays in the curator queue.
     */
    fun afterThreshold(state: ReportState, threshold: Int): ReportState {
        require(threshold > 0) { "threshold must be positive" }
        return state.copy(hidden = state.hidden || state.uniqueAuthors >= threshold)
    }

    /**
     * §4 — a report changes VISIBILITY, never the history or the audience: the
     * people who already saw the post keep that, and the audience stays what the
     * object's own contract says.
     */
    fun audienceStaysTheSame(before: Audience, after: Audience): Boolean = before == after

    /**
     * §4 — the author is never told WHO reported them: the report carries the
     * object, not the reporter, so nothing here hands back a pseudonym.
     */
    fun authorLearns(): Set<String> = emptySet()
}
