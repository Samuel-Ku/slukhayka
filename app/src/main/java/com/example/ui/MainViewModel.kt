package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.App
import com.example.data.catalog.CatalogSection
import com.example.data.db.*
import com.example.data.repository.AudiobookRepository
import com.example.player.AudioPlayerManager
import com.example.player.PlayerState
import com.example.player.SmartRewind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// Spec #8 ticket T4: the WebView is no longer a tab — it survives only as the
// "open on site" fallback on the book page. Bookmarks moved into the Library
// as a sub-tab.
//
// Spec-9 (listen-first IA): the bottom bar is Слухати · Огляд · Медіатека;
// the app always lands on Слухати (the listening panel, not the storefront).
// Enum order defines the bottom-bar order; SETTINGS has no bar entry.
enum class SelectedTab {
    LISTEN,
    EXPLORE,
    LIBRARY,
    SETTINGS
}

/** A series (cycle) opened from the Explore row (spec #8 ticket T8). */
data class SelectedSeries(
    val title: String,
    val url: String
)

// Phase 2.5 hotfix: flatMapLatest is @ExperimentalCoroutinesApi.
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Playback stack is application-scoped (see App.kt) so background playback
    // survives the Activity/ViewModel being destroyed by the system.
    val repository: AudiobookRepository = App.instance.repository
    val playerManager: AudioPlayerManager = App.instance.playerManager

    val playerState: StateFlow<PlayerState> = playerManager.playerState

    val allBooks: StateFlow<List<AudiobookEntity>> = repository.allBooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadedBooks: StateFlow<List<AudiobookEntity>> = repository.downloadedBooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteBooks: StateFlow<List<AudiobookEntity>> = repository.getFavoriteAudiobooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val listeningStats: StateFlow<List<ListeningStatEntity>> = repository.getAllListeningStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _cacheSizeFormatted = MutableStateFlow("0 MB")
    val cacheSizeFormatted: StateFlow<String> = _cacheSizeFormatted.asStateFlow()

    init {
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = repository.getAudioCacheSizeBytes()
            val mb = bytes / (1024 * 1024)
            _cacheSizeFormatted.value = "$mb MB"
        }
    }

    fun clearAllAudioCache() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearAllAudioCache()
            refreshCacheSize()
        }
    }

    fun toggleFavorite(bookId: String, isFavorite: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.toggleFavorite(bookId, isFavorite)
        }
    }

    val allBookmarks: StateFlow<List<BookmarkEntity>> = repository.allBookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Wayfinder #39: the unified Медіатека list — every book with its playback
    // state and chapter-derived metrics. Filtering and sorting happen in the
    // screen via the pure filterAndSortLibrary; this flow only combines.
    val libraryBooks: StateFlow<List<com.example.ui.library.LibraryBook>> = combine(
        repository.allBooks,
        repository.recentProgress,
        repository.allChapters
    ) { books, progress, chapters ->
        com.example.ui.library.buildLibraryBooks(books, progress, chapters.groupBy { it.bookId })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentProgress: StateFlow<List<PlaybackProgressEntity>> = repository.recentProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedTab = MutableStateFlow(SelectedTab.LISTEN)
    val selectedTab: StateFlow<SelectedTab> = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedGenreFilter = MutableStateFlow("Усі")
    val selectedGenreFilter: StateFlow<String> = _selectedGenreFilter.asStateFlow()

    private val _showFullPlayer = MutableStateFlow(false)
    val showFullPlayer: StateFlow<Boolean> = _showFullPlayer.asStateFlow()

    private val _selectedBookId = MutableStateFlow<String?>(null)
    val selectedBookId: StateFlow<String?> = _selectedBookId.asStateFlow()

    val selectedBook: StateFlow<AudiobookEntity?> = _selectedBookId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else repository.observeBook(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val selectedBookChapters: StateFlow<List<ChapterEntity>> = _selectedBookId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else repository.observeChapters(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedBookBookmarks: StateFlow<List<BookmarkEntity>> = _selectedBookId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else repository.observeBookmarks(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Explore catalogue rows (spec #8 tickets T5/T6): populated by the
    // repository's background sync of the 4read.org homepage.
    val catalogSections: StateFlow<List<CatalogSection>> = repository.catalogSections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isCatalogLoading: StateFlow<Boolean> = repository.isCatalogLoading
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // "Open on site" WebView fallback (spec #8 ticket T4).
    private val _webFallbackUrl = MutableStateFlow<String?>(null)
    val webFallbackUrl: StateFlow<String?> = _webFallbackUrl.asStateFlow()

    fun openWebFallback(url: String) {
        _webFallbackUrl.value = url
    }

    fun closeWebFallback() {
        _webFallbackUrl.value = null
    }

    // Series pages (spec #8 ticket T8).
    private val _selectedSeries = MutableStateFlow<SelectedSeries?>(null)
    val selectedSeries: StateFlow<SelectedSeries?> = _selectedSeries.asStateFlow()

    private val _seriesBooks = MutableStateFlow<List<AudiobookEntity>>(emptyList())
    val seriesBooks: StateFlow<List<AudiobookEntity>> = _seriesBooks.asStateFlow()

    private val _isSeriesLoading = MutableStateFlow(false)
    val isSeriesLoading: StateFlow<Boolean> = _isSeriesLoading.asStateFlow()

    fun openSeries(title: String, url: String) {
        _selectedSeries.value = SelectedSeries(title, url)
        _seriesBooks.value = emptyList()
        _isSeriesLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val books = repository.fetchSeriesBooks(url)
            _seriesBooks.value = books
            _isSeriesLoading.value = false
        }
    }

    fun closeSeries() {
        _selectedSeries.value = null
        _seriesBooks.value = emptyList()
    }

    // Continue-the-series block (spec-9 T4): the next volume of the currently
    // listened book's cycle, resolved on demand from the series page and
    // cached by the repository. The block hides when there is no next volume
    // or the network fails — the screen never blocks on it.
    private val _nextInSeries = MutableStateFlow<AudiobookEntity?>(null)
    val nextInSeries: StateFlow<AudiobookEntity?> = _nextInSeries.asStateFlow()

    // Written on the main thread, read inside the IO coroutine — the guard is
    // best-effort, but make the visibility contract real.
    @Volatile
    private var nextInSeriesRequestId: String? = null

    fun loadNextInSeries(book: AudiobookEntity?) {
        val requestId = book?.id
        nextInSeriesRequestId = requestId
        if (book == null || book.seriesUrl.isNullOrBlank()) {
            _nextInSeries.value = null
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val next = try {
                repository.findNextInSeries(book)
            } catch (e: Exception) {
                null
            }
            // Stale-result guard: only apply when the hero book hasn't changed.
            if (nextInSeriesRequestId == requestId) {
                _nextInSeries.value = next
            }
        }
    }

    fun refreshCatalog() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.fetchCatalogSections()
        }
    }

    fun selectTab(tab: SelectedTab) {
        _selectedTab.value = tab
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.trim().length >= 2) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.searchAudiobooksOn4Read(query)
            }
        }
    }

    fun selectGenreFilter(genre: String) {
        _selectedGenreFilter.value = genre
    }

    fun selectBook(bookId: String?) {
        _selectedBookId.value = bookId
        if (bookId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.refreshBookCoverAndDetails(bookId)
            }
        }
    }

    fun setShowFullPlayer(show: Boolean) {
        _showFullPlayer.value = show
    }

    fun playAudiobook(book: AudiobookEntity, chapterIndex: Int? = null, autoPlay: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedBook = repository.getBookSync(book.id) ?: book
            val chapters = repository.getChaptersList(updatedBook.id)
            // Code-review LOW: if the book was deleted while this IO fetch was
            // in flight (e.g. deleteBook on another screen), do not resurrect
            // playback for it.
            if (repository.getBookSync(updatedBook.id) == null) return@launch
            val progress = repository.getProgressSync(updatedBook.id)
            
            val startChapter: Int
            var startPositionSec: Long

            if (chapterIndex != null) {
                // User explicitly selected a specific chapter
                startChapter = chapterIndex
                startPositionSec = if (progress != null && progress.currentChapterIndex == chapterIndex) {
                    progress.currentPositionSeconds
                } else {
                    0L
                }
            } else {
                // Restore from saved Room playback position or default to chapter 0
                startChapter = progress?.currentChapterIndex ?: 0
                startPositionSec = progress?.currentPositionSeconds ?: 0L
            }

            // Smart rewind across restarts (wayfinder #25): the resume position
            // is rewound by how long ago the book was paused, and the marker is
            // cleared so the same pause never rewinds twice.
            progress?.lastPausedAtEpochMs?.let { pausedAt ->
                val rewindSec = SmartRewind.computeRewindSeconds(System.currentTimeMillis() - pausedAt)
                if (rewindSec > 0L && startPositionSec > rewindSec) {
                    startPositionSec -= rewindSec
                }
                repository.updatePausedAt(updatedBook.id, null)
            }

            withContext(Dispatchers.Main) {
                playerManager.loadAndPlayBook(
                    book = updatedBook,
                    chapters = chapters,
                    initialChapterIndex = startChapter,
                    initialPositionSeconds = startPositionSec,
                    autoPlay = autoPlay
                )
            }

            // Asynchronously refresh metadata/cover in background without delaying audio startup
            repository.refreshBookCoverAndDetails(book.id)
        }
    }

    fun addBookmarkAtCurrentPosition(note: String) {
        val currentState = playerState.value
        val book = currentState.currentBook ?: return
        val currentChapterIdx = currentState.currentChapterIndex
        val currentChapterTitle = if (currentState.chapters.isNotEmpty() && currentChapterIdx in currentState.chapters.indices) {
            currentState.chapters[currentChapterIdx].title
        } else "Chapter ${currentChapterIdx + 1}"

        val timestampSec = currentState.currentPositionMs / 1000L

        viewModelScope.launch(Dispatchers.IO) {
            repository.addBookmark(
                BookmarkEntity(
                    bookId = book.id,
                    chapterIndex = currentChapterIdx,
                    chapterTitle = currentChapterTitle,
                    timestampSeconds = timestampSec,
                    note = note.ifBlank { "Bookmark at ${formatTime(timestampSec)}" }
                )
            )
        }
    }

    fun deleteBookmark(bookmarkId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteBookmark(bookmarkId)
        }
    }

    fun jumpToBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = repository.getBookSync(bookmark.bookId) ?: return@launch
            val chapters = repository.getChaptersList(bookmark.bookId)

            viewModelScope.launch(Dispatchers.Main) {
                if (playerState.value.currentBook?.id != bookmark.bookId) {
                    playerManager.loadAndPlayBook(
                        book = book,
                        chapters = chapters,
                        initialChapterIndex = bookmark.chapterIndex,
                        initialPositionSeconds = bookmark.timestampSeconds,
                        autoPlay = true
                    )
                } else {
                    if (playerState.value.currentChapterIndex != bookmark.chapterIndex) {
                        playerManager.selectChapter(bookmark.chapterIndex)
                    }
                    playerManager.seekTo(bookmark.timestampSeconds * 1000L)
                    playerManager.play()
                }
                _showFullPlayer.value = true
            }
        }
    }

    fun downloadBookOffline(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.downloadAudiobookOffline(bookId)
        }
    }

    fun removeOfflineDownload(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeOfflineDownload(bookId)
        }
    }

    /**
     * Cascading book deletion — level 3 "видалити книгу та файли" (spec #8
     * tickets T2/T3, wayfinder #28). If the book is the one currently playing,
     * playback is stopped and the player state cleared; then book + chapters +
     * bookmarks + progress + local files are removed. The UI returns to the
     * catalogue. Requires an explicit confirmation dialog in the UI.
     */
    fun deleteBook(bookId: String) {
        if (playerState.value.currentBook?.id == bookId) {
            playerManager.stopAndClear()
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteBook(bookId)
        }
        if (_selectedBookId.value == bookId) {
            _selectedBookId.value = null
        }
        _showFullPlayer.value = false
    }

    /**
     * Level-1 deletion — "прибрати з медіатеки" (wayfinder #28): removes the
     * book's rows from Room but keeps the downloaded audio files on disk, so
     * the action is reversible by re-adding the book.
     */
    fun removeFromLibrary(bookId: String) {
        if (playerState.value.currentBook?.id == bookId) {
            playerManager.stopAndClear()
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeFromLibrary(bookId)
        }
        if (_selectedBookId.value == bookId) {
            _selectedBookId.value = null
        }
        _showFullPlayer.value = false
    }

    /** Imports a user-picked local audio file (spec #8 ticket T7). */
    fun importLocalAudioFile(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.importLocalAudioFile(uri)
                _importMessage.value = "Аудіофайл додано до бібліотеки"
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Local import failed", e)
                _importMessage.value = "Не вдалося імпортувати файл"
            }
        }
    }

    /** Imports a whole folder of local audiobooks (spec #8 Block 4, SAF tree). */
    fun importLocalAudioFolder(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = repository.importLocalAudioFolder(uri)
                _importMessage.value = if (result.booksImported > 0) {
                    buildString {
                        append("Імпортовано ${result.booksImported} книг (${result.filesImported} файлів)")
                        if (result.skippedFiles > 0) append(" · ${result.skippedFiles} пропущено")
                    }
                } else {
                    "У вибраній папці не знайдено аудіофайлів (mp3/m4a/ogg)"
                }
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Folder import failed", e)
                _importMessage.value = "Не вдалося імпортувати папку"
            }
        }
    }

    /** One-shot user-facing message for import outcomes (consumed by the UI). */
    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    fun consumeImportMessage() {
        _importMessage.value = null
    }

    fun importAndPlay4ReadHtml(url: String, html: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val importedBook = repository.importAudiobookFromHtml(url, html)
            viewModelScope.launch(Dispatchers.Main) {
                playAudiobook(importedBook, chapterIndex = 0, autoPlay = true)
                _showFullPlayer.value = true
            }
        }
    }

    fun importAndPlay4ReadUrl(urlOrSlug: String) {
        if (urlOrSlug.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val importedBook = repository.importAudiobookFrom4ReadUrl(urlOrSlug)
            viewModelScope.launch(Dispatchers.Main) {
                playAudiobook(importedBook, chapterIndex = 0, autoPlay = true)
                _showFullPlayer.value = true
            }
        }
    }

    // NOTE: we intentionally do NOT release the player in onCleared(). The
    // AudioPlayerManager is application-scoped (App.kt) and must keep playing
    // after the Activity is destroyed so background playback works.

    companion object {
        fun formatTime(seconds: Long): String {
            val hrs = seconds / 3600
            val mins = (seconds % 3600) / 60
            val secs = seconds % 60
            return if (hrs > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
            } else {
                String.format(Locale.US, "%02d:%02d", mins, secs)
            }
        }
    }
}
