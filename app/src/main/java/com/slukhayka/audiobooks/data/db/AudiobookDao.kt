package com.slukhayka.audiobooks.data.db

import androidx.paging.PagingSource
import androidx.room.*
import com.slukhayka.audiobooks.data.authors.AuthorSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface AudiobookDao {
    companion object {
        /**
         * ADR-0009 — the read projection of the split book row. Every DAO read
         * of [AudiobookEntity] joins the Work / Library Entry / Listening State
         * rows and fills the @Ignore projections: series + workId + mergeKey
         * from `works`, isFavorite/createdAt/downloadProgress from
         * `library_entries`, preferredSpeed from `playback_progress` (the
         * Listening State row). The scalar subquery for preferredSpeed never
         * multiplies rows (one progress row per Edition today).
         */
        private const val BOOK_SELECT = """
            SELECT a.*,
                   w.seriesTitle AS seriesTitle, w.seriesUrl AS seriesUrl, w.seriesIndex AS seriesIndex,
                   w.id AS workId, w.mergeKey AS mergeKey,
                   le.isFavorite AS isFavorite, le.createdAt AS createdAt, le.downloadProgress AS downloadProgress,
                   COALESCE(le.downloadState, 'IDLE') AS downloadState,
                   (SELECT pp.preferredSpeed FROM playback_progress pp
                      JOIN editions e ON e.id = pp.editionId
                     WHERE e.workId = a.id LIMIT 1) AS preferredSpeed
            FROM audiobooks a
            LEFT JOIN library_entries le ON le.id = a.id
            LEFT JOIN works w ON w.id = le.workId
        """
    }

    /**
     * ADR-0009: every read returns the JOINed [BookRow] — Room does not
     * hydrate `@Ignore` projections, so the modules map the row back to
     * [AudiobookEntity] at their read boundary.
     */
    @Query(BOOK_SELECT + " ORDER BY a.title ASC")
    fun getAllAudiobooks(): Flow<List<BookRow>>

    /** One-shot read of every book — the #54 merge-suggestion pool. */
    @Query(BOOK_SELECT)
    suspend fun getAllAudiobooksOnce(): List<BookRow>

    @Query(BOOK_SELECT + " WHERE a.isDownloaded = 1 ORDER BY a.title ASC")
    fun getDownloadedAudiobooks(): Flow<List<BookRow>>

    @Query(BOOK_SELECT + " WHERE a.id = :id")
    suspend fun getAudiobookById(id: String): BookRow?

    @Query(BOOK_SELECT + " WHERE a.id = :id")
    fun observeAudiobookById(id: String): Flow<BookRow?>

    // Spec-10 T2: Work-level dedup — one library row per normalized merge key
    // (the key lives on the linked `works` row since ADR-0009).
    @Query(BOOK_SELECT + " WHERE w.mergeKey = :mergeKey AND w.mergeKey != '' LIMIT 1")
    suspend fun findByMergeKey(mergeKey: String): BookRow?

    // ADR-0011: the library card of a given RENDITION — the Edition id is the
    // rendition identity (`hash(mergeKey|narrator|language)`), so this is the
    // narration-aware dedup lookup: the same narration of a Work resolves to
    // its card, a different narration resolves to nothing (a new card).
    @Query(BOOK_SELECT + " JOIN editions e ON e.workId = a.id WHERE e.id = :editionId LIMIT 1")
    suspend fun findBookByEditionId(editionId: String): BookRow?

    // --- Sources (spec-10 T2; re-parented to editionId in ADR-0007) ---

    @Query("SELECT * FROM sources WHERE bookId = :bookId ORDER BY addedAt ASC")
    fun getSourcesForBook(bookId: String): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE bookId = :bookId ORDER BY addedAt ASC")
    suspend fun getSourcesForBookSync(bookId: String): List<SourceEntity>

    /** Every source that can play one Edition (ADR-0007). */
    @Query("SELECT * FROM sources WHERE editionId = :editionId ORDER BY addedAt ASC")
    suspend fun getSourcesForEditionSync(editionId: String): List<SourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSources(sources: List<SourceEntity>)

    /** The book a source belongs to — the dedupe lookup of an existing track's owner. */
    @Query("SELECT bookId FROM sources WHERE id = :sourceId LIMIT 1")
    suspend fun getBookIdBySourceId(sourceId: String): String?

    @Query("DELETE FROM sources WHERE bookId = :bookId")
    suspend fun deleteSourcesForBook(bookId: String)

    // Wayfinder #42: every local book imported from one SAF tree — the re-scan
    // diff groups the live tree against these books' chapters.
    @Query(BOOK_SELECT + " WHERE a.sourceTreeUri = :treeUri")
    suspend fun getAudiobooksBySourceTree(treeUri: String): List<BookRow>

    // Wayfinder #42: the distinct trees ever imported — the re-scan-all entry.
    @Query("SELECT DISTINCT sourceTreeUri FROM audiobooks WHERE sourceTreeUri IS NOT NULL AND sourceTreeUri != ''")
    suspend fun getImportedSourceTrees(): List<String>

    @Query("UPDATE sources SET lastScanFingerprint = :fingerprint WHERE id = :sourceId")
    suspend fun updateSourceFingerprint(sourceId: String, fingerprint: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudiobooks(books: List<AudiobookEntity>)

    // --- Library Entry writes (ADR-0009): download state splits across the
    // two rows — isDownloaded stays on audiobooks (metadata), downloadProgress
    // belongs to the Library Entry row.

    @Query("UPDATE audiobooks SET isDownloaded = :isDownloaded WHERE id = :bookId")
    suspend fun updateBookDownloadState(bookId: String, isDownloaded: Boolean)

    /** Upsert (never loses the row): the entry always exists for a stored book. */
    @Query(
        "INSERT INTO library_entries (id, workId, isFavorite, createdAt, downloadProgress) " +
            "VALUES (:bookId, :bookId, 0, 0, :progress) " +
            "ON CONFLICT(id) DO UPDATE SET downloadProgress = excluded.downloadProgress"
    )
    suspend fun upsertEntryDownloadProgress(bookId: String, progress: Float)

    @Query("UPDATE library_entries SET downloadState = :state WHERE id = :bookId")
    suspend fun updateDownloadStateValue(bookId: String, state: String)

    @Transaction
    suspend fun updateDownloadState(bookId: String, isDownloaded: Boolean, progress: Float) {
        updateBookDownloadState(bookId, isDownloaded)
        upsertEntryDownloadProgress(bookId, progress)
    }

    @Transaction
    suspend fun updateDownloadStateWithState(
        bookId: String,
        isDownloaded: Boolean,
        progress: Float,
        state: String
    ) {
        updateBookDownloadState(bookId, isDownloaded)
        upsertEntryDownloadProgress(bookId, progress)
        updateDownloadStateValue(bookId, state)
    }

    /**
     * Series belongs to the Work (ADR-0009): resolved through the book's
     * Library Entry link so callers keep passing the book id.
     */
    @Query(
        "UPDATE works SET seriesTitle = :seriesTitle, seriesUrl = :seriesUrl, seriesIndex = :seriesIndex " +
            "WHERE id = (SELECT le.workId FROM library_entries le WHERE le.id = :bookId)"
    )
    suspend fun updateSeriesFields(bookId: String, seriesTitle: String?, seriesUrl: String?, seriesIndex: Int?)

    @Query("SELECT id FROM editions WHERE workId = :bookId LIMIT 1")
    suspend fun getEditionIdForBook(bookId: String): String?

    @Query("UPDATE playback_progress SET preferredSpeed = :speed WHERE editionId = :editionId")
    suspend fun setProgressPreferredSpeed(editionId: String, speed: Float?)

    /**
     * Per-book preferred speed lives on the Listening State row (ADR-0009):
     * the Edition's progress row carries it. A no-op when the book has no
     * progress row yet (the preference simply applies for the current session;
     * the next save during playback persists it).
     */
    @Transaction
    suspend fun updatePreferredSpeed(bookId: String, speed: Float?) {
        val editionId = getEditionIdForBook(bookId) ?: return
        setProgressPreferredSpeed(editionId, speed)
    }

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    fun getChaptersForBook(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    suspend fun getChaptersListForBook(bookId: String): List<ChapterEntity>

    /** The logical chapter list of one Edition (ADR-0007). */
    @Query("SELECT * FROM chapters WHERE editionId = :editionId ORDER BY chapterIndex ASC")
    suspend fun getChaptersListForEdition(editionId: String): List<ChapterEntity>

    // Wayfinder #39: every chapter of every book, so the library can compute
    // cumulative positions and real total durations for its cards and sorts.
    @Query("SELECT * FROM chapters")
    fun getAllChapters(): Flow<List<ChapterEntity>>

    /**
     * Spec-24 T8 (#169) — the candidate pool of the chapter-duration probe:
     * every book with at least one unknown-duration chapter (0 is the
     * unknown placeholder written at import). The pass filters the stream
     * gate (non-blank sourceUrl) on top.
     */
    @Query("SELECT DISTINCT bookId FROM chapters WHERE durationSeconds <= 0")
    suspend fun getBookIdsWithUnknownChapterDurations(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    /** Real chapter duration discovered during playback (replaces placeholder 0). */
    @Query("UPDATE chapters SET durationSeconds = :durationSeconds WHERE id = :chapterId")
    suspend fun updateChapterDuration(chapterId: String, durationSeconds: Long)

    /** Real chapter/duration counts once the book's chapters are known. */
    @Query("UPDATE audiobooks SET totalChapters = :totalChapters, totalDurationSeconds = :totalDurationSeconds WHERE id = :bookId")
    suspend fun updateBookStats(bookId: String, totalChapters: Int, totalDurationSeconds: Long)

    /**
     * Back-fills the real page metadata (author, narrator, genre, rating)
     * onto a catalogue book once its page has been fetched, replacing the
     * seeded placeholders. Nulls keep the stored value. Series is NOT here
     * anymore — it belongs to the Work ([updateSeriesFields]).
     */
    @Query(
        "UPDATE audiobooks SET " +
            "author = COALESCE(:author, author), " +
            "narrator = COALESCE(:narrator, narrator), " +
            "genre = COALESCE(:genre, genre), " +
            "rating = COALESCE(:rating, rating) " +
            "WHERE id = :bookId"
    )
    suspend fun updateBookMetadata(
        bookId: String,
        author: String?,
        narrator: String?,
        genre: String?,
        rating: Float?
    )

    // --- Source tracks (ADR-0007): the physical playback data of a Source ---

    @Query("SELECT * FROM source_tracks WHERE sourceId = :sourceId ORDER BY trackIndex ASC")
    fun getTracksForSource(sourceId: String): Flow<List<SourceTrackEntity>>

    @Query("SELECT * FROM source_tracks WHERE sourceId = :sourceId ORDER BY trackIndex ASC")
    suspend fun getTracksForSourceSync(sourceId: String): List<SourceTrackEntity>

    /** The tracks of every source of a book — for download state reads. */
    @Query(
        "SELECT st.* FROM source_tracks st JOIN sources s ON s.id = st.sourceId " +
            "WHERE s.bookId = :bookId ORDER BY st.trackIndex ASC"
    )
    suspend fun getTracksForBookSync(bookId: String): List<SourceTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<SourceTrackEntity>)

    /**
     * Download state lives on the TRACK rows (ADR-0007): chapter rows never
     * change on download.
     */
    @Query("UPDATE source_tracks SET isDownloaded = :isDownloaded, localFilePath = :filePath WHERE id = :trackId")
    suspend fun updateTrackDownloadState(trackId: String, isDownloaded: Boolean, filePath: String?)

    /**
     * First track that already holds the given content hash (wayfinder #48):
     * a re-import of the same file is a duplicate and must not consume
     * storage again. NULL when no track has ever imported this file.
     */
    @Query("SELECT * FROM source_tracks WHERE contentHash = :hash LIMIT 1")
    suspend fun getTrackByContentHash(hash: String): SourceTrackEntity?

    /** Every stored content hash — the library-wide re-scan dedupe pool. */
    @Query("SELECT contentHash FROM source_tracks WHERE contentHash IS NOT NULL")
    suspend fun getAllTrackContentHashes(): List<String>

    // Spec-37 T2: content hash of a downloaded track — computed after a
    // successful download with the same SHA-256 infra as local imports.
    @Query("UPDATE source_tracks SET contentHash = :hash WHERE id = :trackId")
    suspend fun updateTrackContentHash(trackId: String, hash: String?)

    // Spec-37 T2: URL-based reuse — a chapter whose stream URL already has a
    // downloaded copy reuses that file without a network request.
    @Query("SELECT * FROM source_tracks WHERE url = :url AND isDownloaded = 1 AND localFilePath IS NOT NULL LIMIT 1")
    suspend fun getDownloadedTrackByUrl(url: String): SourceTrackEntity?

    // Spec-37 T2: file-sharing reference counting — a file is deleted only when
    // no downloaded track still points at it.
    @Query("SELECT * FROM source_tracks WHERE localFilePath = :path AND isDownloaded = 1")
    suspend fun getTracksByFilePath(path: String): List<SourceTrackEntity>

    /**
     * Forget the content hashes of a book's tracks (wayfinder #48 + #50):
     * when an offline copy is removed from disk, its hash must not block a
     * future re-import — the file is gone, so copying it again is legitimate.
     */
    @Query(
        "UPDATE source_tracks SET contentHash = NULL WHERE sourceId IN " +
            "(SELECT id FROM sources WHERE bookId = :bookId)"
    )
    suspend fun clearTrackContentHashesForBook(bookId: String)

    @Query(
        "UPDATE source_tracks SET isDownloaded = 0, localFilePath = NULL WHERE sourceId IN " +
            "(SELECT id FROM sources WHERE bookId = :bookId)"
    )
    suspend fun clearTracksDownloadStateForBook(bookId: String)

    @Query("UPDATE source_tracks SET isDownloaded = 0, localFilePath = NULL")
    suspend fun clearAllTracksDownloadState()

    @Query(
        "DELETE FROM source_tracks WHERE sourceId IN " +
            "(SELECT id FROM sources WHERE bookId = :bookId)"
    )
    suspend fun deleteTracksForBook(bookId: String)

    // --- Domain Editions (ADR-0007) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdition(edition: EditionEntity)

    @Query("SELECT * FROM editions WHERE id = :editionId")
    suspend fun getEditionById(editionId: String): EditionEntity?

    /** The single rendition of a library book (one edition per book today). */
    @Query("SELECT * FROM editions WHERE workId = :bookId LIMIT 1")
    suspend fun getEditionForWork(bookId: String): EditionEntity?

    // --- Spec-24 T1: the one-time stored-title scrub -----------------------
    // The startup pass reads every stored title (audiobooks + works), applies
    // the pure normalizeTitle rule in Kotlin, and rewrites only the rows that
    // change — idempotent by construction (a second run matches nothing).

    @Query("SELECT id, title FROM audiobooks")
    suspend fun getAllBookTitleRows(): List<TitleRow>

    @Query("SELECT id, title FROM works")
    suspend fun getAllWorkTitleRows(): List<TitleRow>

    @Query("UPDATE audiobooks SET title = :title WHERE id = :id")
    suspend fun updateBookTitle(id: String, title: String)

    @Query("UPDATE works SET title = :title WHERE id = :id")
    suspend fun updateWorkTitle(id: String, title: String)

    // --- #264: the stored-description scrub (same pass, same contract) -----

    @Query("SELECT id, description FROM audiobooks")
    suspend fun getAllBookDescriptionRows(): List<DescriptionRow>

    @Query("UPDATE audiobooks SET description = :description WHERE id = :id")
    suspend fun updateBookDescription(id: String, description: String)

    // --- Persisted catalogue: Works + Sources (spec-23 T1, ADR-0007) -------

    /** One Work per normalized merge key — the merge-on-write lookup. */
    @Query("SELECT * FROM works WHERE mergeKey = :mergeKey AND mergeKey != '' LIMIT 1")
    suspend fun findWorkByMergeKey(mergeKey: String): WorkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWork(work: WorkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkSource(workSource: WorkSourceEntity)

    /**
     * #388 — safe version of upsertWorkSource that ensures the work row
     * exists before inserting the source. The FK constraint on work_sources
     * requires the work to be present; without this guard, a missing work
     * row causes SQLiteConstraintException and crashes the app.
     */
    @Transaction
    suspend fun upsertWorkSourceSafe(workSource: WorkSourceEntity) {
        // Ensure the work row exists — insert a minimal placeholder if needed
        val existingWork = findWorkById(workSource.workId)
        if (existingWork == null) {
            upsertWork(
                WorkEntity(
                    id = workSource.workId,
                    title = "",
                    author = "",
                    mergeKey = ""
                )
            )
        }
        upsertWorkSource(workSource)
    }

    @Query("SELECT * FROM works WHERE id = :id")
    suspend fun findWorkById(id: String): WorkEntity?

    // --- Spec-25: series universes (the lazy resolution cache) -------------
    // The universe rows, the series rows (with their universe anchor + order)
    // and the book→series memberships are the cache of the lazy resolution;
    // upserts are idempotent, so re-resolution is a no-op by construction.

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUniverse(universe: UniverseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeries(series: SeriesEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeriesMember(member: SeriesMemberEntity)

    @Query("SELECT * FROM universes WHERE id = :id")
    suspend fun getUniverseById(id: String): UniverseEntity?

    /** Every cached series row — the context read matches across providers. */
    @Query("SELECT * FROM series")
    suspend fun getAllSeries(): List<SeriesEntity>

    /** The ordered series of one universe — precedes/follows come from neighbors. */
    @Query("SELECT * FROM series WHERE universeId = :universeId ORDER BY positionInUniverse ASC")
    suspend fun getSeriesInUniverse(universeId: String): List<SeriesEntity>

    @Query("SELECT * FROM series_members WHERE workId = :workId")
    suspend fun getSeriesMembersForWork(workId: String): List<SeriesMemberEntity>

    /** Drops every membership of one work — a re-resolution that moves the
     *  book to a different series replaces the old membership (spec-26 T9: a
     *  corrected universe must not leave the stale one behind). */
    @Query("DELETE FROM series_members WHERE workId = :workId")
    suspend fun deleteSeriesMembersForWork(workId: String)

    /**
     * Spec-26 T7 — all book→series memberships (the background refresh pass
     * enumerates the stale ones over the tier rule and re-resolves them by
     * priority).
     */
    @Query("SELECT * FROM series_members")
    suspend fun getAllSeriesMembers(): List<SeriesMemberEntity>

    /** The work row by id — the background refresh pass re-resolves a stale
     *  membership straight from the work (title/author/series) without a
     *  library book. */
    @Query("SELECT * FROM works WHERE id = :workId LIMIT 1")
    suspend fun getWorkById(workId: String): WorkEntity?

    /** Every source carrying a Work, for the «Джерела» section (spec-23 T5). */
    @Query("SELECT * FROM work_sources WHERE workId = :workId ORDER BY addedAt ASC")
    fun observeWorkSourcesForWork(workId: String): Flow<List<WorkSourceEntity>>

    @Query("SELECT * FROM work_sources WHERE workId = :workId ORDER BY addedAt ASC")
    suspend fun getWorkSourcesForWorkSync(workId: String): List<WorkSourceEntity>

    @Query("SELECT * FROM works ORDER BY addedAt DESC")
    fun observeWorks(): Flow<List<WorkEntity>>

    @Query("SELECT COUNT(*) FROM works")
    suspend fun countWorks(): Int

    @Query("SELECT COUNT(*) FROM work_sources")
    suspend fun countWorkSources(): Int

    // --- Endless merged feed (spec-23 T4) --------------------------------

    /**
     * The endless feed's paging source: one row per Work with its source
     * count, newest first. Facet dimensions compose with AND and selected
     * values within one dimension compose with OR. Every predicate uses an
     * indexed projection relation; raw assertion text is never searched.
     */
    @Query(
        """
        SELECT w.id AS workId, w.mergeKey, w.title, w.author, w.seriesTitle, w.seriesIndex,
               w.coverImageUrl, w.addedAt,
               (SELECT COUNT(*) FROM work_sources ws WHERE ws.workId = w.id) AS sourceCount,
               (SELECT gf.displayName FROM work_genres wg JOIN genre_facets gf ON gf.id=wg.genreId WHERE wg.workId=w.id ORDER BY gf.displayName ASC LIMIT 1) AS genre,
               (SELECT e.totalDurationSeconds FROM editions e JOIN library_entries le2 ON le2.id=e.workId WHERE le2.workId=w.id LIMIT 1) AS durationSeconds
        FROM works w
        WHERE (:genreActive = 0 OR EXISTS (SELECT 1 FROM work_genres wg WHERE wg.workId=w.id AND wg.genreId IN (:genreIds)))
          AND (:durationActive = 0 OR EXISTS (SELECT 1 FROM edition_facets ef WHERE ef.workId=w.id AND ef.durationBucketId IN (:durationBucketIds)))
          AND (:authorActive = 0 OR EXISTS (SELECT 1 FROM work_facets wf WHERE wf.workId=w.id AND wf.canonicalAuthorId IN (:authorIds)))
        ORDER BY w.addedAt DESC, w.id ASC
        """
    )
    fun pagedWorksFeedRecent(
        genreIds: List<String>, genreActive: Int,
        durationBucketIds: List<String>, durationActive: Int,
        authorIds: List<String>, authorActive: Int
    ): PagingSource<Int, WorkFeedRow>

    /** Same feed, sorted by title (stable tiebreak: addedAt DESC). */
    @Query(
        """
        SELECT w.id AS workId, w.mergeKey, w.title, w.author, w.seriesTitle, w.seriesIndex,
               w.coverImageUrl, w.addedAt,
               (SELECT COUNT(*) FROM work_sources ws WHERE ws.workId = w.id) AS sourceCount,
               (SELECT gf.displayName FROM work_genres wg JOIN genre_facets gf ON gf.id=wg.genreId WHERE wg.workId=w.id ORDER BY gf.displayName ASC LIMIT 1) AS genre,
               (SELECT e.totalDurationSeconds FROM editions e JOIN library_entries le2 ON le2.id=e.workId WHERE le2.workId=w.id LIMIT 1) AS durationSeconds
        FROM works w
        WHERE (:genreActive = 0 OR EXISTS (SELECT 1 FROM work_genres wg WHERE wg.workId=w.id AND wg.genreId IN (:genreIds)))
          AND (:durationActive = 0 OR EXISTS (SELECT 1 FROM edition_facets ef WHERE ef.workId=w.id AND ef.durationBucketId IN (:durationBucketIds)))
          AND (:authorActive = 0 OR EXISTS (SELECT 1 FROM work_facets wf WHERE wf.workId=w.id AND wf.canonicalAuthorId IN (:authorIds)))
        ORDER BY w.title COLLATE NOCASE ASC, w.addedAt DESC, w.id ASC
        """
    )
    fun pagedWorksFeedByTitle(
        genreIds: List<String>, genreActive: Int,
        durationBucketIds: List<String>, durationActive: Int,
        authorIds: List<String>, authorActive: Int
    ): PagingSource<Int, WorkFeedRow>

    // Bookmarks (ADR-0007: anchored to the Edition)
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY timestampSeconds ASC")
    fun getBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE editionId = :editionId ORDER BY timestampSeconds ASC")
    fun getBookmarksForEdition(editionId: String): Flow<List<BookmarkEntity>>

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

    // --- Spec-27 (#184) BUG-002: the one-time duplicate-Work merge ---------
    // The DuplicateWorkMerger collapses library rows that share a hardened
    // Work identity; these two deletes finish the loser row after its
    // progress/bookmarks/sources/entries have been re-pointed. Deleting the
    // works row also cascades its original work_sources rows (FK CASCADE).

    @Query("DELETE FROM works WHERE id = :workId")
    suspend fun deleteWork(workId: String)

    @Query("DELETE FROM editions WHERE workId = :workId")
    suspend fun deleteEditionsForWork(workId: String)

    // Playback Progress (ADR-0007: keyed by Edition; the bookId variants are
    // book-scoped conveniences over the kept expand column).
    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId ORDER BY lastListenedAt DESC LIMIT 1")
    fun getPlaybackProgress(bookId: String): Flow<PlaybackProgressEntity?>

    @Query("SELECT * FROM playback_progress WHERE editionId = :editionId")
    fun getPlaybackProgressByEdition(editionId: String): Flow<PlaybackProgressEntity?>

    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId ORDER BY lastListenedAt DESC LIMIT 1")
    suspend fun getPlaybackProgressSync(bookId: String): PlaybackProgressEntity?

    @Query("SELECT * FROM playback_progress WHERE editionId = :editionId")
    suspend fun getPlaybackProgressSyncByEdition(editionId: String): PlaybackProgressEntity?

    @Query("UPDATE playback_progress SET lastPausedAtEpochMs = :pausedAt WHERE bookId = :bookId")
    suspend fun updatePausedAt(bookId: String, pausedAt: Long?)

    @Query("SELECT * FROM playback_progress ORDER BY lastListenedAt DESC")
    fun getAllPlaybackProgress(): Flow<List<PlaybackProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlaybackProgress(progress: PlaybackProgressEntity)

    @Query(BOOK_SELECT + " WHERE le.isFavorite = 1 ORDER BY a.title ASC")
    fun getFavoriteAudiobooks(): Flow<List<BookRow>>

    @Query("UPDATE library_entries SET isFavorite = :isFavorite WHERE id = :bookId")
    suspend fun setFavorite(bookId: String, isFavorite: Boolean)

    // --- Library Entries (ADR-0009) ----------------------------------------

    /**
     * True upsert: on a fresh row everything lands; on an existing row only
     * the link and the download progress update — the user's isFavorite and
     * the original createdAt never reset on a re-sync.
     */
    @Query(
        "INSERT INTO library_entries (id, workId, isFavorite, createdAt, downloadProgress, downloadState) " +
            "VALUES (:id, :workId, :isFavorite, :createdAt, :downloadProgress, :downloadState) " +
            "ON CONFLICT(id) DO UPDATE SET workId = excluded.workId, downloadProgress = excluded.downloadProgress, downloadState = excluded.downloadState"
    )
    suspend fun upsertLibraryEntry(
        id: String,
        workId: String,
        isFavorite: Boolean,
        createdAt: Long,
        downloadProgress: Float,
        downloadState: String = com.slukhayka.audiobooks.data.db.DownloadState.IDLE
    )

    @Query("DELETE FROM library_entries WHERE id = :bookId")
    suspend fun deleteLibraryEntry(bookId: String)

    @Query("SELECT COUNT(*) FROM library_entries")
    suspend fun countLibraryEntries(): Int

    @Query("UPDATE audiobooks SET coverImageUrl = :coverUrl WHERE id = :bookId")
    suspend fun updateCoverImageUrl(bookId: String, coverUrl: String)

    /**
     * Spec-30 T3 (#218) — the library rows the cover resolver may fill: a
     * row whose cover is blank (never a listener's known cover) AND whose
     * Work identity is present (the key the shared canonical base is keyed
     * by). Bounded by [limit] — the resolver processes the visible library,
     * not the whole base. A row with a known cover is never returned, so the
     * write path can never clobber it.
     */
    @Query(
        BOOK_SELECT + """
        WHERE (a.coverImageUrl IS NULL OR a.coverImageUrl = '')
          AND w.mergeKey IS NOT NULL AND w.mergeKey != ''
        ORDER BY a.title ASC
        LIMIT :limit
        """
    )
    suspend fun getLibraryRowsMissingCovers(limit: Int): List<BookRow>

    @Query("UPDATE library_entries SET downloadProgress = 0 WHERE id = :bookId")
    suspend fun resetEntryDownloadProgress(bookId: String)

    @Query("UPDATE library_entries SET downloadProgress = 0")
    suspend fun resetAllEntryDownloadProgress()

    @Transaction
    suspend fun markBookNotDownloaded(bookId: String) {
        updateBookDownloadState(bookId, false)
        resetEntryDownloadProgress(bookId)
    }

    @Query("UPDATE audiobooks SET isDownloaded = 0")
    suspend fun clearAllBookDownloadState()

    @Transaction
    suspend fun markAllNotDownloaded() {
        clearAllBookDownloadState()
        resetAllEntryDownloadProgress()
    }

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
    // ADR-0007: the log is HISTORY — new rows write sourceKey = "". The
    // (book, source) queries keep working over the kept column for old rows.

    @Insert
    suspend fun insertPlaybackEvent(event: PlaybackEventEntity)

    /**
     * The latest possible undo candidate for a book: the newest
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

    /** Whether one book is tombstoned (ADR-0005 — the guard lives here). */
    @Query("SELECT EXISTS(SELECT 1 FROM tombstones WHERE bookId = :bookId)")
    suspend fun isBookTombstoned(bookId: String): Boolean

    /**
     * ADR-0005 — the catalog write guard: inserts the row ONLY when its Work
     * is not tombstoned (a single insert-unless-tombstoned statement; the
     * caller confirms what landed via [getAudiobookById]). The tombstone check
     * lives here, in the persistence layer — no catalog fetch or upsert site
     * consults a tombstone set anymore. (This is the NEW-book insert path; an
     * existing row is guarded via [isBookTombstoned] by the caller.)
     */
    /**
     * ADR-0009: the audiobooks row is metadata only — the fused columns left
     * the row in the v15 contract step (series/workId/mergeKey → `works`,
     * isFavorite/createdAt/downloadProgress → `library_entries`, speed →
     * `playback_progress`). The caller ([LibraryImport.upsertCatalogBook])
     * writes those owning rows alongside this guarded insert.
     */
    @Query(
        """
        INSERT INTO audiobooks (
            id, title, author, narrator, description, coverDrawableRes, coverImageUrl,
            genre, sourceUrl, isDownloaded, totalDurationSeconds,
            totalChapters, rating, sourceTreeUri
        )
        SELECT :id, :title, :author, :narrator, :description, :coverDrawableRes, :coverImageUrl,
               :genre, :sourceUrl, :isDownloaded, :totalDurationSeconds,
               :totalChapters, :rating, :sourceTreeUri
        WHERE NOT EXISTS (SELECT 1 FROM tombstones WHERE tombstones.bookId = :id)
        ON CONFLICT(id) DO UPDATE SET id = excluded.id
        """
    )
    suspend fun insertCatalogBookIfNotTombstoned(
        id: String,
        title: String,
        author: String,
        narrator: String,
        description: String,
        coverDrawableRes: Int,
        coverImageUrl: String?,
        genre: String,
        sourceUrl: String,
        isDownloaded: Boolean,
        totalDurationSeconds: Long,
        totalChapters: Int,
        rating: Float,
        sourceTreeUri: String?
    ): Long

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

    /** Drops every correction of one kind pinned to one Work (spec-26 T9 —
     *  the «wrong universe» verdict clears the complaint). */
    @Query("DELETE FROM corrections WHERE mergeKey = :mergeKey AND kind = :kind")
    suspend fun deleteCorrection(mergeKey: String, kind: String)

    // --- Local reviewer mute (spec-40 #281) ---------------------------------
    // The listener's own hidden-author list: reviews of a muted author leave
    // THIS listener's feed; unhiding removes the row. REPLACE keeps re-hiding
    // idempotent — one row per author, the stamp refreshed.

    /** Mutes one reviewer (upsert — a re-hide refreshes [HiddenReviewerEntity.hiddenAt]). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hideAuthor(author: HiddenReviewerEntity)

    /** Un-mutes one reviewer — hiding is reversible (settings, US-15). */
    @Query("DELETE FROM hidden_reviewers WHERE authorName = :authorName")
    suspend fun unhideAuthor(authorName: String)

    /** The muted authors, alphabetical — the settings list and feed filters. */
    @Query("SELECT * FROM hidden_reviewers ORDER BY authorName ASC")
    suspend fun hiddenAuthors(): List<HiddenReviewerEntity>

    // --- Explicit recommendation feedback (#290) --------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecommendationPreference(preference: RecommendationPreferenceEntity)

    @Query("DELETE FROM recommendation_preferences WHERE kind = :kind AND targetKey = :targetKey")
    suspend fun deleteRecommendationPreference(kind: String, targetKey: String)

    @Query("SELECT * FROM recommendation_preferences ORDER BY createdAt DESC")
    fun observeRecommendationPreferences(): Flow<List<RecommendationPreferenceEntity>>

    /** Scoped reset: never touches Library Entry, progress or playback events. */
    @Query("DELETE FROM recommendation_preferences")
    suspend fun clearRecommendationPreferences()

    // --- Local catalogue facets (spec-42 #304, frozen Wave-3 seam) -------

    @Query(
        "INSERT INTO work_facets (workId, canonicalAuthorId, updatedAt) " +
        "VALUES (:workId, :authorId, :updatedAt) " +
        "ON CONFLICT(workId) DO UPDATE SET " +
            "canonicalAuthorId=CASE " +
            "WHEN canonicalAuthorId IS NULL THEN excluded.canonicalAuthorId " +
            "WHEN excluded.canonicalAuthorId IS NOT NULL AND excluded.updatedAt > updatedAt " +
            "THEN excluded.canonicalAuthorId ELSE canonicalAuthorId END, " +
            "updatedAt=MAX(updatedAt, excluded.updatedAt)"
    )
    suspend fun mergeWorkFacet(workId: String, authorId: String?, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWorkFacetSeries(rows: List<WorkFacetSeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGenreFacets(rows: List<GenreFacetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkGenres(rows: List<WorkGenreEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGenreAssertions(rows: List<GenreAssertionEntity>)

    @Query("SELECT documentUpdatedAt FROM genre_assertion_states WHERE workId=:workId AND sourceId=:sourceId")
    suspend fun genreDocumentUpdatedAt(workId: String, sourceId: String): Long?

    @Query("DELETE FROM work_genres WHERE workId=:workId AND sourceId=:sourceId")
    suspend fun deleteWorkGenresForSource(workId: String, sourceId: String)

    @Query("DELETE FROM genre_assertions WHERE workId=:workId AND sourceId=:sourceId")
    suspend fun deleteGenreAssertionsForSource(workId: String, sourceId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGenreAssertionState(row: GenreAssertionStateEntity)

    @Query(
        "INSERT INTO edition_facets (editionId, workId, narratorId, language, durationSeconds, durationBucketId, chapterCount, isAbridged, availabilityAvailable, availabilityObservedAtMillis, availabilityTtlSeconds, updatedAt) " +
            "VALUES (:editionId, :workId, :narratorId, :language, :durationSeconds, :durationBucketId, :chapterCount, :isAbridged, :availabilityAvailable, :availabilityObservedAtMillis, :availabilityTtlSeconds, :updatedAt) " +
            "ON CONFLICT(editionId) DO UPDATE SET workId=excluded.workId, " +
            "narratorId=COALESCE(excluded.narratorId, narratorId), language=COALESCE(excluded.language, language), " +
            "durationSeconds=COALESCE(excluded.durationSeconds, durationSeconds), durationBucketId=COALESCE(excluded.durationBucketId, durationBucketId), " +
            "chapterCount=COALESCE(excluded.chapterCount, chapterCount), isAbridged=COALESCE(excluded.isAbridged, isAbridged), " +
            "availabilityAvailable=CASE WHEN excluded.availabilityObservedAtMillis IS NOT NULL AND (availabilityObservedAtMillis IS NULL OR excluded.availabilityObservedAtMillis > availabilityObservedAtMillis) THEN excluded.availabilityAvailable ELSE availabilityAvailable END, " +
            "availabilityObservedAtMillis=CASE WHEN excluded.availabilityObservedAtMillis IS NOT NULL AND (availabilityObservedAtMillis IS NULL OR excluded.availabilityObservedAtMillis > availabilityObservedAtMillis) THEN excluded.availabilityObservedAtMillis ELSE availabilityObservedAtMillis END, " +
            "availabilityTtlSeconds=CASE WHEN excluded.availabilityObservedAtMillis IS NOT NULL AND (availabilityObservedAtMillis IS NULL OR excluded.availabilityObservedAtMillis > availabilityObservedAtMillis) THEN excluded.availabilityTtlSeconds ELSE availabilityTtlSeconds END, " +
            "updatedAt=MAX(updatedAt, excluded.updatedAt)"
    )
    suspend fun mergeEditionFacet(
        editionId: String,
        workId: String,
        narratorId: String?,
        language: String?,
        durationSeconds: Long?,
        durationBucketId: String?,
        chapterCount: Int?,
        isAbridged: Boolean?,
        availabilityAvailable: Boolean?,
        availabilityObservedAtMillis: Long?,
        availabilityTtlSeconds: Long?,
        updatedAt: Long
    )

    @Query(
        "INSERT INTO author_facets(id, displayName, normalizedName, updatedAt) " +
            "VALUES (:id, :displayName, :normalizedName, :updatedAt) " +
            "ON CONFLICT(id) DO UPDATE SET " +
            "displayName=CASE WHEN excluded.updatedAt > author_facets.updatedAt " +
            "THEN excluded.displayName ELSE author_facets.displayName END, " +
            "normalizedName=CASE WHEN excluded.updatedAt > author_facets.updatedAt " +
            "THEN excluded.normalizedName ELSE author_facets.normalizedName END, " +
            "updatedAt=MAX(author_facets.updatedAt, excluded.updatedAt)"
    )
    suspend fun mergeAuthorFacet(
        id: String,
        displayName: String,
        normalizedName: String,
        updatedAt: Long
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAuthorAliases(rows: List<AuthorAliasEntity>)

    @Transaction
    suspend fun applyAuthorIndexRows(
        authors: List<AuthorFacetEntity>,
        aliases: List<AuthorAliasEntity>,
        works: List<WorkFacetEntity>
    ) {
        authors.forEach { mergeAuthorFacet(it.id, it.displayName, it.normalizedName, it.updatedAt) }
        insertAuthorAliases(aliases)
        works.forEach { mergeWorkFacet(it.workId, it.canonicalAuthorId, it.updatedAt) }
    }

    @Query(
        "SELECT a.id, a.displayName, a.normalizedName, COUNT(DISTINCT wf.workId) AS workCount " +
            "FROM author_facets a JOIN work_facets wf ON wf.canonicalAuthorId=a.id " +
            "GROUP BY a.id, a.displayName, a.normalizedName " +
            "ORDER BY a.normalizedName ASC, a.id ASC"
    )
    fun observeAuthorIndex(): Flow<List<AuthorSummary>>

    @Query(
        "SELECT a.id, a.displayName, a.normalizedName, COUNT(DISTINCT wf.workId) AS workCount " +
            "FROM author_aliases aa INDEXED BY index_author_aliases_normalizedAlias " +
            "JOIN author_facets a ON a.id=aa.authorId " +
            "JOIN work_facets wf ON wf.canonicalAuthorId=a.id " +
            "WHERE aa.normalizedAlias >= :lowerBound AND aa.normalizedAlias < :upperBound " +
            "GROUP BY a.id, a.displayName, a.normalizedName " +
            "ORDER BY a.normalizedName ASC, a.id ASC LIMIT :limit"
    )
    suspend fun searchAuthors(lowerBound: String, upperBound: String, limit: Int): List<AuthorSummary>

    @Query(
        "SELECT w.* FROM works w JOIN work_facets wf ON wf.workId=w.id " +
            "WHERE wf.canonicalAuthorId=:authorId ORDER BY w.title COLLATE NOCASE ASC, w.id ASC"
    )
    suspend fun worksForAuthor(authorId: String): List<WorkEntity>

    @Query(
        "SELECT a.id, a.displayName, a.normalizedName, COUNT(DISTINCT allWf.workId) AS workCount " +
            "FROM work_facets selected " +
            "JOIN author_facets a ON a.id=selected.canonicalAuthorId " +
            "JOIN work_facets allWf ON allWf.canonicalAuthorId=a.id " +
            "WHERE selected.workId=:workId GROUP BY a.id, a.displayName, a.normalizedName LIMIT 1"
    )
    suspend fun authorForWork(workId: String): AuthorSummary?

    @Query(
        "SELECT w.* FROM works w LEFT JOIN work_facets wf ON wf.workId=w.id " +
            "WHERE wf.canonicalAuthorId IS NULL ORDER BY w.id ASC LIMIT :limit"
    )
    suspend fun worksMissingCanonicalAuthor(limit: Int): List<WorkEntity>

    @Transaction
    suspend fun applyFacetRows(
        works: List<WorkFacetEntity>,
        workSeries: List<WorkFacetSeriesEntity>,
        genreSources: List<GenreSourceFacetRows>,
        editions: List<EditionFacetEntity>,
        authors: List<AuthorFacetEntity>,
        aliases: List<AuthorAliasEntity>
    ) {
        works.forEach { mergeWorkFacet(it.workId, it.canonicalAuthorId, it.updatedAt) }
        insertWorkFacetSeries(workSeries)
        genreSources.forEach { sourceRows ->
            val currentUpdatedAt = genreDocumentUpdatedAt(sourceRows.workId, sourceRows.sourceId)
            if (currentUpdatedAt == null || sourceRows.documentUpdatedAt > currentUpdatedAt) {
                deleteWorkGenresForSource(sourceRows.workId, sourceRows.sourceId)
                deleteGenreAssertionsForSource(sourceRows.workId, sourceRows.sourceId)
                insertGenreFacets(sourceRows.genres)
                insertWorkGenres(sourceRows.memberships)
                insertGenreAssertions(sourceRows.assertions)
                upsertGenreAssertionState(
                    GenreAssertionStateEntity(sourceRows.workId, sourceRows.sourceId, sourceRows.documentUpdatedAt)
                )
            }
        }
        editions.forEach {
            mergeEditionFacet(
                it.editionId, it.workId, it.narratorId, it.language, it.durationSeconds,
                it.durationBucketId, it.chapterCount, it.isAbridged, it.availabilityAvailable,
                it.availabilityObservedAtMillis, it.availabilityTtlSeconds, it.updatedAt
            )
        }
        authors.forEach { mergeAuthorFacet(it.id, it.displayName, it.normalizedName, it.updatedAt) }
        insertAuthorAliases(aliases)
    }

    @Query(
        "SELECT g.id AS id, g.displayName AS label, COUNT(DISTINCT wg.workId) AS workCount " +
            "FROM genre_facets g JOIN work_genres wg ON wg.genreId=g.id " +
            "GROUP BY g.id, g.displayName ORDER BY g.displayName COLLATE NOCASE ASC, g.id ASC"
    )
    fun observeGenreFacetOptions(): Flow<List<GenreFacetOption>>

    @Query("SELECT * FROM genre_assertions WHERE workId=:workId ORDER BY id ASC")
    suspend fun genreAssertionsForWork(workId: String): List<GenreAssertionEntity>
}
