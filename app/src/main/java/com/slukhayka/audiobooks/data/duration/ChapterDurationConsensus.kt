package com.slukhayka.audiobooks.data.duration

/**
 * #528 — is this chapter's duration corroborated by its siblings?
 *
 * The playback guard needs an expectation to compare a served body against,
 * and the only one available is the chapter's own stored duration. That
 * duration is exactly what the substitution poisons, so on its own it is not
 * evidence: stored 52 s against a 27 MB body looks «too small» in the
 * direction the guard would refuse, and refusing an honest file is worse than
 * playing one bad one.
 *
 * A book's chapters are the corroboration. A duration that agrees with at
 * least one sibling is a real chapter of this book, whatever a single CDN
 * response did; a lone 52-second row in a book of 28-minute chapters is not
 * corroborated by anything, so it can never justify refusing playback.
 *
 * Pure, so both halves of that asymmetry are provable without a player.
 */
object ChapterDurationConsensus {

    /**
     * Two durations agree when neither is more than this many times the
     * other. 2 absorbs the honest spread inside one Edition — a 27-minute
     * chapter beside a 53-minute one is normal — while a 52-second row beside
     * 28-minute chapters (32×) stays an outlier.
     */
    const val AGREEMENT_FACTOR = 2L

    /**
     * @param storedSeconds the chapter's own stored duration.
     * @param siblingSeconds the durations of the book's other chapters.
     * @return true when at least one sibling agrees with [storedSeconds].
     */
    fun corroborated(storedSeconds: Long, siblingSeconds: Collection<Long>): Boolean {
        if (storedSeconds <= 0L) return false
        return siblingSeconds.any { sibling ->
            sibling > 0L &&
                sibling != storedSeconds &&
                maxOf(sibling, storedSeconds) <= minOf(sibling, storedSeconds) * AGREEMENT_FACTOR
        }
    }

    /**
     * The duration the guard may rely on, or null when nothing corroborates
     * it — in which case the guard must stay out of the way.
     */
    fun trustedSeconds(storedSeconds: Long, siblingSeconds: Collection<Long>): Long? =
        storedSeconds.takeIf { corroborated(it, siblingSeconds) }
}
