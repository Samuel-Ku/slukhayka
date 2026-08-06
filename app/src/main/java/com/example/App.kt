package com.example

import android.app.Application
import com.example.data.db.AudiobookDatabase
import com.example.data.repository.AudiobookRepository
import com.example.player.AudioPlayerManager

/**
 * Application-scoped dependency graph.
 *
 * Previously [com.example.ui.MainViewModel] constructed both
 * [AudiobookRepository] and [AudioPlayerManager] and released the player in
 * `onCleared()` — which meant backgrounding the app and letting the system
 * destroy the Activity (or the ViewModel store) killed playback within
 * minutes. The playback stack now lives here, for the lifetime of the process,
 * and is shared by:
 *
 *  - `MainViewModel` (UI state + playback commands)
 *  - `PlaybackService` (MediaSession + notification for background playback)
 *
 * AudioPlaybackEspressoTest reads `App.instance` through the activity's
 * `MainViewModel`, so the single instance is intentionally kept here rather
 * than duplicated.
 */
class App : Application() {

    private val database by lazy { AudiobookDatabase.getDatabase(this) }

    /** Single repository shared by the UI and the playback stack. */
    val repository: AudiobookRepository by lazy { AudiobookRepository(database.audiobookDao(), this) }

    /** Single player manager; created lazily on first playback/service access. */
    val playerManager: AudioPlayerManager by lazy { AudioPlayerManager(this, repository) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        /** Late-init singleton; safe because Application.onCreate runs before any component. */
        lateinit var instance: App
            private set
    }
}
