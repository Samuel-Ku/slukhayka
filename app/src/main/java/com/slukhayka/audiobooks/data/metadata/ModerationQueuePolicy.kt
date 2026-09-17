package com.slukhayka.audiobooks.data.metadata

/** What the moderation queue says about one canonical link. */
enum class ModerationQueueDecision {
    /** The link has no usable canonical form — it can never be a candidate. */
    INVALID,

    /** Nothing known about this link — queue it as a candidate. */
    ENQUEUE,

    /** This link is already waiting for the curator. */
    ALREADY_QUEUED,

    /** This link is already in the shared base (approved earlier). */
    ALREADY_PUBLISHED,

    /** The curator rejected this link: it can never come back. */
    REJECTED
}

/**
 * Moderation T1/T3 (#834/#836) — the pre-check the app runs BEFORE queueing a
 * link, keyed by the canonical URL's hash exactly like the documents
 * themselves. It is deliberately pure: the caller supplies what the three
 * collections already say, and the decision is one place, shared with the
 * listener-facing verdict ("Уже в спільній базі" / "На модерації" /
 * "Відхилено").
 */
object ModerationQueuePolicy {

    fun documentId(canonicalUrl: String): String = SubmissionCandidateCodec.documentId(canonicalUrl)

    /**
     * Precedence is the listener's truth: a REJECTED link can never be queued
     * again (T3), an already published link is not a new candidate, and an
     * already queued link is simply still waiting — a friendly state, never an
     * error.
     */
    fun decide(
        canonicalUrl: String,
        rejected: Boolean,
        published: Boolean,
        queued: Boolean
    ): ModerationQueueDecision = when {
        documentId(canonicalUrl).isEmpty() -> ModerationQueueDecision.INVALID
        rejected -> ModerationQueueDecision.REJECTED
        published -> ModerationQueueDecision.ALREADY_PUBLISHED
        queued -> ModerationQueueDecision.ALREADY_QUEUED
        else -> ModerationQueueDecision.ENQUEUE
    }

    /** The listener-facing verdict for a decision — honest, never a promise. */
    fun listenerVerdict(decision: ModerationQueueDecision): String = when (decision) {
        ModerationQueueDecision.INVALID -> "invalid"
        ModerationQueueDecision.ENQUEUE -> "queued"
        ModerationQueueDecision.ALREADY_QUEUED -> "on-moderation"
        ModerationQueueDecision.ALREADY_PUBLISHED -> "in-shared-base"
        ModerationQueueDecision.REJECTED -> "rejected"
    }
}
