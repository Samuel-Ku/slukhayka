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
import com.slukhayka.audiobooks.data.authors.AuthorIdentity
import com.slukhayka.audiobooks.data.authors.authorMatchesOrEmpty
import com.slukhayka.audiobooks.data.catalog.CatalogPerson
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.catalog.CatalogSeries
import com.slukhayka.audiobooks.data.catalog.CatalogSeriesIndex
import com.slukhayka.audiobooks.data.catalog.CatalogFetchResult
import com.slukhayka.audiobooks.data.db.*
import com.slukhayka.audiobooks.data.duration.ChapterDurationProbe
import com.slukhayka.audiobooks.data.duration.DurationEnrichment
import com.slukhayka.audiobooks.data.imports.ImportGrantStore
import com.slukhayka.audiobooks.data.imports.ImportPlan
import com.slukhayka.audiobooks.data.universe.SeriesUniverseContext
import com.slukhayka.audiobooks.data.universe.SeriesUniverses
import com.slukhayka.audiobooks.data.update.UpdateChecker
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.catalog.CatalogAvailabilityPolicy
import com.slukhayka.audiobooks.data.downloads.OfflineDownloads
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.imports.KnownBookIdentity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.data.imports.ImportPlanner
import com.slukhayka.audiobooks.data.identity.ListenerIdentity
import com.slukhayka.audiobooks.data.facets.WorkFacetFilter
import com.slukhayka.audiobooks.data.personbookmarks.PersonBookmarks
import com.slukhayka.audiobooks.data.privacy.NetworkPrivacy
import com.slukhayka.audiobooks.data.privacy.PrivacyPrefs
import com.slukhayka.audiobooks.data.privacy.RouteResolution
import com.slukhayka.audiobooks.data.privacy.TransportPrivacy
import com.slukhayka.audiobooks.data.reviews.ReviewRemoteResult
import com.slukhayka.audiobooks.data.reviews.ReviewWriteReceipt
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.SourceAccessCandidate
import com.slukhayka.audiobooks.data.source.SourceAccessMode
import com.slukhayka.audiobooks.data.source.SourceIds
import com.slukhayka.audiobooks.data.source.fourReadSearchUrl
import com.slukhayka.audiobooks.data.source.SourceAccessPolicy
import com.slukhayka.audiobooks.data.source.SourceSelectionCoordinator
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.AndroidSourceCookieProvider
import com.slukhayka.audiobooks.data.source.cookieHeadersFor
import com.slukhayka.audiobooks.data.source.headersFor
import com.slukhayka.audiobooks.data.metadata.FirestoreBookMetaStore
import com.slukhayka.audiobooks.data.metadata.BookProfile
import com.slukhayka.audiobooks.data.metadata.ProfileChapter
import com.slukhayka.audiobooks.data.metadata.VerifiedSourceProfile
import com.slukhayka.audiobooks.player.AudioPlayerManager
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.player.PlaybackErrorKind
import com.slukhayka.audiobooks.player.SmartRetryMemo
import com.slukhayka.audiobooks.player.SmartRetryPolicy
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.ui.library.OutcomeMessages
import com.slukhayka.audiobooks.ui.library.ResumeStart
import com.slukhayka.audiobooks.ui.library.computeResumeStart
import com.slukhayka.audiobooks.ui.library.formatBytes
import com.slukhayka.audiobooks.ui.catalog.CatalogCardAction
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionCoordinator
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionGateway
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionState
import com.slukhayka.audiobooks.ui.catalog.CatalogCardSource
import com.slukhayka.audiobooks.ui.catalog.editionScopedCatalogSources
import com.slukhayka.audiobooks.ui.catalog.CatalogCardTarget
import com.slukhayka.audiobooks.ui.catalog.CatalogBrowserFocusReturn
import com.slukhayka.audiobooks.ui.catalog.MediaRangeValidator
import com.slukhayka.audiobooks.ui.catalog.catalogSessionCandidates
import com.slukhayka.audiobooks.ui.catalog.hasUsableSourceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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

/** A WebView-pattern source's browser surface (spec-13 T3 / spec-42 #425). */
data class SelectedWebSource(
    val sourceId: String,
    val homeUrl: String,
    val displayName: String,
    val recoveryBookId: String? = null,
    val recoveryChapterIndex: Int? = null,
    val recoveryPositionMs: Long = 0L,
    val captureTop100: Boolean = false,
    val captureSeriesUrl: String? = null
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
    val path: String,
    val role: PersonRole
)

/** One-shot visible outcome of submitting a listener review. */
enum class ReviewSaveResult {
    PUBLISHED,
    QUEUED,
    FAILED
}

/** One submission outcome, scoped to both its Work and deterministic review document. */
data class ReviewSaveEvent(
    val workId: String,
    val documentId: String,
    val generation: Long,
    val result: ReviewSaveResult
)

internal data class ReviewSubmission(
    val workId: String,
    val documentId: String,
    val generation: Long
) {
    fun event(result: ReviewSaveResult): ReviewSaveEvent = ReviewSaveEvent(
        workId = workId,
        documentId = documentId,
        generation = generation,
        result = result
    )
}

/** Rejects acknowledgements superseded by a newer write to the same review document. */
internal class ReviewSubmissionGate {
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    fun begin(workId: String, documentId: String): ReviewSubmission = ReviewSubmission(
        workId = workId,
        documentId = documentId,
        generation = generations.computeIfAbsent(documentId) { AtomicLong() }.incrementAndGet()
    )

    fun isLatest(submission: ReviewSubmission): Boolean =
        generations[submission.documentId]?.get() == submission.generation
}

internal data class ReviewLoadRequest(
    val workId: String,
    val generation: Long
)

/** Prevents an older fetch of one Work from replacing a newer server snapshot. */
internal class ReviewLoadGate {
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    fun begin(workId: String): ReviewLoadRequest = ReviewLoadRequest(
        workId = workId,
        generation = generations.computeIfAbsent(workId) { AtomicLong() }.incrementAndGet()
    )

    fun isLatest(request: ReviewLoadRequest): Boolean =
        generations[request.workId]?.get() == request.generation
}

/**
 * Delivers the local queue result before waiting for Firestore's backend Task.
 * Remote failure remains a visible event; caller cancellation still escapes.
 */
internal suspend fun followReviewWrite(
    receipt: ReviewWriteReceipt,
    onVisibleResult: suspend (ReviewSaveResult) -> Unit,
    onRemoteResult: suspend (ReviewRemoteResult) -> Unit
) {
    when (receipt) {
        ReviewWriteReceipt.Rejected -> onVisibleResult(ReviewSaveResult.FAILED)
        is ReviewWriteReceipt.Queued -> {
            onVisibleResult(ReviewSaveResult.QUEUED)
            val remoteResult = receipt.awaitRemote()
            onRemoteResult(remoteResult)
            onVisibleResult(
                if (remoteResult == ReviewRemoteResult.PUBLISHED) {
                    ReviewSaveResult.PUBLISHED
                } else {
                    ReviewSaveResult.FAILED
                }
            )
        }
    }
}

/** Listener reviews belong to a Work; legacy Editions fall back to their own id. */
internal fun reviewWorkIdFor(editionId: String, workId: String?): String =
    workId?.takeIf { it.isNotBlank() } ?: editionId

