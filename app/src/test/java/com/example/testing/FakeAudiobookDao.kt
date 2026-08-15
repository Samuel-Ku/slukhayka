package com.example.testing

import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookEntity
import com.example.data.db.BookmarkEntity
import com.example.data.db.ChapterEntity
import com.example.data.db.CorrectionEntity
import com.example.data.db.EditionEntity
import com.example.data.db.ListeningStatEntity
import com.example.data.db.PlaybackEventEntity
import com.example.data.db.PlaybackFailureEntity
import com.example.data.db.PlaybackProgressEntity
import com.example.data.db.SourceEntity
import com.example.data.db.SourceTrackEntity
import com.example.data.db.TombstoneEntity
import com.example.data.db.WorkEntity
import com.example.data.db.WorkFeedRow
import com.example.data.db.WorkSourceEntity
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [AudiobookDao] for JVM unit tests (GitHub issue #6).
 *
 * Chosen over `Room.inMemoryDatabaseBuilder` for tests that only need a
 * persistence boundary, not SQL semantics: no Robolectric SQLite native
 * library, no schema migration, no shared singleton state between test classes.
 * DAO-level tests that genuinely need to exercise the `@Query` strings belong
 * on in-memory Room instead.
 *
 * All writes are copy-on-write over [MutableStateFlow], so concurrent access
 * from `Dispatchers.IO` (which `AudioPlayerManager` uses to persist progress) is
 * safe without locking.
 *
 * ADR-0007 mirror: the fake keeps source tracks and editions as first-class
 * state alongside books/chapters/sources/progress.
 */
class FakeAudiobookDao(
    books: List<AudiobookEntity> = emptyList(),
    chapters: List<ChapterEntity> = emptyList()
) : AudiobookDao {

    private val booksState = MutableStateFlow(books)
    private val chaptersState = MutableStateFlow(chapters)
    private val tracksState = MutableStateFlow(emptyList<SourceTrackEntity>())
    private val editionsState = MutableStateFlow(emptyList<EditionEntity>())
    private val bookmarksState = MutableStateFlow(emptyList<BookmarkEntity>())
    private val progressState = MutableStateFlow(emptyList<PlaybackProgressEntity>())
    private val sourcesState = MutableStateFlow(emptyList<SourceEntity>())
    private val statsState = MutableStateFlow(emptyList<ListeningStatEntity>())
    private val failuresState = MutableStateFlow(emptyList<PlaybackFailureEntity>())
    private val eventsState = MutableStateFlow(emptyList<PlaybackEventEntity>())
    private val tombstonesState = MutableStateFlow(emptyList<TombstoneEntity>())
    private val correctionsState = MutableStateFlow(emptyList<CorrectionEntity>())
    private val worksState = MutableStateFlow(emptyList<WorkEntity>())
    private val workSourcesState = MutableStateFlow(emptyList<WorkSourceEntity>())

    /** Snapshot of the recorded playback failures, for assertions. */
    val savedFailures: List<PlaybackFailureEntity> get() = failuresState.value

    /** Snapshot of the persisted playback progress, for assertions. */
    val savedProgress: List<PlaybackProgressEntity> get() = progressState.value

    /** Snapshot of the persisted bookmarks, for assertions. */
    val savedBookmarks: List<BookmarkEntity> get() = bookmarksState.value

    /** Snapshot of the persisted listening statistics, for assertions. */
    val savedListeningStats: List<ListeningStatEntity> get() = statsState.value

    /** Snapshot of the persisted source rows, for assertions. */
    val savedSources: List<SourceEntity> get() = sourcesState.value

    /** Snapshot of the persisted source-track rows, for assertions. */
    val savedTracks: List<SourceTrackEntity> get() = tracksState.value

    /** Snapshot of the persisted edition rows, for assertions. */
    val savedEditions: List<EditionEntity> get() = editionsState.value

    /** Snapshot of the persisted playback events, for assertions. */
    val savedPlaybackEvents: List<PlaybackEventEntity> get() = eventsState.value

    /** Snapshot of the persisted tombstones, for assertions. */
    val savedTombstones: List<TombstoneEntity> get() = tombstonesState.value

    /** Snapshot of the persisted correction memory, for assertions. */
    val savedCorrections: List<CorrectionEntity> get() = correctionsState.value

    // --- Audiobooks -------------------------------------------------------

    override fun getAllAudiobooks(): Flow<List<AudiobookEntity>> =
        booksState.map { books -> books.sortedBy { it.title } }

    override suspend fun getAllAudiobooksOnce(): List<AudiobookEntity> = booksState.value

    override fun getDownloadedAudiobooks(): Flow<List<AudiobookEntity>> =
        booksState.map { books -> books.filter { it.isDownloaded }.sortedBy { it.title } }

    override fun getFavoriteAudiobooks(): Flow<List<AudiobookEntity>> =
        booksState.map { books -> books.filter { it.isFavorite }.sortedBy { it.title } }

    override suspend fun getAudiobookById(id: String): AudiobookEntity? =
        booksState.value.firstOrNull { it.id == id }

    override fun observeAudiobookById(id: String): Flow<AudiobookEntity?> =
        booksState.map { books -> books.firstOrNull { it.id == id } }

    override suspend fun insertAudiobooks(books: List<AudiobookEntity>) {
        val incomingIds = books.map { it.id }.toSet()
        booksState.update { current -> current.filterNot { it.id in incomingIds } + books }
    }

    override suspend fun updateDownloadState(bookId: String, isDownloaded: Boolean, progress: Float) {
        booksState.update { current ->
            current.map { book ->
                if (book.id == bookId) {
                    book.copy(isDownloaded = isDownloaded, downloadProgress = progress)
                } else {
                    book
                }
            }
        }
    }

    override suspend fun updateSeriesFields(bookId: String, seriesTitle: String?, seriesUrl: String?, seriesIndex: Int?) {
        booksState.update { current ->
            current.map {
                if (it.id == bookId) it.copy(seriesTitle = seriesTitle, seriesUrl = seriesUrl, seriesIndex = seriesIndex) else it
            }
        }
    }

    override suspend fun updatePreferredSpeed(bookId: String, speed: Float?) {
        booksState.update { current ->
            current.map { if (it.id == bookId) it.copy(preferredSpeed = speed) else it }
        }
    }

    override suspend fun updatePausedAt(bookId: String, pausedAt: Long?) {
        progressState.update { current ->
            current.map { if (it.bookId == bookId) it.copy(lastPausedAtEpochMs = pausedAt) else it }
        }
    }

    override suspend fun setFavorite(bookId: String, isFavorite: Boolean) {
        booksState.update { current ->
            current.map { if (it.id == bookId) it.copy(isFavorite = isFavorite) else it }
        }
    }

    override suspend fun updateCoverImageUrl(bookId: String, coverUrl: String) {
        booksState.update { current ->
            current.map { if (it.id == bookId) it.copy(coverImageUrl = coverUrl) else it }
        }
    }

    override suspend fun markBookNotDownloaded(bookId: String) {
        updateDownloadState(bookId, isDownloaded = false, progress = 0f)
    }

    override suspend fun markAllNotDownloaded() {
        booksState.update { current ->
            current.map { it.copy(isDownloaded = false, downloadProgress = 0f) }
        }
    }

    // --- Chapters ---------------------------------------------------------

    override fun getChaptersForBook(bookId: String): Flow<List<ChapterEntity>> =
        chaptersState.map { chapters ->
            chapters.filter { it.bookId == bookId }.sortedBy { it.chapterIndex }
        }

    override suspend fun getChaptersListForBook(bookId: String): List<ChapterEntity> =
        chaptersState.value.filter { it.bookId == bookId }.sortedBy { it.chapterIndex }

    override suspend fun getChaptersListForEdition(editionId: String): List<ChapterEntity> =
        chaptersState.value.filter { it.editionId == editionId }.sortedBy { it.chapterIndex }

    override fun getAllChapters(): Flow<List<ChapterEntity>> =
        chaptersState.map { chapters -> chapters.sortedWith(compareBy({ it.bookId }, { it.chapterIndex })) }

    override suspend fun insertChapters(chapters: List<ChapterEntity>) {
        val incomingIds = chapters.map { it.id }.toSet()
        chaptersState.update { current -> current.filterNot { it.id in incomingIds } + chapters }
    }

    override suspend fun updateChapterDuration(chapterId: String, durationSeconds: Long) {
        chaptersState.update { current ->
            current.map { chapter ->
                if (chapter.id == chapterId) chapter.copy(durationSeconds = durationSeconds) else chapter
            }
        }
    }

    override suspend fun updateBookStats(bookId: String, totalChapters: Int, totalDurationSeconds: Long) {
        booksState.update { current ->
            current.map { book ->
                if (book.id == bookId) book.copy(totalChapters = totalChapters, totalDurationSeconds = totalDurationSeconds) else book
            }
        }
    }

    override suspend fun updateBookMetadata(
        bookId: String,
        author: String?,
        narrator: String?,
        genre: String?,
        rating: Float?,
        seriesTitle: String?,
        seriesIndex: Int?,
        seriesUrl: String?
    ) {
        booksState.update { current ->
            current.map { book ->
                if (book.id == bookId) {
                    book.copy(
                        author = author ?: book.author,
                        narrator = narrator ?: book.narrator,
                        genre = genre ?: book.genre,
                        rating = rating ?: book.rating,
                        seriesTitle = seriesTitle ?: book.seriesTitle,
                        seriesIndex = seriesIndex ?: book.seriesIndex,
                        seriesUrl = seriesUrl ?: book.seriesUrl
                    )
                } else {
                    book
                }
            }
        }
    }

    // --- Source tracks (ADR-0007) -----------------------------------------

    override fun getTracksForSource(sourceId: String): Flow<List<SourceTrackEntity>> =
        tracksState.map { tracks ->
            tracks.filter { it.sourceId == sourceId }.sortedBy { it.trackIndex }
        }

    override suspend fun getTracksForSourceSync(sourceId: String): List<SourceTrackEntity> =
        tracksState.value.filter { it.sourceId == sourceId }.sortedBy { it.trackIndex }

    override suspend fun getTracksForBookSync(bookId: String): List<SourceTrackEntity> {
        val sourceIds = sourcesState.value.filter { it.bookId == bookId }.map { it.id }.toSet()
        return tracksState.value.filter { it.sourceId in sourceIds }.sortedBy { it.trackIndex }
    }

    override suspend fun insertTracks(tracks: List<SourceTrackEntity>) {
        val incomingIds = tracks.map { it.id }.toSet()
        tracksState.update { current -> current.filterNot { it.id in incomingIds } + tracks }
    }

    override suspend fun updateTrackDownloadState(trackId: String, isDownloaded: Boolean, filePath: String?) {
        tracksState.update { current ->
            current.map { track ->
                if (track.id == trackId) {
                    track.copy(isDownloaded = isDownloaded, localFilePath = filePath)
                } else {
                    track
                }
            }
        }
    }

    override suspend fun getTrackByContentHash(hash: String): SourceTrackEntity? =
        tracksState.value.firstOrNull { it.contentHash == hash }

    override suspend fun getAllTrackContentHashes(): List<String> =
        tracksState.value.mapNotNull { it.contentHash }

    override suspend fun clearTrackContentHashesForBook(bookId: String) {
        val sourceIds = sourcesState.value.filter { it.bookId == bookId }.map { it.id }.toSet()
        tracksState.update { current ->
            current.map { tr -> if (tr.sourceId in sourceIds) tr.copy(contentHash = null) else tr }
        }
    }

    override suspend fun clearTracksDownloadStateForBook(bookId: String) {
        val sourceIds = sourcesState.value.filter { it.bookId == bookId }.map { it.id }.toSet()
        tracksState.update { current ->
            current.map { tr -> if (tr.sourceId in sourceIds) tr.copy(isDownloaded = false, localFilePath = null) else tr }
        }
    }

    override suspend fun clearAllTracksDownloadState() {
        tracksState.update { current ->
            current.map { it.copy(isDownloaded = false, localFilePath = null) }
        }
    }

    override suspend fun deleteTracksForBook(bookId: String) {
        val sourceIds = sourcesState.value.filter { it.bookId == bookId }.map { it.id }.toSet()
        tracksState.update { current -> current.filterNot { it.sourceId in sourceIds } }
    }

    // --- Domain Editions (ADR-0007) ---------------------------------------

    override suspend fun insertEdition(edition: EditionEntity) {
        editionsState.update { current -> current.filterNot { it.id == edition.id } + edition }
    }

    override suspend fun getEditionById(editionId: String): EditionEntity? =
        editionsState.value.firstOrNull { it.id == editionId }

    override suspend fun getEditionForWork(bookId: String): EditionEntity? =
        editionsState.value.firstOrNull { it.workId == bookId }

    // --- Bookmarks --------------------------------------------------------

    override fun getBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>> =
        bookmarksState.map { bookmarks ->
            bookmarks.filter { it.bookId == bookId }.sortedBy { it.timestampSeconds }
        }

    override fun getBookmarksForEdition(editionId: String): Flow<List<BookmarkEntity>> =
        bookmarksState.map { bookmarks ->
            bookmarks.filter { it.editionId == editionId }.sortedBy { it.timestampSeconds }
        }

    override fun getAllBookmarks(): Flow<List<BookmarkEntity>> =
        bookmarksState.map { bookmarks -> bookmarks.sortedByDescending { it.createdAt } }

    override suspend fun insertBookmark(bookmark: BookmarkEntity) {
        bookmarksState.update { current ->
            val assigned = if (bookmark.id == 0L) {
                bookmark.copy(id = (current.maxOfOrNull { it.id } ?: 0L) + 1L)
            } else {
                bookmark
            }
            current.filterNot { it.id == assigned.id } + assigned
        }
    }

    override suspend fun deleteBookmark(bookmarkId: Long) {
        bookmarksState.update { current -> current.filterNot { it.id == bookmarkId } }
    }

    // --- Sources (spec-10 T2; re-parented to editionId in ADR-0007) --------

    override suspend fun findByMergeKey(mergeKey: String): AudiobookEntity? =
        booksState.value.firstOrNull { it.mergeKey == mergeKey && it.mergeKey.isNotEmpty() }

    // Wayfinder #42: re-scan diff queries.
    override suspend fun getAudiobooksBySourceTree(treeUri: String): List<AudiobookEntity> =
        booksState.value.filter { it.sourceTreeUri == treeUri }

    override suspend fun getImportedSourceTrees(): List<String> =
        booksState.value.mapNotNull { it.sourceTreeUri }
            .filter { it.isNotBlank() }
            .distinct()

    override suspend fun updateSourceFingerprint(sourceId: String, fingerprint: String?) {
        sourcesState.update { current ->
            current.map { source ->
                if (source.id == sourceId) source.copy(lastScanFingerprint = fingerprint) else source
            }
        }
    }

    override fun getSourcesForBook(bookId: String): Flow<List<SourceEntity>> =
        sourcesState.map { sources -> sources.filter { it.bookId == bookId }.sortedBy { it.addedAt } }

    override suspend fun getSourcesForBookSync(bookId: String): List<SourceEntity> =
        sourcesState.value.filter { it.bookId == bookId }.sortedBy { it.addedAt }

    override suspend fun getSourcesForEditionSync(editionId: String): List<SourceEntity> =
        sourcesState.value.filter { it.editionId == editionId }.sortedBy { it.addedAt }

    override suspend fun insertSources(sources: List<SourceEntity>) {
        val incomingIds = sources.map { it.id }.toSet()
        sourcesState.update { current -> current.filterNot { it.id in incomingIds } + sources }
    }

    override suspend fun deleteSourcesForBook(bookId: String) {
        sourcesState.update { current -> current.filterNot { it.bookId == bookId } }
    }

    override suspend fun getBookIdBySourceId(sourceId: String): String? =
        sourcesState.value.firstOrNull { it.id == sourceId }?.bookId

    // --- Cascade deletion (spec #8 ticket T2) ------------------------------

    override suspend fun deleteChaptersForBook(bookId: String) {
        chaptersState.update { current -> current.filterNot { it.bookId == bookId } }
    }

    override suspend fun deleteBookmarksForBook(bookId: String) {
        bookmarksState.update { current -> current.filterNot { it.bookId == bookId } }
    }

    override suspend fun deletePlaybackProgressForBook(bookId: String) {
        progressState.update { current -> current.filterNot { it.bookId == bookId } }
    }

    override suspend fun deleteAudiobook(bookId: String) {
        booksState.update { current -> current.filterNot { it.id == bookId } }
    }

    // --- Playback progress (ADR-0007: keyed by Edition) --------------------

    override fun getPlaybackProgress(bookId: String): Flow<PlaybackProgressEntity?> =
        progressState.map { progress -> progress.filter { it.bookId == bookId }.maxByOrNull { it.lastListenedAt } }

    override fun getPlaybackProgressByEdition(editionId: String): Flow<PlaybackProgressEntity?> =
        progressState.map { progress -> progress.firstOrNull { it.editionId == editionId } }

    override suspend fun getPlaybackProgressSync(bookId: String): PlaybackProgressEntity? =
        progressState.value.filter { it.bookId == bookId }.maxByOrNull { it.lastListenedAt }

    override suspend fun getPlaybackProgressSyncByEdition(editionId: String): PlaybackProgressEntity? =
        progressState.value.firstOrNull { it.editionId == editionId }

    override fun getAllPlaybackProgress(): Flow<List<PlaybackProgressEntity>> =
        progressState.map { progress -> progress.sortedByDescending { it.lastListenedAt } }

    override suspend fun savePlaybackProgress(progress: PlaybackProgressEntity) {
        progressState.update { current ->
            current.filterNot { it.editionId == progress.editionId } + progress
        }
    }

    // --- Listening stats --------------------------------------------------

    override fun getAllListeningStats(): Flow<List<ListeningStatEntity>> =
        statsState.map { stats -> stats.sortedByDescending { it.dateIso } }

    override suspend fun getListeningStatForDate(dateIso: String): ListeningStatEntity? =
        statsState.value.firstOrNull { it.dateIso == dateIso }

    override suspend fun saveListeningStat(stat: ListeningStatEntity) {
        statsState.update { current -> current.filterNot { it.dateIso == stat.dateIso } + stat }
    }

    override suspend fun insertPlaybackFailure(failure: PlaybackFailureEntity) {
        failuresState.update { current -> current + failure }
    }

    override suspend fun getRecentPlaybackFailures(limit: Int): List<PlaybackFailureEntity> =
        failuresState.value.sortedByDescending { it.timestamp }.take(limit)

    override suspend fun deletePlaybackFailure(id: Long) {
        failuresState.update { current -> current.filterNot { it.id == id } }
    }

    // --- Playback events (spec-16; ADR-0007 rows carry sourceKey "") --------

    override suspend fun insertPlaybackEvent(event: PlaybackEventEntity) {
        eventsState.update { current ->
            val assigned = if (event.id == 0L) {
                event.copy(id = (current.maxOfOrNull { it.id } ?: 0L) + 1L)
            } else {
                event
            }
            current + assigned
        }
    }

    override suspend fun getLatestUndoCandidate(bookId: String, sourceKey: String): PlaybackEventEntity? =
        eventsState.value
            .filter { it.bookId == bookId && it.sourceKey == sourceKey &&
                (it.kind == "SEEK" || it.kind == "SOURCE_SWITCH") && it.fromPositionSeconds != null }
            .sortedWith(compareByDescending<PlaybackEventEntity> { it.timestamp }.thenByDescending { it.id })
            .firstOrNull()

    override suspend fun getPlaybackEventsForBookSource(bookId: String, sourceKey: String): List<PlaybackEventEntity> =
        eventsState.value
            .filter { it.bookId == bookId && it.sourceKey == sourceKey }
            .sortedWith(compareByDescending<PlaybackEventEntity> { it.timestamp }.thenByDescending { it.id })

    override suspend fun deletePlaybackEvents(ids: List<Long>) {
        eventsState.update { current -> current.filterNot { it.id in ids } }
    }

    override suspend fun deletePlaybackEventsForBook(bookId: String) {
        eventsState.update { current -> current.filterNot { it.bookId == bookId } }
    }

    // --- Tombstones (wayfinder #55 Q8, stage-2 S1) --------------------------

    override suspend fun insertTombstone(tombstone: TombstoneEntity) {
        tombstonesState.update { current -> current.filterNot { it.bookId == tombstone.bookId } + tombstone }
    }

    override suspend fun getTombstoneBookIds(): List<String> =
        tombstonesState.value.map { it.bookId }

    override suspend fun isBookTombstoned(bookId: String): Boolean =
        tombstonesState.value.any { it.bookId == bookId }

    // ADR-0005 mirror: the catalog insert is a no-op for a tombstoned Work.
    override suspend fun insertCatalogBookIfNotTombstoned(
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
        downloadProgress: Float,
        totalDurationSeconds: Long,
        totalChapters: Int,
        rating: Float,
        isFavorite: Boolean,
        seriesTitle: String?,
        seriesUrl: String?,
        seriesIndex: Int?,
        preferredSpeed: Float?,
        createdAt: Long,
        sourceTreeUri: String?,
        mergeKey: String,
        workId: String?
    ): Long {
        if (tombstonesState.value.any { it.bookId == id }) return 0L
        val book = AudiobookEntity(
            id = id, title = title, author = author, narrator = narrator, description = description,
            coverDrawableRes = coverDrawableRes, coverImageUrl = coverImageUrl, genre = genre,
            sourceUrl = sourceUrl, isDownloaded = isDownloaded, downloadProgress = downloadProgress,
            totalDurationSeconds = totalDurationSeconds, totalChapters = totalChapters, rating = rating,
            isFavorite = isFavorite, seriesTitle = seriesTitle, seriesUrl = seriesUrl, seriesIndex = seriesIndex,
            preferredSpeed = preferredSpeed, createdAt = createdAt, sourceTreeUri = sourceTreeUri,
            mergeKey = mergeKey, workId = workId
        )
        booksState.update { current -> current.filterNot { it.id == book.id } + book }
        return 1L
    }

    override suspend fun deleteTombstone(bookId: String) {
        tombstonesState.update { current -> current.filterNot { it.bookId == bookId } }
    }

    // --- Corrections (wayfinder #54 Q9, stage-2 S1) ------------------------

    override suspend fun upsertCorrection(correction: CorrectionEntity) {
        correctionsState.update { current ->
            current.filterNot { it.mergeKey == correction.mergeKey && it.kind == correction.kind && it.value == correction.value } + correction
        }
    }

    override suspend fun getCorrectionsForMergeKey(mergeKey: String): List<CorrectionEntity> =
        correctionsState.value.filter { it.mergeKey == mergeKey }.sortedByDescending { it.updatedAt }

    override suspend fun getNeverMatchPairs(mergeKey: String): List<CorrectionEntity> =
        correctionsState.value
            .filter { it.kind == "NEVER_MATCH" && (it.mergeKey == mergeKey || it.value == mergeKey) }
            .sortedByDescending { it.updatedAt }

    // --- Persisted catalogue: Works + Sources (spec-23 T1, ADR-0007) --------

    override suspend fun findWorkByMergeKey(mergeKey: String): WorkEntity? =
        worksState.value.firstOrNull { it.mergeKey == mergeKey && it.mergeKey.isNotEmpty() }

    override suspend fun upsertWork(work: WorkEntity) {
        worksState.update { current -> current.filterNot { it.id == work.id } + work }
    }

    override suspend fun upsertWorkSource(workSource: WorkSourceEntity) {
        workSourcesState.update { current -> current.filterNot { it.id == workSource.id } + workSource }
    }

    override fun observeWorkSourcesForWork(workId: String): Flow<List<WorkSourceEntity>> =
        workSourcesState.map { list -> list.filter { it.workId == workId }.sortedBy { it.addedAt } }

    override suspend fun getWorkSourcesForWorkSync(workId: String): List<WorkSourceEntity> =
        workSourcesState.value.filter { it.workId == workId }.sortedBy { it.addedAt }

    override fun observeWorks(): Flow<List<WorkEntity>> = worksState

    override suspend fun countWorks(): Int = worksState.value.size

    override suspend fun countWorkSources(): Int = workSourcesState.value.size

    override fun pagedWorksFeedRecent(sourceId: String?, genre: String?): PagingSource<Int, WorkFeedRow> =
        fakeFeed(sourceId, genre, sortByTitle = false)

    override fun pagedWorksFeedByTitle(sourceId: String?, genre: String?): PagingSource<Int, WorkFeedRow> =
        fakeFeed(sourceId, genre, sortByTitle = true)

    /** In-memory PagingSource over the same state the fake DAO owns. */
    private fun fakeFeed(sourceId: String?, genre: String?, sortByTitle: Boolean): PagingSource<Int, WorkFeedRow> {
        val rows = worksState.value.mapNotNull { work ->
            if (sourceId != null && workSourcesState.value.none { it.workId == work.id && it.sourceId == sourceId }) {
                return@mapNotNull null
            }
            val libraryGenre = booksState.value.firstOrNull { it.workId == work.id }?.genre
            if (genre != null && (libraryGenre == null || !libraryGenre.contains(genre, ignoreCase = true))) {
                return@mapNotNull null
            }
            WorkFeedRow(
                workId = work.id,
                mergeKey = work.mergeKey,
                title = work.title,
                author = work.author,
                narrator = work.narrator,
                seriesTitle = work.seriesTitle,
                seriesIndex = work.seriesIndex,
                coverImageUrl = work.coverImageUrl,
                addedAt = work.addedAt,
                sourceCount = workSourcesState.value.count { it.workId == work.id },
                genre = libraryGenre
            )
        }
        val sorted = if (sortByTitle) {
            rows.sortedWith(compareBy({ it.title.lowercase() }, { -it.addedAt }))
        } else {
            rows.sortedByDescending { it.addedAt }
        }
        return object : PagingSource<Int, WorkFeedRow>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, WorkFeedRow> {
                val offset = params.key ?: 0
                val page = sorted.drop(offset).take(params.loadSize)
                return LoadResult.Page(
                    data = page,
                    prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
                    nextKey = if (offset + page.size < sorted.size) offset + page.size else null
                )
            }

            override fun getRefreshKey(state: PagingState<Int, WorkFeedRow>): Int? = null
        }
    }

    /** Snapshot of the persisted catalogue works, for assertions. */
    val savedWorks: List<WorkEntity> get() = worksState.value

    /** Snapshot of the persisted catalogue work-sources, for assertions. */
    val savedWorkSources: List<WorkSourceEntity> get() = workSourcesState.value
}
