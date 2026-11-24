@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.slukhayka.audiobooks.player

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 *
 * #1576 — the resume knowledge lives in ONE module. The media session's
 * `onPlaybackResumption` may only return
 * [MediaSession.MediaItemsWithStartPosition]; the decision (which progress row,
 * whether to restore at all) is [resumeMostRecent], and [itemsFor] is the thin
 * Media3-facing adapter that translates that decision into the values the
 * session hands back. The widget, the in-app tile and the media session all go
 * through this object, so the "nothing is loaded → take the freshest row" rule
 * has a single owner.
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

        // The first anchor selects a candidate only. A repair can invalidate
        // its position before we know which book's version to capture.
        val candidate = withContext(ioDispatcher) { libraryEntries.mostRecentListeningAnchor() } ?: return false
        val chapterStructureVersion = playerManager.chapterStructureVersion(candidate.progress.bookId)
        val anchor = withContext(ioDispatcher) { libraryEntries.mostRecentListeningAnchor() }
            ?.takeIf { it.progress.bookId == candidate.progress.bookId } ?: return false
        val latest = anchor.progress

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
                initialChapterId = anchor.chapterId,
                initialPositionSeconds = latest.currentPositionSeconds,
                autoPlay = autoPlay,
                expectedChapterStructureVersion = chapterStructureVersion
            )
        }
        return true
    }

    /** How long a restore may take to materialise on the player. */
    private const val ITEM_WAIT_MS = 1_000L

    /**
     * What [com.slukhayka.audiobooks.player.PlaybackService]'s
     * `MediaSession.Callback.onPlaybackResumption` hands back for a resumption
     * request: the item [resumeMostRecent] just loaded and the listener's saved
     * position.
     *
     * Two details are worth their comments:
     *
     *  - the item appears on the player a moment AFTER `loadAndPlayBook` returns
     *    (the manager prepares on its own scope), so a restore is followed by a
     *    bounded wait — reading the count inline would mostly answer «nothing»;
     *  - the position comes from the manager's state, not from the engine:
     *    `loadAndPlayBook` parks the engine at 0 and applies the saved offset
     *    only once the stream reports READY, so `player.currentPosition` here
     *    would silently resume the listener from zero.
     */
    suspend fun itemsFor(
        playerManager: AudioPlayerManager,
        libraryEntries: LibraryEntries,
        playableFor: suspend (String) -> List<SourceCatalog.PlayableChapter>,
        player: Player
    ): MediaSession.MediaItemsWithStartPosition {
        // A player that already holds a queue (the in-app «Продовжити слухати»
        // tile, the widget, an earlier callback) is the listener's choice —
        // resumeMostRecent leaves it alone and reports false.
        val restored = runCatching {
            resumeMostRecent(
                playerManager = playerManager,
                libraryEntries = libraryEntries,
                playableFor = playableFor,
                // The system asks to resume only because playback was
                // requested — starting here is the requested action.
                autoPlay = true
            )
        }.getOrDefault(false)

        val items = when {
            player.mediaItemCount > 0 -> currentItems(player)
            restored -> awaitItems(player)
            else -> emptyList()
        }

        val startPositionMs = if (items.isEmpty()) {
            0L
        } else {
            playerManager.playerState.value.currentPositionMs.coerceAtLeast(0L)
        }
        // #805 — the one line that tells a phone run whether the system really
        // asked for a resumption and what we answered: `restored=false` with an
        // empty list means the callback fired but found nothing, a non-zero
        // count means Media3 got the queue and the saved position.
        Log.i(
            "PlaybackService",
            "resumption: restored=$restored items=${items.size} positionMs=$startPositionMs"
        )
        return MediaSession.MediaItemsWithStartPosition(
            items,
            player.currentMediaItemIndex.coerceAtLeast(0),
            startPositionMs
        )
    }

    private fun currentItems(player: Player): List<MediaItem> =
        (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }

    /**
     * Waits for the manager's prepare to reach the player. An empty result
     * means «resumption not possible» to Media3 — the app's own load has still
     * started playback, so the honest answer is a short wait rather than an
     * immediate lie.
     */
    private suspend fun awaitItems(player: Player): List<MediaItem> {
        var waited = 0L
        while (player.mediaItemCount == 0 && waited < ITEM_WAIT_MS) {
            delay(25)
            waited += 25
        }
        return currentItems(player)
    }
}
