package com.example.testing

import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookEntity
import com.example.data.db.BookmarkEntity
import com.example.data.db.ChapterEntity
import com.example.data.db.ListeningStatEntity
import com.example.data.db.PlaybackEventEntity
import com.example.data.db.PlaybackFailureEntity
import com.example.data.db.PlaybackProgressEntity
import com.example.data.db.SourceEntity
import com.example.data.db.TombstoneEntity
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
 */
class FakeAudiobookDao(
    books: List<AudiobookEntity> = emptyList(),
    chapters: List<ChapterEntity> = emptyList()
) : AudiobookDao {

    private val booksState = MutableStateFlow(books)
    private val chaptersState = MutableStateFlow(chapters)
    private val bookmarksState = MutableStateFlow(emptyList<BookmarkEntity>())
    private val progressState = MutableStateFlow(emptyList<PlaybackProgressEntity>())
    private val sourcesState = MutableStateFlow(emptyList<SourceEntity>())
    private val statsState = MutableStateFlow(emptyList<ListeningStatEntity>())
    private val failuresState = MutableStateFlow(emptyList<PlaybackFailureEntity>())
    private val eventsState = MutableStateFlow(emptyList<PlaybackEventEntity>())
    private val tombstonesState = MutableStateFlow(emptyList<TombstoneEntity>())

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

    /** Snapshot of the persisted playback events, for assertions. */
    val savedPlaybackEvents: List<PlaybackEventEntity> get() = eventsState.value

    /** Snapshot of the persisted tombstones, for assertions. */
    val savedTombstones: List<TombstoneEntity> get() = tombstonesState.value

    // --- Audiobooks -------------------------------------------------------

    override fun getAllAudiobooks(): Flow<List<AudiobookEntity>> =
        booksState.map { books -> books.sortedBy { it.title } }

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

    override suspend fun updatePausedAt(bookId: String, pausedAt: Long?, sourceKey: String) {
        progressState.update { current ->
            current.map {
                if (it.bookId == bookId && it.sourceKey == sourceKey) it.copy(lastPausedAtEpochMs = pausedAt) else it
            }
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

    override fun getAllChapters(): Flow<List<ChapterEntity>> =
        chaptersState.map { chapters -> chapters.sortedWith(compareBy({ it.bookId }, { it.chapterIndex })) }

    override suspend fun insertChapters(chapters: List<ChapterEntity>) {
        val incomingIds = chapters.map { it.id }.toSet()
        chaptersState.update { current -> current.filterNot { it.id in incomingIds } + chapters }
    }

    override suspend fun updateChapterDownloadState(
        chapterId: String,
        isDownloaded: Boolean,
        filePath: String?
    ) {
        chaptersState.update { current ->
            current.map { chapter ->
                if (chapter.id == chapterId) {
                    chapter.copy(isDownloaded = isDownloaded, localFilePath = filePath)
                } else {
                    chapter
                }
            }
        }
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

    override suspend fun clearChaptersDownloadState(bookId: String) {
        chaptersState.update { current ->
            current.map {
                if (it.bookId == bookId) it.copy(isDownloaded = false, localFilePath = null) else it
            }
        }
    }

    override suspend fun clearAllChaptersDownloadState() {
        chaptersState.update { current ->
            current.map { it.copy(isDownloaded = false, localFilePath = null) }
        }
    }

    // --- Bookmarks --------------------------------------------------------

    override fun getBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>> =
        bookmarksState.map { bookmarks ->
            bookmarks.filter { it.bookId == bookId }.sortedBy { it.timestampSeconds }
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

    // --- Sources (spec-10 T2) ----------------------------------------------

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

    override suspend fun insertSources(sources: List<SourceEntity>) {
        val incomingIds = sources.map { it.id }.toSet()
        sourcesState.update { current -> current.filterNot { it.id in incomingIds } + sources }
    }

    override suspend fun deleteSourcesForBook(bookId: String) {
        sourcesState.update { current -> current.filterNot { it.bookId == bookId } }
    }

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

    // --- Playback progress (spec-10 T2: per-source rows) -------------------

    override fun getPlaybackProgress(bookId: String): Flow<PlaybackProgressEntity?> =
        progressState.map { progress -> progress.filter { it.bookId == bookId }.maxByOrNull { it.lastListenedAt } }

    override fun getPlaybackProgress(bookId: String, sourceKey: String): Flow<PlaybackProgressEntity?> =
        progressState.map { progress -> progress.firstOrNull { it.bookId == bookId && it.sourceKey == sourceKey } }

    override suspend fun getPlaybackProgressSync(bookId: String): PlaybackProgressEntity? =
        progressState.value.filter { it.bookId == bookId }.maxByOrNull { it.lastListenedAt }

    override suspend fun getPlaybackProgressSync(bookId: String, sourceKey: String): PlaybackProgressEntity? =
        progressState.value.firstOrNull { it.bookId == bookId && it.sourceKey == sourceKey }

    override fun getAllPlaybackProgress(): Flow<List<PlaybackProgressEntity>> =
        progressState.map { progress -> progress.sortedByDescending { it.lastListenedAt } }

    override suspend fun savePlaybackProgress(progress: PlaybackProgressEntity) {
        progressState.update { current ->
            current.filterNot { it.bookId == progress.bookId && it.sourceKey == progress.sourceKey } + progress
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

    override suspend fun getChapterByContentHash(hash: String): ChapterEntity? =
        chaptersState.value.firstOrNull { it.contentHash == hash }

    override suspend fun clearChapterContentHashes(bookId: String) {
        chaptersState.update { current ->
            current.map { ch -> if (ch.bookId == bookId) ch.copy(contentHash = null) else ch }
        }
    }

    override suspend fun insertPlaybackFailure(failure: PlaybackFailureEntity) {
        failuresState.update { current -> current + failure }
    }

    override suspend fun getRecentPlaybackFailures(limit: Int): List<PlaybackFailureEntity> =
        failuresState.value.sortedByDescending { it.timestamp }.take(limit)

    override suspend fun deletePlaybackFailure(id: Long) {
        failuresState.update { current -> current.filterNot { it.id == id } }
    }

    // --- Playback events (spec-16) -----------------------------------------

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

    override suspend fun deleteTombstone(bookId: String) {
        tombstonesState.update { current -> current.filterNot { it.bookId == bookId } }
    }
}
