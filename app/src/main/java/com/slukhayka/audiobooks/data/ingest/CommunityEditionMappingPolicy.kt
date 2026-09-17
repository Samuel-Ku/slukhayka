package com.slukhayka.audiobooks.data.ingest

/** An Edition already on this device: its Work, its narration, its public audio. */
data class KnownEdition(
    val workKey: String,
    val narrator: String,
    /** True when a PUBLIC source already serves audio for this Edition. */
    val hasPublicSource: Boolean
)

/** What a community post adds to the library. */
enum class CommunityAddition {
    /** The link is already in the shared base — a friendly state, not an error. */
    ALREADY_IN_SHARED_BASE,

    /** The same Work AND the same narration already has public audio. */
    ALREADY_PUBLIC,

    /** A new Edition of the Work (a different, or unknown, narration). */
    NEW_EDITION
}

/** One submission batch split by the honest daily budget. */
data class CommunitySubmissionBatch(
    val accepted: List<CommunityBookGroup>,
    val deferred: List<CommunityBookGroup>
)

/**
 * ADR-0050 / #830 — where a community submission lands.
 *
 * The rules come from ADR-0010 (an Edition IS its Work + narration) and the
 * `NarrationClaimPolicy` precedent:
 *
 * - a Work whose SAME narration is already on this device maps onto that
 *   Edition, so listening progress is never forked into a second card;
 * - a DIFFERENT named narration is a different Edition — never merged by
 *   title alone;
 * - an unknown narration on either side is NEVER evidence of sameness
 *   (ADR-0014: absence is not an anchor);
 * - a link already in the shared base is a friendly state, and a Work whose
 *   same narration already has PUBLIC audio is deduped against it;
 * - the day's budget accepts what fits and DEFERS the rest honestly instead of
 *   refusing the whole batch.
 */
object CommunityEditionMappingPolicy {

    fun decide(
        post: CommunityPostRef,
        sharedBaseContainsLink: Boolean,
        knownEditions: List<KnownEdition>
    ): CommunityAddition {
        if (sharedBaseContainsLink) return CommunityAddition.ALREADY_IN_SHARED_BASE
        val workKey = CommunityLibrarySubmissionPolicy.workKeyFor(post.title, post.author)
        val reused = reuseEdition(workKey, post.narrator, knownEditions)
        return if (reused != null && reused.hasPublicSource) {
            CommunityAddition.ALREADY_PUBLIC
        } else {
            CommunityAddition.NEW_EDITION
        }
    }

    /**
     * The existing Edition this Work + narration maps onto, or null when the
     * submission honestly needs a new one. Never forks progress: a match is
     * REUSED, and a non-match is a genuinely different narration.
     */
    fun reuseEdition(
        workKey: String,
        narrator: String?,
        knownEditions: List<KnownEdition>
    ): KnownEdition? {
        val claimed = narrator.orEmpty().trim()
        return knownEditions.firstOrNull { edition ->
            edition.workKey == workKey && sameNarration(edition.narrator, claimed)
        }
    }

    /**
     * The only sameness rule: both sides name the same narrator, or BOTH are
     * unknown. One named and one blank is never a match — absence is not an
     * anchor, so it can never silently reuse (and thereby fork) an Edition.
     */
    private fun sameNarration(stored: String, found: String): Boolean {
        val a = stored.trim()
        val b = found.trim()
        return when {
            a.isEmpty() && b.isEmpty() -> true
            a.isEmpty() || b.isEmpty() -> false
            else -> a.equals(b, ignoreCase = true)
        }
    }

    /**
     * Splits one batch by the day's remaining budget: the first [remainingToday]
     * books are accepted, the rest are DEFERRED (not refused) in the submitted
     * order. A zero or negative budget defers everything.
     */
    fun planBatch(
        groups: List<CommunityBookGroup>,
        remainingToday: Int
    ): CommunitySubmissionBatch {
        if (groups.isEmpty()) return CommunitySubmissionBatch(emptyList(), emptyList())
        val fits = remainingToday.coerceAtLeast(0).coerceAtMost(groups.size)
        return CommunitySubmissionBatch(
            accepted = groups.take(fits),
            deferred = groups.drop(fits)
        )
    }
}
