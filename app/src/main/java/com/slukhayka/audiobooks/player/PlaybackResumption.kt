package com.slukhayka.audiobooks.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import kotlinx.coroutines.delay

/**
 * #805 — what the media session hands back for a resumption request.
 *
 * [PlaybackService]'s `MediaSession.Callback.onPlaybackResumption` may only
 * return a [MediaSession.MediaItemsWithStartPosition]; the interesting decision
 * (which progress row, whether to restore at all) lives in [PlaybackResume].
 * This object is the seam between the two, kept out of the service so the
 * values handed to Media3 are testable without a running `MediaSessionService`.
 *
 * Two details are worth their comments:
 *
 *  - the item appears on the player a moment AFTER `loadAndPlayBook` returns
 *    (the manager prepares on its own scope), so a restore is followed by a
 *    bounded wait — reading the count inline would mostly answer «nothing»;
 *  - the position comes from the manager's state, not from the engine:
 *    `loadAndPlayBook` parks the engine at 0 and applies the saved offset only
 *    once the stream reports READY, so `player.currentPosition` here would
 *    silently resume the listener from zero.
 */
object PlaybackResumption {

    /** How long a restore may take to materialise on the player. */
    private const val ITEM_WAIT_MS = 1_000L

    suspend fun itemsFor(
        playerManager: AudioPlayerManager,
        libraryEntries: LibraryEntries,
        playableFor: suspend (String) -> List<SourceCatalog.PlayableChapter>,
        player: Player
    ): MediaSession.MediaItemsWithStartPosition {
        // A player that already holds a queue (the in-app «Продовжити слухати»
        // tile, the widget, an earlier callback) is the listener's choice —
        // PlaybackResume leaves it alone and reports false.
        val restored = runCatching {
            PlaybackResume.resumeMostRecent(
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
