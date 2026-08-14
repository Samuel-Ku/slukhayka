package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.App
import com.example.data.catalog.CatalogGenre
import com.example.data.catalog.CatalogPerson
import com.example.data.catalog.CatalogSection
import com.example.data.db.*
import com.example.data.imports.ImportGrantStore
import com.example.data.imports.ImportPlan
import com.example.data.imports.ImportPlanner
import com.example.data.repository.AudiobookRepository
import com.example.data.source.GlobalSearchResult
import com.example.data.source.catalogCardDownloadAllowed
import com.example.player.AudioPlayerManager
import com.example.player.PlayerState
import com.example.player.SmartRewind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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

/** A WebView-pattern source's browser surface (spec-13 T3). */
data class SelectedWebSource(
    val sourceId: String,
    val homeUrl: String,
    val displayName: String
)

/** A genre (category) opened from the Explore "Жанри" chips row. */
data class SelectedGenre(
    val title: String,
    val url: String
)

/** Виконавці or Автори index (from the Explore "Каталог" chips row). */
data class PeopleKind(
    val title: String,
    val url: String
)

/** One person (narrator/author) whose books list was opened. */
data class SelectedPerson(
    val name: String,
    val path: String
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

    // Wayfinder #62 — the rule-based personalized Listen: local-only prefs
    // (order / hidden / dismissed) feed the pure ListenComposer, whose output
    // is the ordered, eligible block list with reasons. The version counter
    // re-runs the composer whenever the user reorders/hides/dismisses.
    //
    // Declared BEFORE listenBlocks: the composer reads nextInSeries, and a
    // property initializer must never reference a field initialized later
    // (it would read the JVM default null).
    private val listenPrefs = com.example.ui.library.ListenPrefsStore(getApplication())
    private val _listenPrefsVersion = MutableStateFlow(0)
    private val _nextInSeries = MutableStateFlow<AudiobookEntity?>(null)
    val nextInSeries: StateFlow<AudiobookEntity?> = _nextInSeries.asStateFlow()

    val listenBlocks: StateFlow<List<com.example.ui.library.ListenComposer.Block>> = combine(
        libraryBooks,
        nextInSeries,
        _listenPrefsVersion
    ) { books, seriesBook, _ ->
        com.example.ui.library.ListenComposer.compose(
            library = books,
            nextInSeries = seriesBook,
            prefs = listenPrefs
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The blocks the user hid — the UI shows a restore row when all are hidden. */
    val hiddenListenBlocks: StateFlow<Set<com.example.ui.library.ListenComposer.BlockId>> =
        _listenPrefsVersion.map { listenPrefs.hiddenBlockIds }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun moveListenBlockUp(id: com.example.ui.library.ListenComposer.BlockId) {
        listenPrefs.moveBlockUp(id)
        _listenPrefsVersion.value++
    }

    fun moveListenBlockDown(id: com.example.ui.library.ListenComposer.BlockId) {
        listenPrefs.moveBlockDown(id)
        _listenPrefsVersion.value++
    }

    fun hideListenBlock(id: com.example.ui.library.ListenComposer.BlockId) {
        listenPrefs.hideBlock(id)
        _listenPrefsVersion.value++
    }

    fun restoreHiddenListenBlocks() {
        listenPrefs.restoreHiddenBlocks()
        _listenPrefsVersion.value++
    }

    /** «Не цікаво» on a Listen card — local taste preference, reversible. */
    fun dismissListenBook(bookId: String) {
        listenPrefs.dismissBook(bookId)
        _listenPrefsVersion.value++
    }

    // Spec-10 T2: positions are stored per source, so the raw flow can hold
    // several rows per book; the UI wants one card per Work — the latest.
    val recentProgress: StateFlow<List<PlaybackProgressEntity>> = repository.recentProgress
        .map { rows ->
            rows.groupBy { it.bookId }
                .map { (_, perBook) -> perBook.maxBy { it.lastListenedAt } }
        }
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

    // Genre navigation chips, parsed from the homepage sidebar ("Аудіокниги
    // жанру:") during the same catalogue sync that fills [catalogSections].
    val catalogGenres: StateFlow<List<CatalogGenre>> = repository.catalogGenres
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Spec-15 T2: «Відкрити на сайті» leaves the app. The 4read legacy
    // browser is removed from the UI entirely, so the book page's open-on-site
    // action always launches the system browser (ACTION_VIEW) — never an
    // in-app WebView.
    fun openWebFallback(url: String) {
        openInSystemBrowser(url)
    }

    private fun openInSystemBrowser(url: String) {
        if (url.isBlank()) return
        try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)
            )
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "No browser for $url", e)
        }
    }

    // Spec-13 T3: a WebView-pattern source's browser surface (sluhay.com
    // first; sluhayknigi joins later). A fullscreen pushed destination, NOT a
    // tab and NOT a bottom sheet (#73 decisions). The source id + home URL
    // drive the whole surface; the source's own search/browse happens
    // in-session.
    private val _selectedWebSource = MutableStateFlow<SelectedWebSource?>(null)
    val selectedWebSource: StateFlow<SelectedWebSource?> = _selectedWebSource.asStateFlow()

    /**
     * Spec-15 T2 — a WebView-source browser surface is debug-only: a debug
     * build pushes the in-app surface ([selectedWebSource]); a release build
     * opens the source in the system browser instead. The same decision
     * function gates the UI entry points (see [browserDestinationFor]).
     */
    fun openWebSource(sourceId: String, homeUrl: String, displayName: String) {
        when (browserDestinationFor(com.example.BuildConfig.DEBUG, sourceId)) {
            BrowserDestination.IN_APP_BROWSER ->
                _selectedWebSource.value = SelectedWebSource(sourceId, homeUrl, displayName)
            BrowserDestination.SYSTEM_BROWSER -> openInSystemBrowser(homeUrl)
        }
    }

    fun closeWebSource() {
        _selectedWebSource.value = null
        // Spec-13 T4: returning from the browser surface may have refreshed the
        // Cloudflare session — re-hydrate the session-bound feeds («Нове з
        // Sluhay») immediately so a fresh challenge shows the row, not the CTA.
        loadSourceFeeds()
        // Spec-15 T1: same session re-hydration applies to the unified
        // catalogue union — a fresh challenge must surface the source's books
        // in «Увесь каталог» on the next Огляд visit.
        loadUnifiedCatalog()
    }

    /**
     * Spec-13 T3 — «Додати до медіатеки» from the browser surface: the page
     * HTML captured in the session is imported through the adapter (metadata +
     * inline playlist) and plays through the app player.
     */
    fun importWebSourcePage(sourceId: String, url: String, html: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = try {
                repository.importWebSourcePage(sourceId, url, html)
            } catch (e: Exception) {
                null
            }
            if (book != null) {
                playAudiobook(book)
                _showFullPlayer.value = true
            }
        }
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

    // Genre pages ("Аудіокниги жанру:" from the homepage sidebar): one
    // full-screen book list per genre, same shape as the series page.
    private val _selectedGenre = MutableStateFlow<SelectedGenre?>(null)
    val selectedGenre: StateFlow<SelectedGenre?> = _selectedGenre.asStateFlow()

    private val _genreBooks = MutableStateFlow<List<AudiobookEntity>>(emptyList())
    val genreBooks: StateFlow<List<AudiobookEntity>> = _genreBooks.asStateFlow()

    private val _isGenreLoading = MutableStateFlow(false)
    val isGenreLoading: StateFlow<Boolean> = _isGenreLoading.asStateFlow()

    fun openGenre(title: String, url: String) {
        _selectedGenre.value = SelectedGenre(title, url)
        _genreBooks.value = emptyList()
        _isGenreLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val books = repository.fetchGenreBooks(url)
            _genreBooks.value = books
            _isGenreLoading.value = false
        }
    }

    fun closeGenre() {
        _selectedGenre.value = null
        _genreBooks.value = emptyList()
    }

    // ТОП 100 АудіоКниг (`/top-100.html`): a ranked book list.
    private val _selectedTop100 = MutableStateFlow(false)
    val selectedTop100: StateFlow<Boolean> = _selectedTop100.asStateFlow()

    private val _top100Books = MutableStateFlow<List<AudiobookEntity>>(emptyList())
    val top100Books: StateFlow<List<AudiobookEntity>> = _top100Books.asStateFlow()

    private val _isTop100Loading = MutableStateFlow(false)
    val isTop100Loading: StateFlow<Boolean> = _isTop100Loading.asStateFlow()

    fun openTop100() {
        _selectedTop100.value = true
        _top100Books.value = emptyList()
        _isTop100Loading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            _top100Books.value = repository.fetchTop100()
            _isTop100Loading.value = false
        }
    }

    fun closeTop100() {
        _selectedTop100.value = false
        _top100Books.value = emptyList()
    }

    // Виконавці / Автори index pages (`/readers.html`, `/avtors.html`).
    private val _selectedPeopleKind = MutableStateFlow<PeopleKind?>(null)
    val selectedPeopleKind: StateFlow<PeopleKind?> = _selectedPeopleKind.asStateFlow()

    private val _peopleEntries = MutableStateFlow<List<CatalogPerson>>(emptyList())
    val peopleEntries: StateFlow<List<CatalogPerson>> = _peopleEntries.asStateFlow()

    private val _isPeopleLoading = MutableStateFlow(false)
    val isPeopleLoading: StateFlow<Boolean> = _isPeopleLoading.asStateFlow()

    fun openPeople(kind: PeopleKind) {
        _selectedPeopleKind.value = kind
        _peopleEntries.value = emptyList()
        _isPeopleLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            _peopleEntries.value = repository.fetchPeople(kind.url)
            _isPeopleLoading.value = false
        }
    }

    fun closePeople() {
        _selectedPeopleKind.value = null
        _peopleEntries.value = emptyList()
    }

    // One person's books (`/xfsearch/chitaet|avtor/<name>/` — a poster grid).
    private val _selectedPerson = MutableStateFlow<SelectedPerson?>(null)
    val selectedPerson: StateFlow<SelectedPerson?> = _selectedPerson.asStateFlow()

    private val _personBooks = MutableStateFlow<List<AudiobookEntity>>(emptyList())
    val personBooks: StateFlow<List<AudiobookEntity>> = _personBooks.asStateFlow()

    private val _isPersonLoading = MutableStateFlow(false)
    val isPersonLoading: StateFlow<Boolean> = _isPersonLoading.asStateFlow()

    fun openPersonBooks(person: CatalogPerson) {
        _selectedPerson.value = SelectedPerson(person.name, person.path)
        _personBooks.value = emptyList()
        _isPersonLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            _personBooks.value = repository.fetchPersonBooks(person.path)
            _isPersonLoading.value = false
        }
    }

    fun closePersonBooks() {
        _selectedPerson.value = null
        _personBooks.value = emptyList()
    }

    // Continue-the-series block (spec-9 T4): the next volume of the currently
    // listened book's cycle, resolved on demand from the series page and
    // cached by the repository. The block hides when there is no next volume
    // or the network fails — the screen never blocks on it.
    // (State declaration lives above, next to listenBlocks — see there.)

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

    // Spec-10 T4: aggregated search results across all verified sources
    // (ephemeral — nothing is imported until the user taps a result).
    private val _globalSearchResults = MutableStateFlow<List<GlobalSearchResult>>(emptyList())
    val globalSearchResults: StateFlow<List<GlobalSearchResult>> = _globalSearchResults.asStateFlow()

    private val _isGlobalSearchLoading = MutableStateFlow(false)
    val isGlobalSearchLoading: StateFlow<Boolean> = _isGlobalSearchLoading.asStateFlow()

    private var globalSearchJob: kotlinx.coroutines.Job? = null

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        globalSearchJob?.cancel()
        val clean = query.trim()
        if (clean.length < 2) {
            _globalSearchResults.value = emptyList()
            _isGlobalSearchLoading.value = false
            return
        }
        _isGlobalSearchLoading.value = true
        globalSearchJob = viewModelScope.launch(Dispatchers.IO) {
            // Debounce keystrokes; cancellation keeps a stale search from
            // overwriting a newer one.
            delay(350)
            val results = try {
                repository.searchAllSources(clean)
            } catch (e: Exception) {
                emptyList()
            }
            _globalSearchResults.value = results
            _isGlobalSearchLoading.value = false
        }
    }

    /**
     * Spec-10 T4 — tap a global search result: import from the found source
     * (merging into the existing Work card when the merge key matches) and
     * play through the app player.
     */
    fun playGlobalSearchResult(result: GlobalSearchResult) {
        val source = result.sources.firstOrNull() ?: return
        playFromSource(source.sourceId, source.url)
    }

    /**
     * Spec-10 T4/T5 — import-and-play from any source url: fetch the book
     * page, import the Work (merge-aware), play through the app player.
     * Shared by the global-search cards and the «Нове з кожного джерела»
     * feed rows.
     */
    fun playFromSource(sourceId: String, url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = try {
                repository.importFromSourceUrl(sourceId, url)
            } catch (e: Exception) {
                null
            }
            if (book != null) {
                playAudiobook(book)
            }
        }
    }

    // Spec-10 T5: per-source «Нове з кожного джерела» rows on the Listen tab.
    val sourceFeeds: StateFlow<List<com.example.data.repository.AudiobookRepository.SourceNewFeed>> =
        repository.sourceFeeds
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isFeedsLoading: StateFlow<Boolean> = repository.isFeedsLoading
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun loadSourceFeeds() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.refreshSourceFeeds()
        }
    }

    // Spec-15 T3: the WebView catalogue hydration tool. Debug-only by
    // construction (the browser surface that exposes it is debug-gated, T2):
    // crawls a WebView source's catalogue through the live session and
    // imports every found book into Room via the shared MergeKey path. The
    // result counts surface in the browser surface so the tool reports, never
    // silently no-ops.
    private val _hydration = MutableStateFlow<AudiobookRepository.HydrationResult?>(null)
    val hydrationResult: StateFlow<AudiobookRepository.HydrationResult?> = _hydration.asStateFlow()

    private val _isHydrating = MutableStateFlow(false)
    val isHydrating: StateFlow<Boolean> = _isHydrating.asStateFlow()

    fun hydrateWebSourceCatalog(sourceId: String) {
        if (_isHydrating.value) return
        _isHydrating.value = true
        _hydration.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = repository.hydrateWebSourceCatalog(sourceId)
                _hydration.value = result
                // The unified «Увесь каталог» union is re-computed after a
                // hydration so the fresh imported books surface immediately;
                // the embedding pass follows so the recommendation row sees
                // the new catalogue (spec-19 T2).
                repository.refreshUnifiedCatalog()
                refreshEmbeddingVectors()
            } catch (e: Exception) {
                _hydration.value = AudiobookRepository.HydrationResult(sourceId, found = 0, imported = 0, failed = 0)
            } finally {
                _isHydrating.value = false
            }
        }
    }

    // Spec-15 T1: the deduplicated «Увесь каталог» union — every verified
    // source's catalogue enumeration merged into one Work card per book, with
    // a badge per carried source. Ephemeral (nothing imported until a card is
    // tapped → playFromSource); cached in the repository for the session.
    val unifiedCatalog: StateFlow<List<GlobalSearchResult>> = repository.unifiedCatalog
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isUnifiedCatalogLoading: StateFlow<Boolean> = repository.isUnifiedCatalogLoading
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun loadUnifiedCatalog() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.refreshUnifiedCatalog()
            // Spec-19 T2: the embedding pass runs right after the catalogue
            // sync, on the background dispatcher — never on the UI thread.
            refreshEmbeddingVectors()
        }
    }

    // Spec-19 T2 — the background embedding pass. Embeds every catalogue
    // book's text through the [TextEmbedder] seam on an idle (IO) dispatcher
    // and publishes the id → vector map for the recommendation row. Cache
    // hits (same catalogue version) are a file read; misses compute and
    // persist. Never throws: CatalogEmbeddingService degrades failures to a
    // smaller (or empty) map, so the row just goes quiet — no crash.
    private val _catalogVectors =
        MutableStateFlow<Map<String, FloatArray>>(emptyMap())
    val catalogVectors: StateFlow<Map<String, FloatArray>> =
        _catalogVectors.asStateFlow()

    fun refreshEmbeddingVectors() {
        viewModelScope.launch(Dispatchers.IO) {
            val catalog = unifiedCatalog.value
            val embedder = com.example.data.recommend.KeywordEmbedder()
            val candidates = catalog.map { result ->
                com.example.data.recommend.RecommendationEngine.Candidate(
                    id = result.key,
                    title = result.title,
                    author = result.author
                )
            }
            if (candidates.isEmpty()) {
                _catalogVectors.value = emptyMap()
                return@launch
            }
            _catalogVectors.value = embeddingService.vectorsFor(candidates, embedder)
        }
    }

    // Spec-19 Track A (on-device recommendations): the «Рекомендовано для
    // вас» row in Огляд. Signals = favourite (1.0) + completed (0.8) +
    // recently listened (0.6); candidates = the unified catalogue;
    // already-known books are excluded. Pure JVM engine, local keyword
    // baseline embedder behind the TextEmbedder seam — no network, no
    // telemetry (Q2/Q8).
    //
    // Q7: catalogue vectors come from the file cache keyed by catalogue
    // version (CatalogEmbeddingService) — the background pass recomputes
    // only on a version change; the row reads the cached map. Signal vectors
    // are few (library books), so they embed inline.
    private val embeddingCache = com.example.data.recommend.EmbeddingCache(
        File(application.filesDir, "embeddings")
    )
    private val embeddingService = com.example.data.recommend.CatalogEmbeddingService(embeddingCache)

    val recommendedBooks: StateFlow<List<com.example.data.recommend.RecommendationEngine.Recommendation>> = combine(
        libraryBooks,
        unifiedCatalog,
        catalogVectors
    ) { library, catalog, vectors ->
        // T2: an empty (or not-yet-computed) vector map means the background
        // pass has not finished — degrade to an empty row, never compute on
        // the UI thread and never crash.
        if (vectors.isEmpty()) return@combine emptyList()
        val embedder = com.example.data.recommend.KeywordEmbedder()
        val signals = library.flatMap { lb ->
            val weight = when {
                lb.book.isFavorite -> 1.0
                lb.isCompleted -> 0.8
                else -> 0.0
            }
            if (weight > 0.0) {
                listOf(
                    com.example.data.recommend.RecommendationEngine.Signal(
                        id = lb.book.id,
                        title = lb.book.title,
                        author = lb.book.author,
                        genre = lb.book.genre,
                        weight = weight
                    )
                )
            } else emptyList()
        }
        val recentlyListened = recentProgress.value
            .sortedByDescending { it.lastListenedAt }
            .take(5)
            .mapNotNull { row ->
                library.firstOrNull { it.book.id == row.bookId }?.let { lb ->
                    com.example.data.recommend.RecommendationEngine.Signal(
                        id = lb.book.id,
                        title = lb.book.title,
                        author = lb.book.author,
                        genre = lb.book.genre,
                        weight = 0.6
                    )
                }
            }
        val allSignals = signals + recentlyListened
        // Candidates are catalogue cards only: every library book is excluded
        // anyway, and the row's job is to surface books the user does not
        // know yet. The card id is the Work key, so tapping plays from the
        // found source (playRecommended).
        val candidates = catalog.map { result ->
            com.example.data.recommend.RecommendationEngine.Candidate(
                id = result.key,
                title = result.title,
                author = result.author
            )
        }
        val knownIds = library.map { it.book.id }.toSet()
        if (candidates.isEmpty() || allSignals.isEmpty()) return@combine emptyList()
        // Catalogue vectors come from the background pass (T2). Signal
        // vectors: the few library signals embed inline (cheap, already on
        // the reading thread of this combine) and merge into the map.
        val merged = vectors.toMutableMap()
        for (signal in allSignals) {
            if (signal.id !in merged) merged[signal.id] = embedder.embed(signal.text)
        }
        com.example.data.recommend.RecommendationEngine.recommendWithVectors(
            candidates = candidates,
            signals = allSignals,
            vectors = merged,
            excludeIds = knownIds,
            topN = 10
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Plays a recommended catalogue card from its first found source. */
    fun playRecommended(candidateId: String) {
        val result = unifiedCatalog.value.firstOrNull { it.key == candidateId } ?: return
        playGlobalSearchResult(result)
    }

    fun selectGenreFilter(genre: String) {
        _selectedGenreFilter.value = genre
    }

    // Related books from the book page ("Можливо, Тебе зацікавить:"). Loaded
    // per opened book by the detail screen; cleared when the selection moves.
    private val _relatedBooks = MutableStateFlow<List<AudiobookEntity>>(emptyList())
    val relatedBooks: StateFlow<List<AudiobookEntity>> = _relatedBooks.asStateFlow()

    fun selectBook(bookId: String?) {
        _selectedBookId.value = bookId
        if (bookId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.refreshBookCoverAndDetails(bookId)
            }
            // Spec-15 T5: load what every source carrying the Work says about
            // it (description, rating, narrator, genres) — best-effort per
            // source, a failing source degrades to the remaining blocks.
            loadSourceProfiles(bookId)
        } else {
            _relatedBooks.value = emptyList()
            _sourceProfiles.value = emptyList()
        }
    }

    // Spec-15 T5: the labelled per-source detail blocks of the selected book.
    private val _sourceProfiles = MutableStateFlow<List<AudiobookRepository.SourceProfile>>(emptyList())
    val sourceProfiles: StateFlow<List<AudiobookRepository.SourceProfile>> = _sourceProfiles.asStateFlow()

    private val _isSourceProfilesLoading = MutableStateFlow(false)
    val isSourceProfilesLoading: StateFlow<Boolean> = _isSourceProfilesLoading.asStateFlow()

    fun loadSourceProfiles(bookId: String) {
        _isSourceProfilesLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val profiles = try {
                repository.fetchSourceProfiles(bookId)
            } catch (e: Exception) {
                emptyList()
            }
            // Stale-result guard: only apply while the user is still on this book.
            if (_selectedBookId.value == bookId) {
                _sourceProfiles.value = profiles
                _isSourceProfilesLoading.value = false
            }
        }
    }

    fun loadRelatedBooks(bookId: String) {
        // Clear first: switching books must never show the previous book's
        // "Можливо, Тебе зацікавить" row while the new list is in flight.
        _relatedBooks.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            val books = repository.fetchRelatedBooks(bookId)
            // Stale-result guard: only apply while the user is still on this book.
            if (_selectedBookId.value == bookId) {
                _relatedBooks.value = books
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
                // Spec-10 T2: the marker lives on the source's progress row.
                repository.updatePausedAt(updatedBook.id, null, sourceKey = progress.sourceKey)
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

    // Offline download state: one download at a time, with explicit progress
    // and outcome feedback so the Download button never silently no-ops (a
    // catalogue book with no chapters in Room used to do nothing at all).
    private val _downloadingBookId = MutableStateFlow<String?>(null)
    val downloadingBookId: StateFlow<String?> = _downloadingBookId.asStateFlow()

    private val _downloadMessage = MutableStateFlow<String?>(null)
    val downloadMessage: StateFlow<String?> = _downloadMessage.asStateFlow()

    /** Spec-10 T6: whether the book's primary source is stream-only. */
    fun isStreamOnly(book: AudiobookEntity): Boolean = repository.isStreamOnly(book)

    // Spec-15 T4: one-tap download from a catalogue card. The card is
    // ephemeral — nothing is in Room until the user acts — so the flow imports
    // the book transparently from its primary source, then runs the shared
    // offline-download loop. Progress is keyed by the card key and derived
    // from the imported book's Room row (the repository writes downloadProgress
    // per chapter, so the card recomposes as chapters complete).
    private val _catalogDownloadingKeys = MutableStateFlow<Set<String>>(emptySet())
    val catalogDownloadingKeys: StateFlow<Set<String>> = _catalogDownloadingKeys.asStateFlow()

    /**
     * Per-card download progress (card key → 0..1). A card whose book is not
     * yet in Room reports 0.02 so the affordance visibly turns into a
     * progress indicator the moment the tap lands (the import happens first,
     * then the loop starts writing real progress).
     */
    val catalogDownloadProgress: StateFlow<Map<String, Float>> = combine(
        _catalogDownloadingKeys,
        repository.allBooks
    ) { keys, books ->
        keys.associateWith { key ->
            books.firstOrNull { book ->
                book.mergeKey == key || book.sourceUrl == key
            }?.downloadProgress?.coerceIn(0f, 1f) ?: 0.02f
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * Card keys whose book is already downloaded offline (so the card shows
     * CloudDone instead of the download affordance). Matched on the same
     * identity the union cards use — merge key, else source url.
     */
    val catalogDownloadedKeys: StateFlow<Set<String>> = repository.allBooks
        .map { books ->
            books.asSequence()
                .filter { it.isDownloaded }
                .mapNotNull { it.mergeKey.ifBlank { it.sourceUrl } }
                .filter { it.isNotBlank() }
                .toSet()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * Spec-15 T4 — one-tap download from a catalogue card: import the book
     * from its primary source (transparent merge into the Work) and download
     * the whole book offline. Stream-only sources never reach this — the card
     * hides the affordance via [catalogCardDownloadAllowed] and the repository
     * refuses in depth.
     */
    fun downloadCatalogBook(result: GlobalSearchResult) {
        val source = result.sources.firstOrNull() ?: return
        val key = result.key
        if (_catalogDownloadingKeys.value.contains(key)) return
        if (_downloadingBookId.value != null) return
        _catalogDownloadingKeys.update { it + key }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val book = repository.importFromSourceUrl(source.sourceId, source.url)
                if (book != null) {
                    _downloadingBookId.value = book.id
                    repository.downloadAudiobookOffline(book.id)
                }
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Catalog download failed", e)
            } finally {
                _downloadingBookId.value = null
                _catalogDownloadingKeys.update { it - key }
            }
        }
    }

    fun downloadBookOffline(bookId: String) {
        if (_downloadingBookId.value != null) return
        _downloadingBookId.value = bookId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = repository.downloadAudiobookOffline(bookId)
                // Stale-result guard (same pattern as relatedBooks): only
                // surface the outcome while the user is still on this book —
                // otherwise the message would pop on whichever book screen is
                // open next.
                if (_selectedBookId.value == bookId) {
                    _downloadMessage.value = when {
                        result.totalChapters == 0 ->
                            "Не вдалося знайти аудіо для завантаження. Перевірте з'єднання."
                        result.downloadedChapters == 0 ->
                            "Не вдалося завантажити книгу. Спробуйте пізніше."
                        result.downloadedChapters < result.totalChapters ->
                            "Завантажено ${result.downloadedChapters} з ${result.totalChapters} глав"
                        else -> "Книгу завантажено для офлайн-прослуховування"
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Offline download failed", e)
                if (_selectedBookId.value == bookId) {
                    _downloadMessage.value = "Не вдалося завантажити книгу"
                }
            } finally {
                _downloadingBookId.value = null
            }
        }
    }

    fun consumeDownloadMessage() {
        _downloadMessage.value = null
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

    /**
     * Imports a whole folder of local audiobooks (spec #8 Block 4, SAF tree).
     *
     * wayfinder #29: the import now runs scan → plan → confirm → apply. The
     * folder is scanned into a previewable [ImportPlan] (grouping, natural
     * sort, merge suggestions) and shown to the user; only
     * [confirmImportPreview] writes to Room.
     */
    fun importLocalAudioFolder(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val plan = repository.planLocalAudioFolder(uri)
                if (plan.books.isEmpty()) {
                    _importMessage.value = "У вибраній папці не знайдено аудіофайлів (mp3/m4a/ogg)"
                    return@launch
                }
                _importPreview.value = ImportPreviewState(plan = plan, treeUri = uri.toString())
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Folder import failed", e)
                _importMessage.value = "Не вдалося імпортувати папку"
            }
        }
    }

    /** The user confirmed the preview — apply the plan (the only writer). */
    fun confirmImportPreview() {
        val preview = _importPreview.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = repository.applyImportPlan(preview.plan, preview.treeUri)
                // wayfinder #48: remember the tree so a future rescan can
                // re-open it without asking the user to pick again.
                if (result.booksImported > 0 || result.duplicateFiles > 0) {
                    ImportGrantStore(getApplication()).addTreeUri(preview.treeUri)
                }
                _importPreview.value = null
                _importMessage.value = if (result.booksImported > 0) {
                    buildString {
                        append("Імпортовано ${result.booksImported} книг (${result.filesImported} файлів)")
                        if (result.duplicateFiles > 0) append(" · ${result.duplicateFiles} дублікатів пропущено")
                        if (result.skippedFiles > 0) append(" · ${result.skippedFiles} не вдалося прочитати")
                    }
                } else if (result.duplicateFiles > 0) {
                    "Всі файли вже в бібліотеці (${result.duplicateFiles} дублікатів пропущено)"
                } else {
                    "Імпорт завершено"
                }
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Preview import failed", e)
                _importMessage.value = "Не вдалося імпортувати папку"
            }
        }
    }

    /** Dismisses the preview without applying — zero trace left (wayfinder #29). */
    fun dismissImportPreview() {
        _importPreview.value = null
    }

    /** Accepts a T0/T2 merge suggestion in the preview. */
    fun acceptMergeInPreview(bookId: String) {
        val preview = _importPreview.value ?: return
        _importPreview.value = preview.copy(plan = ImportPlanner.acceptMerge(preview.plan, bookId))
    }

    /** Rejects a suggestion — the pair becomes a remembered NEVER_MATCH. */
    fun rejectMergeInPreview(bookId: String) {
        val preview = _importPreview.value ?: return
        _importPreview.value = preview.copy(plan = ImportPlanner.rejectMerge(preview.plan, bookId))
    }

    /**
     * Re-scans every previously imported local folder (wayfinder #42): walks
     * the SAF trees, diffs by content hash, adds new chapters/books, and
     * reports missing/moved/duplicate files. Nothing is ever deleted.
     */
    fun rescanLocalFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val reports = repository.rescanAllLocalFolders()
                val totals = reports.fold(AudiobookRepository.RescanReport("")) { acc, r ->
                    acc.copy(
                        newChapters = acc.newChapters + r.newChapters,
                        newBooks = acc.newBooks + r.newBooks,
                        missingFiles = acc.missingFiles + r.missingFiles,
                        movedFiles = acc.movedFiles + r.movedFiles,
                        duplicateFiles = acc.duplicateFiles + r.duplicateFiles
                    )
                }
                _importMessage.value = buildString {
                    append("Пересканування завершено")
                    when {
                        totals.newChapters > 0 || totals.newBooks > 0 -> {
                            append(": +${totals.newChapters} глав")
                            if (totals.newBooks > 0) append(" (${totals.newBooks} нових книг)")
                        }
                        else -> append(" — змін не знайдено")
                    }
                    if (totals.missingFiles > 0) append(" · ${totals.missingFiles} файлів зникло")
                    if (totals.movedFiles > 0) append(" · ${totals.movedFiles} перейменовано")
                    if (totals.duplicateFiles > 0) append(" · ${totals.duplicateFiles} дублікатів пропущено")
                }
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Re-scan failed", e)
                _importMessage.value = "Не вдалося пересканувати папки"
            }
        }
    }

    /** One-shot user-facing message for import outcomes (consumed by the UI). */
    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    /** The pending smart-import preview (wayfinder #29), null when none. */
    private val _importPreview = MutableStateFlow<ImportPreviewState?>(null)
    val importPreview: StateFlow<ImportPreviewState?> = _importPreview.asStateFlow()

    fun consumeImportMessage() {
        _importMessage.value = null
    }

    // Spec-15 T2: the 4read legacy browser (the only caller of the two
    // import-and-play doors below) is removed from the UI. The seam-tested
    // import doors themselves stay behind the repository seam (spec-14 T2–T4:
    // importAudiobookFromHtml / importAudiobookFrom4ReadUrl) for fixtures.

    // NOTE: we intentionally do NOT release the player in onCleared(). The
    // AudioPlayerManager is application-scoped (App.kt) and must keep playing
    // after the Activity is destroyed so background playback works.

    /** The pending smart-import preview (wayfinder #29) plus its tree uri. */
    data class ImportPreviewState(
        val plan: ImportPlan,
        val treeUri: String
    )

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
