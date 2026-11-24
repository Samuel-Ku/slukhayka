package com.slukhayka.audiobooks.player

/**
 * #1752 — why a cast session handed playback back to the phone.
 *
 * [CastPlaybackController.endCasting] cannot use [AudioPlayerManager]'s
 * `lastErrorMsg` for this: `prepareChapter` deliberately clears that field on
 * every local prepare, and the hand-back prepare happens in the same frame.
 * The two outcomes are also not interchangeable — [ENDED] is the listener's
 * deliberate disconnect, [LOST] is a dropped receiver connection.
 */
enum class CastSessionOutcome {
    /** The listener (or the app) ended the cast session deliberately. */
    ENDED,

    /** The receiver connection dropped; this is a loss, not a normal end. */
    LOST
}

/**
 * Cast's `SessionManagerListener.onSessionEnded(session, error)` reports a
 * `CastStatusCodes` value: `SUCCESS` (0) for a normal end, anything else for a
 * failure. Keeping the classification pure makes the deliberate/lost split
 * testable without a Cast device.
 */
internal fun castSessionOutcomeFor(errorCode: Int): CastSessionOutcome =
    if (errorCode == 0) CastSessionOutcome.ENDED else CastSessionOutcome.LOST
