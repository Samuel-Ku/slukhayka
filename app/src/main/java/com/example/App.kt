package com.example

import android.app.Application
import com.example.data.db.AudiobookDatabase
import com.example.data.listening.ListeningStateStore
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

    /** ADR-0002: one Listening State Store shared by the repository and the player. */
    val listeningState: ListeningStateStore by lazy { ListeningStateStore(database.audiobookDao()) }

    /** Single repository shared by the UI and the playback stack. */
    val repository: AudiobookRepository by lazy {
        AudiobookRepository(database.audiobookDao(), this, listeningState = listeningState)
    }

    /** Single player manager; created lazily on first playback/service access. */
    val playerManager: AudioPlayerManager by lazy {
        // The player runs on the store; chapter materialisation (incl. the
        // 4read page fallback) stays on the repository's chapter-fetch path.
        AudioPlayerManager(this, listeningState, repository::getChaptersList)
    }

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
