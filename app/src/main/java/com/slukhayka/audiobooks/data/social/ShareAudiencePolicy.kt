package com.slukhayka.audiobooks.data.social

/**
 * #893 — the audience of a post. `docs/specs/2026-09-16-social-layer.md` §1: ONE
 * dimension with three values and **no implicit one**. There is deliberately no
 * "default" constant here: every value has to be chosen by the listener, and the
 * policy below refuses anything that was not.
 */
enum class Audience {
    /** Only the author — the normal state of a personal record. */
    PRIVATE,

    /** The author and accepted friends. */
    FRIENDS,

    /** Anyone — allowed only where the content already has a public contract. */
    PUBLIC
}

/** What is about to be shared. */
enum class ShareKind {
    /** A private note / diary entry: it has no public contract. */
    NOTE,

    /** A review: public by its own contract. */
    REVIEW,

    /** A published collection: public by its own contract. */
    PUBLISHED_COLLECTION
}

/** The exact thing the listener is looking at before confirming. */
data class ShareDraft(
    val kind: ShareKind,
    val text: String,
    /** Id of the record the post points at, when there is one. */
    val sourceId: String? = null
)

sealed interface ShareDecision {
    data class Publish(val audience: Audience) : ShareDecision

    data class Refused(val reason: Reason) : ShareDecision

    enum class Reason {
        /** Nothing to share (the form was closed, or no draft was made). */
        NO_DRAFT,

        /** §6.1 — the listener did not confirm; closing a form publishes nothing. */
        NOT_CONFIRMED,

        /** §6.1 — what would be posted has to say something. */
        EMPTY_TEXT,

        /** §1 — this kind of content has no public contract. */
        AUDIENCE_NOT_ALLOWED,

        /** §1 — no implicit audience. */
        NO_AUDIENCE
    }
}

object ShareAudiencePolicy {

    /**
     * §1 — «Публічно» exists only for content that already carries a public
     * contract (a review, a published collection). A private note may be kept or
     * shared with friends, never made public by the social layer.
     */
    fun allowedAudiences(kind: ShareKind): Set<Audience> = when (kind) {
        ShareKind.NOTE -> setOf(Audience.PRIVATE, Audience.FRIENDS)
        ShareKind.REVIEW, ShareKind.PUBLISHED_COLLECTION ->
            setOf(Audience.PRIVATE, Audience.FRIENDS, Audience.PUBLIC)
    }

    /**
     * §6.1 — a private record becomes a post only after the listener SAW the
     * exact text and book, chose an audience and confirmed. Every other ending
     * — including closing the form — publishes nothing.
     */
    fun decide(
        draft: ShareDraft?,
        audience: Audience?,
        confirmed: Boolean
    ): ShareDecision {
        if (draft == null) return ShareDecision.Refused(ShareDecision.Reason.NO_DRAFT)
        if (!confirmed) return ShareDecision.Refused(ShareDecision.Reason.NOT_CONFIRMED)
        if (draft.text.isBlank()) return ShareDecision.Refused(ShareDecision.Reason.EMPTY_TEXT)
        if (audience == null) return ShareDecision.Refused(ShareDecision.Reason.NO_AUDIENCE)
        if (audience !in allowedAudiences(draft.kind)) {
            return ShareDecision.Refused(ShareDecision.Reason.AUDIENCE_NOT_ALLOWED)
        }
        return ShareDecision.Publish(audience)
    }
}
