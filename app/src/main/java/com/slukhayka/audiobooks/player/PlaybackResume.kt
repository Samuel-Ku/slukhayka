package com.slukhayka.audiobooks.player

import com.slukhayka.audiobooks.data.db.ChapterEntity
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
 * This object is that step, shared by both doors. It deliberately goes through
 * [AudioPlayerManager.loadAndPlayBook]: the manager owns `playerState`, the
 * chapter→track pairing, the heal budget and progress saving, so restoring the
 * queue any other way would leave the UI and the session out of sync.
 */
object PlaybackResume {

    /**
     * Loads the freshest listened row into [playerManager] when nothing is
     * loaded yet.
     *
     * @param chaptersFor chapter list for a book id; production passes
     *   `SourceCatalog::getChaptersList`. Injected so the decision is testable
     *   without the catalogue graph.
     * @return true when a book was handed to the manager, false when there was
     *   nothing to resume (already loaded, no progress rows, missing book or
     *   chapters).
     */
    suspend fun resumeMostRecent(
        playerManager: AudioPlayerManager,
        libraryEntries: LibraryEntries,
        chaptersFor: suspend (String) -> List<ChapterEntity>,
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

        val chapters = withContext(ioDispatcher) { chaptersFor(book.id) }
        if (chapters.isEmpty()) return false

        // ExoPlayer is driven from the application thread; every other
        // loadAndPlayBook caller (MainViewModel, the widget) does the same.
        withContext(playerDispatcher) {
            playerManager.loadAndPlayBook(
                book = book,
                chapters = chapters,
                initialChapterIndex = latest.currentChapterIndex,
                initialPositionSeconds = latest.currentPositionSeconds,
                autoPlay = autoPlay
            )
        }
        return true
    }
}
