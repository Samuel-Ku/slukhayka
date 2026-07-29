package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.*
import com.example.data.repository.AudiobookRepository
import com.example.player.AudioPlayerManager
import com.example.player.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SelectedTab {
    EXPLORE,
    FOUR_READ_WEB,
    LIBRARY,
    BOOKMARKS,
    SETTINGS
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AudiobookDatabase.getDatabase(application)
    val repository = AudiobookRepository(db.audiobookDao(), application)
    val playerManager = AudioPlayerManager(application, repository)

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

    val recentProgress: StateFlow<List<PlaybackProgressEntity>> = repository.recentProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedTab = MutableStateFlow(SelectedTab.EXPLORE)
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
            val progress = repository.getProgressSync(updatedBook.id)
            
            val startChapter: Int
            val startPositionSec: Long

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

    override fun onCleared() {
        super.onCleared()
        playerManager.release()
    }

    companion object {
        fun formatTime(seconds: Long): String {
            val hrs = seconds / 3600
            val mins = (seconds % 3600) / 60
            val secs = seconds % 60
            return if (hrs > 0) {
                String.format("%d:%02d:%02d", hrs, mins, secs)
            } else {
                String.format("%02d:%02d", mins, secs)
            }
        }
    }
}
