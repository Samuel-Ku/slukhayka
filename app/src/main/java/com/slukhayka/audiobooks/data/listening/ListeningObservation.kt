package com.slukhayka.audiobooks.data.listening

/**
 * #1173 (T9) — what the engine actually observed, and the single input of the
 * listening recorder. The day row, the session and the counter are written
 * from one place, in one order (ADR-0060: one writer, immutable facts).
 */
sealed interface ListeningObservation {
    /**
     * One interval of verified playback: the monotonic wall milliseconds the
     * engine really played, the book that played, and the STRONG evidence seen
     * while it played.
     *
     * [offline] means the local file / content URI of the current track really
     * played — never `book.isDownloaded`. [cast] means the receiver was
     * confirmed PLAYING (`reportActualCastPlayback`) — never the `isCasting`
     * flag and never a transport command. Weak evidence adds nothing
     * (ADR-0014, ADR-0060).
     */
    data class Played(
        val millis: Long,
        val bookId: String? = null,
        val offline: Boolean = false,
        val cast: Boolean = false
    ) : ListeningObservation

    /**
     * Playback was STOPPED, not paused: the open session ends here and the next
     * [Played] interval starts a new one. A pause does not come through here —
     * it is simply the gap between two [Played] observations.
     */
    data object Stopped : ListeningObservation
}
