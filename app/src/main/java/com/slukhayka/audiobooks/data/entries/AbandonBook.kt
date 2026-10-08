package com.slukhayka.audiobooks.data.entries

/**
 * spec-52 US28 / #1174 — what the book page offers the listener about
 * abandoning one book. The decision is pure and lives here; the screen only
 * renders the answer.
 */
enum class AbandonOffer {
    /** Nothing to offer: a finished book, or one the listener never started. */
    NONE,

    /** The book has a position and no completion — it can be abandoned. */
    ABANDON,

    /** The book carries the mark right now — the offer is to take it back. */
    CANCEL
}

/**
 * spec-52 US28 / #1174 — the pure rules of «покинути книгу».
 *
 * The action belongs to a book the listener really STARTED and has not
 * finished (AC: both edges are tested). "Started" is the Listening State row —
 * the very evidence the 45->46 backfill read when it wrote IN_PROGRESS
 * (ADR-0046 §3: the live position lives there, and nothing else proves a
 * start), never the mere existence of a library card: that is what separates
 * the action from «Не цікаво», which is about a rejected recommendation,
 * not about a book already listened to.
 *
 * Completion always wins over the mark: a finished book is never offered the
 * abandon and never reads as abandoned, even while the stored pass still says
 * so (the completion itself clears the mark — the reward slice owns that
 * write).
 */
object AbandonBookPolicy {

    /**
     * The badge and the cancel offer live only while the book is not finished.
     */
    fun markIsLive(abandoned: Boolean, isCompleted: Boolean): Boolean =
        abandoned && !isCompleted

    /**
     * @return the ONE action the book page shows for this book, or
     *   [AbandonOffer.NONE] — decided from the stored mark, the Listening
     *   State and the completion, and from nothing else.
     */
    fun offer(
        storedAbandoned: Boolean,
        hasListeningState: Boolean,
        isCompleted: Boolean
    ): AbandonOffer = when {
        markIsLive(storedAbandoned, isCompleted) -> AbandonOffer.CANCEL
        isCompleted -> AbandonOffer.NONE
        hasListeningState -> AbandonOffer.ABANDON
        else -> AbandonOffer.NONE
    }
}
