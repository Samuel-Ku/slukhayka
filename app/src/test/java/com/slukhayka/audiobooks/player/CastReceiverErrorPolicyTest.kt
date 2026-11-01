package com.slukhayka.audiobooks.player

import com.google.android.gms.cast.MediaStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1751 — the receiver branch that used to be ignored. `IDLE_REASON_ERROR` is
 * a playback failure; `IDLE_REASON_FINISHED` is a normal chapter end and must
 * keep its completion path. Only media this app owns may fail the cast.
 */
class CastReceiverErrorPolicyTest {

    @Test
    fun `idle with error on our own chapter is a playback failure`() {
        assertTrue(
            isReceiverPlaybackError(
                ownMedia = true,
                playerState = MediaStatus.PLAYER_STATE_IDLE,
                idleReason = MediaStatus.IDLE_REASON_ERROR
            )
        )
    }

    @Test
    fun `idle finished is a normal end, not an error`() {
        assertFalse(
            isReceiverPlaybackError(
                ownMedia = true,
                playerState = MediaStatus.PLAYER_STATE_IDLE,
                idleReason = MediaStatus.IDLE_REASON_FINISHED
            )
        )
    }

    @Test
    fun `a plain idle is not an error`() {
        assertFalse(
            isReceiverPlaybackError(
                ownMedia = true,
                playerState = MediaStatus.PLAYER_STATE_IDLE,
                idleReason = MediaStatus.IDLE_REASON_NONE
            )
        )
    }

    @Test
    fun `an error on media we do not own must not end our cast`() {
        assertFalse(
            isReceiverPlaybackError(
                ownMedia = false,
                playerState = MediaStatus.PLAYER_STATE_IDLE,
                idleReason = MediaStatus.IDLE_REASON_ERROR
            )
        )
    }

    @Test
    fun `a playing state is never an idle error`() {
        assertFalse(
            isReceiverPlaybackError(
                ownMedia = true,
                playerState = MediaStatus.PLAYER_STATE_PLAYING,
                idleReason = MediaStatus.IDLE_REASON_ERROR
            )
        )
    }
}
