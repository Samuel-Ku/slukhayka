package com.slukhayka.audiobooks.data.listening

import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.FailureCategory
import com.slukhayka.audiobooks.data.db.ListeningStatEntity
import com.slukhayka.audiobooks.data.db.PlaybackEventEntity
import com.slukhayka.audiobooks.data.db.PlaybackEventPolicy
import com.slukhayka.audiobooks.data.db.PlaybackFailureEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * ADR-0002 — Listening State Store: the deep module that owns every piece of
 * listening state over the shared DAO. Constructs with only the DAO; no
 * repository graph, no network, no Context.
 *
 * Owned state:
 *  - playback progress, keyed by the domain Edition (ADR-0007) — one row per
 *    rendition; switching sources mid-book keeps the position;
 *  - the last-pause marker for the smart rewind (wayfinder #25);
 *  - the durable playback-event log (spec-16, wayfinder #53): append, failure
 *    ledger, undo candidate, bucket compaction;
 *  - chapter durations discovered during playback;
 *  - book-stats back-fill (real chapter count / total duration);
 *  - the per-book preferred speed (wayfinder #26);
 *  - bookmarks (anchored to the Edition, ADR-0007);
 *  - listening-time stats.
 *
 * ADR-0007: progress/bookmarks are keyed by Edition; callers keep passing the
 * book id (one Edition per book today) and the store resolves it — the
 * sourceKey concept is gone from listening identity. New playback-event rows
 * write sourceKey = "" (the column is history).
 */
class ListeningStateStore(private val dao: AudiobookDao) {

    // --- Progress (keyed by Edition, ADR-0007) -----------------------------

    /** The domain Edition id of a book: the stored rendition, or the
     *  deterministic fallback the import path would have created. */
    private suspend fun editionIdOf(bookId: String): String? =
        dao.getEditionForWork(bookId)?.id
            ?: dao.getAudiobookById(bookId)?.let {
                // ADR-0010: the deterministic fallback carries the rendition
                // narrator, so two narrations never share a progress row.
                EditionId.forBook(it.mergeKey ?: "", bookId, it.narrator)
            }

    fun observeProgress(bookId: String): Flow<PlaybackProgressEntity?> = dao.getPlaybackProgress(bookId)

    suspend fun getProgressSync(bookId: String): PlaybackProgressEntity? = dao.getPlaybackProgressSync(bookId)

    /**
     * Persists the playback position of one Edition (ADR-0007). The source is
     * no longer part of the listening identity — progress belongs to the
     * rendition, so a source switch mid-book keeps the position.
     */
    suspend fun updateProgress(bookId: String, chapterIndex: Int, positionSeconds: Long) {
        val editionId = editionIdOf(bookId) ?: return
        val progress = PlaybackProgressEntity(
            editionId = editionId,
            bookId = bookId,
            currentChapterIndex = chapterIndex,
            currentPositionSeconds = positionSeconds,
            lastListenedAt = System.currentTimeMillis()
        )
        dao.savePlaybackProgress(progress)
    }

    /** Last-pause marker for the smart rewind (wayfinder #25); null clears it. */
    suspend fun updatePausedAt(bookId: String, pausedAt: Long?) =
        dao.updatePausedAt(bookId, pausedAt)

    // --- Playback event log (spec-16, wayfinder #53) ------------------------
    // The state row above stays the authoritative "where am I now"; the log is
    // history for undo, future sync and listening intelligence. Every write
    // funnels through recordPlaybackEvent, which also compacts the bucket.

    /**
     * Appends one discrete transition to the log and runs the bucket's
     * compaction. [timestampMs] is injectable so tests stay free of the wall
     * clock. The player calls this from its transition points (T2); nothing
     * else here changes behaviour yet.
     */
    suspend fun recordPlaybackEvent(
        bookId: String,
        kind: String,
        chapterIndex: Int,
        positionSeconds: Long,
        sourceKey: String = "",
        fromPositionSeconds: Long? = null,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        // ADR-0007: the event log is HISTORY — new rows write sourceKey = ""
        // (progress re-keyed to the Edition; the source is not part of the
        // listening identity anymore).
        dao.insertPlaybackEvent(
            PlaybackEventEntity(
                bookId = bookId,
                sourceKey = "",
                kind = kind,
                chapterIndex = chapterIndex,
                positionSeconds = positionSeconds,
                fromPositionSeconds = fromPositionSeconds,
                timestamp = timestampMs,
                deviceId = ""
            )
        )
        compactPlaybackEvents(bookId, sourceKey = "", nowMs = timestampMs)
    }

    /**
     * The undo candidate for (book, source): the latest SEEK / SOURCE_SWITCH
     * event whose jump met the threshold (pure policy). Null when there is
     * nothing undoable — the caller shows no «Повернутися» offer.
     */
    suspend fun lastUndoCandidate(bookId: String, sourceKey: String = ""): PlaybackEventEntity? {
        val latest = dao.getLatestUndoCandidate(bookId, sourceKey) ?: return null
        return if (PlaybackEventPolicy.isUndoCandidate(latest)) latest else null
    }

    /**
     * Prunes one (book, source) bucket to the policy: newest [cap] events
     * kept, stale undo candidates dropped. The state row is never touched.
     */
    suspend fun compactPlaybackEvents(bookId: String, sourceKey: String = "", nowMs: Long = System.currentTimeMillis()) {
        val events = dao.getPlaybackEventsForBookSource(bookId, sourceKey)
        val prune = PlaybackEventPolicy.pruneIds(events, nowMs = nowMs)
        if (prune.isNotEmpty()) dao.deletePlaybackEvents(prune)
    }

    /**
     * Appends one row to the durable playback-failure ledger (wayfinder #52).
     * Called from the player's failure path; write failures here are logged,
     * never thrown back into playback.
     */
    suspend fun recordPlaybackFailure(
        bookId: String,
        chapterIndex: Int,
        errorCodeName: String,
        streamUrl: String,
        audioEngineMode: String
    ) = withContext(Dispatchers.IO) {
        dao.insertPlaybackFailure(
            PlaybackFailureEntity(
                timestamp = System.currentTimeMillis(),
                bookId = bookId,
                chapterIndex = chapterIndex,
                errorCodeName = errorCodeName,
                streamUrl = streamUrl,
                audioEngineMode = audioEngineMode,
                // wayfinder #61 Q1: the coarse diagnosability bucket, derived
                // from the error code by a pure function (stage-2 S1).
                category = FailureCategory.fromErrorCodeName(errorCodeName)
            )
        )
    }

    // --- Per-book playback facts -------------------------------------------

    /**
     * Per-book preferred playback speed (wayfinder #26); null clears the
     * preference. ADR-0009: the preference lives on the Listening State row
     * (`playback_progress`, keyed by the Edition) — not on the book row.
     */
    suspend fun setPreferredSpeed(bookId: String, speed: Float?) = dao.updatePreferredSpeed(bookId, speed)

    /** Real chapter duration discovered during playback (replaces unknown 0). */
    suspend fun updateChapterDuration(chapterId: String, durationSeconds: Long) =
        dao.updateChapterDuration(chapterId, durationSeconds)

    /** Real chapter count / total duration once the book's chapters are known. */
    suspend fun updateBookStats(bookId: String, totalChapters: Int, totalDurationSeconds: Long) =
        dao.updateBookStats(bookId, totalChapters, totalDurationSeconds)

    // --- Bookmarks (anchored to the Edition, ADR-0007) ---------------------

    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>> = dao.getBookmarksForBook(bookId)

    /** Inserts a bookmark, filling the Edition anchor when the caller didn't. */
    suspend fun addBookmark(bookmark: BookmarkEntity) {
        val editionId = bookmark.editionId ?: editionIdOf(bookmark.bookId)
        dao.insertBookmark(if (editionId != null) bookmark.copy(editionId = editionId) else bookmark)
    }

    suspend fun deleteBookmark(bookmarkId: Long) = dao.deleteBookmark(bookmarkId)

    // --- Listening-time stats ----------------------------------------------

    fun getAllListeningStats(): Flow<List<ListeningStatEntity>> = dao.getAllListeningStats()

    suspend fun recordListeningTime(seconds: Long) {
        if (seconds <= 0) return
        val dateIso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        withContext(Dispatchers.IO) {
            val current = dao.getListeningStatForDate(dateIso)
            val updatedSeconds = (current?.listenedSeconds ?: 0L) + seconds
            dao.saveListeningStat(ListeningStatEntity(dateIso, updatedSeconds))
        }
    }
}
