package com.slukhayka.audiobooks.player

import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * #805 — resuming the most recently listened book from outside the UI.
 *
 * Two doors needed this step and only one had it:
 *
 *  - the home-screen widget ([com.slukhayka.audiobooks.widget.TogglePlayActionCallback])
 *    already took the freshest progress row and loaded it when the player was
 *    idle;
 *  - the media session did not. After the system kills the process, Media3
 *    rebuilds the session around the **empty** shared player, so a play command
 *    from the notification shade had nothing to resume: the app answered
 *    «Не вдалося відновити відтворення», «Повторити» had nothing to retry,
 *    while the in-app «Продовжити слухати» tile (which goes through
 *    `MainViewModel`) still worked.
 *
 * The load goes through [AudioPlayerManager.loadAndPlayBook] **with the playable
 * list**: the manager pairs each chapter with a physical track from that list,
 * and without it every chapter is trackless — `prepareChapter` then answers
 * «No playable locator for chapter 0», which is the very symptom above. Both
 * production callers therefore pass
 * [com.slukhayka.audiobooks.data.catalog.SourceCatalog.getPlayableChapters],
 * exactly like `MainViewModel` does for the in-app tile.
 */
object PlaybackResume {

    /**
     * Loads the freshest listened row into [playerManager] when nothing is
     * loaded yet.
     *
     * @param playableFor chapter→track pairs for a book id; production passes
     *   `SourceCatalog::getPlayableChapters`. Injected so the decision is
     *   testable without the catalogue graph.
     * @return true when a book with at least one playable chapter was handed to
     *   the manager, false when there was nothing to resume (already loaded, no
     *   progress rows, missing book, no playable chapters).
     */
    suspend fun resumeMostRecent(
        playerManager: AudioPlayerManager,
        libraryEntries: LibraryEntries,
        playableFor: suspend (String) -> List<SourceCatalog.PlayableChapter>,
        autoPlay: Boolean,
        // Injectable so the JVM tests run on their test scheduler; production
        // keeps the real dispatchers.
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        playerDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
    ): Boolean {
        // A loaded player is already the listener's choice — never replace it.
        if (playerManager.playerState.value.currentBook != null) return false

        val latest = withContext(ioDispatcher) {
            libraryEntries.recentProgress.first().maxByOrNull { it.lastListenedAt }
        } ?: return false

        val book = withContext(ioDispatcher) { libraryEntries.getBookSync(latest.bookId) }
            ?: return false

        val playable = withContext(ioDispatcher) { playableFor(book.id) }
        if (playable.isEmpty()) return false

        // ExoPlayer is driven from the application thread; every other
        // loadAndPlayBook caller (MainViewModel, the widget) does the same.
        withContext(playerDispatcher) {
            playerManager.loadAndPlayBook(
                book = book,
                chapters = playable.map { it.chapter },
                playable = playable,
                initialChapterIndex = latest.currentChapterIndex,
                initialPositionSeconds = latest.currentPositionSeconds,
                autoPlay = autoPlay
            )
        }
        return true
    }
}
