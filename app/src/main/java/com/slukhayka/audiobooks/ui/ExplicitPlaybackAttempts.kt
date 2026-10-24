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
 *  - [Kind.RESUME] and [Kind.RELISTEN] supersede the previous allowance
 *    ([AutomaticPlaybackRecoveryGate.beginAttempt]) at the gesture and arm it
 *    only when the start actually succeeds ([armOnSuccess]) — a previous
 *    session's error arriving during asynchronous preparation must not spend
 *    the new attempt's budget;
 *  - [Kind.BOOKMARK] also arms only when its preparation installs the anchor:
 *    its preparation is asynchronous too, so arming at the gesture would let a
 *    previous session's error spend the new gesture's allowance.
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
            Kind.RESUME, Kind.RELISTEN, Kind.BOOKMARK -> recoveryGate.beginAttempt(bookId)
        }
        return Attempt(nextId.incrementAndGet(), bookId, kind).also { current = it }
    }

    /** True while [attempt] is still the gesture the listener last made. */
    fun isCurrent(attempt: Attempt): Boolean = current?.id == attempt.id

    /**
     * #1241 — a player clear or a record deletion after preparation started
     * invalidates it: its result must not reopen the player or restore deleted
     * audio. A later gesture starts a new attempt and supersedes it anyway.
     */
    fun supersede() {
        current = null
    }

    /**
     * A successful preparation installs [installedBookId] (the replacement
     * mapping may have swapped the Edition): only the current attempt may arm
     * recovery, and only for the book that was actually installed.
     */
    fun armOnSuccess(attempt: Attempt, installedBookId: String): Boolean {
        if (!isCurrent(attempt)) return false
        recoveryGate.arm(installedBookId)
        return true
    }
}
