package com.slukhayka.audiobooks.player

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity

/**
 * #1647 — the player must show duration FACTS, not a playback-only snapshot.
 *
 * `AudioPlayerManager.playerState.chapters` is installed when a book is
 * prepared and refreshed on a chapter switch / structural repair — a snapshot.
 * The background chapter-duration probe ([com.slukhayka.audiobooks.data.duration.ChapterDurationProbe])
 * and the enrichment pass write real durations into Room later, while the
 * listener keeps the same player screen open; the snapshot therefore kept
 * saying «Тривалість невідома» for chapters whose rows were already measured,
 * and the book total stayed «unknown» even after the probe had written it.
 *
 * This overlay merges the LIVE Room facts onto that snapshot for display:
 * a chapter keeps its identity, title and position from the player's committed
 * list, and takes the measured duration from its Room row when that row knows
 * more. A book row follows the live entity (its `totalDurationSeconds` may
 * have been filled by the probe). Playback progress keeps using the same
 * merged list, so the chapter ruler and the honest book ruler
 * ([knownBookTotalSeconds]) see the same facts as the lists.
 *
 * The overlay never invents: a zero/absent live duration leaves the snapshot
 * value alone, and a missing live row is not removed (a structural repair
 * owns list identity, not this display merge).
 */
fun PlayerState.withLiveDurationFacts(
    liveBook: AudiobookEntity?,
    liveChapters: List<ChapterEntity>
): PlayerState {
    val liveById = liveChapters.associateBy { it.id }
    var changed = liveBook != null && liveBook != currentBook
    val merged = chapters.map { chapter ->
        val live = liveById[chapter.id]
        if (live != null && live.durationSeconds > 0L && live.durationSeconds != chapter.durationSeconds) {
            changed = true
            chapter.copy(durationSeconds = live.durationSeconds)
        } else {
            chapter
        }
    }
    if (!changed) return this
    return copy(currentBook = liveBook ?: currentBook, chapters = merged)
}
