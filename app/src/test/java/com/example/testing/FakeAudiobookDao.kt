package com.example.testing

import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookEntity
import com.example.data.db.BookmarkEntity
import com.example.data.db.ChapterEntity
import com.example.data.db.ListeningStatEntity
import com.example.data.db.PlaybackProgressEntity
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
    private val statsState = MutableStateFlow(emptyList<ListeningStatEntity>())

    /** Snapshot of the persisted playback progress, for assertions. */
    val savedProgress: List<PlaybackProgressEntity> get() = progressState.value

    /** Snapshot of the persisted bookmarks, for assertions. */
    val savedBookmarks: List<BookmarkEntity> get() = bookmarksState.value

    /** Snapshot of the persisted listening statistics, for assertions. */
    val savedListeningStats: List<ListeningStatEntity> get() = statsState.value

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

    // --- Playback progress ------------------------------------------------

    override fun getPlaybackProgress(bookId: String): Flow<PlaybackProgressEntity?> =
        progressState.map { progress -> progress.firstOrNull { it.bookId == bookId } }

    override suspend fun getPlaybackProgressSync(bookId: String): PlaybackProgressEntity? =
        progressState.value.firstOrNull { it.bookId == bookId }

    override fun getAllPlaybackProgress(): Flow<List<PlaybackProgressEntity>> =
        progressState.map { progress -> progress.sortedByDescending { it.lastListenedAt } }

    override suspend fun savePlaybackProgress(progress: PlaybackProgressEntity) {
        progressState.update { current ->
            current.filterNot { it.bookId == progress.bookId } + progress
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
}
