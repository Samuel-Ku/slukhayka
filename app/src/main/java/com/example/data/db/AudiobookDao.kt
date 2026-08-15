package com.example.data.db

import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AudiobookDao {
    @Query("SELECT * FROM audiobooks ORDER BY title ASC")
    fun getAllAudiobooks(): Flow<List<AudiobookEntity>>

    /** One-shot read of every book — the #54 merge-suggestion pool. */
    @Query("SELECT * FROM audiobooks")
    suspend fun getAllAudiobooksOnce(): List<AudiobookEntity>

    @Query("SELECT * FROM audiobooks WHERE isDownloaded = 1 ORDER BY title ASC")
    fun getDownloadedAudiobooks(): Flow<List<AudiobookEntity>>

    @Query("SELECT * FROM audiobooks WHERE id = :id")
    suspend fun getAudiobookById(id: String): AudiobookEntity?

    @Query("SELECT * FROM audiobooks WHERE id = :id")
    fun observeAudiobookById(id: String): Flow<AudiobookEntity?>

    // Spec-10 T2: Work-level dedup — one book per normalized merge key.
    @Query("SELECT * FROM audiobooks WHERE mergeKey = :mergeKey AND mergeKey != '' LIMIT 1")
    suspend fun findByMergeKey(mergeKey: String): AudiobookEntity?

    // --- Sources (spec-10 T2) ---

    @Query("SELECT * FROM sources WHERE bookId = :bookId ORDER BY addedAt ASC")
    fun getSourcesForBook(bookId: String): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE bookId = :bookId ORDER BY addedAt ASC")
    suspend fun getSourcesForBookSync(bookId: String): List<SourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSources(sources: List<SourceEntity>)

    @Query("DELETE FROM sources WHERE bookId = :bookId")
    suspend fun deleteSourcesForBook(bookId: String)

    // Wayfinder #42: every local book imported from one SAF tree — the re-scan
    // diff groups the live tree against these books' chapters.
    @Query("SELECT * FROM audiobooks WHERE sourceTreeUri = :treeUri")
    suspend fun getAudiobooksBySourceTree(treeUri: String): List<AudiobookEntity>

    // Wayfinder #42: the distinct trees ever imported — the re-scan-all entry.
    @Query("SELECT DISTINCT sourceTreeUri FROM audiobooks WHERE sourceTreeUri IS NOT NULL AND sourceTreeUri != ''")
    suspend fun getImportedSourceTrees(): List<String>

    @Query("UPDATE sources SET lastScanFingerprint = :fingerprint WHERE id = :sourceId")
    suspend fun updateSourceFingerprint(sourceId: String, fingerprint: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudiobooks(books: List<AudiobookEntity>)

    @Query("UPDATE audiobooks SET isDownloaded = :isDownloaded, downloadProgress = :progress WHERE id = :bookId")
    suspend fun updateDownloadState(bookId: String, isDownloaded: Boolean, progress: Float)

    @Query("UPDATE audiobooks SET seriesTitle = :seriesTitle, seriesUrl = :seriesUrl, seriesIndex = :seriesIndex WHERE id = :bookId")
    suspend fun updateSeriesFields(bookId: String, seriesTitle: String?, seriesUrl: String?, seriesIndex: Int?)

    @Query("UPDATE audiobooks SET preferredSpeed = :speed WHERE id = :bookId")
    suspend fun updatePreferredSpeed(bookId: String, speed: Float?)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    fun getChaptersForBook(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    suspend fun getChaptersListForBook(bookId: String): List<ChapterEntity>

    // Wayfinder #39: every chapter of every book, so the library can compute
    // cumulative positions and real total durations for its cards and sorts.
    @Query("SELECT * FROM chapters")
    fun getAllChapters(): Flow<List<ChapterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("UPDATE chapters SET isDownloaded = :isDownloaded, localFilePath = :filePath WHERE id = :chapterId")
    suspend fun updateChapterDownloadState(chapterId: String, isDownloaded: Boolean, filePath: String?)

    /**
     * First chapter that already holds the given content hash (wayfinder
     * #48): a re-import of the same file is a duplicate and must not consume
     * storage again. NULL when no chapter has ever imported this file.
     */
    @Query("SELECT * FROM chapters WHERE contentHash = :hash LIMIT 1")
    suspend fun getChapterByContentHash(hash: String): ChapterEntity?

    /**
     * Forget the content hashes of a book's chapters (wayfinder #48 + #50):
     * when an offline copy is removed from disk, its hash must not block a
     * future re-import — the file is gone, so copying it again is legitimate.
     */
    @Query("UPDATE chapters SET contentHash = NULL WHERE bookId = :bookId")
    suspend fun clearChapterContentHashes(bookId: String)

    /** Real chapter duration discovered during playback (replaces placeholder 0). */
    @Query("UPDATE chapters SET durationSeconds = :durationSeconds WHERE id = :chapterId")
    suspend fun updateChapterDuration(chapterId: String, durationSeconds: Long)

    /** Real chapter/duration counts once the book's chapters are known. */
    @Query("UPDATE audiobooks SET totalChapters = :totalChapters, totalDurationSeconds = :totalDurationSeconds WHERE id = :bookId")
    suspend fun updateBookStats(bookId: String, totalChapters: Int, totalDurationSeconds: Long)

    /**
     * Back-fills the real page metadata (author, narrator, genre, rating,
     * series) onto a catalogue book once its page has been fetched, replacing
     * the seeded placeholders. Nulls keep the stored value.
     */
    @Query(
        "UPDATE audiobooks SET " +
            "author = COALESCE(:author, author), " +
            "narrator = COALESCE(:narrator, narrator), " +
            "genre = COALESCE(:genre, genre), " +
            "rating = COALESCE(:rating, rating), " +
            "seriesTitle = COALESCE(:seriesTitle, seriesTitle), " +
            "seriesIndex = COALESCE(:seriesIndex, seriesIndex), " +
            "seriesUrl = COALESCE(:seriesUrl, seriesUrl) " +
            "WHERE id = :bookId"
    )
    suspend fun updateBookMetadata(
        bookId: String,
        author: String?,
        narrator: String?,
        genre: String?,
        rating: Float?,
        seriesTitle: String?,
        seriesIndex: Int?,
        seriesUrl: String?
    )

    // Bookmarks
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY timestampSeconds ASC")
    fun getBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: Long)

    // Cascade book deletion (spec #8 ticket T2): the entities have no FK
    // constraints, so deletion is coordinated explicitly by the repository.
    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteChaptersForBook(bookId: String)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksForBook(bookId: String)

    @Query("DELETE FROM playback_progress WHERE bookId = :bookId")
    suspend fun deletePlaybackProgressForBook(bookId: String)

    @Query("DELETE FROM audiobooks WHERE id = :bookId")
    suspend fun deleteAudiobook(bookId: String)

    // Playback Progress (spec-10 T2: position is keyed per source; the
    // bookId-only variants return the latest row for compatibility).
    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId ORDER BY lastListenedAt DESC LIMIT 1")
    fun getPlaybackProgress(bookId: String): Flow<PlaybackProgressEntity?>

    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId AND sourceKey = :sourceKey")
    fun getPlaybackProgress(bookId: String, sourceKey: String): Flow<PlaybackProgressEntity?>

    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId ORDER BY lastListenedAt DESC LIMIT 1")
    suspend fun getPlaybackProgressSync(bookId: String): PlaybackProgressEntity?

    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId AND sourceKey = :sourceKey")
    suspend fun getPlaybackProgressSync(bookId: String, sourceKey: String): PlaybackProgressEntity?

    @Query("UPDATE playback_progress SET lastPausedAtEpochMs = :pausedAt WHERE bookId = :bookId AND sourceKey = :sourceKey")
    suspend fun updatePausedAt(bookId: String, pausedAt: Long?, sourceKey: String = "")

    @Query("SELECT * FROM playback_progress ORDER BY lastListenedAt DESC")
    fun getAllPlaybackProgress(): Flow<List<PlaybackProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlaybackProgress(progress: PlaybackProgressEntity)
    @Query("SELECT * FROM audiobooks WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavoriteAudiobooks(): Flow<List<AudiobookEntity>>

    @Query("UPDATE audiobooks SET isFavorite = :isFavorite WHERE id = :bookId")
    suspend fun setFavorite(bookId: String, isFavorite: Boolean)

    @Query("UPDATE audiobooks SET coverImageUrl = :coverUrl WHERE id = :bookId")
    suspend fun updateCoverImageUrl(bookId: String, coverUrl: String)

    @Query("UPDATE audiobooks SET isDownloaded = 0, downloadProgress = 0 WHERE id = :bookId")
    suspend fun markBookNotDownloaded(bookId: String)

    @Query("UPDATE chapters SET isDownloaded = 0, localFilePath = NULL WHERE bookId = :bookId")
    suspend fun clearChaptersDownloadState(bookId: String)

    @Query("UPDATE audiobooks SET isDownloaded = 0, downloadProgress = 0")
    suspend fun markAllNotDownloaded()

    @Query("UPDATE chapters SET isDownloaded = 0, localFilePath = NULL")
    suspend fun clearAllChaptersDownloadState()

    // Listening Stats
    @Query("SELECT * FROM listening_stats ORDER BY dateIso DESC")
    fun getAllListeningStats(): Flow<List<ListeningStatEntity>>

    @Query("SELECT * FROM listening_stats WHERE dateIso = :dateIso")
    suspend fun getListeningStatForDate(dateIso: String): ListeningStatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveListeningStat(stat: ListeningStatEntity)

    @Insert
    suspend fun insertPlaybackFailure(failure: PlaybackFailureEntity)

    /** Most recent failures first (wayfinder #52): the durable error ledger. */
    @Query("SELECT * FROM playback_failures ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentPlaybackFailures(limit: Int): List<PlaybackFailureEntity>

    @Query("DELETE FROM playback_failures WHERE id = :id")
    suspend fun deletePlaybackFailure(id: Long)

    // --- Playback events (spec-16): the capped transition log --------------

    @Insert
    suspend fun insertPlaybackEvent(event: PlaybackEventEntity)

    /**
     * The latest possible undo candidate for (book, source): the newest
     * SEEK / SOURCE_SWITCH carrying a from-position. The ≥ 5-min jump policy
     * is applied by the repository (PlaybackEventPolicy.isUndoCandidate) —
     * this query only narrows the kind.
     */
    @Query(
        "SELECT * FROM playback_events WHERE bookId = :bookId AND sourceKey = :sourceKey " +
            "AND kind IN ('SEEK', 'SOURCE_SWITCH') AND fromPositionSeconds IS NOT NULL " +
            "ORDER BY timestamp DESC, id DESC LIMIT 1"
    )
    suspend fun getLatestUndoCandidate(bookId: String, sourceKey: String): PlaybackEventEntity?

    /**
     * All events of one (book, source), newest first — the input of the pure
     * compaction policy (PlaybackEventPolicy.pruneIds).
     */
    @Query(
        "SELECT * FROM playback_events WHERE bookId = :bookId AND sourceKey = :sourceKey " +
            "ORDER BY timestamp DESC, id DESC"
    )
    suspend fun getPlaybackEventsForBookSource(bookId: String, sourceKey: String): List<PlaybackEventEntity>

    @Query("DELETE FROM playback_events WHERE id IN (:ids)")
    suspend fun deletePlaybackEvents(ids: List<Long>)

    /** Cascade: removing a book removes its event trail too. */
    @Query("DELETE FROM playback_events WHERE bookId = :bookId")
    suspend fun deletePlaybackEventsForBook(bookId: String)

    // --- Tombstones (wayfinder #55 Q8, stage-2 S1) -------------------------

    /**
     * Marks a book as deleted. Written on delete/remove-from-library so the
     * 4read catalogue sync can never resurrect it; removed only when the user
     * explicitly imports the book again.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: TombstoneEntity)

    /** Every deleted book id — the durable replacement of the in-memory set. */
    @Query("SELECT bookId FROM tombstones")
    suspend fun getTombstoneBookIds(): List<String>

    /** Clears the tombstone when the user explicitly re-imports the book. */
    @Query("DELETE FROM tombstones WHERE bookId = :bookId")
    suspend fun deleteTombstone(bookId: String)

    // --- Corrections (wayfinder #54 Q9, stage-2 S1) ------------------------

    /**
     * Upserts one synced correction-memory row (MERGE / SPLIT / NEVER_MATCH /
     * FIELD). PK is (mergeKey, kind, value) — re-answering the same question
     * overwrites, never duplicates.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCorrection(correction: CorrectionEntity)

    /** Every correction pinned to one Work, for the #54 review pipeline. */
    @Query("SELECT * FROM corrections WHERE mergeKey = :mergeKey ORDER BY updatedAt DESC")
    suspend fun getCorrectionsForMergeKey(mergeKey: String): List<CorrectionEntity>

    /** The NEVER_MATCH pairs involving one Work, newest first. */
    @Query(
        "SELECT * FROM corrections WHERE kind = 'NEVER_MATCH' AND (mergeKey = :mergeKey OR value = :mergeKey) " +
            "ORDER BY updatedAt DESC"
    )
    suspend fun getNeverMatchPairs(mergeKey: String): List<CorrectionEntity>

    // --- Persisted catalogue: Works + Editions (spec-23 T1) ----------------

    /** One Work per normalized merge key — the merge-on-write lookup. */
    @Query("SELECT * FROM works WHERE mergeKey = :mergeKey AND mergeKey != '' LIMIT 1")
    suspend fun findWorkByMergeKey(mergeKey: String): WorkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWork(work: WorkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEdition(edition: EditionEntity)

    /** Every source carrying a Work, for the «Джерела» section (spec-23 T5). */
    @Query("SELECT * FROM editions WHERE workId = :workId ORDER BY addedAt ASC")
    fun observeEditionsForWork(workId: String): Flow<List<EditionEntity>>

    @Query("SELECT * FROM editions WHERE workId = :workId ORDER BY addedAt ASC")
    suspend fun getEditionsForWorkSync(workId: String): List<EditionEntity>

    @Query("SELECT * FROM works ORDER BY addedAt DESC")
    fun observeWorks(): Flow<List<WorkEntity>>

    @Query("SELECT COUNT(*) FROM works")
    suspend fun countWorks(): Int

    @Query("SELECT COUNT(*) FROM editions")
    suspend fun countEditions(): Int

    // --- Endless merged feed (spec-23 T4) --------------------------------

    /**
     * The endless feed's paging source: one row per Work with its edition
     * count, newest first. Filters compose with paging: `sourceId` keeps only
     * Works carried by that source (EXISTS on editions); `genre` keeps only
     * Works whose library row carries the genre (LEFT JOIN — null until the
     * Work is linked into audiobooks). Dedup is inherited from merge-on-write
     * — the feed never re-implements it at read time.
     */
    @Query(
        """
        SELECT w.id AS workId, w.mergeKey, w.title, w.author, w.narrator, w.seriesTitle, w.seriesIndex,
               w.coverImageUrl, w.addedAt,
               (SELECT COUNT(*) FROM editions e WHERE e.workId = w.id) AS editionCount,
               a.genre AS genre
        FROM works w
        LEFT JOIN audiobooks a ON a.workId = w.id
        WHERE (:sourceId IS NULL OR EXISTS (SELECT 1 FROM editions e WHERE e.workId = w.id AND e.sourceId = :sourceId))
          AND (:genre IS NULL OR a.genre LIKE '%' || :genre || '%')
        ORDER BY w.addedAt DESC, w.id ASC
        """
    )
    fun pagedWorksFeedRecent(sourceId: String?, genre: String?): PagingSource<Int, WorkFeedRow>

    /** Same feed, sorted by title (stable tiebreak: addedAt DESC). */
    @Query(
        """
        SELECT w.id AS workId, w.mergeKey, w.title, w.author, w.narrator, w.seriesTitle, w.seriesIndex,
               w.coverImageUrl, w.addedAt,
               (SELECT COUNT(*) FROM editions e WHERE e.workId = w.id) AS editionCount,
               a.genre AS genre
        FROM works w
        LEFT JOIN audiobooks a ON a.workId = w.id
        WHERE (:sourceId IS NULL OR EXISTS (SELECT 1 FROM editions e WHERE e.workId = w.id AND e.sourceId = :sourceId))
          AND (:genre IS NULL OR a.genre LIKE '%' || :genre || '%')
        ORDER BY w.title COLLATE NOCASE ASC, w.addedAt DESC, w.id ASC
        """
    )
    fun pagedWorksFeedByTitle(sourceId: String?, genre: String?): PagingSource<Int, WorkFeedRow>
}
