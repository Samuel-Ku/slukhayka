package com.slukhayka.audiobooks.data.listening

/**
 * #1173 (T9) — where one listening session ends and the next begins. Pure on
 * purpose: the 14:59 / 15:00 edge is a table test, not a comment.
 *
 * A session is a continuous interval of playback. A pause of fifteen minutes
 * or more, a stop, or another book closes it (owner's decision, 2026-10-07).
 */
object PlaybackSessionPolicy {
    /** The pause that still belongs to the session. Fifteen minutes closes it. */
    const val PAUSE_GAP_MILLIS: Long = 15L * 60L * 1000L

    /**
     * Whether an interval that started at [startedAt] continues the session
     * that last observed playback at [previousEndedAt] for [previousBookId].
     *
     * The pause is measured between two OBSERVED edges — the end of the last
     * interval and the start of the next one — so a gap nobody played in is
     * exactly the silence between them. An unknown book (null) is not a
     * different book: only a real change closes the session.
     */
    fun continuesSession(
        previousBookId: String?,
        previousEndedAt: Long,
        bookId: String?,
        startedAt: Long
    ): Boolean = previousBookId == bookId && startedAt - previousEndedAt < PAUSE_GAP_MILLIS
}
