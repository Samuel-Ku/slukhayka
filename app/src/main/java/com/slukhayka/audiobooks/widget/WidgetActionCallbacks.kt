package com.slukhayka.audiobooks.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.player.PlaybackResume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Interactive Glance Action Callbacks for the Home Screen Widget (spec-21
 * Track B, restored as spec-22 T4). Each action drives the shared
 * app-scoped [com.slukhayka.audiobooks.player.AudioPlayerManager] and refreshes the
 * widget afterwards.
 */
class TogglePlayActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        try {
            val playerManager = App.instance.playerManager
            if (playerManager.playerState.value.currentBook != null) {
                withContext(Dispatchers.Main) { playerManager.togglePlayPause() }
            } else {
                // Nothing loaded: resume the most recently listened book. #805
                // moved this step into PlaybackResume so the media session's
                // resumption uses the exact same path instead of a second copy.
                PlaybackResume.resumeMostRecent(
                    playerManager = playerManager,
                    libraryEntries = App.instance.libraryEntries,
                    chaptersFor = { bookId -> App.instance.sourceCatalog.getChaptersList(bookId) },
                    autoPlay = true
                )
            }
        } catch (_: Exception) {}
        AudiobookGlanceWidget().updateAll(context)
    }
}

class Rewind15ActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        withContext(Dispatchers.Main) {
            try {
                App.instance.playerManager.skipBackward(15)
            } catch (_: Exception) {}
        }
        AudiobookGlanceWidget().updateAll(context)
    }
}

class FastForward15ActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        withContext(Dispatchers.Main) {
            try {
                App.instance.playerManager.skipForward(15)
            } catch (_: Exception) {}
        }
        AudiobookGlanceWidget().updateAll(context)
    }
}
