package com.slukhayka.audiobooks.ui.catalog

import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.catalog.SourceReplacementMapping
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.player.SmartRetryPolicy
import kotlinx.coroutines.CancellationException

/**
 * Spec-49 T2b — the player-preparation twin of the card-tap mapping
 * ([CatalogCardActionCoordinator.crossResolveOnce]): a refused-only (or
 * sourceless) library book asks the replacement mapping ONCE per touch
 * when the resume/auto-play path finds nothing playable.
 *
 * The resolver owns the request discipline (union-first, the per-Work
 * 6h/15m memo, at most one volley); this unit owns the WHEN — never on a
 * book with a real locator, never over an explicit source choice, never
 * twice per call. A miss or a failure returns null and the caller keeps
 * its honest path; cancellation is rethrown, never swallowed into a
 * verdict.
 *
 * "Nothing playable" is NOT "an empty chapter list": a refused-only book
 * (4read is refused built-in) still carries its logical chapters with
 * every pair unpaired — the plan is non-empty and dead. The verdict is
 * therefore computed here, over the [SourceCatalog.PlayableChapter] plan
 * with the same locator rule the Player applies.
 *
 * Pure seams (resolve/import/notify as lambdas, fakes over mocks) so the
 * touch discipline is unit-testable without the Player: the composition in
 * `MainViewModel` plugs the real resolver, the ordinary import door and
 * the Source Watch notification.
 */
class PlaybackReplacementMapping(
    private val resolve: suspend (title: String, author: String, mergeKey: String) -> SourceReplacementMapping.Match?,
    private val importMatch: suspend (book: AudiobookEntity, match: SourceReplacementMapping.Match) -> AudiobookEntity?,
    private val onMapped: suspend (mergeKey: String, sourceId: String) -> Unit = { _, _ -> }
) {

    /**
     * The book to continue playback with (the imported replacement), or
     * null when no mapping applies and the caller keeps its honest path.
     */
    suspend fun mapIfNeeded(
        book: AudiobookEntity,
        playable: List<SourceCatalog.PlayableChapter>,
        hasPreferredSource: Boolean
    ): AudiobookEntity? {
        if (hasPreferredSource || playable.any { it.hasPlayableLocator() }) {
            return null
        }
        val mergeKey = book.mergeKey.ifBlank { MergeKey.keyFor(book.title, book.author) }
        if (mergeKey.isBlank()) return null
        val match = try {
            resolve(book.title, book.author, mergeKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        try {
            onMapped(mergeKey, match.sourceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The watch notification is best-effort — a failing notify
            // must not lose the mapped book.
        }
        return try {
            importMatch(book, match)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /** The Player's own locator rule: a ready local copy or a non-blank URL. */
    private fun SourceCatalog.PlayableChapter.hasPlayableLocator(): Boolean {
        val track = track ?: return false
        return SmartRetryPolicy.localFileReady(track.localFilePath) || track.url.isNotBlank()
    }
}
