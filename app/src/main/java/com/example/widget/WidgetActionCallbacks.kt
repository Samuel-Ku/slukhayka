package com.example.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.example.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Interactive Glance Action Callbacks for the Home Screen Widget (spec-21
 * Track B, restored as spec-22 T4). Each action drives the shared
 * app-scoped [com.example.player.AudioPlayerManager] and refreshes the
 * widget afterwards.
 */
class TogglePlayActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        withContext(Dispatchers.Main) {
            try {
                val playerManager = App.instance.playerManager
                if (playerManager.playerState.value.currentBook != null) {
                    playerManager.togglePlayPause()
                } else {
                    // Nothing loaded: resume the most recently listened book.
                    val latest = App.instance.repository.recentProgress.first().maxByOrNull { it.lastListenedAt }
                    if (latest != null) {
                        val book = App.instance.repository.getBookSync(latest.bookId)
                        if (book != null) {
                            val chapters = App.instance.repository.getChaptersList(book.id)
                            playerManager.loadAndPlayBook(
                                book = book,
                                chapters = chapters,
                                initialChapterIndex = latest.currentChapterIndex,
                                initialPositionSeconds = latest.currentPositionSeconds,
                                autoPlay = true
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }
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
