package com.example.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

/**
 * Spec-17 (#110): widget button actions. Every transport command goes through
 * the MediaSession in place (no app launch) via [SessionCommandSender].
 *
 * Glance instantiates each callback reflectively, so every class must keep a
 * no-arg constructor.
 */

/** Play/pause toggle: mirrors the session's playing flag. */
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        SessionCommandSender.send(context) { controller ->
            if (controller.isPlaying) controller.pause() else controller.play()
        }
    }
}

/** Jump to the next chapter. */
class NextChapterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        SessionCommandSender.send(context) { controller -> controller.seekToNextMediaItem() }
    }
}

/** Jump to the previous chapter. */
class PreviousChapterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        SessionCommandSender.send(context) { controller -> controller.seekToPreviousMediaItem() }
    }
}