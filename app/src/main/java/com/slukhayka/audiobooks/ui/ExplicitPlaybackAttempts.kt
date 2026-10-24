package com.slukhayka.audiobooks.ui

import java.util.concurrent.atomic.AtomicLong

/**
 * #1240 — the ONE seam every explicit playback gesture passes through.
 *
 * A start is more than "call loadAndPlayBook": it must drop the previous
 * session's failure message (so a bookmark opened after an error never shows
 * that stale error) and put the automatic-recovery allowance into the state
 * THIS gesture asks for. Those two actions used to be assembled by hand in
 * each entry point, so a new door could silently skip one of them.
 *
 * The differences between gestures stay explicit in [Kind]:
 *  - [Kind.RESUME] supersedes the previous allowance ([AutomaticPlaybackRecoveryGate.beginAttempt])
 *    and arms it only when the start actually succeeds;
 *  - [Kind.RELISTEN] and [Kind.BOOKMARK] are deliberate gestures: they arm
 *    recovery up front.
 *
 * #1241 builds on the returned [Attempt]: later preparation results may only
 * publish if their attempt is still the current one.
 */
class ExplicitPlaybackAttempts(
    private val recoveryGate: AutomaticPlaybackRecoveryGate,
    private val clearPreviousFailure: () -> Unit
) {

    enum class Kind { RESUME, RELISTEN, BOOKMARK }

    data class Attempt(val id: Long, val bookId: String, val kind: Kind)

    private val nextId = AtomicLong(0)

    @Volatile
    private var current: Attempt? = null

    /** Starts a new explicit attempt: clears the old failure, then sets the allowance. */
    fun begin(bookId: String, kind: Kind): Attempt {
        clearPreviousFailure()
        when (kind) {
            Kind.RESUME -> recoveryGate.beginAttempt(bookId)
            Kind.RELISTEN, Kind.BOOKMARK -> recoveryGate.arm(bookId)
        }
        return Attempt(nextId.incrementAndGet(), bookId, kind).also { current = it }
    }

    /** True while [attempt] is still the gesture the listener last made. */
    fun isCurrent(attempt: Attempt): Boolean = current?.id == attempt.id

    /** A later action superseded the attempt: its preparation must not publish. */
    fun supersede() {
        current = null
    }

    /** Claims the automatic-recovery budget only for the current attempt. */
    fun claimAutomaticRecovery(attempt: Attempt): Boolean =
        isCurrent(attempt) && recoveryGate.claimFailure(attempt.bookId)
}
