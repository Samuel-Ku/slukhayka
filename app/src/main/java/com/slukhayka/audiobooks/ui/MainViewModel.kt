package com.slukhayka.audiobooks.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.data.authors.AuthorSummary
import com.slukhayka.audiobooks.data.authors.AuthorIndex
import com.slukhayka.audiobooks.data.catalog.CatalogPerson
import com.slukhayka.audiobooks.data.catalog.CatalogSeries
import com.slukhayka.audiobooks.data.catalog.CatalogSeriesIndex
import com.slukhayka.audiobooks.data.db.*
import com.slukhayka.audiobooks.data.duration.ChapterDurationProbe
import com.slukhayka.audiobooks.data.duration.DurationEnrichment
import com.slukhayka.audiobooks.data.imports.ImportGrantStore
import com.slukhayka.audiobooks.data.imports.ImportPlan
import com.slukhayka.audiobooks.data.universe.SeriesUniverseContext
import com.slukhayka.audiobooks.data.universe.SeriesUniverses
import com.slukhayka.audiobooks.data.update.UpdateChecker
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.downloads.OfflineDownloads
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.imports.KnownBookIdentity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.data.imports.ImportPlanner
import com.slukhayka.audiobooks.data.identity.ListenerIdentity
import com.slukhayka.audiobooks.data.facets.WorkFacetFilter
import com.slukhayka.audiobooks.data.privacy.NetworkPrivacy
import com.slukhayka.audiobooks.data.privacy.PrivacyPrefs
import com.slukhayka.audiobooks.data.privacy.RouteResolution
import com.slukhayka.audiobooks.data.privacy.TransportPrivacy
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.player.AudioPlayerManager
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.ui.library.OutcomeMessages
import com.slukhayka.audiobooks.ui.library.ResumeStart
import com.slukhayka.audiobooks.ui.library.computeResumeStart
import com.slukhayka.audiobooks.ui.library.formatBytes
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
    // ADR-0002 (#140): the god repository is gone — the ViewModel composes the
    // five deep modules directly.
    val listeningState: ListeningStateStore = App.instance.listeningState

    /** ADR-0023 (spec-43 T6): pull-before-resume and push-after-save. */
    private val progressSync = App.instance.progressSync
    val libraryImport: LibraryImport = App.instance.libraryImport
    val sourceCatalog: SourceCatalog = App.instance.sourceCatalog
    val offlineDownloads: OfflineDownloads = App.instance.offlineDownloads
    val libraryEntries: LibraryEntries = App.instance.libraryEntries
    val durationEnrichment: DurationEnrichment = App.instance.durationEnrichment
    // spec-24 T8 (#169): the throttled chapter-duration probing pass — the
    // same detached-window idiom as the duration enrichment above.
    val chapterDurationProbe: ChapterDurationProbe = App.instance.chapterDurationProbe
    // Spec-25 (#171): the lazy series-universe resolution over the curated assets.
    val seriesUniverses: SeriesUniverses = App.instance.seriesUniverses
    val playerManager: AudioPlayerManager = App.instance.playerManager
    val recommendationPersonalization = App.instance.recommendationPreferences

    // Spec-36 T1 (#244): the app-release check — a pass-through module
    // reference like the fields above (the screen reads its flow directly,
    // ADR-0008; no forwarding StateFlows here).
    val updateChecker: UpdateChecker = App.instance.updateChecker

    val playerState: StateFlow<PlayerState> = playerManager.playerState

    // Spec-27 (#184) BUG-001: the raw byte count feeds the confirm dialog's
    // exact scope («Видалити 12 завантажених книг, 2,3 ГБ?»), while the
    // formatted flow drives the storage row. Both refresh together so the
    // dialog can never quote a stale size.
    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val _cacheSizeFormatted = MutableStateFlow("0 МБ")
    val cacheSizeFormatted: StateFlow<String> = _cacheSizeFormatted.asStateFlow()

    // Spec-40: these fields must be initialized before the init block starts
    // identity resolution on Dispatchers.IO. Keeping them below init made a
    // fast local-only ensure() race the constructor and write into a field
    // that Kotlin had not initialized yet.
    val listenerIdentityModule: ListenerIdentity = App.instance.listenerIdentity
    private val _listenerIdentity =
        MutableStateFlow<com.slukhayka.audiobooks.data.identity.ListenerProfile?>(null)
    val listenerIdentity: StateFlow<com.slukhayka.audiobooks.data.identity.ListenerProfile?> =
        _listenerIdentity.asStateFlow()

    init {
        refreshCacheSize()
        // Spec-40 integration — lane-a's identity module feeds lane-b's
        // resolved-profile state; the reviews block unlocks for writing the
        // moment ensure() answers (or degrades to local-only, its contract).
        attachListenerIdentity(listenerIdentityModule)
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = offlineDownloads.getAudioCacheSizeBytes()
            _cacheSizeBytes.value = bytes
            _cacheSizeFormatted.value = formatBytes(bytes)
        }
    }

    fun clearAllAudioCache() {
        viewModelScope.launch(Dispatchers.IO) {
            offlineDownloads.clearAllAudioCache()
            refreshCacheSize()
        }
    }

    // Wayfinder #39: the unified Медіатека list — every book with its playback
    // state and chapter-derived metrics. Filtering and sorting happen in the
    // screen via the pure filterAndSortLibrary; this flow only combines.
    val libraryBooks: StateFlow<List<com.slukhayka.audiobooks.ui.library.LibraryBook>> = combine(
        libraryEntries.allBooks,
        libraryEntries.recentProgress,
        libraryEntries.allChapters
    ) { books, progress, chapters ->
        com.slukhayka.audiobooks.ui.library.buildLibraryBooks(books, progress, chapters.groupBy { it.bookId })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Wayfinder #62 — the rule-based personalized Listen: local-only prefs
    // (order / hidden / dismissed) feed the pure ListenComposer, whose output
    // is the ordered, eligible block list with reasons. The version counter
    // re-runs the composer whenever the user reorders/hides/dismisses.
    //
    // Declared BEFORE listenBlocks: the composer reads nextInSeries, and a
    // property initializer must never reference a field initialized later
    // (it would read the JVM default null).
    private val listenPrefs = com.slukhayka.audiobooks.ui.library.ListenPrefsStore(getApplication())
    private val _listenPrefsVersion = MutableStateFlow(0)
    private val _nextInSeries = MutableStateFlow<AudiobookEntity?>(null)
    val nextInSeries: StateFlow<AudiobookEntity?> = _nextInSeries.asStateFlow()

    val listenBlocks: StateFlow<List<com.slukhayka.audiobooks.ui.library.ListenComposer.Block>> = combine(
        libraryBooks,
        nextInSeries,
        _listenPrefsVersion
    ) { books, seriesBook, _ ->
        com.slukhayka.audiobooks.ui.library.ListenComposer.compose(
            library = books,
            nextInSeries = seriesBook,
            prefs = listenPrefs
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The blocks the user hid — the UI shows a restore row when all are hidden. */
    val hiddenListenBlocks: StateFlow<Set<com.slukhayka.audiobooks.ui.library.ListenComposer.BlockId>> =
        _listenPrefsVersion.map { listenPrefs.hiddenBlockIds }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun moveListenBlockUp(id: com.slukhayka.audiobooks.ui.library.ListenComposer.BlockId) {
        listenPrefs.moveBlockUp(id)
        _listenPrefsVersion.value++
    }

    fun moveListenBlockDown(id: com.slukhayka.audiobooks.ui.library.ListenComposer.BlockId) {
        listenPrefs.moveBlockDown(id)
        _listenPrefsVersion.value++
    }

    fun hideListenBlock(id: com.slukhayka.audiobooks.ui.library.ListenComposer.BlockId) {
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
    val recentProgress: StateFlow<List<PlaybackProgressEntity>> = libraryEntries.recentProgress
        .map { rows ->
            rows.groupBy { it.bookId }
                .map { (_, perBook) -> perBook.maxBy { it.lastListenedAt } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedTab = MutableStateFlow(SelectedTab.LISTEN)
    val selectedTab: StateFlow<SelectedTab> = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _authorSearchResults = MutableStateFlow<List<AuthorSummary>>(emptyList())
    val authorSearchResults: StateFlow<List<AuthorSummary>> = _authorSearchResults.asStateFlow()

    private val _selectedGenreFilter = MutableStateFlow("Усі")
    val selectedGenreFilter: StateFlow<String> = _selectedGenreFilter.asStateFlow()

    private val _showFullPlayer = MutableStateFlow(false)
    val showFullPlayer: StateFlow<Boolean> = _showFullPlayer.asStateFlow()

    private val _selectedBookId = MutableStateFlow<String?>(null)
    val selectedBookId: StateFlow<String?> = _selectedBookId.asStateFlow()

    val selectedBook: StateFlow<AudiobookEntity?> = _selectedBookId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else libraryEntries.observeBook(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val selectedBookChapters: StateFlow<List<ChapterEntity>> = _selectedBookId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else libraryEntries.observeChapters(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedBookBookmarks: StateFlow<List<BookmarkEntity>> = _selectedBookId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else listeningState.observeBookmarks(id)
        }
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
        when (browserDestinationFor(com.slukhayka.audiobooks.BuildConfig.DEBUG, sourceId)) {
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
        // Spec-15 T1: same session re-hydration applies to the unified
        // catalogue union — a fresh challenge must surface the source's books
        // in «Увесь каталог» on the next Огляд visit.
        viewModelScope.launch(Dispatchers.IO) {
            sourceCatalog.refreshSourceFeeds()
            sourceCatalog.refreshUnifiedCatalog()
            // Spec-19 T2: the embedding pass runs right after the catalogue
            // sync, on the background dispatcher — never on the UI thread.
            refreshEmbeddingVectors()
        }
    }

    /**
     * Spec-13 T3 — «Додати до медіатеки» from the browser surface: the page
     * HTML captured in the session is imported through the adapter (metadata +
     * inline playlist) and plays through the app player.
     */
    fun importWebSourcePage(sourceId: String, url: String, html: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = try {
                libraryImport.importWebSourcePage(sourceId, url, html)
            } catch (e: Exception) {
                null
            }
            if (book != null) {
                probeDurationsAfterImport(book.id)
                playAudiobook(book)
                _showFullPlayer.value = true
            }
        }
    }

    /**
     * #349 — the targeted post-import duration probe: fire-and-forget on the
     * ViewModel scope, so a fresh card gets its chapter durations in seconds
     * instead of waiting for the throttled Огляд pass (#350). Best-effort —
     * a failing probe never surfaces as an import error.
     */
    private fun probeDurationsAfterImport(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chapterDurationProbe.probeBookNow(bookId) }
        }
    }

    // Series pages (spec #8 ticket T8).
    private val _selectedSeries = MutableStateFlow<SelectedSeries?>(null)
    val selectedSeries: StateFlow<SelectedSeries?> = _selectedSeries.asStateFlow()

    private val _seriesBooks = MutableStateFlow<List<AudiobookEntity>>(emptyList())
    val seriesBooks: StateFlow<List<AudiobookEntity>> = _seriesBooks.asStateFlow()

    private val _isSeriesLoading = MutableStateFlow(false)
    val isSeriesLoading: StateFlow<Boolean> = _isSeriesLoading.asStateFlow()

    // Spec-25 (#171): the resolved universe context of the CURRENT series
    // page (the header block: universe name, position, precedes/follows
    // chips). Null until a seeded series page is opened — silent.
    private val _selectedSeriesUniverse = MutableStateFlow<SeriesUniverseContext?>(null)
    val selectedSeriesUniverse: StateFlow<SeriesUniverseContext?> = _selectedSeriesUniverse.asStateFlow()

    fun openSeries(title: String, url: String) {
        _selectedSeries.value = SelectedSeries(title, url)
        _seriesBooks.value = emptyList()
        _isSeriesLoading.value = true
        _selectedSeriesUniverse.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val books = sourceCatalog.fetchSeriesBooks(url)
            _seriesBooks.value = books
            _isSeriesLoading.value = false
            // Spec-25 (#171): resolve + surface the series' universe, same
            // cache-first-then-fresher idiom as selectBook.
            _selectedSeriesUniverse.value = seriesUniverses.contextOf(title, url)
            seriesUniverses.resolveForSeries(title, url)
            _selectedSeriesUniverse.value = seriesUniverses.contextOf(title, url)
        }
    }

    fun closeSeries() {
        _selectedSeries.value = null
        _seriesBooks.value = emptyList()
        _selectedSeriesUniverse.value = null
    }

    // spec-28 (#189): the «Серії» index — every series aggregated from the
    // catalogue sections, deduplicated by URL. No new data source: the index
    // re-shapes what the catalogue parser already produces. One read-only
    // StateFlow plus an open/close pair, mirroring the series/top-100/people
    // seam. The index is a snapshot of the sections at open time — reopening
    // after a catalogue refresh shows the fresh list.
    private val _seriesIndexOpen = MutableStateFlow(false)
    val seriesIndexOpen: StateFlow<Boolean> = _seriesIndexOpen.asStateFlow()

    private val _seriesIndex = MutableStateFlow<List<CatalogSeries>>(emptyList())
    val seriesIndex: StateFlow<List<CatalogSeries>> = _seriesIndex.asStateFlow()

    fun openSeriesIndex() {
        _seriesIndex.value = CatalogSeriesIndex.aggregate(sourceCatalog.catalogSections.value)
        _seriesIndexOpen.value = true
    }

    fun closeSeriesIndex() {
        _seriesIndexOpen.value = false
        _seriesIndex.value = emptyList()
    }

    // spec-28 (#190): the «Колекції» index — every matched smart collection
    // with its books. Same seam as the series/top-100/people index: one
    // read-only StateFlow plus an open/close pair. The list is a snapshot of
    // the matched collections at open time; the module flow
    // ([SourceCatalog.smartCollections]) recomputes them on every union
    // refresh, so reopening after a refresh shows the fresh list. Tapping a
    // book resolves-and-plays exactly like the inline Огляд cards.
    private val _collectionsIndexOpen = MutableStateFlow(false)
    val collectionsIndexOpen: StateFlow<Boolean> = _collectionsIndexOpen.asStateFlow()

    private val _collectionsIndex =
        MutableStateFlow<List<com.slukhayka.audiobooks.data.collections.CollectionMatcher.MatchedCollection>>(emptyList())
    val collectionsIndex: StateFlow<List<com.slukhayka.audiobooks.data.collections.CollectionMatcher.MatchedCollection>> =
        _collectionsIndex.asStateFlow()

    fun openCollectionsIndex() {
        _collectionsIndex.value = sourceCatalog.smartCollections.value
        _collectionsIndexOpen.value = true
    }

    fun closeCollectionsIndex() {
        _collectionsIndexOpen.value = false
        _collectionsIndex.value = emptyList()
    }

    // spec-28 (#194): the «Завантаження та пам'ять» destination — the
    // storage line and the destructive delete moved off the main Медіатека
    // screen into a pushed screen reached from the ⋮ overflow menu. Same
    // one-read-only-StateFlow + open/close seam as the index screens.
    private val _storageDestinationOpen = MutableStateFlow(false)
    val storageDestinationOpen: StateFlow<Boolean> = _storageDestinationOpen.asStateFlow()

    fun openStorageDestination() {
        _storageDestinationOpen.value = true
    }

    fun closeStorageDestination() {
        _storageDestinationOpen.value = false
    }

    // spec-38 T2 (#254): the «Приватність мережі» destination — the route
    // choice (direct / custom proxy / Tor) reached from the same ⋮ overflow
    // menu, with the identical pushed-screen seam as the storage destination
    // above. Saving persists through the store and re-installs the resolved
    // route into the process-wide door immediately; an invalid address is
    // rejected with its reason and changes nothing.
    private val _privacySettingsOpen = MutableStateFlow(false)
    val privacySettingsOpen: StateFlow<Boolean> = _privacySettingsOpen.asStateFlow()

    private val _privacyPrefs = MutableStateFlow(App.instance.privacySettings.load())
    val privacyPrefs: StateFlow<PrivacyPrefs> = _privacyPrefs.asStateFlow()

    /** The rejection reason of the last save attempt; null when clean. */
    private val _privacyError = MutableStateFlow<String?>(null)
    val privacyError: StateFlow<String?> = _privacyError.asStateFlow()

    fun openPrivacySettings() {
        _privacySettingsOpen.value = true
    }

    fun closePrivacySettings() {
        _privacySettingsOpen.value = false
        _privacyError.value = null
    }

    private val _recommendationSettingsOpen = MutableStateFlow(false)
    val recommendationSettingsOpen: StateFlow<Boolean> = _recommendationSettingsOpen.asStateFlow()
    val recommendationSettings = recommendationPersonalization.settings

    fun openRecommendationSettings() { _recommendationSettingsOpen.value = true }
    fun closeRecommendationSettings() { _recommendationSettingsOpen.value = false }

    fun savePrivacyPrefs(prefs: PrivacyPrefs) {
        when (val resolution = NetworkPrivacy.resolve(prefs)) {
            is RouteResolution.Invalid -> _privacyError.value = resolution.reason
            is RouteResolution.Ok -> {
                App.instance.privacySettings.save(prefs)
                TransportPrivacy.install(prefs)
                _privacyPrefs.value = prefs
                _privacyError.value = null
            }
        }
    }

    // ADR-0023 (spec-43 T6): ⚙️ Профіль reads the settings store directly
    // (ADR-0008) — no forwarding state here.
    val progressSyncSettingsModule get() = App.instance.progressSyncSettings

    // Spec-40 #275 (t1): the ⚙️ Профіль destination — the silent listener
    // identity's one visible surface, reached from the same Медіатека ⋮
    // overflow menu. The module itself is exposed for direct screen reads
    // (ADR-0008); the ViewModel only owns the pushed-screen navigation.
    // (Named …Module: the `listenerIdentity` state initialized before init
    // carries the resolved profile for the reviews block.)
    private val _profileOpen = MutableStateFlow(false)
    val profileOpen: StateFlow<Boolean> = _profileOpen.asStateFlow()

    fun openProfileSettings() {
        _profileOpen.value = true
    }

    fun closeProfileSettings() {
        _profileOpen.value = false
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
            val books = sourceCatalog.fetchGenreBooks(url)
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
            _top100Books.value = sourceCatalog.fetchTop100()
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
            _peopleEntries.value = sourceCatalog.fetchPeople(kind.url)
            _isPeopleLoading.value = false
        }
    }

    fun closePeople() {
        _selectedPeopleKind.value = null
        _peopleEntries.value = emptyList()
    }

    // Canonical cross-source author destinations. Provider narrator pages keep
    // using [selectedPeopleKind]; author discovery always reads the local Work
    // index, so it remains instant and does not depend on a provider page.
    private val _authorsIndexOpen = MutableStateFlow(false)
    val authorsIndexOpen: StateFlow<Boolean> = _authorsIndexOpen.asStateFlow()

    private val _authorsIndexResults = MutableStateFlow<List<AuthorSummary>?>(null)
    val authorsIndexResults: StateFlow<List<AuthorSummary>?> = _authorsIndexResults.asStateFlow()

    private val _selectedCanonicalAuthor = MutableStateFlow<AuthorSummary?>(null)
    val selectedCanonicalAuthor: StateFlow<AuthorSummary?> = _selectedCanonicalAuthor.asStateFlow()

    private val _canonicalAuthorWorks = MutableStateFlow<List<WorkEntity>>(emptyList())
    val canonicalAuthorWorks: StateFlow<List<WorkEntity>> = _canonicalAuthorWorks.asStateFlow()

    private val _isCanonicalAuthorLoading = MutableStateFlow(false)
    val isCanonicalAuthorLoading: StateFlow<Boolean> = _isCanonicalAuthorLoading.asStateFlow()

    private val _canonicalAuthorLoadFailed = MutableStateFlow(false)
    val canonicalAuthorLoadFailed: StateFlow<Boolean> = _canonicalAuthorLoadFailed.asStateFlow()

    fun openAuthorsIndex() {
        _authorsIndexResults.value = null
        _authorsIndexOpen.value = true
    }

    fun openAllAuthorSearchResults() {
        _authorsIndexResults.value = _authorSearchResults.value
        _authorsIndexOpen.value = true
        val query = _searchQuery.value.trim()
        viewModelScope.launch(Dispatchers.IO) {
            val allMatches = runCatching {
                sourceCatalog.searchAuthors(query, AuthorIndex.MAX_FULL_LIST)
            }.getOrNull() ?: return@launch
            if (_authorsIndexOpen.value && _searchQuery.value.trim() == query) {
                _authorsIndexResults.value = allMatches
            }
        }
    }

    fun closeAuthorsIndex() {
        _authorsIndexOpen.value = false
        _authorsIndexResults.value = null
    }

    fun openCanonicalAuthor(author: AuthorSummary) {
        _selectedCanonicalAuthor.value = author
        _canonicalAuthorWorks.value = emptyList()
        _canonicalAuthorLoadFailed.value = false
        _isCanonicalAuthorLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val works = runCatching { sourceCatalog.authorWorks(author.id) }
            if (_selectedCanonicalAuthor.value?.id == author.id) {
                works.onSuccess { _canonicalAuthorWorks.value = it }
                    .onFailure { _canonicalAuthorLoadFailed.value = true }
                _isCanonicalAuthorLoading.value = false
            }
        }
    }

    fun closeCanonicalAuthor() {
        _selectedCanonicalAuthor.value = null
        _canonicalAuthorWorks.value = emptyList()
        _isCanonicalAuthorLoading.value = false
        _canonicalAuthorLoadFailed.value = false
    }

    fun openCanonicalAuthorWork(work: WorkEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val source = sourceCatalog.workSourcesForWork(work.id).firstOrNull() ?: return@launch
            val book = try {
                libraryImport.importFromSourceUrl(
                    source.sourceId,
                    source.sourceUrl,
                    KnownBookIdentity(work.title, work.author, coverImageUrl = work.coverImageUrl)
                )
            } catch (_: Exception) {
                null
            }
            if (book != null) {
                closeCanonicalAuthor()
                closeAuthorsIndex()
                selectBook(book.id)
            }
        }
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
            _personBooks.value = sourceCatalog.fetchPersonBooks(person.path)
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
                sourceCatalog.findNextInSeries(book)
            } catch (e: Exception) {
                null
            }
            // Stale-result guard: only apply when the hero book hasn't changed.
            if (nextInSeriesRequestId == requestId) {
                _nextInSeries.value = next
            }
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
    private var authorSearchJob: kotlinx.coroutines.Job? = null

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        globalSearchJob?.cancel()
        authorSearchJob?.cancel()
        val clean = query.trim()
        if (clean.length < 2) {
            _globalSearchResults.value = emptyList()
            _authorSearchResults.value = emptyList()
            _isGlobalSearchLoading.value = false
            return
        }
        _authorSearchResults.value = emptyList()
        authorSearchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(350)
            _authorSearchResults.value = runCatching { sourceCatalog.searchAuthors(clean) }.getOrDefault(emptyList())
        }
        _isGlobalSearchLoading.value = true
        globalSearchJob = viewModelScope.launch(Dispatchers.IO) {
            // Debounce keystrokes; cancellation keeps a stale search from
            // overwriting a newer one.
            delay(350)
            val results = try {
                sourceCatalog.searchAllSources(clean)
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
     * open the book's DETAIL page — the user reads description, narrator and
     * chapters before deciding to listen (same as a recommended card).
     */
    fun openGlobalSearchResult(result: GlobalSearchResult) {
        val source = result.sources.firstOrNull() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val book = try {
                libraryImport.importFromSourceUrl(
                    source.sourceId, source.url,
                    KnownBookIdentity(result.title, result.author, result.narrator, result.coverImageUrl)
                )
            } catch (e: Exception) {
                null
            }
            if (book != null) {
                probeDurationsAfterImport(book.id)
                selectBook(book.id)
            }
        }
    }

    /**
     * Spec-10 T4/T5 — import-and-play from any source url: fetch the book
     * page, import the Work (merge-aware), play through the app player.
     * Shared by the global-search cards and the «Нове з кожного джерела»
     * feed rows.
     */
    fun playFromSource(sourceId: String, url: String, known: KnownBookIdentity? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = try {
                libraryImport.importFromSourceUrl(sourceId, url, known)
            } catch (e: Exception) {
                null
            }
            if (book != null) {
                probeDurationsAfterImport(book.id)
                playAudiobook(book)
            }
        }
    }

    // Spec-23 T4: the endless merged feed (Paging 3) over the persisted
    // Works/Editions catalogue — one card per Work, dedup inherited from
    // merge-on-write (never re-implemented at read time). Filters live at the
    // SQL level so they compose with paging; the Pager is rebuilt when a
    // committed filter set or sort changes. Empty genre set = all genres;
    // selected ids compose with OR.

    private val _feedGenreFilters = MutableStateFlow<Set<String>>(emptySet())
    val feedGenreFilters: StateFlow<Set<String>> = _feedGenreFilters.asStateFlow()

    private val _feedSortByTitle = MutableStateFlow(false)
    val feedSortByTitle: StateFlow<Boolean> = _feedSortByTitle.asStateFlow()

    val workFeed: Flow<PagingData<WorkFeedRow>> =
        combine(_feedGenreFilters, _feedSortByTitle) { genres, byTitle -> genres to byTitle }
        .distinctUntilChanged()
        .flatMapLatest { (genres, byTitle) ->
            val filter = WorkFacetFilter(genreIds = genres)
            Pager(
                config = PagingConfig(pageSize = 30, prefetchDistance = 15, enablePlaceholders = false)
            ) {
                if (byTitle) {
                    sourceCatalog.pagedWorkFeedByTitle(filter)
                } else {
                    sourceCatalog.pagedWorkFeedRecent(filter)
                }
            }.flow
        }.cachedIn(viewModelScope)

    fun setFeedGenreFilters(genres: Set<String>) {
        _feedGenreFilters.value = genres
    }

    fun setFeedSortByTitle(byTitle: Boolean) {
        _feedSortByTitle.value = byTitle
    }

    /**
     * Spec-23 T4 — tap a feed card: resolve the Work's first Edition and
     * import-and-play from that source (the same path as the global-search
     * cards, so the Work merges into the library on the merge key).
     */
    fun openWorkFeedRow(row: WorkFeedRow) {
        viewModelScope.launch(Dispatchers.IO) {
            val source = sourceCatalog.workSourcesForWork(row.workId).firstOrNull() ?: return@launch
            playFromSource(source.sourceId, source.sourceUrl, KnownBookIdentity(row.title, row.author, coverImageUrl = row.coverImageUrl))
        }
    }

    // Spec-15 T3: the WebView catalogue hydration tool. Debug-only by
    // construction (the browser surface that exposes it is debug-gated, T2):
    // crawls a WebView source's catalogue through the live session and
    // imports every found book into Room via the shared MergeKey path. The
    // result counts surface in the browser surface so the tool reports, never
    // silently no-ops.
    private val _hydration = MutableStateFlow<com.slukhayka.audiobooks.data.catalog.SourceCatalog.HydrationResult?>(null)
    val hydrationResult: StateFlow<com.slukhayka.audiobooks.data.catalog.SourceCatalog.HydrationResult?> = _hydration.asStateFlow()

    private val _isHydrating = MutableStateFlow(false)
    val isHydrating: StateFlow<Boolean> = _isHydrating.asStateFlow()

    fun hydrateWebSourceCatalog(sourceId: String) {
        if (_isHydrating.value) return
        _isHydrating.value = true
        _hydration.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = sourceCatalog.hydrateWebSourceCatalog(sourceId)
                _hydration.value = result
                // The unified «Увесь каталог» union is re-computed after a
                // hydration so the fresh imported books surface immediately;
                // the embedding pass follows so the recommendation row sees
                // the new catalogue (spec-19 T2).
                sourceCatalog.refreshUnifiedCatalog()
                refreshEmbeddingVectors()
            } catch (e: Exception) {
                _hydration.value = com.slukhayka.audiobooks.data.catalog.SourceCatalog.HydrationResult(sourceId, found = 0, imported = 0, failed = 0)
            } finally {
                _isHydrating.value = false
            }
        }
    }

    // Spec-23 T2: the 4read full-catalog hydration pass — enumerates the
    // homepage, genre categories and series pages into the persisted
    // Works/Editions layer (merge-on-write, idempotent). Same result state as
    // the WebView tool so the debug surface reports counts the same way.
    fun hydrateFourReadCatalog() {
        if (_isHydrating.value) return
        _isHydrating.value = true
        _hydration.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = sourceCatalog.hydrateFourReadCatalog()
                _hydration.value = result
                // Refresh the ephemeral union so freshly hydrated books
                // surface immediately in the Explore feed.
                sourceCatalog.refreshUnifiedCatalog()
            } catch (e: Exception) {
                _hydration.value = com.slukhayka.audiobooks.data.catalog.SourceCatalog.HydrationResult("4read", found = 0, imported = 0, failed = 0)
            } finally {
                _isHydrating.value = false
            }
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
    /** Distinguishes an empty recommendation result from a pass still computing it. */
    private val _recommendationsReady = MutableStateFlow(false)
    val recommendationsReady: StateFlow<Boolean> = _recommendationsReady.asStateFlow()

    /** Single-flight guard: a running embedding pass is never re-launched. */
    private val _embeddingPassInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val recommendationWorks = sourceCatalog.allWorks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refreshEmbeddingVectors() {
        if (!_embeddingPassInFlight.compareAndSet(false, true)) return
        _recommendationsReady.value = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val catalog = sourceCatalog.unifiedCatalog.value
                val library = libraryBooks.value
                val worksByKey = recommendationWorks.value.associateBy { it.mergeKey.ifBlank { it.id } }
                val candidates = catalog.map { result ->
                    val work = worksByKey[result.key]
                    com.slukhayka.audiobooks.data.recommend.RecommendationEngine.Candidate(
                        id = result.key,
                        title = result.title,
                        author = result.author,
                        series = work?.seriesTitle.orEmpty()
                    )
                }
                if (candidates.isEmpty()) {
                    _catalogVectors.value = emptyMap()
                    return@launch
                }
                // Catalogue vectors go through the versioned file cache; the
                // few library signal vectors embed right here on IO too (T4:
                // never on the UI thread). The combine only reads the
                // published map.
                val vectors = embeddingService.vectorsFor(candidates, embedder).toMutableMap()
                for (signal in currentSignals(library)) {
                    if (signal.id !in vectors) vectors[signal.id] = embedder.embed(signal.text)
                }
                _catalogVectors.value = vectors
            } finally {
                _recommendationsReady.value = true
                _embeddingPassInFlight.set(false)
            }
        }
    }

    // Spec-19 Track A (on-device recommendations): the «Рекомендовано для
    // вас» row in Огляд. Signals = favourite (1.0) + completed (0.8) +
    // recently listened (0.6); candidates = the unified catalogue;
    // already-known books are excluded. Pure JVM engine behind the
    // TextEmbedder seam — no network, no telemetry (Q2/Q8).
    //
    // Q7: catalogue vectors come from the file cache keyed by catalogue
    // version (CatalogEmbeddingService) — the background pass recomputes
    // only on a version change; the row reads the cached map.
    private val embeddingCache = com.slukhayka.audiobooks.data.recommend.EmbeddingCache(
        File(application.filesDir, "embeddings")
    )
    private val embeddingService = com.slukhayka.audiobooks.data.recommend.CatalogEmbeddingService(embeddingCache)

    /**
     * The production embedder (spec-19 T3/T4): the ONNX multilingual-e5-small
     * model under assets/models/e5 (fetched by the downloadE5Model Gradle
     * task — never committed). Created lazily, so the first — and only — load
     * happens on the IO dispatcher inside the background pass, never on the
     * UI thread. When the asset is absent or fails to load, the keyword
     * baseline takes over (T2 contract: the row degrades, never crashes).
     */
    private val embedder: com.slukhayka.audiobooks.data.recommend.TextEmbedder by lazy {
        val fromAssets = try {
            val model = application.assets.open("models/e5/model.onnx").use { it.readBytes() }
            val tokenizer = application.assets.open("models/e5/tokenizer.json").use { it.readBytes() }
            com.slukhayka.audiobooks.data.recommend.OnnxEmbedder.fromBytes(
                ai.onnxruntime.OrtEnvironment.getEnvironment(), model, tokenizer
            )
        } catch (e: Exception) {
            null
        }
        fromAssets ?: com.slukhayka.audiobooks.data.recommend.KeywordEmbedder()
    }

    /**
     * The weighted listening signals (Q3): favourite 1.0 > completed 0.8 >
     * recently listened 0.6 — shared by the background pass (which embeds
     * them) and the recommendation combine (which ranks against them).
     */
    private fun currentSignals(
        library: List<com.slukhayka.audiobooks.ui.library.LibraryBook>
    ): List<com.slukhayka.audiobooks.data.recommend.RecommendationEngine.Signal> {
        return com.slukhayka.audiobooks.data.recommend.RecommendationPersonalization.signalsFor(
            behaviors = library.map { lb ->
                com.slukhayka.audiobooks.data.recommend.RecommendationPersonalization.WorkBehavior(
                    workId = lb.book.mergeKey.ifBlank { lb.book.workId.orEmpty().ifBlank { lb.book.id } },
                    title = lb.book.title,
                    author = lb.book.author,
                    genre = lb.book.genre,
                    series = lb.book.seriesTitle.orEmpty(),
                    isFavorite = lb.book.isFavorite,
                    progressFraction = lb.percent.toDouble(),
                    progressRecordedAt = lb.progress?.lastListenedAt,
                    completed = lb.isCompleted
                )
            },
            nowEpochMs = System.currentTimeMillis()
        )
    }

    val recommendationPreferences: StateFlow<List<RecommendationPreferenceEntity>> =
        recommendationPersonalization.preferences
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recommendedBooks: StateFlow<List<com.slukhayka.audiobooks.data.recommend.RecommendationEngine.Recommendation>> = combine(
        libraryBooks,
        sourceCatalog.unifiedCatalog,
        catalogVectors,
        recommendationPreferences,
        recommendationSettings
    ) { library, catalog, vectors, preferences, settings ->
        if (!settings.localPersonalizationEnabled) return@combine emptyList()
        // T2: an empty (or not-yet-computed) vector map means the background
        // pass has not finished — degrade to an empty row, never compute on
        // the UI thread and never crash.
        if (vectors.isEmpty()) return@combine emptyList()
        val reducedWorkIds = preferences
            .filter { it.kind == RecommendationPreferenceEntity.REDUCE_SIMILAR }
            .mapTo(mutableSetOf()) { it.targetKey }
        val feedbackSignals = catalog.asSequence()
            .filter { it.key in reducedWorkIds }
            .map { result ->
                com.slukhayka.audiobooks.data.recommend.RecommendationEngine.Signal(
                    id = result.key,
                    title = result.title,
                    author = result.author,
                    weight = -1.0
                )
            }
            .toList()
        val allSignals = currentSignals(library) + feedbackSignals
        // Candidates are catalogue cards only: every library book is excluded
        // anyway, and the row's job is to surface books the user does not
        // know yet. The card id is the Work key, so tapping opens the book
        // page through the same identity resolution as any other Огляд row
        // (openRecommendedBook).
        val candidates = catalog.map { result ->
            val work = recommendationWorks.value.firstOrNull {
                it.mergeKey.ifBlank { it.id } == result.key
            }
            com.slukhayka.audiobooks.data.recommend.RecommendationEngine.Candidate(
                id = result.key,
                title = result.title,
                author = result.author,
                series = work?.seriesTitle.orEmpty()
            )
        }
        val knownIds = library.flatMap { lb ->
            listOfNotNull(lb.book.id, lb.book.workId, lb.book.mergeKey.takeIf { it.isNotBlank() })
        }.toMutableSet().apply {
            addAll(preferences.filter {
                it.kind == RecommendationPreferenceEntity.HIDE_WORK ||
                    it.kind == RecommendationPreferenceEntity.REDUCE_SIMILAR
            }.map { it.targetKey })
        }
        val hiddenAuthors = preferences
            .filter { it.kind == RecommendationPreferenceEntity.HIDE_AUTHOR }
            .mapTo(mutableSetOf()) { it.targetKey }
        if (candidates.isEmpty() || allSignals.isEmpty()) return@combine emptyList()
        // Catalogue vectors come from the background pass (T2), signal
        // vectors too (T4) — nothing embeds on the UI thread. With the ONNX
        // embedder a not-yet-embedded signal is skipped for this emission
        // and the pass is kicked so the row catches up; the keyword baseline
        // is cheap enough to embed the few missing signals inline.
        val merged = vectors.toMutableMap()
        val missing = allSignals.filter { it.id !in merged }
        if (missing.isNotEmpty()) {
            if (embedder is com.slukhayka.audiobooks.data.recommend.OnnxEmbedder) {
                refreshEmbeddingVectors()
            } else {
                for (signal in missing) merged[signal.id] = embedder.embed(signal.text)
            }
        }
        com.slukhayka.audiobooks.data.recommend.RecommendationPersonalization.rank(
            candidates = candidates,
            signals = allSignals,
            vectors = merged,
            excludedWorkIds = knownIds,
            excludedAuthors = hiddenAuthors,
            weights = settings.weights,
            topN = 10
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val pendingRecommendationBookId = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /**
     * Spec-19 T4 — tapping a recommended card opens that book's page. The
     * identity resolution is the same as any other Огляд row: the Work is
     * imported from its first found source (merge-aware, shared with
     * openGlobalSearchResult) and the native book page opens — where the
     * user reads the description, chapters and reviews before deciding to
     * listen.
     */
    fun openRecommendedBook(candidateId: String) {
        val result = sourceCatalog.unifiedCatalog.value.firstOrNull { it.key == candidateId } ?: return
        val source = result.sources.firstOrNull() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val book = try {
                libraryImport.importFromSourceUrl(
                        source.sourceId, source.url,
                        KnownBookIdentity(result.title, result.author, result.narrator, result.coverImageUrl)
                    )
            } catch (e: Exception) {
                null
            }
            if (book != null) {
                pendingRecommendationBookId.set(book.id)
                recommendationPersonalization.recordDetailOpen()
                probeDurationsAfterImport(book.id)
                selectBook(book.id)
            }
        }
    }

    fun selectGenreFilter(genre: String) {
        _selectedGenreFilter.value = genre
    }

    // Related books from the book page ("Можливо, Тебе зацікавить:"). Loaded
    // per opened book by the detail screen; cleared when the selection moves.
    private val _relatedBooks = MutableStateFlow<List<AudiobookEntity>>(emptyList())
    val relatedBooks: StateFlow<List<AudiobookEntity>> = _relatedBooks.asStateFlow()

    // Spec-25 (#171): the resolved universe context of the CURRENT book (the
    // book page's «Всесвіт» line under the series pill). Null until a book
    // with a seeded series is opened — the UI stays silent (never a guess).
    private val _selectedBookUniverse = MutableStateFlow<SeriesUniverseContext?>(null)
    val selectedBookUniverse: StateFlow<SeriesUniverseContext?> = _selectedBookUniverse.asStateFlow()

    fun selectBook(bookId: String?) {
        _selectedBookId.value = bookId
        bookDetailSourceState.select(bookId)
        if (bookId != null) {
            _selectedBookUniverse.value = null
            viewModelScope.launch(Dispatchers.IO) {
                libraryEntries.refreshBookCoverAndDetails(bookId)
                // Spec-25 (#171): resolve the book's series universe lazily —
                // cache-first read, then the (idempotent) resolution, then the
                // fresher read. Best-effort: an unseeded series contributes
                // nothing.
                _selectedBookUniverse.value = seriesUniverses.contextOfBook(bookId)
                seriesUniverses.resolveForBook(bookId)
                _selectedBookUniverse.value = seriesUniverses.contextOfBook(bookId)
            }
            // Spec-15 T5: load what every source carrying the Work says about
            // it (description, rating, narrator, genres) — best-effort per
            // source, a failing source degrades to the remaining blocks.
            loadSourceProfiles(bookId)
            // Spec-23 T5: the «Джерела» section — every Edition carrying the
            // Work (source name + stream-only marker), from the persisted
            // `editions` rows, not a read-time guess.
            loadBookSources(bookId)
        } else {
            _relatedBooks.value = emptyList()
            _selectedBookUniverse.value = null
        }
    }

    // Spec-26 T9 (#183): the «wrong universe» feedback. The universe line
    // hides immediately; the re-resolution verdict either corrects the
    // cached + shared resolution or clears the complaint.
    fun reportWrongUniverse(bookId: String) {
        _selectedBookUniverse.value = null
        viewModelScope.launch(Dispatchers.IO) {
            seriesUniverses.reportWrongUniverseForBook(bookId)
            // Stale-result guard: only re-surface while the user is on this book.
            if (_selectedBookId.value == bookId) {
                _selectedBookUniverse.value = seriesUniverses.contextOfBook(bookId)
            }
        }
    }

    // Spec-23 T5: every source carrying the selected book's Work — the
    // «Джерела» section on the book page. Tapping one plays that variant
    // through [playFromSource] (per-source policy, incl. Referer/UA).
    private val bookDetailSourceState = BookDetailSourceState()
    val bookSources: StateFlow<List<SourceCatalog.WorkSourceRow>> = bookDetailSourceState.sources

    fun loadBookSources(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val rows = try {
                sourceCatalog.sourcesForBook(bookId)
            } catch (e: Exception) {
                emptyList()
            }
            // Stale-result guard: only apply while the user is still on this book.
            bookDetailSourceState.acceptSources(bookId, rows)
        }
    }

    // ---------------------------------------------------------------------
    // Spec-40 #277/#278/#280 — «Відгуки» on the book page. The store is the
    // composition-root module (null without Firebase keys → no block at
    // all); the identity seam arrives from lane-a's composition wiring via
    // [attachListenerIdentity] — until then the block stays read-only,
    // because writing needs a listener identity but reading does not.
    // ---------------------------------------------------------------------

    val listenerReviews: com.slukhayka.audiobooks.data.reviews.ListenerReviewsStore? =
        App.instance.listenerReviews

    // --- ADR-0023 (#348): «Оцінка начитки» — stars per (Work × Edition) ----

    /** Null without Firebase keys: the rating UI simply does not render. */
    val narrationRatingsStore: com.slukhayka.audiobooks.data.reviews.NarrationRatingsStore? =
        App.instance.narrationRatings

    private val _narrationRatings =
        MutableStateFlow<List<com.slukhayka.audiobooks.data.reviews.NarrationRating>>(emptyList())

    /** Every rating of one Work across its Editions; the UI filters by editionId. */
    val narrationRatings: StateFlow<List<com.slukhayka.audiobooks.data.reviews.NarrationRating>> =
        _narrationRatings.asStateFlow()

    /** Best-effort refresh of one Work's narration ratings; a failure serves silence. */
    fun loadNarrationRatings(workId: String) {
        val store = narrationRatingsStore ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _narrationRatings.value = try {
                store.getForWork(workId)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Best-effort removal of THIS listener's narration rating (#358); the
     * reload restores the truth.
     */
    fun deleteOwnNarrationRating(workId: String, editionId: String) {
        val store = narrationRatingsStore ?: return
        val profile = _listenerIdentity.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.deleteRating(workId, profile.uid, editionId) }
            loadNarrationRatings(workId)
        }
    }

    /**
     * The stored Edition id of one book row — the anchor of the current
     * card's own narration rating.
     */
    suspend fun editionIdForBook(bookId: String): String? =
        App.instance.audiobookDao.getEditionIdForBook(bookId)

    /**
     * Create or EDIT one narration rating (idempotent `set()` under the same
     * `${workId}_${uid}_${editionId}` key). Needs a listener identity; a
     * failing write stays silent and the reload restores the truth.
     */
    fun saveNarrationRating(
        workId: String,
        editionId: String,
        rating: Int,
        editingCreatedAt: Long? = null
    ) {
        val store = narrationRatingsStore ?: return
        val profile = _listenerIdentity.value ?: return
        val now = System.currentTimeMillis()
        val value = com.slukhayka.audiobooks.data.reviews.NarrationRating(
            workId = workId,
            uid = profile.uid,
            editionId = editionId,
            rating = rating,
            createdAt = editingCreatedAt ?: now,
            editedAt = if (editingCreatedAt != null) now else null
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.putRating(value) }
            loadNarrationRatings(workId)
        }
    }

    /**
     * Lane-a wiring point: attach the [com.slukhayka.audiobooks.data.identity.ListenerIdentity]
     * module once it exists in the composition root. Resolves the profile in
     * the background ([ensure] may sign in anonymously over the network);
     * a failure keeps the block read-only — degrade-never.
     */
    fun attachListenerIdentity(identity: com.slukhayka.audiobooks.data.identity.ListenerIdentity) {
        viewModelScope.launch(Dispatchers.IO) {
            val profile = runCatching { identity.ensure() }.getOrNull()
            if (profile != null) _listenerIdentity.value = profile
        }
    }

    private val _serverBookReviews = MutableStateFlow<List<com.slukhayka.audiobooks.data.reviews.ListenerReview>>(emptyList())

    /** Optimistically submitted reviews not yet confirmed online (#280). */
    private val _pendingReviews =
        MutableStateFlow<Map<String, com.slukhayka.audiobooks.data.reviews.ListenerReview>>(emptyMap())
    val pendingReviewKeys: StateFlow<Set<String>> = _pendingReviews
        .map { it.keys }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // Spec-40 #281 — the LOCAL mute list: purely per-device, server-free.
    private val _hiddenAuthors = MutableStateFlow<Set<String>>(emptySet())
    val hiddenAuthors: StateFlow<List<String>> = _hiddenAuthors
        .map { it.sorted() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Best-effort load of the mute list; a failure hides nobody. */
    fun loadHiddenAuthors() {
        viewModelScope.launch(Dispatchers.IO) {
            _hiddenAuthors.value = try {
                App.instance.audiobookDao.hiddenAuthors().map { it.authorName }.toSet()
            } catch (e: Exception) {
                emptySet()
            }
        }
    }

    /** Hide every review of one author on THIS device, immediately (#281). */
    fun hideAuthor(authorName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                App.instance.audiobookDao.hideAuthor(
                    com.slukhayka.audiobooks.data.db.HiddenReviewerEntity(
                        authorName = authorName,
                        hiddenAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
            }
            _hiddenAuthors.value = _hiddenAuthors.value + authorName
        }
    }

    /** Un-mute from ⚙️ — the action is reversible by contract (#281). */
    fun unhideAuthor(authorName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                App.instance.audiobookDao.unhideAuthor(authorName)
            } catch (e: Exception) {
            }
            _hiddenAuthors.value = _hiddenAuthors.value - authorName
        }
    }

    /** Server truth overlaid with the honest pending cards, newest first. */
    val bookReviews: StateFlow<List<com.slukhayka.audiobooks.data.reviews.ListenerReview>> = combine(
        _serverBookReviews,
        _pendingReviews,
        _hiddenAuthors
    ) { server, pending, hidden ->
        ((server.filterNot { com.slukhayka.audiobooks.data.reviews.ListenerReviewCodec.documentId(it.workId, it.uid) in pending.keys } +
            pending.values)
            .sortedByDescending { it.createdAt })
            .filterNot { it.authorName in hidden }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Best-effort refresh of one Work's reviews; offline serves the cache silently (#280). */
    fun loadReviews(workId: String) {
        val store = listenerReviews ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val fresh = try {
                store.getReviews(workId)
            } catch (e: Exception) {
                emptyList()
            }
            if (_selectedBookId.value == workId) _serverBookReviews.value = fresh
            // A queued write has reached the server when a live read carries
            // its card back — only then does the «надішлемо при мережі»
            // badge honestly retire.
            if (!fresh.isEmpty()) retireConfirmedPending(fresh)
        }
    }

    private fun retireConfirmedPending(serverReviews: List<com.slukhayka.audiobooks.data.reviews.ListenerReview>) {
        if (_pendingReviews.value.isEmpty() || !isOnline()) return
        val serverKeys = serverReviews
            .map { com.slukhayka.audiobooks.data.reviews.ListenerReviewCodec.documentId(it.workId, it.uid) }
            .toSet()
        _pendingReviews.value = _pendingReviews.value.filterKeys { it !in serverKeys }
    }

    /**
     * #277/#278 — create or EDIT one review (idempotent `set()` under the
     * same `${workId}_${uid}` key). The card appears optimistically with the
     * honest pending state; the Firestore persistence queue does the actual
     * sending (survives kill-and-restart, #280).
     */
    fun saveReview(
        workId: String,
        rating: Int,
        body: String?,
        editionTag: String?,
        editing: com.slukhayka.audiobooks.data.reviews.ListenerReview?
    ) {
        val store = listenerReviews ?: return
        val profile = _listenerIdentity.value ?: return
        val now = System.currentTimeMillis()
        val review = com.slukhayka.audiobooks.data.reviews.ListenerReview(
            workId = workId,
            uid = profile.uid,
            authorName = profile.nickname.ifBlank { profile.uid },
            rating = rating,
            body = body?.trim()?.takeIf { it.isNotEmpty() },
            editionTag = editionTag?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = editing?.createdAt ?: now,
            editedAt = if (editing != null) now else null
        )
        val key = com.slukhayka.audiobooks.data.reviews.ListenerReviewCodec.documentId(review.workId, review.uid)
        // Optimistic insert FIRST — the user sees their card instantly.
        _pendingReviews.value = _pendingReviews.value + (key to review)
        viewModelScope.launch(Dispatchers.IO) {
            val accepted = try {
                store.putReview(review)
            } catch (e: Exception) {
                false
            }
            // Online: the local ack IS the send — retire the badge. Offline:
            // true means durably queued locally; the badge stays («надішлемо
            // при мережі») until a later live refresh carries the card back.
            if (accepted && isOnline()) {
                _pendingReviews.value = _pendingReviews.value - key
            }
            loadReviews(workId)
        }
    }

    /** Best-effort delete of the listener's own review; the list re-reads the truth. */
    fun deleteOwnReview(workId: String, uid: String) {
        val store = listenerReviews ?: return
        val key = com.slukhayka.audiobooks.data.reviews.ListenerReviewCodec.documentId(workId, uid)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                store.deleteReview(workId, uid)
            } catch (e: Exception) {
                // Silent — the refresh below restores whatever is real.
            }
            _pendingReviews.value = _pendingReviews.value - key
            loadReviews(workId)
        }
    }

    /** The cheap connectivity gate behind the pending-badge honesty (#280). */
    private fun isOnline(): Boolean = runCatching {
        val cm = getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    // Spec-15 T5: the labelled per-source detail blocks of the selected book.
    val sourceProfiles: StateFlow<List<LibraryEntries.SourceProfile>> = bookDetailSourceState.profiles

    fun loadSourceProfiles(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val profiles = try {
                libraryEntries.fetchSourceProfiles(bookId)
            } catch (e: Exception) {
                emptyList()
            }
            // Stale-result guard: only apply while the user is still on this book.
            if (bookDetailSourceState.acceptProfiles(bookId, profiles)) {
                // #266 — lazy backfill from sibling carriers; local persist
                // survives restart, shared-base write-back rides the
                // existing metadata sync (best-effort).
                libraryEntries.fillMissingDescriptionFromProfiles(
                    bookId,
                    profiles.map { it.description }
                )
            }
        }
    }

    fun loadRelatedBooks(bookId: String) {
        // Clear first: switching books must never show the previous book's
        // "Можливо, Тебе зацікавить" row while the new list is in flight.
        _relatedBooks.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            val books = sourceCatalog.fetchRelatedBooks(bookId)
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
        if (autoPlay && pendingRecommendationBookId.compareAndSet(book.id, null)) {
            recommendationPersonalization.recordPlaybackStart()
        }
        startAudiobookPlayback(book, chapterIndex = chapterIndex, autoPlay = autoPlay, forceRelisten = false)
    }

    // #40 decision 1: the book page's «Почати спочатку» — always chapter 0 /
    // position 0, logged as RELISTEN, no smart-rewind of a stale pause marker.
    fun relistenBook(book: AudiobookEntity) {
        startAudiobookPlayback(book, chapterIndex = null, autoPlay = true, forceRelisten = true)
    }

    private fun startAudiobookPlayback(
        book: AudiobookEntity,
        chapterIndex: Int?,
        autoPlay: Boolean,
        forceRelisten: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedBook = libraryEntries.getBookSync(book.id) ?: book
            // ADR-0007: the chapter→track pairing rides the same fetch — the
            // player resolves chapter → track 1:1 by index.
            val playable = sourceCatalog.getPlayableChapters(updatedBook.id)
            val chapters = playable.map { it.chapter }
            // Code-review LOW: if the book was deleted while this IO fetch was
            // in flight (e.g. deleteBook on another screen), do not resurrect
            // playback for it.
            if (libraryEntries.getBookSync(updatedBook.id) == null) return@launch
            // ADR-0023 (spec-43 T6): the cloud mirror lands BEFORE the resume
            // decision — «почав на телефоні — продовж тут». A forced
            // re-listen skips it: the explicit restart intent wins.
            if (!forceRelisten) {
                runCatching { progressSync.pullBeforeResume(updatedBook.id) }
            }
            val progress = listeningState.getProgressSync(updatedBook.id)

            // ADR-0008: the ONE pure resume decision — an explicit chapter
            // request vs. the saved progress, then the ADR-0003 smart rewind
            // (same tiers, same clamp-at-zero as the in-session path). A
            // forced re-listen skips the resume decision entirely: chapter 0,
            // position 0, no smart-rewind of a stale pause marker.
            val resume = if (forceRelisten) {
                ResumeStart(chapterIndex = 0, positionSeconds = 0L)
            } else {
                computeResumeStart(
                    requestedChapter = chapterIndex,
                    progress = progress,
                    nowEpochMs = System.currentTimeMillis()
                )
            }
            // ADR-0007: the pause marker lives on the Edition's progress row;
            // cleared so the same pause never rewinds twice.
            if (progress?.lastPausedAtEpochMs != null) {
                listeningState.updatePausedAt(updatedBook.id, null)
            }

            withContext(Dispatchers.Main) {
                playerManager.loadAndPlayBook(
                    book = updatedBook,
                    chapters = chapters,
                    playable = playable,
                    initialChapterIndex = resume.chapterIndex,
                    initialPositionSeconds = resume.positionSeconds,
                    autoPlay = autoPlay,
                    forceRelisten = forceRelisten
                )
            }

            // Asynchronously refresh metadata/cover in background without delaying audio startup
            libraryEntries.refreshBookCoverAndDetails(book.id)
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
            listeningState.addBookmark(
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

    fun jumpToBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = libraryEntries.getBookSync(bookmark.bookId) ?: return@launch
            // ADR-0007: chapter→track pairing rides the same fetch.
            val playable = sourceCatalog.getPlayableChapters(bookmark.bookId)
            val chapters = playable.map { it.chapter }

            viewModelScope.launch(Dispatchers.Main) {
                if (playerState.value.currentBook?.id != bookmark.bookId) {
                    playerManager.loadAndPlayBook(
                        book = book,
                        chapters = chapters,
                        playable = playable,
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
        libraryEntries.allBooks
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
    val catalogDownloadedKeys: StateFlow<Set<String>> = libraryEntries.allBooks
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
     * hides the affordance via `catalogCardDownloadAllowed` and the repository
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
                val book = libraryImport.importFromSourceUrl(
                    source.sourceId, source.url,
                    KnownBookIdentity(result.title, result.author, result.narrator, result.coverImageUrl)
                )
                if (book != null) {
                    _downloadingBookId.value = book.id
                    probeDurationsAfterImport(book.id)
                    offlineDownloads.downloadAudiobookOffline(book.id)
                }
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Catalog download failed", e)
            } finally {
                _downloadingBookId.value = null
                _catalogDownloadingKeys.update { it - key }
                // Spec-27 (#185) BUG-013: the storage row must reflect a
                // finished download without a screen restart — the byte
                // counter refreshes after every download attempt.
                refreshCacheSize()
            }
        }
    }

    fun downloadBookOffline(bookId: String) {
        if (_downloadingBookId.value != null) return
        _downloadingBookId.value = bookId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = offlineDownloads.downloadAudiobookOffline(bookId)
                // Stale-result guard (same pattern as relatedBooks): only
                // surface the outcome while the user is still on this book —
                // otherwise the message would pop on whichever book screen is
                // open next.
                if (_selectedBookId.value == bookId) {
                    _downloadMessage.value = OutcomeMessages.downloadOutcome(result)
                }
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Offline download failed", e)
                if (_selectedBookId.value == bookId) {
                    _downloadMessage.value = OutcomeMessages.downloadFailure()
                }
            } finally {
                _downloadingBookId.value = null
                // Spec-27 (#185) BUG-013: the storage row must reflect a
                // finished download without a screen restart — the byte
                // counter refreshes after every download attempt.
                refreshCacheSize()
            }
        }
    }

    fun consumeDownloadMessage() {
        _downloadMessage.value = null
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
            libraryEntries.deleteBook(bookId)
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
            libraryEntries.removeFromLibrary(bookId)
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
                libraryImport.importLocalAudioFile(uri)
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
                val plan = libraryImport.planLocalAudioFolder(uri)
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
                val result = libraryImport.applyImportPlan(preview.plan, preview.treeUri)
                // wayfinder #48: remember the tree so a future rescan can
                // re-open it without asking the user to pick again.
                if (result.booksImported > 0 || result.duplicateFiles > 0) {
                    ImportGrantStore(getApplication()).addTreeUri(preview.treeUri)
                }
                _importPreview.value = null
                _importMessage.value = OutcomeMessages.importOutcome(result)
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
                val reports = libraryImport.rescanAllLocalFolders()
                val totals = reports.fold(LibraryImport.RescanReport("")) { acc, r ->
                    acc.copy(
                        newChapters = acc.newChapters + r.newChapters,
                        newBooks = acc.newBooks + r.newBooks,
                        missingFiles = acc.missingFiles + r.missingFiles,
                        movedFiles = acc.movedFiles + r.movedFiles,
                        duplicateFiles = acc.duplicateFiles + r.duplicateFiles
                    )
                }
                _importMessage.value = OutcomeMessages.rescanOutcome(totals)
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
