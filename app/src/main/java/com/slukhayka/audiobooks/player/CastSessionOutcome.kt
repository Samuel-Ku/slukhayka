package com.slukhayka.audiobooks.player

import com.google.android.gms.cast.MediaStatus

/**
 * #1752 — why a cast session handed playback back to the phone.
 *
 * [CastPlaybackController.endCasting] cannot use [AudioPlayerManager]'s
 * `lastErrorMsg` for this: `prepareChapter` deliberately clears that field on
 * every local prepare, and the hand-back prepare happens in the same frame.
 * The outcomes are also not interchangeable — [ENDED] is the listener's
 * deliberate disconnect, [LOST] is a dropped receiver connection, and
 * [ERROR] is a receiver that stayed connected but could not play the bytes
 * (#1751: it reached `IDLE_REASON_ERROR`).
 */
enum class CastSessionOutcome {
    /** The listener (or the app) ended the cast session deliberately. */
    ENDED,

    /** The receiver connection dropped; this is a loss, not a normal end. */
    LOST,

    /**
     * The receiver reported `IDLE_REASON_ERROR` while the session was alive:
     * the proxy's bytes were not playable. The session was torn down and
     * playback handed back to the phone.
     */
    ERROR
}

/**
 * Cast's `SessionManagerListener.onSessionEnded(session, error)` reports a
 * `CastStatusCodes` value: `SUCCESS` (0) for a normal end, anything else for a
 * failure. Keeping the classification pure makes the deliberate/lost split
 * testable without a Cast device.
 */
internal fun castSessionOutcomeFor(errorCode: Int): CastSessionOutcome =
    if (errorCode == 0) CastSessionOutcome.ENDED else CastSessionOutcome.LOST

/**
 * #1751 — `IDLE_REASON_ERROR` on a chapter this app owns is a playback
 * failure. `IDLE_REASON_FINISHED` is a normal chapter end, and an IDLE for
 * somebody else's media is not ours to fail. Pure so the branch is testable
 * without a receiver or a Cast device.
 */
internal fun isReceiverPlaybackError(
    ownMedia: Boolean,
    playerState: Int,
    idleReason: Int
): Boolean = ownMedia &&
    playerState == MediaStatus.PLAYER_STATE_IDLE &&
    idleReason == MediaStatus.IDLE_REASON_ERROR
