package com.example.widget

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.player.PlaybackService
import kotlinx.coroutines.guava.await

/**
 * Spec-17 (#110): the widget's only window into the app is the MediaSession
 * hosted by [PlaybackService]. Both directions go through a [MediaController]:
 *
 *  - [SessionSnapshotReader] reads playback state + metadata (the session is
 *    the single source of truth — the widget never touches app-scoped state);
 *  - [SessionCommandSender] sends transport commands in place (no app launch).
 *
 * The controller connect pattern mirrors `AudioPlayerManager`'s existing
 * `ensureMediaControllerConnected()`.
 */

object SessionSnapshotReader {

    /** One-shot read: connect, snapshot, release. Null when no session is reachable. */
    suspend fun read(context: Context): SessionSnapshot? {
        val controller = connect(context) ?: return null
        return try {
            from(controller)
        } finally {
            controller.release()
        }
    }

    /** media3 values → [SessionSnapshot]. Thin glue; the mapping itself is pure. */
    fun from(player: Player): SessionSnapshot {
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        return SessionSnapshot(
            title = player.mediaMetadata.title?.toString(),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition,
            durationMs = durationMs,
            hasBook = player.mediaItemCount > 0 && player.currentMediaItem != null,
        )
    }
}

object SessionCommandSender {

    /** Sends one transport command through the session; no-op when unreachable. */
    suspend fun send(context: Context, command: (MediaController) -> Unit) {
        val controller = connect(context) ?: return
        try {
            command(controller)
        } finally {
            controller.release()
        }
    }

    /**
     * Sends one command through an already-established session. Used by the
     * session-integration tests to drive the exact commands the actions send,
     * without a fresh connection per invocation.
     */
    internal fun send(controller: MediaController, command: (MediaController) -> Unit) {
        command(controller)
    }
}

internal suspend fun connect(context: Context): MediaController? = try {
    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    MediaController.Builder(context, token).buildAsync().await()
} catch (e: Exception) {
    null
}