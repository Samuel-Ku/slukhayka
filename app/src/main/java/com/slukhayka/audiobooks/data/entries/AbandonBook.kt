package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.data.listening.BookProgress

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
 * Completion arrives as [BookProgress] — the ONE shared rule
 * (`data.listening.isBookFinished`): the manual «Прослухано» flag, or a position
 * that reached the book's own end. This slice only HIDES the mark of a
 * finished book: the stored pass keeps ABANDONED, and the write that clears it
 * belongs to the reward slice of #1174 (after #1160), which has to see the mark
 * at the moment of completion to award «Друге дихання». Until that lands, a
 * finished book simply never reads as abandoned anywhere — no badge and no
 * cancel offer — which is honest: a finished book is not an abandoned one, and
 * nothing in the UI claims a write that does not exist yet.
 */
object AbandonBookPolicy {

    /**
     * The badge and the cancel offer live only while the book is not finished.
     */
    fun markIsLive(abandoned: Boolean, isFinished: Boolean): Boolean =
        abandoned && !isFinished

    /**
     * @return the ONE action the book page shows for this book, or
     *   [AbandonOffer.NONE] — decided from the stored mark, the Listening
     *   State and the shared completion verdict, and from nothing else.
     */
    fun offer(
        storedAbandoned: Boolean,
        hasListeningState: Boolean,
        bookProgress: BookProgress
    ): AbandonOffer = when {
        markIsLive(storedAbandoned, bookProgress.isFinished) -> AbandonOffer.CANCEL
        bookProgress.isFinished -> AbandonOffer.NONE
        hasListeningState -> AbandonOffer.ABANDON
        else -> AbandonOffer.NONE
    }
}