// Phase 2.5 hotfix: flatMapLatest is @ExperimentalCoroutinesApi.
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Playback stack is application-scoped (see App.kt) so background playback
    // survives the Activity/ViewModel being destroyed by the system.
    // ADR-0002 (#140): the god repository is gone — the ViewModel composes the
    // deep modules directly.
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
    // Spec-42 #431: shared metadata store for verified profile publish
    val sharedMetaStore: FirestoreBookMetaStore? = App.instance.sharedMetaStore
    val playerManager: AudioPlayerManager = App.instance.playerManager
    val recommendationPersonalization = App.instance.recommendationPreferences

    // #399/#400 — person bookmarks module (ADR-0008: screens read Flows directly).
    val personBookmarks: PersonBookmarks = App.instance.personBookmarks

    // Spec-36 T1 (#244): the app-release check — a pass-through module
    // reference like the fields above (the screen reads its flow directly,
    // ADR-0008; no forwarding StateFlows here).
    val updateChecker: UpdateChecker = App.instance.updateChecker

    val playerState: StateFlow<PlayerState> = playerManager.playerState

    private val catalogCardCoordinator = CatalogCardActionCoordinator(
        scope = viewModelScope,
        gateway = object : CatalogCardActionGateway<AudiobookEntity> {
            override suspend fun savedBook(target: CatalogCardTarget): AudiobookEntity? =
                sourceCatalog.resumableLibraryBookForWork(
                    target.workId,
                    target.preferredEditionId,
                    target.mergeKey
                )

            override suspend fun sourceCandidates(target: CatalogCardTarget) =
                catalogSourceCandidates(target)

            override suspend fun sourceCandidates(
                target: CatalogCardTarget,
                savedBook: AudiobookEntity?
            ): List<SourceSelectionCoordinator.SourceCandidate> {
                val savedEditionId = savedBook?.let { editionIdForBook(it.id) }
                return catalogSourceCandidates(
                    if (savedEditionId.isNullOrBlank()) target
                    else target.copy(preferredEditionId = savedEditionId)
                )
            }

            override suspend fun import(
                target: CatalogCardTarget,
                source: SourceEntity
            ): AudiobookEntity? = libraryImport.importFromSourceUrl(
                source.type,
                source.url,
                KnownBookIdentity(
                    title = target.title,
                    author = target.author,
                    narrator = target.narrator,
                    coverImageUrl = target.coverImageUrl
                )
            )

            override suspend fun open(book: AudiobookEntity): Boolean {
                probeDurationsAfterImport(book.id)
                selectBook(book.id)
                return true
            }

            override suspend fun prepare(
                book: AudiobookEntity,
                source: SourceEntity?
            ): Boolean = preflightCatalogMedia(book, source)

            override suspend fun play(book: AudiobookEntity, source: SourceEntity?): Boolean {
                val playing = startCatalogPlaybackAndAwait(book, source)
                if (playing && source != null) {
                    publishVerifiedCatalogProfile(book, source)
                }
                return playing
            }

            override suspend fun recordAvailability(book: AudiobookEntity, available: Boolean) {
                sourceCatalog.recordBookAvailability(book, available)
            }

            // #469 (spec #462 ID7) — reached only before the browser door:
            // one sluhayua search (title + author) matched by MergeKey. The
            // resolver owns the request discipline and the availability-TTL
            // cache; a null keeps the honest «потребує браузер» door.
            override suspend fun crossResolveDirectSource(
                target: CatalogCardTarget
            ): SourceEntity? {
                val match = App.instance.sluhayuaCrossResolve.resolve(
                    title = target.title,
                    author = target.author,
                    mergeKey = target.mergeKey
                ) ?: return null
                return SourceEntity(
                    id = "sluhayua-cross",
                    bookId = "",
                    type = com.slukhayka.audiobooks.data.source.SourceIds.SLUHAYUA,
                    url = match.url,
                    streamOnly = false
                )
            }

            // #477 — one best-effort direct page fetch before the browser
            // door: a resolved non-empty page imports silently through the
            // MergeKey/upsert door; challenge/empty → null, the door stays.
            // No hidden WebView — one transport request per tap at most.
            override suspend fun importBrowserSourceDirect(
                target: CatalogCardTarget,
                source: SourceEntity
            ): AudiobookEntity? = libraryImport.importBrowserSourceDirectPage(
                source.type,
                source.url,
                KnownBookIdentity(
                    title = target.title,
                    author = target.author,
                    narrator = target.narrator,
                    coverImageUrl = target.coverImageUrl
                )
            )
        },
        sourceProbe = SourceSelectionCoordinator.SourceProbe { source, remainingMs ->
            // A book-page 2xx proves only that HTML was returned (a challenge
            // page is often 200 too), not that its audio is playable. The
            // import path resolves the physical track and play performs the
            // range preflight immediately before ExoPlayer, which is the only
            // meaningful availability check for a catalogue card.
            SourceSelectionCoordinator.ProbeResult.Success
        }
    )
    val catalogCardActionState: StateFlow<CatalogCardActionState> = catalogCardCoordinator.state
    private val catalogPreflightKeys = ConcurrentHashMap.newKeySet<String>()
    private val catalogPreflightSlots = Semaphore(
        CatalogAvailabilityPolicy.MAX_PARALLEL_SOURCES
    )

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
        // #394 — notification button actions are handled by the App-scoped
        // DownloadNotificationActionCoordinator (works with no Activity);
        // the VM-side collect was removed with that change.
        // Spec-45 (#405) T8 (#496): re-evaluate the one-time bilingual prompt
        // on every process start — a previous session may have synced the
        // first English books before the listener ever saw the question
        // (idempotent: the persisted marker lets it fire at most once ever).
        viewModelScope.launch(Dispatchers.IO) {
            delay(1_500)
            App.instance.bilingualPrompt.evaluate()
        }
    }

    /**
     * Spec-45 (#405) T8 (#496): called when a catalogue sync completes — the
     * sync may have written the first known-English rendition, so the
     * one-time bilingual prompt re-evaluates (idempotent, fires at most once
     * ever; [BilingualPromptEngine.evaluate] never re-asks after an answer).
     */
    fun onCatalogueSynced() {
        viewModelScope.launch(Dispatchers.IO) {
            App.instance.bilingualPrompt.evaluate()
        }
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

    // Spec-15 T2: direct sources may still leave the app through ACTION_VIEW.
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

    /** Opens a source's browser session when the source contract requires it. */
    fun openWebSource(
        sourceId: String,
        homeUrl: String,
        displayName: String,
        recoveryBookId: String? = null,
        recoveryChapterIndex: Int? = null,
        recoveryPositionMs: Long = 0L
    ) {
        when (browserDestinationFor(com.slukhayka.audiobooks.BuildConfig.DEBUG, sourceId)) {
            BrowserDestination.IN_APP_BROWSER ->
                _selectedWebSource.value = SelectedWebSource(
                    sourceId, homeUrl, displayName,
                    recoveryBookId, recoveryChapterIndex, recoveryPositionMs
                )
            BrowserDestination.SYSTEM_BROWSER -> openInSystemBrowser(homeUrl)
        }
    }

    /**
     * Spec-42 #440 — open the 4read catalogue pre-filled with [query] in the
     * in-app browser (release-accessible per ADR-0027). The query is URL-encoded
     * exactly like [FourReadAdapter.search]; 4read resolves to the in-app
     * browser in every build via [browserDestinationFor].
     */
    fun open4readSearch(query: String) {
        openWebSource(sourceId = "4read", homeUrl = fourReadSearchUrl(query), displayName = "4read")
    }

    fun closeWebSource() {
        _selectedWebSource.value = null
        CatalogBrowserFocusReturn.publishAfterBrowserClose()
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
     * #456 — a session-bound source is opened only after the listener asks
     * for it from a card's explicit recovery action.  The current source URL
     * is kept as the destination so a completed challenge returns them to the
     * book they selected, while the WebView cookie jar remains source-scoped.
     */
    fun openCatalogBrowserRequired() {
        val required = catalogCardActionState.value as? CatalogCardActionState.BrowserRequired
            ?: return
        if (browserDestinationFor(com.slukhayka.audiobooks.BuildConfig.DEBUG, required.source.type) == BrowserDestination.IN_APP_BROWSER) {
            CatalogBrowserFocusReturn.remember(required.target.cardKey)
        }
        openWebSource(
            sourceId = required.source.type,
            homeUrl = required.source.url,
            displayName = sourceDisplayName(required.source.type)
        )
    }

    /**
     * Spec-13 T3 — «Додати до медіатеки» from the browser surface: the page
     * HTML captured in the session is imported through the adapter (metadata +
     * inline playlist) and plays through the app player.
     */
    fun importWebSourcePage(
        sourceId: String,
        url: String,
        html: String,
        capturedAudioUrls: List<String> = emptyList(),
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = try {
                libraryImport.importWebSourcePage(sourceId, url, html, capturedAudioUrls)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) { onComplete(book != null) }
            if (book != null) {
                probeDurationsAfterImport(book.id)
                playAudiobook(book)
                _showFullPlayer.value = true
            }
        }
    }

    /** Opens 4read's in-app browser as an explicit user action — “Відкрити браузер”. */
    fun open4ReadBrowser() {
        _selectedWebSource.value = SelectedWebSource(
            sourceId = "4read",
            homeUrl = "https://4read.org/",
            displayName = "4read"
        )
    }

    /** Opens 4read search with prefilled Work title — “Знайти на 4read”. */
    fun open4ReadSearch(workTitle: String) {
        val encoded = java.net.URLEncoder.encode(workTitle.trim(), "UTF-8")
        val searchUrl = "https://4read.org/index.php?do=search&subaction=search&story=$encoded"
        _selectedWebSource.value = SelectedWebSource(
            sourceId = "4read",
            homeUrl = searchUrl,
            displayName = "4read"
        )
    }

    /** Opens 4read's in-app browser as an explicit recovery action — “Оновити через браузер”. */
    fun open4ReadRecovery(bookId: String, chapterIndex: Int, positionMs: Long) {
        // The full player is an overlay above the app destination tree.  Leave
        // it before publishing the browser route; otherwise recovery did open
        // the WebView, but it remained invisible and untappable behind Player.
        _showFullPlayer.value = false
        viewModelScope.launch(Dispatchers.IO) {
            val book = libraryEntries.getBookSync(bookId)
            val sourceUrl = try {
                com.slukhayka.audiobooks.data.imports.BrowserRecoveryCoordinator.recoveryEntryUrl(
                    App.instance.audiobookDao,
                    bookId
                )
            } catch (_: Exception) {
                book?.sourceUrl?.takeIf { it.contains("4read.org") } ?: "https://4read.org/"
            }
            withContext(Dispatchers.Main) {
                _selectedWebSource.value = SelectedWebSource(
                    sourceId = "4read",
                    homeUrl = sourceUrl,
                    displayName = "4read",
                    recoveryBookId = bookId,
                    recoveryChapterIndex = chapterIndex,
                    recoveryPositionMs = positionMs
                )
            }
        }
    }

    // #471 (spec #462, Implementation Decision 8) — розумний retry плеєра.

    /** The bounded re-resolve memo (ADR-0019); process-wide like AutoRepairMemo. */
    private val smartRetryMemo = SmartRetryMemo.shared

    /** The overall re-resolve budget of ONE retry pass (the card-tap budget). */
    private val smartRetryResolveTimeoutMs = 10_000L

    /**
     * Розумний retry плеєра — тап «Повторити» у плеєрі:
     *
     *  (a) **локальний файл розділу існує → грати з нього** — завантажена
     *      копія перемагає мертвий remote (регресійний контракт: «скачана
     *      книга грає після смерті remote»);
     *  (b) **інакше один обмежений ре-резолв джерел книги** через
     *      LOCAL → DIRECT → UNKNOWN ([SourceAccessPolicy.order]) з
     *      cross-source пошуком sluhayua (#469) перед відмовою; BROWSER
     *      джерело ніколи не відкривається неявно (ADR-0026);
     *  (c) **нічого не знайдено → чесне «Книга недоступна»**
     *      ([AudioPlayerManager.reportRetryUnavailable]); явні браузерні
     *      двері для будь-якого BROWSER джерела рендерить PlayerScreen
     *      ([browserRecoverySources]).
     *
     * Послідовність спроб обмежена [SmartRetryMemo] (ADR-0019): невдача
     * блокує автоматичний ре-резолв на негативне вікно, успіх не повторюється
     * у позитивному. Локальний файл і браузерні двері memo не читають —
     * явна людська втеча ніколи не є циклом.
     */
    fun smartRetryPlayback(bookId: String, chapterIndex: Int, positionMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val playable = runCatching { sourceCatalog.getPlayableChapters(bookId) }.getOrDefault(emptyList())
            val track = playable.getOrNull(chapterIndex)?.track
            when (
                SmartRetryPolicy.decide(
                    localFileReady = SmartRetryPolicy.localFileReady(track?.localFilePath),
                    canReResolve = smartRetryMemo.canAttempt(bookId)
                )
            ) {
                SmartRetryPolicy.Decision.PlayLocal -> withContext(Dispatchers.Main) {
                    // prepare грає локальний файл (buildMediaItem) і ніколи
                    // не стукає у мертвий стрім, поки копія існує.
                    playerManager.prepareChapter(chapterIndex, positionMs, autoPlay = true)
                }

                SmartRetryPolicy.Decision.Unavailable -> withContext(Dispatchers.Main) {
                    // Bounded: спроба вже витрачена — чесна відмова одразу,
                    // без повторного обходу джерел.
                    playerManager.reportRetryUnavailable()
                }

                SmartRetryPolicy.Decision.ReResolve -> resolveAndReplay(bookId, chapterIndex, positionMs)
            }
        }
    }

    /** (b) — один обмежений прохід ре-резолву, потім чесна відмова. */
    private suspend fun resolveAndReplay(bookId: String, chapterIndex: Int, positionMs: Long) {
        val book = runCatching { libraryEntries.getBookSync(bookId) }.getOrNull()
        if (book == null) {
            smartRetryMemo.recordFailure(bookId)
            withContext(Dispatchers.Main) { playerManager.reportRetryUnavailable() }
            return
        }
        val identity = KnownBookIdentity(
            title = book.title,
            author = book.author,
            narrator = book.narrator,
            coverImageUrl = book.coverImageUrl
        )
        // LOCAL → DIRECT → UNKNOWN у стабільному порядку; BROWSER виключено —
        // браузер лише за явною дією слухача (ADR-0026).
        val sources = runCatching { App.instance.audiobookDao.getSourcesForBookSync(bookId) }
            .getOrDefault(emptyList())
        val candidates = SourceAccessPolicy.order(
            sources.map { SourceAccessCandidate(it.type, url = it.url) }
        ).filter { it.accessMode != SourceAccessMode.BROWSER && it.url.isNotBlank() }
        var resolved: AudiobookEntity? = null
        for (candidate in candidates) {
            resolved = runCatching {
                withTimeoutOrNull(smartRetryResolveTimeoutMs) {
                    libraryImport.importFromSourceUrl(candidate.sourceId, candidate.url, identity)
                }
            }.getOrNull()
            if (resolved != null) break
        }
        // Cross-source search (#469): один sluhayua-запит перед відмовою —
        // той самий резолвер, що й перед браузерними дверима картки.
        if (resolved == null) {
            val mergeKey = book.mergeKey.ifBlank { MergeKey.keyFor(book.title, book.author) }
            val match = runCatching {
                App.instance.sluhayuaCrossResolve.resolve(
                    title = book.title,
                    author = book.author,
                    mergeKey = mergeKey
                )
            }.getOrNull()
            if (match != null) {
                resolved = runCatching {
                    withTimeoutOrNull(smartRetryResolveTimeoutMs) {
                        libraryImport.importFromSourceUrl(
                            com.slukhayka.audiobooks.data.source.SourceIds.SLUHAYUA,
                            match.url,
                            identity
                        )
                    }
                }.getOrNull()
            }
        }
        if (resolved == null) {
            smartRetryMemo.recordFailure(bookId)
            withContext(Dispatchers.Main) { playerManager.reportRetryUnavailable() }
            return
        }
        smartRetryMemo.recordSuccess(bookId)
        val freshPlayable = runCatching { sourceCatalog.getPlayableChapters(bookId) }.getOrDefault(emptyList())
        if (freshPlayable.isEmpty()) {
            withContext(Dispatchers.Main) { playerManager.reportRetryUnavailable() }
            return
        }
        withContext(Dispatchers.Main) {
            playerManager.loadAndPlayBook(
                book = resolved,
                chapters = freshPlayable.map { it.chapter },
                playable = freshPlayable,
                initialChapterIndex = chapterIndex.coerceIn(0, (freshPlayable.size - 1).coerceAtLeast(0)),
                initialPositionSeconds = positionMs / 1000L,
                autoPlay = true
            )
            _showFullPlayer.value = true
        }
    }

    /**
     * #471 — BROWSER джерела книги, для яких існує явні двері: 4read завжди
     * має двері (пошук із підставленою назвою), будь-яке інше браузерне
     * джерело — лише зі збереженим URL (домушок не вигадується).
     */
    suspend fun browserRecoverySources(bookId: String): List<String> {
        val book = runCatching { libraryEntries.getBookSync(bookId) }.getOrNull() ?: return emptyList()
        val sources = runCatching { App.instance.audiobookDao.getSourcesForBookSync(bookId) }
            .getOrDefault(emptyList())
        val candidateIds = sources.map { it.type } +
            listOfNotNull(sourceIdForUrl(book.sourceUrl).takeIf { book.sourceUrl.isNotBlank() })
        return SmartRetryPolicy.browserDoorSourceIds(candidateIds).filter { sourceId ->
            sourceId == SourceIds.FOUR_READ ||
                sources.any { it.type == sourceId && it.url.isNotBlank() }
        }
    }

    /**
     * #471 — відкриває браузер ЛЮБОГО BROWSER джерела як явну дію
     * відновлення (узагальнює [open4ReadRecovery] beyond 4read).
     */
    fun openBrowserRecovery(bookId: String, sourceId: String, chapterIndex: Int, positionMs: Long) {
        if (sourceId == SourceIds.FOUR_READ) {
            open4ReadRecovery(bookId, chapterIndex, positionMs)
            return
        }
        // The full player is an overlay above the app destination tree — the
        // browser route must be visible (same reason as open4ReadRecovery).
        _showFullPlayer.value = false
        viewModelScope.launch(Dispatchers.IO) {
            val entry = runCatching {
                com.slukhayka.audiobooks.data.imports.BrowserRecoveryCoordinator.recoveryEntryUrl(
                    App.instance.audiobookDao,
                    bookId,
                    sourceId
                )
            }.getOrDefault("")
            if (entry.isBlank()) return@launch
            withContext(Dispatchers.Main) {
                _selectedWebSource.value = SelectedWebSource(
                    sourceId = sourceId,
                    homeUrl = entry,
                    displayName = sourceDisplayName(sourceId),
                    recoveryBookId = bookId,
                    recoveryChapterIndex = chapterIndex,
                    recoveryPositionMs = positionMs
                )
            }
        }
    }

    fun recoverWebSourcePage(
        bookId: String,
        sourceId: String,
        url: String,
        html: String,
        capturedAudioUrls: List<String> = emptyList(),
        chapterIndex: Int,
        positionMs: Long,
        onComplete: (Boolean) -> Unit = {},
        onStructureMismatch: (com.slukhayka.audiobooks.data.imports.BrowserRecoveryCoordinator.Outcome.StructureMismatch) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val coordinator = com.slukhayka.audiobooks.data.imports.BrowserRecoveryCoordinator(
                dao = App.instance.audiobookDao,
                libraryImport = libraryImport,
                profileStore = sharedMetaStore,
                playbackVerifier = com.slukhayka.audiobooks.data.imports.BrowserRecoveryCoordinator.PlaybackVerifier { _, trackUrl ->
                    val fetcher = HttpFetcher()
                    val len = try { fetcher.headContentLength(trackUrl, headersFor(sourceId, trackUrl)) } catch (_: Exception) { null }
                    len != null
                },
                cleanProbe = com.slukhayka.audiobooks.data.imports.BrowserRecoveryCoordinator.CleanProbe { trackUrl ->
                    val fetcher = HttpFetcher()
                    val len = try { fetcher.headContentLength(trackUrl) } catch (_: Exception) { null }
                    len != null
                }
            )
            val outcome = try {
                coordinator.recover(bookId, sourceId, url, html, capturedAudioUrls, chapterIndex, positionMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                when (outcome) {
                    is com.slukhayka.audiobooks.data.imports.BrowserRecoveryCoordinator.Outcome.Success -> {
                        val playable = sourceCatalog.getPlayableChapters(outcome.book.id)
                        val ch = playable.getOrNull(outcome.resumeChapterIndex)
                        if (ch?.track?.url?.startsWith("http", ignoreCase = true) == true) {
                            playerManager.loadAndPlayBook(
                                book = outcome.book,
                                chapters = playable.map { it.chapter },
                                playable = playable,
                                initialChapterIndex = outcome.resumeChapterIndex,
                                initialPositionSeconds = outcome.resumePositionMs / 1000L,
                                autoPlay = true
                            )
                            _showFullPlayer.value = true
                        }
                        // #431 — publishing is strictly post-verdict and
                        // best-effort. The publisher repeats a clean,
                        // cookie-free transport probe, so the private WebView
                        // session never becomes shared metadata.
                        val editionId = editionIdForBook(outcome.book.id)
                        val recoveredSource = App.instance.audiobookDao.getSourcesForBookSync(outcome.book.id)
                            .firstOrNull { it.type == sourceId && it.url == url }
                        if (editionId != null && recoveredSource != null) {
                            val profile = BookProfile(
                                title = outcome.book.title,
                                author = outcome.book.author,
                                narrator = outcome.book.narrator,
                                description = outcome.book.description,
                                chapters = playable.mapNotNull { chapter ->
                                    chapter.track?.url
                                        ?.takeIf { it.startsWith("http", ignoreCase = true) }
                                        ?.let { trackUrl ->
                                            ProfileChapter(chapter.chapter.title, trackUrl, chapter.chapter.durationSeconds)
                                        }
                                },
                                totalDurationSeconds = outcome.book.totalDurationSeconds.takeIf { it > 0L }
                            )
                            runCatching {
                                App.instance.verifiedSourceProfilePublisher.publish(
                                    VerifiedSourceProfile(
                                        sourceId = sourceId,
                                        editionId = editionId,
                                        playerOpened = true,
                                        source = SourceAccessCandidate(sourceId, url = url),
                                        profile = profile
                                    )
                                )
                            }
                        }
                        if (sourceId == "4read") {
                            offlineDownloads.confirmBrowserRefresh(outcome.book.id)
                        }
                        onComplete(true)
                    }
                    is com.slukhayka.audiobooks.data.imports.BrowserRecoveryCoordinator.Outcome.Failure -> onComplete(false)
                    is com.slukhayka.audiobooks.data.imports.BrowserRecoveryCoordinator.Outcome.StructureMismatch -> onStructureMismatch(outcome)
                    null -> onComplete(false)
                }
            }
        }
    }

    fun repairConfirmedWebSourceStructure(
        bookId: String,
        sourceId: String,
        url: String,
        html: String,
        capturedAudioUrls: List<String>,
        onComplete: (Boolean) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val activeBookId = playerState.value.currentBook?.id
            val repaired = try {
                libraryImport.repairConfirmedWebSourceStructure(bookId, sourceId, url, html, capturedAudioUrls)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (repaired != null) probeDurationsAfterImport(repaired.id)
            val repairedPlayable = repaired
                ?.takeIf { it.id == activeBookId }
                ?.let { repairedBook ->
                    sourceCatalog.getPlayableChapters(repairedBook.id)
                }
            withContext(Dispatchers.Main) {
                // A confirmed topology repair replaces the tracks under this
                // Edition. If it is already loaded, the manager still holds
                // the old playlist/URLs in memory; reload it paused so the
                // mini-player cannot retry a removed stale track.
                if (repaired != null && repairedPlayable != null) {
                    playerManager.loadAndPlayBook(
                        book = repaired,
                        chapters = repairedPlayable.map { it.chapter },
                        playable = repairedPlayable,
                        initialChapterIndex = 0,
                        initialPositionSeconds = 0,
                        autoPlay = false
                    )
                }
                onComplete(repaired != null)
            }
        }
    }

    /**
     * #349 — targeted duration probe, fired after import and when opening an
     * older card with unknown chapter lengths. It bypasses the throttled Огляд
     * pass (#350); a failure is best-effort and never blocks the screen.
     */
    private fun probeDurationsAfterImport(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chapterDurationProbe.probeBookNow(bookId) }
        }
    }

    // Series pages (spec #8 ticket T8).
    private val _selectedSeries = MutableStateFlow<SelectedSeries?>(null)
    val selectedSeries: StateFlow<SelectedSeries?> = _selectedSeries.asStateFlow()

    private val seriesLoader = KeyedCatalogLoader<SelectedSeries, AudiobookEntity>(viewModelScope) {
        sourceCatalog.fetchSeriesBooksResult(it.url)
    }
    val seriesBooks: StateFlow<List<AudiobookEntity>> = seriesLoader.items
    val isSeriesLoading: StateFlow<Boolean> = seriesLoader.isLoading
    val seriesLoadFailed: StateFlow<Boolean> = seriesLoader.failed

    // Spec-25 (#171): the resolved universe context of the CURRENT series
    // page (the header block: universe name, position, precedes/follows
    // chips). Null until a seeded series page is opened — silent.
    private val _selectedSeriesUniverse = MutableStateFlow<SeriesUniverseContext?>(null)
    val selectedSeriesUniverse: StateFlow<SeriesUniverseContext?> = _selectedSeriesUniverse.asStateFlow()
    private var seriesUniverseJob: Job? = null

    fun openSeries(title: String, url: String) {
        val selected = SelectedSeries(title, url)
        _selectedSeries.value = selected
        seriesLoader.open(selected)
        _selectedSeriesUniverse.value = null
        seriesUniverseJob?.cancel()
        seriesUniverseJob = viewModelScope.launch(Dispatchers.IO) {
            // The universe lookup is an independent cache/refresh sidecar, so
            // it has the same route-key guard as the catalogue loader.
            val cached = seriesUniverses.contextOf(title, url)
            if (_selectedSeries.value == selected) {
                _selectedSeriesUniverse.value = cached
            }
            seriesUniverses.resolveForSeries(title, url)
            val refreshed = seriesUniverses.contextOf(title, url)
            if (_selectedSeries.value == selected) {
                _selectedSeriesUniverse.value = refreshed
            }
        }
    }

    fun closeSeries() {
        _selectedSeries.value = null
        seriesLoader.close()
        seriesUniverseJob?.cancel()
        seriesUniverseJob = null
        _selectedSeriesUniverse.value = null
    }

    /** Explicit 4read browser door for a cycle whose direct page is challenged. */
    fun openSeriesInBrowser() {
        val series = _selectedSeries.value ?: return
        _selectedWebSource.value = SelectedWebSource(
            sourceId = "4read",
            homeUrl = series.url,
            displayName = "4read",
            captureSeriesUrl = series.url
        )
    }

    fun importCapturedSeries(html: String, onComplete: (Boolean) -> Unit) {
        val series = _selectedSeries.value
        if (series == null) {
            onComplete(false)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = sourceCatalog.importCapturedSeriesBooksResult(series.url, html)
            withContext(Dispatchers.Main) {
                if (result is CatalogFetchResult.Success && _selectedSeries.value == series) {
                    seriesLoader.open(series)
                }
                onComplete(result is CatalogFetchResult.Success)
            }
        }
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
        viewModelScope.launch(Dispatchers.IO) {
            val catalogueSeries = CatalogSeriesIndex.aggregate(sourceCatalog.catalogSections.value)
            val localSeries = sourceCatalog.localSeriesIndex()
            val merged = (catalogueSeries + localSeries)
                .distinctBy { it.url }
                .sortedBy { it.title.lowercase() }
            if (_seriesIndexOpen.value) _seriesIndex.value = merged
        }
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

    // Spec-45 (#405) T6 (#494): the «Мови контенту» destination (⚙️ overflow)
    // and the Огляд «Мова» chip both write the SAME persisted store whose
    // flow the feed Pager and every SourceCatalog surface already read — one
    // preference source (US6/US7/US8).
    private val _contentLanguagesOpen = MutableStateFlow(false)
    val contentLanguagesOpen: StateFlow<Boolean> = _contentLanguagesOpen.asStateFlow()

    /** The checked content languages — the store's live flow (both on = «Усі»). */
    val contentLanguages: StateFlow<Set<String>> = App.instance.contentLanguagePrefs.languages

    fun openContentLanguages() {
        _contentLanguagesOpen.value = true
    }

    fun closeContentLanguages() {
        _contentLanguagesOpen.value = false
    }

    fun setContentLanguages(languages: Set<String>) {
        App.instance.contentLanguagePrefs.setLanguages(languages)
    }

    /** Огляд chip (US8): Усі → Українська → English → Усі. */
    fun cycleContentLanguages() {
        val current = App.instance.contentLanguagePrefs.languages.value
        App.instance.contentLanguagePrefs.setLanguages(
            when {
                current == setOf("uk", "en") -> setOf("uk")
                current == setOf("uk") -> setOf("en")
                else -> setOf("uk", "en")
            }
        )
    }

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

    private val genreLoader = KeyedCatalogLoader<SelectedGenre, AudiobookEntity>(viewModelScope) {
        sourceCatalog.fetchGenreBooksResult(it.url)
    }
    val genreBooks: StateFlow<List<AudiobookEntity>> = genreLoader.items
    val isGenreLoading: StateFlow<Boolean> = genreLoader.isLoading
    val genreLoadFailed: StateFlow<Boolean> = genreLoader.failed

    fun openGenre(title: String, url: String) {
        val selected = SelectedGenre(title, url)
        _selectedGenre.value = selected
        genreLoader.open(selected)
    }

    fun closeGenre() {
        _selectedGenre.value = null
        genreLoader.close()
    }

    // ТОП 100 АудіоКниг (`/top-100.html`): a ranked book list.
    private val _selectedTop100 = MutableStateFlow(false)
    val selectedTop100: StateFlow<Boolean> = _selectedTop100.asStateFlow()

    private val top100Loader = KeyedCatalogLoader<Unit, AudiobookEntity>(viewModelScope) {
        sourceCatalog.fetchTop100Result()
    }
    val top100Books: StateFlow<List<AudiobookEntity>> = top100Loader.items
    val isTop100Loading: StateFlow<Boolean> = top100Loader.isLoading
    val top100LoadFailed: StateFlow<Boolean> = top100Loader.failed

    fun openTop100() {
        _selectedTop100.value = true
        top100Loader.open(Unit)
    }

    /** Explicit 4read door used when Cloudflare rejects the ranking HTTP call. */
    fun openTop100InBrowser() {
        _selectedWebSource.value = SelectedWebSource(
            sourceId = "4read",
            homeUrl = "https://4read.org/top-100.html",
            displayName = "4read",
            captureTop100 = true
        )
    }

    fun importCapturedTop100(html: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = sourceCatalog.importCapturedTop100Result(html)
            withContext(Dispatchers.Main) {
                if (result is CatalogFetchResult.Success) top100Loader.open(Unit)
                onComplete(result is CatalogFetchResult.Success)
            }
        }
    }

    fun closeTop100() {
        _selectedTop100.value = false
        top100Loader.close()
    }

    // Виконавці / Автори index pages (`/readers.html`, `/avtors.html`).
    private val _selectedPeopleKind = MutableStateFlow<PeopleKind?>(null)
    val selectedPeopleKind: StateFlow<PeopleKind?> = _selectedPeopleKind.asStateFlow()

    private val peopleLoader = KeyedCatalogLoader<PeopleKind, CatalogPerson>(viewModelScope) {
        sourceCatalog.fetchPeopleResult(it.url)
    }
    val peopleEntries: StateFlow<List<CatalogPerson>> = peopleLoader.items
    val isPeopleLoading: StateFlow<Boolean> = peopleLoader.isLoading
    val peopleLoadFailed: StateFlow<Boolean> = peopleLoader.failed

    fun openPeople(kind: PeopleKind) {
        _selectedPeopleKind.value = kind
        peopleLoader.open(kind)
    }

    fun closePeople() {
        _selectedPeopleKind.value = null
        peopleLoader.close()
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

    // #307: scroll-position restoration — the index of the selected author in
    // the alphabetical list so Back from the canonical page restores the viewport.
    private val _authorsIndexScrollIndex = MutableStateFlow(0)
    val authorsIndexScrollIndex: StateFlow<Int> = _authorsIndexScrollIndex.asStateFlow()

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

    fun openCanonicalAuthor(author: AuthorSummary, authorIndex: Int = 0) {
        _authorsIndexScrollIndex.value = authorIndex
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

    fun openCanonicalAuthorForWork(workId: String?, fallbackName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val author = workId?.let { sourceCatalog.authorForWork(it) } ?: run {
                val exactId = runCatching { AuthorIdentity.fromWorkName(fallbackName).id }.getOrNull()
                    ?: return@launch
                authorMatchesOrEmpty(fallbackName) {
                    sourceCatalog.searchAuthors(it, AuthorIndex.MAX_FULL_LIST)
                }.firstOrNull { it.id == exactId } ?: return@launch
            }
            openCanonicalAuthor(author)
        }
    }

    fun closeCanonicalAuthor() {
        _selectedCanonicalAuthor.value = null
        _canonicalAuthorWorks.value = emptyList()
        _isCanonicalAuthorLoading.value = false
        _canonicalAuthorLoadFailed.value = false
    }

    fun openCanonicalAuthorWork(work: WorkEntity) {
        // An author result is still an Overview Work card. It must take the
        // same capability-aware, cancellable path as search and feed cards;
        // selecting `workSources.first()` here used to make an arbitrary
        // Source look like a user choice.
        closeCanonicalAuthor()
        closeAuthorsIndex()
        catalogCardCoordinator.start(
            CatalogCardTarget(
                workId = work.id,
                title = work.title,
                author = work.author,
                coverImageUrl = work.coverImageUrl,
                mergeKey = work.mergeKey,
                cardKey = work.id
            ),
            CatalogCardAction.OPEN
        )
    }

    // One person's books (`/xfsearch/chitaet|avtor/<name>/` — a poster grid).
    private val _selectedPerson = MutableStateFlow<SelectedPerson?>(null)
    val selectedPerson: StateFlow<SelectedPerson?> = _selectedPerson.asStateFlow()

    private val personLoader = KeyedCatalogLoader<SelectedPerson, AudiobookEntity>(viewModelScope) {
        sourceCatalog.fetchPersonBooksResult(it.path)
    }
    val personBooks: StateFlow<List<AudiobookEntity>> = personLoader.items
    val isPersonLoading: StateFlow<Boolean> = personLoader.isLoading
    val personLoadFailed: StateFlow<Boolean> = personLoader.failed

    fun openPersonBooks(person: CatalogPerson) {
        val selected = SelectedPerson(person.name, person.path, person.role)
        _selectedPerson.value = selected
        personLoader.open(selected)
    }

    fun closePersonBooks() {
        _selectedPerson.value = null
        personLoader.close()
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

    // Spec-44 #328 coordinator-approved boundary exception: this is a
    // general, user-visible search failure state, not accessibility-only
    // branching. Without it the screen cannot distinguish a failed request
    // from an honest empty result.
    private val _globalSearchError = MutableStateFlow(false)
    val globalSearchError: StateFlow<Boolean> = _globalSearchError.asStateFlow()

    private var globalSearchJob: kotlinx.coroutines.Job? = null
    private var authorSearchJob: kotlinx.coroutines.Job? = null

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        globalSearchJob?.cancel()
        authorSearchJob?.cancel()
        val clean = query.trim()
        _globalSearchError.value = false
        if (clean.length < 2) {
            _globalSearchResults.value = emptyList()
            _authorSearchResults.value = emptyList()
            _isGlobalSearchLoading.value = false
            return
        }
        _authorSearchResults.value = emptyList()
        authorSearchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(350)
            _authorSearchResults.value = authorMatchesOrEmpty(clean) { sourceCatalog.searchAuthors(it) }
        }
        // Do not leave a previous query's Works visible under a new query;
        // the screen now presents the truthful loading state instead.
        _globalSearchResults.value = emptyList()
        _isGlobalSearchLoading.value = true
        globalSearchJob = viewModelScope.launch(Dispatchers.IO) {
            // Debounce keystrokes; cancellation keeps a stale search from
            // overwriting a newer one.
            delay(350)
            try {
                val results = sourceCatalog.searchAllSources(clean)
                if (_searchQuery.value.trim() == clean) {
                    _globalSearchResults.value = results
                    _globalSearchError.value = false
                    _isGlobalSearchLoading.value = false
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (_searchQuery.value.trim() == clean) {
                    _globalSearchResults.value = emptyList()
                    _globalSearchError.value = true
                    _isGlobalSearchLoading.value = false
                }
            }
        }
    }

    /** #454 — global search uses the same explicit Open/Play coordinator as the feed. */
    fun openGlobalSearchResult(result: GlobalSearchResult) {
        catalogCardCoordinator.start(result.asCatalogCardTarget(), CatalogCardAction.OPEN)
    }

    fun playGlobalSearchResult(result: GlobalSearchResult) {
        catalogCardCoordinator.start(result.asCatalogCardTarget(), CatalogCardAction.PLAY)
    }

    fun preflightGlobalSearchResult(result: GlobalSearchResult) {
        startCatalogPreflight(result.asCatalogCardTarget())
    }

    private fun GlobalSearchResult.asCatalogCardTarget() = CatalogCardTarget(
        workId = mergeKey.ifBlank { key },
        title = title,
        author = author,
        narrator = narrator,
        coverImageUrl = coverImageUrl,
        mergeKey = mergeKey,
        preferredEditionId = sources.firstOrNull()?.editionId?.takeIf(String::isNotBlank),
        sources = sources.map {
            CatalogCardSource(
                sourceId = it.sourceId,
                url = it.url,
                editionId = it.editionId.takeIf(String::isNotBlank)
            )
        },
        cardKey = key
    )

    fun openCatalogBook(book: CatalogBook) {
        catalogCardCoordinator.start(book.asCatalogCardTarget(), CatalogCardAction.OPEN)
    }

    fun playCatalogBook(book: CatalogBook) {
        catalogCardCoordinator.start(book.asCatalogCardTarget(), CatalogCardAction.PLAY)
    }

    fun preflightCatalogBook(book: CatalogBook) {
        startCatalogPreflight(book.asCatalogCardTarget())
    }

    private fun CatalogBook.asCatalogCardTarget() = CatalogCardTarget(
        workId = workId ?: id,
        title = title,
        author = author,
        narrator = narrator,
        coverImageUrl = coverImageUrl,
        mergeKey = mergeKey,
        sources = if (url.isBlank()) emptyList() else {
            listOf(CatalogCardSource(sourceIdForUrl(url), url))
        },
        cardKey = id
    )

    /**
     * Tries only direct/unknown sources in the shared 10-second budget. A
     * browser-only source is never opened as a side effect of a card tap; the
     * browser remains an explicit user action.
     */
    private suspend fun importPreferredSource(
        result: GlobalSearchResult,
        forDownload: Boolean = false
    ): AudiobookEntity? = importPreferredSource(
        candidates = result.sources,
        identity = KnownBookIdentity(result.title, result.author, result.narrator, result.coverImageUrl),
        forDownload = forDownload
    )

    /** Same coordinator for persisted Work-feed rows and search cards. */
    private suspend fun importPreferredSource(
        candidates: List<com.slukhayka.audiobooks.data.source.GlobalSearchSource>,
        identity: KnownBookIdentity,
        forDownload: Boolean = false
    ): AudiobookEntity? {
        val ordered = SourceAccessPolicy.order(
            candidates.map { SourceAccessCandidate(it.sourceId, it.sourceName, it.url) }
        )
        val deadline = System.nanoTime() + 10_000_000_000L
        val directCandidates = ordered.filter {
            it.accessMode != com.slukhayka.audiobooks.data.source.SourceAccessMode.BROWSER &&
                (!forDownload || !com.slukhayka.audiobooks.data.source.streamOnlyFor(it.sourceId))
        }
        // A browser Source is intentionally excluded here. The caller must
        // first perform the explicit browser import/recovery action; a
        // download tap must never launch WebView or perform a direct 4read
        // request behind the listener's back.
        for (candidate in directCandidates) {
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
            val book = kotlinx.coroutines.withTimeoutOrNull(remainingMs) {
                try {
                    libraryImport.importFromSourceUrl(
                        candidate.sourceId,
                        candidate.url,
                        identity
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            if (book != null) return book
        }
        return null
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

    private val _feedDurationFilters = MutableStateFlow<Set<String>>(emptySet())
    val feedDurationFilters: StateFlow<Set<String>> = _feedDurationFilters.asStateFlow()

    private val _feedSortByTitle = MutableStateFlow(false)
    val feedSortByTitle: StateFlow<Boolean> = _feedSortByTitle.asStateFlow()

    val workFeed: Flow<PagingData<WorkFeedRow>> =
        // Spec-45 (#405) T4/T6: the content-language dimension rides the
        // SAME persisted preference flow as every other surface — a change
        // (⚙️ destination or the Огляд chip) rebuilds the Pager live, no
        // restart. Both on = «Усі» behaves exactly like an inactive filter.
        combine(
            _feedGenreFilters,
            _feedDurationFilters,
            _feedSortByTitle,
            App.instance.contentLanguagePrefs.languages
        ) { genres, durations, byTitle, languages ->
            FilterKey(genres, durations, byTitle, languages)
        }
        .distinctUntilChanged()
        .flatMapLatest { key ->
            val filter = WorkFacetFilter(
                genreIds = key.genres,
                durationBucketIds = key.durations,
                languages = key.languages
            )
            Pager(
                config = PagingConfig(pageSize = 30, prefetchDistance = 15, enablePlaceholders = false)
            ) {
                if (key.byTitle) {
                    sourceCatalog.pagedWorkFeedByTitle(filter)
                } else {
                    sourceCatalog.pagedWorkFeedRecent(filter)
                }
            }.flow
        }.cachedIn(viewModelScope)

    fun setFeedGenreFilters(genres: Set<String>) {
        _feedGenreFilters.value = genres
    }

    /** Immutable combine key so flatMapLatest restarts only on a real change. */
    private data class FilterKey(
        val genres: Set<String>,
        val durations: Set<String>,
        val byTitle: Boolean,
        val languages: Set<String>
    )

    fun setFeedDurationFilters(durationBucketIds: Set<String>) {
        _feedDurationFilters.value = durationBucketIds
    }

    fun setFeedSortByTitle(byTitle: Boolean) {
        _feedSortByTitle.value = byTitle
    }

    /** #453 — the body opens details. It never starts playback. */
    fun openWorkFeedRow(row: WorkFeedRow) {
        catalogCardCoordinator.start(row.asCatalogCardTarget(), CatalogCardAction.OPEN)
    }

    /** #453 — the separate Play action resumes Listening State or starts the selected Edition. */
    fun playWorkFeedRow(row: WorkFeedRow) {
        catalogCardCoordinator.start(row.asCatalogCardTarget(), CatalogCardAction.PLAY)
    }

    fun preflightWorkFeedRow(row: WorkFeedRow) {
        startCatalogPreflight(row.asCatalogCardTarget())
    }

    fun cancelCatalogCardAction() {
        catalogCardCoordinator.cancel()
    }

    private fun WorkFeedRow.asCatalogCardTarget() = CatalogCardTarget(
        workId = workId,
        title = title,
        author = author,
        coverImageUrl = coverImageUrl,
        preferredEditionId = matchingEditionId,
        mergeKey = mergeKey,
        cardKey = workId
    )

    private suspend fun catalogSourceCandidates(
        target: CatalogCardTarget
    ): List<SourceSelectionCoordinator.SourceCandidate> {
        val persistedEditionSources = target.preferredEditionId
            ?.takeIf(String::isNotBlank)
            ?.let { sourceCatalog.editionSources(it) }
            .orEmpty()
        val entities = when {
            persistedEditionSources.isNotEmpty() -> persistedEditionSources
            else -> editionScopedCatalogSources(
                target.preferredEditionId,
                if (target.sources.isNotEmpty()) {
                    target.sources
                } else {
                    sourceCatalog.workSourcesForWork(target.workId).map { source ->
                        CatalogCardSource(
                            sourceId = source.sourceId,
                            url = source.sourceUrl,
                            streamOnly = source.streamOnly
                        )
                    }
                }
            ).mapIndexed { index, source ->
                SourceEntity(
                    id = "${target.cardKey}|${source.sourceId}|$index",
                    bookId = target.workId,
                    // Never manufacture Edition provenance for a Work-level
                    // browse row. Only adapters/search or persisted Source
                    // rows may assert that two URLs carry one narration.
                    editionId = source.editionId,
                    type = source.sourceId,
                    url = source.url,
                    streamOnly = source.streamOnly,
                    addedAt = index.toLong()
                )
            }
        }
        return entities.flatMap { entity ->
            catalogSessionCandidates(
                source = entity,
                mode = SourceAccessPolicy.modeFor(entity.type),
                hasFirstPartySession = hasUsableSourceSession(
                    AndroidSourceCookieProvider.cookieFor(entity.url)
                )
            )
        }
    }

    /**
     * Compose creates only visible Lazy items plus its bounded look-ahead.
     * One key is probed at most once for this screen/ViewModel session and a
     * process-wide semaphore keeps all cards to two in-flight Sources total.
     */
    private fun startCatalogPreflight(target: CatalogCardTarget) {
        if (!catalogPreflightKeys.add(target.cardKey)) return
        viewModelScope.launch(Dispatchers.IO) {
            val saved = sourceCatalog.resumableLibraryBookForWork(
                target.workId,
                target.preferredEditionId,
                target.mergeKey
            )
            if (saved != null) {
                catalogPreflightSlots.withPermit { preflightCatalogMedia(saved, null) }
                return@launch
            }
            coroutineScope {
                catalogSourceCandidates(target)
                    .filter { candidate ->
                        candidate.category == SourceSelectionCoordinator.SourceCategory.DIRECT ||
                            candidate.category == SourceSelectionCoordinator.SourceCategory.UNKNOWN ||
                            (candidate.category == SourceSelectionCoordinator.SourceCategory.BROWSER &&
                                hasUsableSourceSession(
                                    AndroidSourceCookieProvider.cookieFor(candidate.source.url)
                                ))
                    }
                    .distinctBy { it.source.type to it.source.url }
                    .take(CatalogAvailabilityPolicy.MAX_PARALLEL_SOURCES)
                    .map { candidate ->
                        async {
                            catalogPreflightSlots.withPermit {
                                validateCatalogMediaRange(candidate.source.url, candidate.source.type)
                            }
                        }
                    }
                    .awaitAll()
            }
        }
    }

    private suspend fun startCatalogPlaybackAndAwait(
        book: AudiobookEntity,
        preferredSource: SourceEntity?
    ): Boolean = coroutineScope {
            // Media was preflighted while both Source candidates prepared in
            // parallel. Only this ready candidate now enters the one Player;
            // terminal success remains the real `isPlaying` event.
            val before = playerState.value
            val verdict = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(CatalogAvailabilityPolicy.SOURCE_BUDGET_MS) {
                    playerState
                        .dropWhile { it == before }
                        .first { state ->
                            state.currentBook?.id == book.id &&
                                (state.isPlaying || state.errorKind != PlaybackErrorKind.NONE)
                        }
                    }
                }
            startAudiobookPlaybackNow(
                book = book,
                chapterIndex = null,
                autoPlay = true,
                forceRelisten = false,
                preferredSource = preferredSource
            )
            verdict.await()?.isPlaying == true
    }

    private suspend fun preflightCatalogMedia(
        book: AudiobookEntity,
        preferredSource: SourceEntity?
    ): Boolean =
        withContext(Dispatchers.IO) {
            val first = sourceCatalog.getPlayableChapters(
                book.id,
                preferredSourceType = preferredSource?.type,
                preferredSourceUrl = preferredSource?.url
            ).firstOrNull()
                ?: return@withContext false
            val track = first.track ?: return@withContext false
            if (first.sourceId == "local" || track.localFilePath != null) return@withContext true
            val sourceId = first.sourceId ?: sourceIdForUrl(book.sourceUrl)
            validateCatalogMediaRange(track.url, sourceId)
        }

    private suspend fun validateCatalogMediaRange(url: String, sourceId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext false
            withTimeoutOrNull(CatalogAvailabilityPolicy.SOURCE_BUDGET_MS) {
                val response = HttpFetcher()
                    // CookieManager is consulted just-in-time for this exact
                    // first-party host; no cookie value leaves request memory.
                    .getRangeStream(
                        url,
                        headersFor(sourceId, url, AndroidSourceCookieProvider.cookieFor(url)) +
                            AndroidSourceCookieProvider.cookieHeadersFor(url) +
                            ("Range" to "bytes=0-2047")
                    )
                    ?: return@withTimeoutOrNull false
                response.stream.use { stream ->
                    val buffer = ByteArray(512)
                    val count = stream.read(buffer)
                    MediaRangeValidator.isValid(
                        response.contentType,
                        if (count > 0) buffer.copyOf(count) else byteArrayOf()
                    )
                }
            } ?: false
        }

    /** Best-effort shared shortcut, gated by the publisher's second cookie-free probe. */
    private suspend fun publishVerifiedCatalogProfile(
        book: AudiobookEntity,
        source: SourceEntity
    ) {
        val editionId = source.editionId?.takeIf(String::isNotBlank)
            ?: editionIdForBook(book.id)
            ?: return
        val playable = sourceCatalog.getPlayableChapters(
            book.id,
            preferredSourceType = source.type,
            preferredSourceUrl = source.url
        )
        val profile = BookProfile(
            title = book.title,
            author = book.author,
            narrator = book.narrator,
            description = book.description,
            chapters = playable.mapNotNull { chapter ->
                chapter.track?.url
                    ?.takeIf { it.startsWith("http", ignoreCase = true) }
                    ?.let { trackUrl ->
                        ProfileChapter(
                            chapter.chapter.title,
                            trackUrl,
                            chapter.chapter.durationSeconds
                        )
                    }
            },
            totalDurationSeconds = book.totalDurationSeconds.takeIf { it > 0L }
        )
        runCatching {
            App.instance.verifiedSourceProfilePublisher.publish(
                VerifiedSourceProfile(
                    sourceId = source.type,
                    editionId = editionId,
                    playerOpened = true,
                    source = SourceAccessCandidate(source.type, url = source.url),
                    profile = profile
                )
            )
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
        recommendationPersonalization.recordDetailOpen()
        catalogCardCoordinator.start(result.asCatalogCardTarget(), CatalogCardAction.OPEN)
    }

    fun playRecommendedBook(candidateId: String) {
        val result = sourceCatalog.unifiedCatalog.value.firstOrNull { it.key == candidateId } ?: return
        catalogCardCoordinator.start(result.asCatalogCardTarget(), CatalogCardAction.PLAY)
    }

    fun preflightRecommendedBook(candidateId: String) {
        sourceCatalog.unifiedCatalog.value.firstOrNull { it.key == candidateId }
            ?.let { startCatalogPreflight(it.asCatalogCardTarget()) }
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
            // A structural repair recreates its Chapter rows. Re-probe on the
            // next detail open as well, so a book repaired before this build
            // does not keep showing «невідомо» for every chapter.
            probeDurationsAfterImport(bookId)
            viewModelScope.launch(Dispatchers.IO) {
                // #388 — refresh is best-effort; a FK violation or network
                // failure must never crash the book page (tapping «Сни» was
                // force-finishing MainActivity). Degrade gracefully.
                try {
                    libraryEntries.refreshBookCoverAndDetails(bookId)
                } catch (e: Exception) {
                    android.util.Log.w("MainViewModel", "refreshBookCoverAndDetails failed for $bookId", e)
                }
                // Spec-25 (#171): resolve the book's series universe lazily —
                // cache-first read, then the (idempotent) resolution, then the
                // fresher read. Best-effort: an unseeded series contributes
                // nothing.
                try {
                    _selectedBookUniverse.value = seriesUniverses.contextOfBook(bookId)
                    seriesUniverses.resolveForBook(bookId)
                    _selectedBookUniverse.value = seriesUniverses.contextOfBook(bookId)
                } catch (e: Exception) {
                    android.util.Log.w("MainViewModel", "series universe resolve failed for $bookId", e)
                }
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

    // A submission result is an event, not accessibility-specific state: the
    // screen uses it to keep failed input open and to retire successful forms.
    private val reviewSubmissionGate = ReviewSubmissionGate()
    private val reviewLoadGate = ReviewLoadGate()
    private val _reviewSaveResults = MutableSharedFlow<ReviewSaveEvent>(extraBufferCapacity = 16)
    val reviewSaveResults: SharedFlow<ReviewSaveEvent> = _reviewSaveResults.asSharedFlow()

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
        val request = reviewLoadGate.begin(workId)
        viewModelScope.launch(Dispatchers.IO) {
            val fresh = try {
                store.getReviews(workId)
            } catch (e: Exception) {
                emptyList()
            }
            val selectedEdition = selectedBook.value
            val stillSelected = selectedEdition?.id == _selectedBookId.value &&
                selectedEdition?.let { reviewWorkIdFor(it.id, it.workId) } == workId
            if (stillSelected && reviewLoadGate.isLatest(request)) {
                _serverBookReviews.value = fresh
            }
        }
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
        val store = listenerReviews
        val profile = _listenerIdentity.value
        val documentId = com.slukhayka.audiobooks.data.reviews.ListenerReviewCodec.documentId(
            workId,
            profile?.uid.orEmpty()
        )
        if (
            store == null || profile == null ||
            rating !in com.slukhayka.audiobooks.data.reviews.ListenerReviewLimits.MIN_RATING..
                com.slukhayka.audiobooks.data.reviews.ListenerReviewLimits.MAX_RATING
        ) {
            val rejected = reviewSubmissionGate.begin(workId, documentId)
            _reviewSaveResults.tryEmit(rejected.event(ReviewSaveResult.FAILED))
            return
        }
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
        val submission = reviewSubmissionGate.begin(review.workId, key)
        // Optimistic insert FIRST — the user sees their card instantly.
        _pendingReviews.update { it + (key to review) }
        viewModelScope.launch(Dispatchers.IO) {
            val receipt = try {
                store.enqueueReview(review)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReviewWriteReceipt.Rejected
            }
            followReviewWrite(
                receipt = receipt,
                onVisibleResult = { result ->
                    if (!reviewSubmissionGate.isLatest(submission)) return@followReviewWrite
                    if (result == ReviewSaveResult.FAILED) {
                        _pendingReviews.update { it - key }
                    }
                    _reviewSaveResults.emit(submission.event(result))
                },
                onRemoteResult = {
                    if (!reviewSubmissionGate.isLatest(submission)) return@followReviewWrite
                    // Either backend verdict ends the local pending badge. A
                    // failure is announced by followReviewWrite immediately
                    // after this callback; publication needs no second toast.
                    _pendingReviews.update { it - key }
                }
            )
            if (reviewSubmissionGate.isLatest(submission)) loadReviews(workId)
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
            _pendingReviews.update { it - key }
            loadReviews(workId)
        }
    }

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

    fun playAudiobook(
        book: AudiobookEntity,
        chapterIndex: Int? = null,
        autoPlay: Boolean = true,
        preferredSource: SourceEntity? = null
    ) {
        if (autoPlay && pendingRecommendationBookId.compareAndSet(book.id, null)) {
            recommendationPersonalization.recordPlaybackStart()
        }
        startAudiobookPlayback(
            book,
            chapterIndex = chapterIndex,
            autoPlay = autoPlay,
            forceRelisten = false,
            preferredSource = preferredSource
        )
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
        forceRelisten: Boolean,
        preferredSource: SourceEntity? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            startAudiobookPlaybackNow(
                book = book,
                chapterIndex = chapterIndex,
                autoPlay = autoPlay,
                forceRelisten = forceRelisten,
                preferredSource = preferredSource
            )
        }
    }

    private suspend fun startAudiobookPlaybackNow(
        book: AudiobookEntity,
        chapterIndex: Int?,
        autoPlay: Boolean,
        forceRelisten: Boolean,
        preferredSource: SourceEntity? = null
    ) {
            val updatedBook = libraryEntries.getBookSync(book.id) ?: book
            // ADR-0007: the chapter→track pairing rides the same fetch — the
            // player resolves chapter → track 1:1 by index.
            val playable = sourceCatalog.getPlayableChapters(
                updatedBook.id,
                preferredSourceType = preferredSource?.type,
                preferredSourceUrl = preferredSource?.url
            )
            val chapters = playable.map { it.chapter }
            // Code-review LOW: if the book was deleted while this IO fetch was
            // in flight (e.g. deleteBook on another screen), do not resurrect
            // playback for it.
            if (libraryEntries.getBookSync(updatedBook.id) == null) return
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

    /** Book whose failed 4read download is waiting for a browser refresh. */
    private val _downloadRecoveryBookId = MutableStateFlow<String?>(null)
    val downloadRecoveryBookId: StateFlow<String?> = _downloadRecoveryBookId.asStateFlow()

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
        val key = result.key
        if (_catalogDownloadingKeys.value.contains(key)) return
        if (_downloadingBookId.value != null) {
            android.widget.Toast.makeText(getApplication(), "Вже завантажується інша книга", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        _catalogDownloadingKeys.update { it + key }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val book = importPreferredSource(result, forDownload = true)
                if (book != null) {
                    _downloadingBookId.value = book.id
                    probeDurationsAfterImport(book.id)
                    startDownloadNotification(book.id, result.title, result.author)
                    offlineDownloads.downloadAudiobookOffline(book.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Catalog download failed", e)
            } finally {
                _downloadingBookId.value = null
                _catalogDownloadingKeys.update { it - key }
                stopDownloadNotification()
                refreshCacheSize()
            }
        }
    }

    fun downloadBookOffline(bookId: String) {
        if (_downloadingBookId.value != null) {
            android.widget.Toast.makeText(getApplication(), "Вже завантажується інша книга", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        _downloadingBookId.value = bookId
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val book = libraryEntries.getBookSync(bookId)
                startDownloadNotification(bookId, book?.title ?: "", book?.author ?: "")
                offlineDownloads.registerDownloadJob(bookId, kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!)
                // Browser recovery only prepares the persisted 4read queue.
                // This explicit listener action continues its failed and
                // pending chapters instead of restarting the whole book.
                val result = offlineDownloads.resumePendingBrowserRefresh(bookId)
                    ?: offlineDownloads.downloadAudiobookOffline(bookId)
                if (_selectedBookId.value == bookId) {
                    _downloadMessage.value = OutcomeMessages.downloadOutcome(result)
                    _downloadRecoveryBookId.value = bookId.takeIf { result.requiresBrowserRefresh }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Offline download failed", e)
                if (_selectedBookId.value == bookId) {
                    _downloadMessage.value = OutcomeMessages.downloadFailure()
                    _downloadRecoveryBookId.value = null
                }
            } finally {
                offlineDownloads.unregisterDownloadJob(bookId)
                _downloadingBookId.value = null
                stopDownloadNotification()
                refreshCacheSize()
            }
        }
        offlineDownloads.registerDownloadJob(bookId, job)
    }

    // #394 — Download controls: pause / continue / cancel
    fun pauseDownload(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            offlineDownloads.pauseDownload(bookId)
            _downloadingBookId.value = null
            com.slukhayka.audiobooks.data.downloads.DownloadNotificationService.notifyPaused(getApplication(), bookId)
            refreshCacheSize()
        }
    }

    fun continueDownload(bookId: String) {
        if (_downloadingBookId.value != null) {
            android.widget.Toast.makeText(getApplication(), "Вже завантажується інша книга", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        _downloadingBookId.value = bookId
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val book = libraryEntries.getBookSync(bookId)
                startDownloadNotification(bookId, book?.title ?: "", book?.author ?: "")
                offlineDownloads.registerDownloadJob(bookId, kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!)
                // CONTEXT.md/ADR-0026: browser recovery never resumes on its
                // own; this explicit Continue is the only door that does.
                val result = offlineDownloads.resumePendingBrowserRefresh(bookId)
                    ?: offlineDownloads.continueDownload(bookId)
                if (_selectedBookId.value == bookId) {
                    _downloadMessage.value = OutcomeMessages.downloadOutcome(result)
                }
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Offline continue failed", e)
            } finally {
                offlineDownloads.unregisterDownloadJob(bookId)
                _downloadingBookId.value = null
                stopDownloadNotification()
                refreshCacheSize()
            }
        }
        offlineDownloads.registerDownloadJob(bookId, job)
    }

    fun cancelDownload(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            offlineDownloads.cancelDownload(bookId)
            _downloadingBookId.value = null
            stopDownloadNotification()
            refreshCacheSize()
        }
    }

    // #393 — download notification helpers
    private var downloadProgressJob: kotlinx.coroutines.Job? = null
    private fun startDownloadNotification(bookId: String, title: String, author: String) {
        val ctx = getApplication<Application>()
        com.slukhayka.audiobooks.data.downloads.DownloadNotificationService.start(ctx, bookId, title, author)
        downloadProgressJob?.cancel()
        downloadProgressJob = viewModelScope.launch {
            offlineDownloads.downloadBytesProgress.collect { map ->
                val p = map[bookId] ?: return@collect
                com.slukhayka.audiobooks.data.downloads.DownloadNotificationService.updateProgress(
                    ctx, bookId, p.completedChapters, p.totalChapters, p.totalBytes, p.isApproximate
                )
            }
        }
    }
    private fun stopDownloadNotification() {
        downloadProgressJob?.cancel(); downloadProgressJob = null
        com.slukhayka.audiobooks.data.downloads.DownloadNotificationService.stop(getApplication())
    }

    fun consumeDownloadMessage() {
        _downloadMessage.value = null
    }

    fun consumeDownloadRecovery() {
        _downloadRecoveryBookId.value = null
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

    /**
     * Honest refusal for a browser-only search card (#434): the work title is
     * carried so the UI can open the prefilled 4read browser search.
     */
    data class BrowserNeededImport(val workTitle: String)
    private val _browserNeededImport = MutableStateFlow<BrowserNeededImport?>(null)
    val browserNeededImport: StateFlow<BrowserNeededImport?> = _browserNeededImport.asStateFlow()

    fun consumeBrowserNeededImport() {
        _browserNeededImport.value = null
    }

    /** The pending smart-import preview (wayfinder #29), null when none. */
    private val _importPreview = MutableStateFlow<ImportPreviewState?>(null)
    val importPreview: StateFlow<ImportPreviewState?> = _importPreview.asStateFlow()

    fun consumeImportMessage() {
        _importMessage.value = null
    }

    // The captured-page import doors remain repository seams; 4read's release
    // browser now reaches them through WebSourceBrowserScreen as well.

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
