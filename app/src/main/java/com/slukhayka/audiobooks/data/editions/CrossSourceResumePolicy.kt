package com.slukhayka.audiobooks.data.editions

/** #531 — what one resume attempt from an alternate Source may do. */
enum class ResumeDecision {
    /** Same Edition, safe mapping: fetch ONLY the chapters that are missing. */
    RESUME_MISSING_ONLY,

    /** Every chapter already has a ready local file — nothing to fetch. */
    NOTHING_MISSING,

    /**
     * The alternate does not serve the same Edition, or its chapters do not
     * map onto this Edition: NOT ONE file may be written, and the work goes to
     * an honest paused state instead of mixing narrations.
     */
    PAUSE_INCOMPATIBLE
}

/** The chapters a resume may fetch, and the reason when it may fetch none. */
data class ResumePlan(
    val decision: ResumeDecision,
    /** Ascending, de-duplicated; empty unless [ResumeDecision.RESUME_MISSING_ONLY]. */
    val missingChapterIndexes: List<Int>
)

/**
 * #531 — the cross-source resume rule, fail closed. Chapters already on disk
 * are ALWAYS kept (a ready file is addressed by Edition + chapter, not by the
 * source that happened to download it), so a Source refusal, a cancel/resume
 * or a restart never costs the listener a finished chapter. A resume asks the
 * confirmed alternate for the MISSING chapters only, and a mapping mismatch or
 * a different Edition writes nothing at all.
 */
object CrossSourceResumePolicy {

    fun plan(
        readyChapterIndexes: Set<Int>,
        requiredChapterIndexes: List<Int>,
        sameEdition: Boolean,
        chapterMappingSafe: Boolean
    ): ResumePlan {
        // An unproven or different Edition never writes a byte.
        if (!sameEdition || !chapterMappingSafe) {
            return ResumePlan(ResumeDecision.PAUSE_INCOMPATIBLE, emptyList())
        }
        val missing = requiredChapterIndexes
            .distinct()
            .filterNot { it in readyChapterIndexes }
            .sorted()
        if (missing.isEmpty()) {
            return ResumePlan(ResumeDecision.NOTHING_MISSING, emptyList())
        }
        return ResumePlan(ResumeDecision.RESUME_MISSING_ONLY, missing)
    }
}
