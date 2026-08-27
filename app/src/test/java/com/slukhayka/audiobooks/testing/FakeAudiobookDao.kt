package com.slukhayka.audiobooks.testing

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookRow
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.CorrectionEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.HiddenReviewerEntity
import com.slukhayka.audiobooks.data.db.RecommendationPreferenceEntity
import com.slukhayka.audiobooks.data.db.LibraryEntryEntity
import com.slukhayka.audiobooks.data.db.ListeningStatEntity
import com.slukhayka.audiobooks.data.db.toBookRow
import com.slukhayka.audiobooks.data.db.PlaybackEventEntity
import com.slukhayka.audiobooks.data.db.PlaybackFailureEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.SeriesEntity
import com.slukhayka.audiobooks.data.db.SeriesMemberEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.db.DescriptionRow
import com.slukhayka.audiobooks.data.db.TitleRow
import com.slukhayka.audiobooks.data.db.UniverseEntity
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import com.slukhayka.audiobooks.data.db.WorkFacetEntity
import com.slukhayka.audiobooks.data.db.WorkFacetSeriesEntity
import com.slukhayka.audiobooks.data.db.GenreFacetEntity
import com.slukhayka.audiobooks.data.db.WorkGenreEntity
import com.slukhayka.audiobooks.data.db.GenreAssertionEntity
import com.slukhayka.audiobooks.data.db.GenreAssertionStateEntity
import com.slukhayka.audiobooks.data.db.GenreFacetOption
import com.slukhayka.audiobooks.data.db.EditionFacetEntity
import com.slukhayka.audiobooks.data.db.AuthorFacetEntity
import com.slukhayka.audiobooks.data.db.AuthorAliasEntity
import com.slukhayka.audiobooks.data.authors.AuthorSummary
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
    // Spec-40 (#281): the local reviewer mute.
    private val hiddenReviewersState = MutableStateFlow(emptyList<HiddenReviewerEntity>())
    private val recommendationPreferencesState = MutableStateFlow(emptyList<RecommendationPreferenceEntity>())
    private val worksState = MutableStateFlow(emptyList<WorkEntity>())
    private val workSourcesState = MutableStateFlow(emptyList<WorkSourceEntity>())
    private val libraryEntriesState = MutableStateFlow(emptyList<LibraryEntryEntity>())
    // Spec-25 (#171): the universe resolution cache.
    private val universesState = MutableStateFlow(emptyList<UniverseEntity>())
    private val seriesState = MutableStateFlow(emptyList<SeriesEntity>())
    private val seriesMembersState = MutableStateFlow(emptyList<SeriesMemberEntity>())
    private val workFacetsState = MutableStateFlow(emptyList<WorkFacetEntity>())
    private val workFacetSeriesState = MutableStateFlow(emptyList<WorkFacetSeriesEntity>())
    private val genreFacetsState = MutableStateFlow(emptyList<GenreFacetEntity>())
    private val workGenresState = MutableStateFlow(emptyList<WorkGenreEntity>())
    private val genreAssertionsState = MutableStateFlow(emptyList<GenreAssertionEntity>())
    private val genreAssertionStatesState = MutableStateFlow(emptyList<GenreAssertionStateEntity>())
    private val editionFacetsState = MutableStateFlow(emptyList<EditionFacetEntity>())
    private val authorFacetsState = MutableStateFlow(emptyList<AuthorFacetEntity>())
    private val authorAliasesState = MutableStateFlow(emptyList<AuthorAliasEntity>())

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

    /** Snapshot of the persisted library-entry rows (ADR-0009), for assertions. */
    val savedLibraryEntries: List<LibraryEntryEntity> get() = libraryEntriesState.value

    // --- Audiobooks (ADR-0009: the fake returns the JOINed [BookRow] shape) -

    override fun getAllAudiobooks(): Flow<List<BookRow>> =
        booksState.map { books -> books.sortedBy { it.title }.map { it.toBookRow() } }

    override suspend fun getAllAudiobooksOnce(): List<BookRow> = booksState.value.map { it.toBookRow() }

    override fun getDownloadedAudiobooks(): Flow<List<BookRow>> =
        booksState.map { books -> books.filter { it.isDownloaded }.sortedBy { it.title }.map { it.toBookRow() } }

    override fun getFavoriteAudiobooks(): Flow<List<BookRow>> =
        booksState.map { books -> books.filter { it.isFavorite }.sortedBy { it.title }.map { it.toBookRow() } }

    override suspend fun getAudiobookById(id: String): BookRow? =
        booksState.value.firstOrNull { it.id == id }?.toBookRow()

    override fun observeAudiobookById(id: String): Flow<BookRow?> =
        booksState.map { books -> books.firstOrNull { it.id == id }?.toBookRow() }

    override suspend fun insertAudiobooks(books: List<AudiobookEntity>) {
        val incomingIds = books.map { it.id }.toSet()
        booksState.update { current -> current.filterNot { it.id in incomingIds } + books }
    }

    override suspend fun updateDownloadState(bookId: String, isDownloaded: Boolean, progress: Float) {
        // ADR-0009: isDownloaded stays on the audiobooks row, downloadProgress
        // belongs to the Library Entry row — the fake mirrors both (the book
        // copy keeps the projection in sync for in-memory reads).
        booksState.update { current ->
            current.map { book ->
                if (book.id == bookId) {
                    // ADR-0009: downloadProgress is an @Ignore projection.
                    book.copy(isDownloaded = isDownloaded).also { it.downloadProgress = progress }
                } else {
                    book
                }
            }
        }
        upsertEntryDownloadProgress(bookId, progress)
    }

    override suspend fun updateBookDownloadState(bookId: String, isDownloaded: Boolean) {
        booksState.update { current ->
            current.map { book -> if (book.id == bookId) book.copy(isDownloaded = isDownloaded) else book }
        }
    }

    override suspend fun upsertEntryDownloadProgress(bookId: String, progress: Float) {
        libraryEntriesState.update { current ->
            val existing = current.firstOrNull { it.id == bookId }
            if (existing != null) {
                current.map { if (it.id == bookId) it.copy(downloadProgress = progress) else it }
            } else {
                current + LibraryEntryEntity(
                    id = bookId, workId = bookId, isFavorite = false,
                    createdAt = System.currentTimeMillis(), downloadProgress = progress
                )
            }
        }
    }

    override suspend fun updateDownloadStateValue(bookId: String, state: String) {
        booksState.update { current ->
            current.map { book ->
                if (book.id == bookId) {
                    book.downloadState = state
                    book
                } else book
            }
        }
        libraryEntriesState.update { current ->
            current.map { if (it.id == bookId) it.copy(downloadState = state) else it }
        }
    }

    override suspend fun updateSeriesFields(bookId: String, seriesTitle: String?, seriesUrl: String?, seriesIndex: Int?) {
        // ADR-0009: series persists on the Work row; the book copy keeps the
        // projection in sync for in-memory reads.
        val workId = libraryEntriesState.value.firstOrNull { it.id == bookId }?.workId
        worksState.update { current ->
            current.map {
                if (it.id == workId) it.copy(seriesTitle = seriesTitle, seriesUrl = seriesUrl, seriesIndex = seriesIndex) else it
            }
        }
        booksState.update { current ->
            current.map {
                // ADR-0009: series fields are @Ignore projections.
                if (it.id == bookId) {
                    it.copy().also { b ->
                        b.seriesTitle = seriesTitle
                        b.seriesUrl = seriesUrl
                        b.seriesIndex = seriesIndex
                    }
                } else {
                    it
                }
            }
        }
    }

    override suspend fun getEditionIdForBook(bookId: String): String? =
        editionsState.value.firstOrNull { it.workId == bookId }?.id

    override suspend fun setProgressPreferredSpeed(editionId: String, speed: Float?) {
        progressState.update { current ->
            current.map { if (it.editionId == editionId) it.copy(preferredSpeed = speed) else it }
        }
    }

    override suspend fun updatePreferredSpeed(bookId: String, speed: Float?) {
        // ADR-0009: the preference lives on the Listening State row.
        val editionId = getEditionIdForBook(bookId) ?: return
        setProgressPreferredSpeed(editionId, speed)
    }

    override suspend fun updatePausedAt(bookId: String, pausedAt: Long?) {
        progressState.update { current ->
            current.map { if (it.bookId == bookId) it.copy(lastPausedAtEpochMs = pausedAt) else it }
        }
    }

    override suspend fun setFavorite(bookId: String, isFavorite: Boolean) {
        // ADR-0009: favourite lives on the Library Entry row.
        libraryEntriesState.update { current ->
            val existing = current.firstOrNull { it.id == bookId }
            if (existing != null) {
                current.map { if (it.id == bookId) it.copy(isFavorite = isFavorite) else it }
            } else {
                current + LibraryEntryEntity(
                    id = bookId, workId = bookId, isFavorite = isFavorite,
                    createdAt = System.currentTimeMillis(), downloadProgress = 0f
                )
            }
        }
        booksState.update { current ->
            current.map {
                // ADR-0009: isFavorite is an @Ignore projection.
                if (it.id == bookId) it.copy().also { b -> b.isFavorite = isFavorite } else it
            }
        }
    }

    override suspend fun updateCoverImageUrl(bookId: String, coverUrl: String) {
        booksState.update { current ->
            current.map { if (it.id == bookId) it.copy(coverImageUrl = coverUrl) else it }
        }
    }

    override suspend fun resetEntryDownloadProgress(bookId: String) {
        libraryEntriesState.update { current ->
            current.map { if (it.id == bookId) it.copy(downloadProgress = 0f) else it }
        }
    }

    override suspend fun resetAllEntryDownloadProgress() {
        libraryEntriesState.update { current -> current.map { it.copy(downloadProgress = 0f) } }
    }

    override suspend fun markBookNotDownloaded(bookId: String) {
        updateDownloadState(bookId, isDownloaded = false, progress = 0f)
    }

    override suspend fun clearAllBookDownloadState() {
        booksState.update { current -> current.map { it.copy(isDownloaded = false) } }
    }

    override suspend fun markAllNotDownloaded() {
        booksState.update { current ->
            current.map {
                it.copy(isDownloaded = false).also { b -> b.downloadProgress = 0f }
            }
        }
        libraryEntriesState.update { current -> current.map { it.copy(downloadProgress = 0f) } }
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

    // Spec-24 T8 (#169): the chapter-duration probe's candidate pool — every
    // book with at least one unknown-duration chapter (0 is the placeholder).
    override suspend fun getBookIdsWithUnknownChapterDurations(): List<String> =
        chaptersState.value.filter { it.durationSeconds <= 0L }.map { it.bookId }.distinct()

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
        rating: Float?
    ) {
        booksState.update { current ->
            current.map { book ->
                if (book.id == bookId) {
                    book.copy(
                        author = author ?: book.author,
                        narrator = narrator ?: book.narrator,
                        genre = genre ?: book.genre,
                        rating = rating ?: book.rating
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

    // Spec-37 T2 (#251): URL-based reuse + hash bookkeeping — the same
    // projections the real DAO serves.
    override suspend fun getDownloadedTrackByUrl(url: String): SourceTrackEntity? =
        tracksState.value.firstOrNull { it.url == url && it.isDownloaded && it.localFilePath != null }

    override suspend fun updateTrackContentHash(trackId: String, hash: String?) {
        tracksState.update { current ->
            current.map { if (it.id == trackId) it.copy(contentHash = hash) else it }
        }
    }

    override suspend fun getTracksByFilePath(path: String): List<SourceTrackEntity> =
        tracksState.value.filter { it.localFilePath == path && it.isDownloaded }

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

    override suspend fun findByMergeKey(mergeKey: String): BookRow? =
        booksState.value.firstOrNull { it.mergeKey == mergeKey && it.mergeKey.isNotEmpty() }?.toBookRow()

    // Spec-30 T3 (#218): the coverless library rows with a Work identity.
    override suspend fun getLibraryRowsMissingCovers(limit: Int): List<BookRow> =
        booksState.value
            .filter { it.coverImageUrl.isNullOrBlank() && !it.mergeKey.isNullOrBlank() }
            .sortedBy { it.title }
            .take(limit)
            .map { it.toBookRow() }

    // ADR-0011: the card of a rendition — resolve the edition's owner book.
    override suspend fun findBookByEditionId(editionId: String): BookRow? {
        val bookId = editionsState.value.firstOrNull { it.id == editionId }?.workId ?: return null
        return booksState.value.firstOrNull { it.id == bookId }?.toBookRow()
    }

    // Wayfinder #42: re-scan diff queries.
    override suspend fun getAudiobooksBySourceTree(treeUri: String): List<BookRow> =
        booksState.value.filter { it.sourceTreeUri == treeUri }.map { it.toBookRow() }

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

    // Spec-27 (#184) BUG-002: the duplicate-Work merge's cleanup — the loser's
    // works row (cascade-removing its original work_sources in the real DAO)
    // and its edition rows (dropped after progress/bookmarks/sources moved).
    override suspend fun deleteWork(workId: String) {
        worksState.update { current -> current.filterNot { it.id == workId } }
        workSourcesState.update { current -> current.filterNot { it.workId == workId } }
    }

    override suspend fun deleteEditionsForWork(workId: String) {
        editionsState.update { current -> current.filterNot { it.workId == workId } }
    }

    override suspend fun upsertLibraryEntry(
        id: String,
        workId: String,
        isFavorite: Boolean,
        createdAt: Long,
        downloadProgress: Float,
        downloadState: String
    ) {
        libraryEntriesState.update { current ->
            val existing = current.firstOrNull { it.id == id }
            if (existing != null) {
                // True upsert: only the link and progress update on an
                // existing row (isFavorite/createdAt never reset on re-sync).
                current.map {
                    if (it.id == id) it.copy(workId = workId, downloadProgress = downloadProgress, downloadState = downloadState) else it
                }
            } else {
                current + LibraryEntryEntity(
                    id = id, workId = workId, isFavorite = isFavorite,
                    createdAt = createdAt, downloadProgress = downloadProgress, downloadState = downloadState
                )
            }
        }
    }

    override suspend fun deleteLibraryEntry(bookId: String) {
        libraryEntriesState.update { current -> current.filterNot { it.id == bookId } }
    }

    override suspend fun countLibraryEntries(): Int = libraryEntriesState.value.size

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
        totalDurationSeconds: Long,
        totalChapters: Int,
        rating: Float,
        sourceTreeUri: String?
    ): Long {
        if (tombstonesState.value.any { it.bookId == id }) return 0L
        val book = AudiobookEntity(
            id = id, title = title, author = author, narrator = narrator, description = description,
            coverDrawableRes = coverDrawableRes, coverImageUrl = coverImageUrl, genre = genre,
            sourceUrl = sourceUrl, isDownloaded = isDownloaded,
            totalDurationSeconds = totalDurationSeconds, totalChapters = totalChapters, rating = rating,
            sourceTreeUri = sourceTreeUri
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

    override suspend fun deleteCorrection(mergeKey: String, kind: String) {
        correctionsState.update { current -> current.filterNot { it.mergeKey == mergeKey && it.kind == kind } }
    }

    // --- Local reviewer mute (spec-40 #281) ---------------------------------

    override suspend fun hideAuthor(author: HiddenReviewerEntity) {
        hiddenReviewersState.update { current -> current.filterNot { it.authorName == author.authorName } + author }
    }

    override suspend fun unhideAuthor(authorName: String) {
        hiddenReviewersState.update { current -> current.filterNot { it.authorName == authorName } }
    }

    override suspend fun hiddenAuthors(): List<HiddenReviewerEntity> =
        hiddenReviewersState.value.sortedBy { it.authorName }

    override suspend fun upsertRecommendationPreference(preference: RecommendationPreferenceEntity) {
        recommendationPreferencesState.update { current ->
            current.filterNot { it.kind == preference.kind && it.targetKey == preference.targetKey } + preference
        }
    }

    override suspend fun deleteRecommendationPreference(kind: String, targetKey: String) {
        recommendationPreferencesState.update { current ->
            current.filterNot { it.kind == kind && it.targetKey == targetKey }
        }
    }

    override fun observeRecommendationPreferences(): Flow<List<RecommendationPreferenceEntity>> =
        recommendationPreferencesState

    override suspend fun clearRecommendationPreferences() {
        recommendationPreferencesState.value = emptyList()
    }

    // --- Persisted catalogue: Works + Sources (spec-23 T1, ADR-0007) --------

    override suspend fun findWorkByMergeKey(mergeKey: String): WorkEntity? =
        worksState.value.firstOrNull { it.mergeKey == mergeKey && it.mergeKey.isNotEmpty() }

    override suspend fun getWorkById(workId: String): WorkEntity? =
        worksState.value.firstOrNull { it.id == workId }

    override suspend fun upsertWork(work: WorkEntity) {
        worksState.update { current -> current.filterNot { it.id == work.id } + work }
    }

    override suspend fun upsertWorkSource(workSource: WorkSourceEntity) {
        workSourcesState.update { current -> current.filterNot { it.id == workSource.id } + workSource }
    }

    // --- Spec-25 (#171): the universe resolution cache ---------------------

    override suspend fun upsertUniverse(universe: UniverseEntity) {
        universesState.update { current -> current.filterNot { it.id == universe.id } + universe }
    }

    override suspend fun upsertSeries(series: SeriesEntity) {
        seriesState.update { current -> current.filterNot { it.id == series.id } + series }
    }

    override suspend fun upsertSeriesMember(member: SeriesMemberEntity) {
        seriesMembersState.update { current -> current.filterNot { it.workId == member.workId && it.seriesId == member.seriesId } + member }
    }

    override suspend fun getUniverseById(id: String): UniverseEntity? =
        universesState.value.firstOrNull { it.id == id }

    override suspend fun getSeriesInUniverse(universeId: String): List<SeriesEntity> =
        seriesState.value.filter { it.universeId == universeId }.sortedBy { it.positionInUniverse }

    override suspend fun getAllSeries(): List<SeriesEntity> = seriesState.value

    override suspend fun getSeriesMembersForWork(workId: String): List<SeriesMemberEntity> =
        seriesMembersState.value.filter { it.workId == workId }

    override suspend fun getAllSeriesMembers(): List<SeriesMemberEntity> = seriesMembersState.value

    override suspend fun deleteSeriesMembersForWork(workId: String) {
        seriesMembersState.update { current -> current.filterNot { it.workId == workId } }
    }

    /** Snapshot of the cached universes, for assertions. */
    val savedUniverses: List<UniverseEntity> get() = universesState.value

    /** Snapshot of the cached series rows, for assertions. */
    val savedSeries: List<SeriesEntity> get() = seriesState.value

    /** Snapshot of the cached series memberships, for assertions. */
    val savedSeriesMembers: List<SeriesMemberEntity> get() = seriesMembersState.value

    override fun observeWorkSourcesForWork(workId: String): Flow<List<WorkSourceEntity>> =
        workSourcesState.map { list -> list.filter { it.workId == workId }.sortedBy { it.addedAt } }

    override suspend fun getWorkSourcesForWorkSync(workId: String): List<WorkSourceEntity> =
        workSourcesState.value.filter { it.workId == workId }.sortedBy { it.addedAt }

    override fun observeWorks(): Flow<List<WorkEntity>> = worksState

    // Spec-24 T1: the stored-title scrub reads through the same projection
    // the real DAO serves.
    override suspend fun getAllBookTitleRows(): List<TitleRow> =
        booksState.value.map { TitleRow(it.id, it.title) }

    override suspend fun getAllWorkTitleRows(): List<TitleRow> =
        worksState.value.map { TitleRow(it.id, it.title) }

    override suspend fun updateBookTitle(id: String, title: String) {
        booksState.update { current -> current.map { if (it.id == id) it.copy(title = title) else it } }
    }

    override suspend fun updateWorkTitle(id: String, title: String) {
        worksState.update { current -> current.map { if (it.id == id) it.copy(title = title) else it } }
    }

    // #264: the stored-description scrub reads through the same projection
    // the real DAO serves.
    override suspend fun getAllBookDescriptionRows(): List<DescriptionRow> =
        booksState.value.map { DescriptionRow(it.id, it.description) }

    override suspend fun updateBookDescription(id: String, description: String) {
        booksState.update { current -> current.map { if (it.id == id) it.copy(description = description) else it } }
    }

    override suspend fun countWorks(): Int = worksState.value.size

    override suspend fun countWorkSources(): Int = workSourcesState.value.size

    override fun pagedWorksFeedRecent(
        genreIds: List<String>, genreActive: Int,
        durationBucketIds: List<String>, durationActive: Int,
        authorIds: List<String>, authorActive: Int,
        availabilityAtMillis: Long
    ): PagingSource<Int, WorkFeedRow> =
        fakeFeed(genreIds, genreActive, durationBucketIds, durationActive, authorIds, authorActive, sortByTitle = false)

    override fun pagedWorksFeedByTitle(
        genreIds: List<String>, genreActive: Int,
        durationBucketIds: List<String>, durationActive: Int,
        authorIds: List<String>, authorActive: Int,
        availabilityAtMillis: Long
    ): PagingSource<Int, WorkFeedRow> =
        fakeFeed(genreIds, genreActive, durationBucketIds, durationActive, authorIds, authorActive, sortByTitle = true)

    /** In-memory PagingSource over the same state the fake DAO owns. */
    private fun fakeFeed(
        genreIds: List<String>, genreActive: Int,
        durationBucketIds: List<String>, durationActive: Int,
        authorIds: List<String>, authorActive: Int,
        sortByTitle: Boolean
    ): PagingSource<Int, WorkFeedRow> {
        val rows = worksState.value.mapNotNull { work ->
            if (genreActive != 0 && workGenresState.value.none { it.workId == work.id && it.genreId in genreIds }) {
                return@mapNotNull null
            }
            if (durationActive != 0 && editionFacetsState.value.none { it.workId == work.id && it.durationBucketId in durationBucketIds }) return@mapNotNull null
            if (authorActive != 0 && workFacetsState.value.none { it.workId == work.id && it.canonicalAuthorId in authorIds }) return@mapNotNull null
            val libraryBook = booksState.value.firstOrNull { it.workId == work.id }
            val libraryGenre = workGenresState.value.firstOrNull { it.workId == work.id }
                ?.let { relation -> genreFacetsState.value.firstOrNull { it.id == relation.genreId }?.displayName }
            // Spec-24 T1: mirrors the real feed SQL — the Work's duration is
            // the linked library copy's Edition total (the Edition owns the
            // listening totals, ADR-0010); null until one exists.
            val durationSeconds = libraryBook?.let { book ->
                editionsState.value.firstOrNull { it.workId == book.id }?.totalDurationSeconds
            }
            WorkFeedRow(
                workId = work.id,
                mergeKey = work.mergeKey,
                title = work.title,
                author = work.author,
                seriesTitle = work.seriesTitle,
                seriesIndex = work.seriesIndex,
                coverImageUrl = work.coverImageUrl,
                addedAt = work.addedAt,
                sourceCount = workSourcesState.value.count { it.workId == work.id },
                genre = libraryGenre,
                durationSeconds = durationSeconds
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

    override suspend fun mergeWorkFacet(workId: String, authorId: String?, updatedAt: Long) {
        val current = workFacetsState.value.firstOrNull { it.workId == workId }
        val merged = WorkFacetEntity(
            workId,
            when {
                current?.canonicalAuthorId == null -> authorId
                authorId != null && updatedAt > current.updatedAt -> authorId
                else -> current.canonicalAuthorId
            },
            maxOf(updatedAt, current?.updatedAt ?: 0)
        )
        workFacetsState.update { rows -> rows.filterNot { it.workId == workId } + merged }
    }

    override suspend fun insertWorkFacetSeries(rows: List<WorkFacetSeriesEntity>) {
        workFacetSeriesState.update { current -> (current + rows).distinctBy { it.workId to it.seriesId } }
    }

    override suspend fun insertGenreFacets(rows: List<GenreFacetEntity>) {
        genreFacetsState.update { current -> current.filterNot { old -> rows.any { it.id == old.id } } + rows }
    }

    override suspend fun insertWorkGenres(rows: List<WorkGenreEntity>) {
        workGenresState.update { current -> (current + rows).distinctBy { Triple(it.workId, it.genreId, it.sourceId) } }
    }

    override suspend fun insertGenreAssertions(rows: List<GenreAssertionEntity>) {
        genreAssertionsState.update { current -> current.filterNot { old -> rows.any { it.id == old.id } } + rows }
    }

    override suspend fun genreDocumentUpdatedAt(workId: String, sourceId: String): Long? =
        genreAssertionStatesState.value.firstOrNull { it.workId == workId && it.sourceId == sourceId }?.documentUpdatedAt

    override suspend fun deleteWorkGenresForSource(workId: String, sourceId: String) {
        workGenresState.update { rows -> rows.filterNot { it.workId == workId && it.sourceId == sourceId } }
    }

    override suspend fun deleteGenreAssertionsForSource(workId: String, sourceId: String) {
        genreAssertionsState.update { rows -> rows.filterNot { it.workId == workId && it.sourceId == sourceId } }
    }

    override suspend fun upsertGenreAssertionState(row: GenreAssertionStateEntity) {
        genreAssertionStatesState.update { rows -> rows.filterNot { it.workId == row.workId && it.sourceId == row.sourceId } + row }
    }

    override suspend fun mergeEditionFacet(
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
    ) {
        val current = editionFacetsState.value.firstOrNull { it.editionId == editionId }
        val currentObservedAt = current?.availabilityObservedAtMillis
        val acceptAvailability = availabilityObservedAtMillis != null &&
            (currentObservedAt == null || availabilityObservedAtMillis > currentObservedAt)
        val merged = EditionFacetEntity(
            editionId, workId, narratorId ?: current?.narratorId, language ?: current?.language,
            durationSeconds ?: current?.durationSeconds, durationBucketId ?: current?.durationBucketId,
            chapterCount ?: current?.chapterCount, isAbridged ?: current?.isAbridged,
            if (acceptAvailability) availabilityAvailable else current?.availabilityAvailable,
            if (acceptAvailability) availabilityObservedAtMillis else current?.availabilityObservedAtMillis,
            if (acceptAvailability) availabilityTtlSeconds else current?.availabilityTtlSeconds,
            maxOf(updatedAt, current?.updatedAt ?: 0)
        )
        editionFacetsState.update { rows -> rows.filterNot { it.editionId == editionId } + merged }
    }

    override suspend fun mergeAuthorFacet(id: String, displayName: String, normalizedName: String, updatedAt: Long) {
        authorFacetsState.update { current ->
            val old = current.firstOrNull { it.id == id }
            val merged = if (old == null || updatedAt > old.updatedAt) {
                AuthorFacetEntity(id, displayName, normalizedName, updatedAt)
            } else {
                old
            }
            current.filterNot { it.id == id } + merged
        }
    }

    override suspend fun insertAuthorAliases(rows: List<AuthorAliasEntity>) {
        authorAliasesState.update { current -> (current + rows).distinctBy { Triple(it.authorId, it.normalizedAlias, it.sourceId) } }
    }

    override fun observeAuthorIndex(): Flow<List<AuthorSummary>> =
        combine(authorFacetsState, workFacetsState) { authors, workFacets ->
            authors.mapNotNull { author ->
                val count = workFacets.count { it.canonicalAuthorId == author.id }
                author.takeIf { count > 0 }?.let {
                    AuthorSummary(it.id, it.displayName, it.normalizedName, count)
                }
            }.sortedWith(compareBy(AuthorSummary::normalizedName, AuthorSummary::id))
        }

    override suspend fun searchAuthors(
        lowerBound: String,
        upperBound: String,
        limit: Int
    ): List<AuthorSummary> {
        val matchingIds = authorAliasesState.value
            .filter { it.normalizedAlias >= lowerBound && it.normalizedAlias < upperBound }
            .map { it.authorId }
            .toSet()
        return authorFacetsState.value.mapNotNull { author ->
            val count = workFacetsState.value.count { it.canonicalAuthorId == author.id }
            author.takeIf { it.id in matchingIds && count > 0 }?.let {
                AuthorSummary(it.id, it.displayName, it.normalizedName, count)
            }
        }.sortedWith(compareBy(AuthorSummary::normalizedName, AuthorSummary::id)).take(limit)
    }

    override suspend fun worksForAuthor(authorId: String): List<WorkEntity> {
        val ids = workFacetsState.value.filter { it.canonicalAuthorId == authorId }.map { it.workId }.toSet()
        return worksState.value.filter { it.id in ids }.sortedWith(compareBy(WorkEntity::title, WorkEntity::id))
    }

    override suspend fun authorForWork(workId: String): AuthorSummary? {
        val authorId = workFacetsState.value.firstOrNull { it.workId == workId }?.canonicalAuthorId ?: return null
        val author = authorFacetsState.value.firstOrNull { it.id == authorId } ?: return null
        return AuthorSummary(
            author.id,
            author.displayName,
            author.normalizedName,
            workFacetsState.value.count { it.canonicalAuthorId == authorId }
        )
    }

    override suspend fun worksMissingCanonicalAuthor(limit: Int): List<WorkEntity> {
        val indexed = workFacetsState.value.filter { it.canonicalAuthorId != null }.map { it.workId }.toSet()
        return worksState.value.filterNot { it.id in indexed }.sortedBy(WorkEntity::id).take(limit)
    }

    override fun observeGenreFacetOptions(): Flow<List<GenreFacetOption>> =
        combine(genreFacetsState, workGenresState) { genres, memberships ->
            genres.map { genre ->
                GenreFacetOption(
                    genre.id,
                    genre.displayName,
                    memberships.filter { it.genreId == genre.id }.map { it.workId }.distinct().size
                )
            }.filter { it.workCount > 0 }.sortedWith(compareBy({ it.label.lowercase() }, { it.id }))
        }

    override suspend fun genreAssertionsForWork(workId: String): List<GenreAssertionEntity> =
        genreAssertionsState.value.filter { it.workId == workId }.sortedBy { it.id }
}
