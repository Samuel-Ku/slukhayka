package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.ingest.ListenerSubmissionFlow
import com.slukhayka.audiobooks.data.ingest.SubmissionState

/**
 * Moderation T4 (#837) — the ONE honest badge a book card may show for the
 * listener's own submission. It is derived from the stored row (never from a
 * promise), and a REJECTED badge appears ONLY when the curator actually
 * rejected the link: a daily-limit or metadata refusal is not a rejection and
 * must not be dressed as one (ADR-0014).
 */
enum class SubmissionBadge {
    /** No submission of mine rides this book — nothing to show. */
    NONE,

    /** The candidate waits for the curator's decision. */
    PENDING_MODERATION,

    /** The curator approved it; it is in the shared base. */
    IN_SHARED_BASE,

    /** The curator rejected this link: it can never come back. */
    REJECTED
}

object SubmissionBadgePolicy {

    /** The badge for a stored row, or [SubmissionBadge.NONE] when there is none. */
    fun badgeFor(row: SubmissionState?): SubmissionBadge {
        if (row == null) return SubmissionBadge.NONE
        return when (row.state) {
            SubmissionState.State.PENDING_MODERATION -> SubmissionBadge.PENDING_MODERATION
            SubmissionState.State.PUBLISHED -> SubmissionBadge.IN_SHARED_BASE
            SubmissionState.State.REFUSED ->
                if (row.reason == ListenerSubmissionFlow.Reason.REJECTED.name) {
                    SubmissionBadge.REJECTED
                } else {
                    // A budget or metadata refusal is NOT a curator decision.
                    SubmissionBadge.NONE
                }
            else -> SubmissionBadge.NONE
        }
    }
}
