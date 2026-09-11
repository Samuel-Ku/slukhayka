package com.slukhayka.audiobooks.ui.screens

import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.slukhayka.audiobooks.ui.components.BookRow
import com.slukhayka.audiobooks.ui.components.CycleCard
import com.slukhayka.audiobooks.ui.components.MetadataChip
import com.slukhayka.audiobooks.ui.components.OpenWebSourceRow
import com.slukhayka.audiobooks.ui.components.PosterCard
import com.slukhayka.audiobooks.ui.components.PosterWidth
import com.slukhayka.audiobooks.ui.components.applySourceCoverHeaders
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.catalog.CatalogSeries
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.LanguageCode
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.GenreFacetOption
import com.slukhayka.audiobooks.data.duration.ChapterDurationProbe
import com.slukhayka.audiobooks.data.duration.DurationEnrichment
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.personbookmarks.PersonBookmarks
import com.slukhayka.audiobooks.data.personbookmarks.PersonNewArrivals
import com.slukhayka.audiobooks.data.metadata.EditionDurationPolicy
import com.slukhayka.audiobooks.data.metadata.EditionDurationSummary
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.update.UpdateChecker
import com.slukhayka.audiobooks.ui.DurationBooks
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.EmptyState
import com.slukhayka.audiobooks.ui.components.AppSectionHeader
import com.slukhayka.audiobooks.ui.components.SectionHeaderLevel
import com.slukhayka.audiobooks.ui.components.AppTabHeader
import com.slukhayka.audiobooks.ui.components.BookCoverSemantics
import com.slukhayka.audiobooks.ui.components.CatalogCoverImage
import com.slukhayka.audiobooks.ui.components.NavigationChip
import com.slukhayka.audiobooks.ui.components.UpdateBanner
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.components.RestoreFocusAfterModal
import com.slukhayka.audiobooks.ui.components.genreAccentColor
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.durationBooksFrom
import com.slukhayka.audiobooks.ui.catalog.CatalogCardAction
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionState
import com.slukhayka.audiobooks.ui.catalog.CatalogCardFailure
import com.slukhayka.audiobooks.ui.theme.*

/**
 * Огляд tab (spec #8 tickets T6/T1, spec-9 T2): a Netflix-style feed of
 * horizontal rows parsed from the 4read.org homepage ("Новинки" book row,
 * "Цикли" series row) plus search and genre filters. The Continue-Listening
 * card and the full local library moved to the Слухати/Медіатека tabs
 * (spec-9). While the catalogue syncs on a fresh install a spinner is shown;
 * if nothing arrives the user gets an actionable empty state (retry / import)
 * instead of mocks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    // ADR-0008 batches 2 + contract (#156, #160): the screen receives the
    // modules it reads from as parameters, wired from the composition root —
    // the injection idiom settled by #154. Search, the feed, recommendations
    // and navigation orchestration stay on the ViewModel.
    libraryEntries: LibraryEntries,
    sourceCatalog: SourceCatalog,
    personBookmarks: PersonBookmarks,
    durationEnrichment: DurationEnrichment,
    // spec-24 T8 (#169): the throttled chapter-duration probing pass.
    chapterDurationProbe: ChapterDurationProbe,
    // Spec-36 T1 (#244): the app-release check — the screen reads the
    // module's flow directly (ADR-0008) and renders the update banner.
    updateChecker: UpdateChecker,
    onBookClick: (String) -> Unit,
    onPlayClick: (AudiobookEntity) -> Unit,
    // spec-28 (#192): the «Більше книг на Sluhay» exit CTA — wired from the
    // composition root exactly like on Listen (debug-only, spec-13 T3/T2).
    onOpenWebSource: (() -> Unit)? = null,
    // Spec-42 #440: the 4read door is release-accessible (ADR-0027) — wired
    // unconditionally from the composition root.
    onOpenWebSource4read: (() -> Unit)? = null
) {
    // ADR-0008: module flows are read directly — no forwarding StateFlow on
    // the ViewModel. Cold flows need an initial value; the catalogue StateFlows
    // carry their own.
    val allBooks by libraryEntries.allBooks.collectAsState(initial = emptyList())
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sections by sourceCatalog.catalogSections.collectAsState()
    val isCatalogLoading by sourceCatalog.isCatalogLoading.collectAsState()
    val genreFacetOptions by sourceCatalog.genreFacetOptions.collectAsState(initial = emptyList())
    // Spec-10 T4: aggregated global search across all verified sources.
    val globalResults by viewModel.globalSearchResults.collectAsState()
    val isGlobalSearchLoading by viewModel.isGlobalSearchLoading.collectAsState()
    val authorResults by viewModel.authorSearchResults.collectAsState()
    val globalSearchError by viewModel.globalSearchError.collectAsState()
    // Spec-23 T4: the endless merged feed (Paging 3) over the persisted
    // Works/Editions catalogue — pages through the whole catalogue, one card
    // per Work, dedup inherited from merge-on-write. Filter/sort states live
    // in the ViewModel (they rebuild the Pager); the feed is collected here.
    val workFeedItems = viewModel.workFeed.collectAsLazyPagingItems()
    val feedGenreFilters by viewModel.feedGenreFilters.collectAsState()
    val feedDurationFilters by viewModel.feedDurationFilters.collectAsState()
    val feedSortByTitle by viewModel.feedSortByTitle.collectAsState()
    // Spec-45 (#405) T6 (#494): the «Мова» chip state — both on = «Усі»;
    // a single-language selection renders as that language and marks the
    // chip selected (the filter is active).
    val contentLanguages by viewModel.contentLanguages.collectAsState()
    val catalogCardActionState by viewModel.catalogCardActionState.collectAsState()
    // Spec-19 Track A: the on-device «Рекомендовано для вас» row — semantic
    // similarity of catalogue descriptions to favourite/completed/recent
    // signals, computed locally, with a per-card reason chip.
    val recommendedBooks by viewModel.recommendedBooks.collectAsState()
    val recommendationsReady by viewModel.recommendationsReady.collectAsState()
    val recommendationSettings by viewModel.recommendationSettings.collectAsState()
    var showRecommendationDisclosure by rememberSaveable { mutableStateOf(false) }
    val recommendationDisclosureTriggerFocusRequester = remember { FocusRequester() }
    var showWorkFeedFilters by rememberSaveable { mutableStateOf(false) }
    val workFeedFilterTriggerFocusRequester = remember { FocusRequester() }

    RestoreFocusAfterModal(
        modalVisible = showWorkFeedFilters,
        returnFocusRequester = workFeedFilterTriggerFocusRequester
    )

    // Spec-39 T1 (#261): «Ваші цикли» — derived purely from the local base
    // (library rows + Listening State + every known Work), no network and no
    // loading states; recomputed only when an input actually changes.
    val recentProgress by libraryEntries.recentProgress.collectAsState(initial = emptyList())
    val allWorks by sourceCatalog.allWorks.collectAsState(initial = emptyList())
    val allLibraryEntries by sourceCatalog.allLibraryEntries.collectAsState(initial = emptyList())
    val allEditions by sourceCatalog.allEditions.collectAsState(initial = emptyList())
    val allPersonBookmarks by personBookmarks.allBookmarks().collectAsState(initial = emptyList())
    val unifiedCatalog by sourceCatalog.unifiedCatalog.collectAsState()
    val peopleNewArrivals = remember(allPersonBookmarks, allWorks, allEditions, allLibraryEntries, unifiedCatalog) {
        PersonNewArrivals.projectCatalog(
            bookmarks = allPersonBookmarks,
            works = allWorks,
            editions = allEditions,
            unifiedCatalog = unifiedCatalog,
            libraryEntries = allLibraryEntries
        )
    }

    // Spec-16 T2: the «Колекції» block — curated lists matched against the
    // union, recomputed on every union refresh (same trigger). The flow
    // already excludes empty collections; the block itself hides when all are
    // empty.
    val collections by sourceCatalog.smartCollections.collectAsState()

    // spec-28 (#192): the cross-source «Новинки» rail — 4read's new arrivals
    // plus every other source's new feed, merged by Work with a source badge
    // per card (re-homed from Слухати; the «Новинки» catalogue section below
    // is skipped so 4read appears exactly once).
    val newArrivals by sourceCatalog.newArrivals.collectAsState()

    // Spec-36 T1 (#244): an available app release, resolved by the module's
    // own throttled check — null means everything is current.
    val availableRelease by updateChecker.available.collectAsState()

    // Spec-15 T1: refresh the ephemeral union once per Огляд composition —
    // the ViewModel still needs it for the recommendation enrichment, even
    // though the browse surface is now the spec-23 T4 persisted feed.
    // spec-28 (#192): the feeds refresh on the same trigger — the rail's
    // other-source half lives there, so the move never loses a feed.
    // ADR-0008: the module call is made directly from the composition scope;
    // the embedding pass stays orchestrated by the ViewModel (single-flight).
    val scope = rememberCoroutineScope()
    val recommendationSnackbar = remember { SnackbarHostState() }
    // #434: a browser-only search card cannot import silently — offer the
    // explicit 4read browser door with the work title prefilled.
    val browserNeededImport by viewModel.browserNeededImport.collectAsState()
    val browserOnlyMessage = stringResource(R.string.home_browser_only_snackbar)
    val openLabel = stringResource(R.string.home_open)
    val recommendationUpdated = stringResource(R.string.home_recommendation_updated)
    val cancelLabel = stringResource(R.string.download_action_cancel)
    LaunchedEffect(browserNeededImport) {
        val needed = browserNeededImport ?: return@LaunchedEffect
        val result = recommendationSnackbar.showSnackbar(
            message = browserOnlyMessage,
            actionLabel = openLabel,
            withDismissAction = true
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.open4ReadSearch(needed.workTitle)
        }
        viewModel.consumeBrowserNeededImport()
    }
    LaunchedEffect(Unit) {
        // One cancellable delta chain for this active Огляд session. Filters,
        // cards and recompositions only read Room; none of them touch Firestore.
        launch { sourceCatalog.syncSharedFacets() }
        launch { sourceCatalog.syncSharedSubmissions() }
        launch { sourceCatalog.syncSharedTombstones() }
        sourceCatalog.refreshUnifiedCatalog()
        com.slukhayka.audiobooks.data.personbookmarks.PeopleNewArrivalWorker.notifyIfNeeded(App.instance)
        sourceCatalog.refreshSourceFeeds()
        viewModel.refreshEmbeddingVectors()
        // spec-18 T2: one throttled, bounded duration-enrichment pass is
        // detached so it never delays browsing or catalogue completion. The
        // module's own atomic throttle collapses overlapping triggers.
        durationEnrichment.enrichUnknownDurations()
        // spec-24 T8 (#169): one throttled, bounded chapter-duration probing
        // pass, same detached idiom — fills unknown chapter durations from
        // the provider streams (HEAD + ranged GET, CBR only, never a guess).
        chapterDurationProbe.probeUnknownChapters()
        viewModel.seedLibraryIfDue()
        // Spec-45 (#405) T8 (#496): this sync may have written the first
        // English rendition — re-evaluate the one-time bilingual prompt
        // (idempotent; fires at most once ever).
        viewModel.onCatalogueSynced()
    }

    // spec-18 T3: the «За тривалістю» rows, derived live from the library
    // through the pure DurationBuckets module — only books with a known
    // duration surface, never guesses.
    val durationBooks: DurationBooks = remember(allBooks) { durationBooksFrom(allBooks) }

    // Spec-39 T1/T2 (#261/#262): the pure builder turns the same shaped rows
    // into the shelf; the T2 similar tier lifts the engine's ranked picks to
    // cycle level (best-effort — empty picks yield no tier). An empty result
    // leaves Огляд byte-for-byte as before.
    val personalCycles = remember(allBooks, recentProgress, allWorks, recommendedBooks) {
        com.slukhayka.audiobooks.ui.library.PersonalCycles.build(
            libraryBooks = allBooks,
            progress = recentProgress,
            works = allWorks,
            recommendations = recommendedBooks
        )
    }

    // Spec-39 T2 (#262): the pure builder lifts the engine's top picks to
    // cycles through the local Work rows (series identity); the listener's
    // own cycle titles are excluded so nothing owned is recommended back.
    val similarCycles = remember(recommendedBooks, allWorks, personalCycles) {
        com.slukhayka.audiobooks.ui.library.SimilarCycles.build(
            picks = recommendedBooks,
            works = allWorks,
            ownCycleTitles = personalCycles.map { it.title }
        )
    }

    // Spec-22 T3: the search bar and filter chips are collapsible — the
    // header shows brand + [🔍] + [🔄], and the field + chips expand on
    // demand with auto-focus. Closing (✕ or Back) clears the query and
    // resets the filter to «Усі».
    val haptic = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // A query always keeps its field visible, including when Android restores
    // an older collapsed header state. The flag only opens an empty field.
    var searchRequested by rememberSaveable { mutableStateOf(false) }
    val searchExpanded = searchRequested || searchQuery.isNotBlank()

    val filteredBooks = allBooks.filter { book ->
        searchQuery.isBlank() ||
            book.title.contains(searchQuery, ignoreCase = true) ||
            book.author.contains(searchQuery, ignoreCase = true)
    }

    // Text-search mode: genre filtering has one home in the feed sheet.
    val inSearchMode = searchQuery.isNotBlank()

    HomeModalUnderlay(
        modalVisible = showRecommendationDisclosure || showWorkFeedFilters,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("home_screen"),
            contentPadding = PaddingValues(bottom = AppDimens.SpaceAboveMiniPlayer)
        ) {
        // Header & collapsible search (spec-22 T3) — the field and chips
        // expand from the header's [🔍] and close via ✕ or the Back gesture.
        item {
            HomeHeader(
                searchExpanded = searchExpanded,
                searchQuery = searchQuery,
                onToggleSearch = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    searchRequested = !searchExpanded
                    if (!searchRequested) {
                        if (searchQuery.isNotBlank()) viewModel.updateSearchQuery("")
                    }
                },
                onRefresh = { scope.launch { sourceCatalog.fetchCatalogSections(forceRefresh = true) } },
                onSearchQueryChange = { query ->
                    searchRequested = true
                    viewModel.updateSearchQuery(query)
                },
                onCloseSearch = {
                    searchRequested = false
                    if (searchQuery.isNotBlank()) viewModel.updateSearchQuery("")
                }
            )
        }

        if (inSearchMode) {
            // ---- Search / genre result list -------------------------------
            // Canonical authors are local and appear first, before both book
            // result sections. The compact block is capped at five.
            if (authorResults.isNotEmpty()) {
                item(key = "author_search_results") {
                    AuthorSearchResults(
                        authors = authorResults,
                        onAuthorClick = viewModel::openCanonicalAuthor,
                        onShowAll = viewModel::openAllAuthorSearchResults
                    )
                }
            }

            // In-library matches next, then the spec-10 T4 global section
            // (all sources, imported on tap).
            item {
                Text(
                    text = stringResource(R.string.home_library_results, filteredBooks.size),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { heading() }
                )
            }
            if (filteredBooks.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.SearchOff,
                        title = stringResource(R.string.home_search_no_results),
                        body = stringResource(R.string.home_search_no_results_hint),
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                        }
                    )
                }
            }
            items(filteredBooks, key = { it.id }) { book ->
                BookRow(
                    book = book,
                    onClick = { onBookClick(book.id) },
                    onPlayClick = { onPlayClick(book) }
                )
            }

            // Spec-10 T4: aggregated search across every verified source —
            // one card per Work with a source badge each. Only once the query
            // is long enough to actually search (the ViewModel debounces at
            // >= 2 chars).
            if (searchQuery.trim().length >= 2) {
                item {
                    Text(
                        text = stringResource(R.string.home_all_sources, globalResults.size),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .semantics { heading() }
                    )
                }
                if (globalResults.isEmpty()) {
                    item {
                        GlobalSearchStatus(
                            isLoading = isGlobalSearchLoading,
                            hasError = globalSearchError,
                            resultsEmpty = true
                        )
                    }
                    // Spec-42 #440: empty result set — offer the 4read catalogue
                    // pre-filled with the query (release-accessible, ADR-0027).
                    item {
                        OpenWebSourceRow(
                            displayName = "4read",
                            onClick = { viewModel.open4readSearch(searchQuery) },
                            text = stringResource(R.string.home_search_on_4read, searchQuery.trim()),
                            testTag = "open_4read_search_empty"
                        )
                    }
                } else {
                    // Some sources matched, but none resolved to 4read: surface a
                    // browser door below the results (spec-42 #440).
                    val has4read = globalResults.any { it.sources.any { s -> s.sourceId == "4read" } }
                    if (!has4read) {
                        item {
                            OpenWebSourceRow(
                                displayName = "4read",
                                onClick = { viewModel.open4readSearch(searchQuery) },
                                text = stringResource(R.string.home_search_browser_fallback),
                                testTag = "open_4read_search_footer"
                            )
                        }
                    }
                }
                items(globalResults, key = { it.key }) { result ->
                    GlobalSearchResultCard(
                        result = result,
                        onClick = { viewModel.openGlobalSearchResult(result) },
                        actionState = catalogCardActionState,
                        onOpenBrowser = viewModel::openCatalogBrowserRequired,
                        onPreflight = { viewModel.preflightGlobalSearchResult(result) }
                    )
                }
            }
        } else {
            // ---- Netflix feed ---------------------------------------------
            // spec-42 T1 (#302): one hierarchy keeps curated content above the
            // final endless feed, pinned by HomeFeedOrderSnapshotTest. The body lives in
            // [homeFeedContent] so the order is stateless and testable.

            // Spec-36 T1 (#244): the non-blocking update banner sits above
            // every content row; «Завантажити» hands off to the browser on
            // the release's direct apk link.
            availableRelease?.let { release ->
                item(key = "app_update_banner") {
                    UpdateBanner(
                        update = release,
                        onDownload = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl))
                            )
                        },
                        onDismiss = updateChecker::dismiss
                    )
                }
            }

            homeFeedContent(
                isCatalogLoading = isCatalogLoading,
                hasLibraryBooks = allBooks.isNotEmpty(),
                sections = sections,
                genreFacetOptions = genreFacetOptions,
                collections = collections,
                newArrivals = newArrivals,
                peopleNewArrivals = peopleNewArrivals,
                recommendedBooks = recommendedBooks,
                recommendationsReady = recommendationsReady,
                personalCycles = personalCycles,
                similarCycles = similarCycles,
                shortBooks = durationBooks.short.map { it.asCatalogBook() },
                longBooks = durationBooks.long.map { it.asCatalogBook() },
                workFeedItems = workFeedItems,
                feedGenreFilters = feedGenreFilters,
                feedDurationFilters = feedDurationFilters,
                feedSortByTitle = feedSortByTitle,
                // Spec-45 (#405) T6 (#494): the «Мова» chip mirrors the ONE
                // persisted preference (both on = «Усі»); one tap cycles it.
                contentLanguages = contentLanguages,
                onOpenContentLanguages = viewModel::openContentLanguages,
                onRefreshCatalog = { scope.launch { sourceCatalog.fetchCatalogSections(forceRefresh = true) } },
                onGoToLibrary = { viewModel.selectTab(com.slukhayka.audiobooks.ui.SelectedTab.LIBRARY) },
                onOpenTop100 = { viewModel.openTop100() },
                onOpenPeople = { kind ->
                    if (kind.title == "Автори") viewModel.openAuthorsIndex()
                    else viewModel.openPeople(kind)
                },
                onOpenSeriesIndex = { viewModel.openSeriesIndex() },
                onOpenCollectionsIndex = { viewModel.openCollectionsIndex() },
                onOpenSeries = { title, url -> viewModel.openSeries(title, url) },
                onOpenGlobalSearchResult = { viewModel.openGlobalSearchResult(it) },
                onMarkPeopleNewArrivalsSeen = {
                    scope.launch {
                        peopleNewArrivals.bookmarkKeys.forEach { personBookmarks.markSeen(it) }
                    }
                },
                onOpenRecommendedBook = { viewModel.openRecommendedBook(it) },
                onOpenCatalogBook = { viewModel.openCatalogBook(it) },
                onOpenWorkFeedRow = { viewModel.openWorkFeedRow(it) },
                onPlayWorkFeedRow = { viewModel.playWorkFeedRow(it) },
                catalogCardActionState = catalogCardActionState,
                onCancelCatalogCardAction = viewModel::cancelCatalogCardAction,
                onOpenCatalogBrowser = viewModel::openCatalogBrowserRequired,
                onPreflightGlobalSearchResult = viewModel::preflightGlobalSearchResult,
                onPreflightCatalogBook = viewModel::preflightCatalogBook,
                onPreflightRecommendedBook = viewModel::preflightRecommendedBook,
                onPreflightWorkFeedRow = viewModel::preflightWorkFeedRow,
                onBookClick = onBookClick,
                onSetFeedGenreFilters = { viewModel.setFeedGenreFilters(it) },
                onSetFeedDurationFilters = { viewModel.setFeedDurationFilters(it) },
                onSetFeedSortByTitle = { viewModel.setFeedSortByTitle(it) },
                onOpenFeedFilters = { showWorkFeedFilters = true },
                feedFilterTriggerModifier = Modifier.focusRequester(workFeedFilterTriggerFocusRequester),
                onOpenWebSource = onOpenWebSource,
                onOpenWebSource4read = onOpenWebSource4read,
                onRecommendationFeedback = { rec, kind ->
                    scope.launch {
                        val token = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            viewModel.recommendationPersonalization.applyFeedback(
                                rec.candidate.id,
                                rec.candidate.author,
                                kind
                            )
                        } ?: return@launch
                        val result = recommendationSnackbar.showSnackbar(
                            message = recommendationUpdated,
                            actionLabel = cancelLabel,
                            withDismissAction = true
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                viewModel.recommendationPersonalization.undo(token)
                            }
                        }
                    }
                },
                showRecommendationConsent = recommendationSettings.shouldOfferSharedLearning(System.currentTimeMillis()),
                onOpenRecommendationConsent = { showRecommendationDisclosure = true },
                onDeclineRecommendationConsent = viewModel.recommendationPersonalization::declineSharedLearning,
                recommendationDisclosureTriggerModifier = Modifier
                    .focusRequester(recommendationDisclosureTriggerFocusRequester)
                    .testTag("recommendation_disclosure_trigger")
            )

            // Spec-9: the full library list lives in Медіатека (Library tab),
            // not at the bottom of Огляд.
        }
        }
        SnackbarHost(
            hostState = recommendationSnackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)
        )
    }
    if (showWorkFeedFilters) {
        WorkFeedFilterSheet(
            selectedGenreIds = feedGenreFilters,
            genres = genreFacetOptions,
            onGenresChange = { viewModel.setFeedGenreFilters(it) },
            onDismiss = { showWorkFeedFilters = false },
            selectedDurationBucketIds = feedDurationFilters,
            onDurationBucketsChange = { viewModel.setFeedDurationFilters(it) }
        )
    }
    RecommendationDisclosureDialog(
        visible = showRecommendationDisclosure,
        returnFocusRequester = recommendationDisclosureTriggerFocusRequester,
        onAgree = {
            viewModel.recommendationPersonalization.setSharedLearningConsent(true)
            showRecommendationDisclosure = false
        },
        onDecline = {
            viewModel.recommendationPersonalization.declineSharedLearning()
            showRecommendationDisclosure = false
        },
        onDismiss = { showRecommendationDisclosure = false }
    )
}

/** The complete non-modal Огляд layer, including its Snackbar feedback. */
@Composable
internal fun HomeModalUnderlay(
    modalVisible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .testTag("home_modal_underlay")
            .accessibilityModalBackground(modalVisible),
        content = content
    )
}

@Composable
fun RecommendationDisclosureDialog(
    visible: Boolean,
    returnFocusRequester: FocusRequester,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit
) {
    val title = stringResource(R.string.recommendation_disclosure_title)
    val headingFocusRequester = remember { FocusRequester() }
    var restoreFocusAfterClose by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            restoreFocusAfterClose = true
        } else if (restoreFocusAfterClose) {
            withFrameNanos { }
            runCatching { returnFocusRequester.requestFocus() }
            restoreFocusAfterClose = false
        }
    }

    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .testTag("recommendation_disclosure_dialog")
            .accessibilityPane(title),
        title = {
            LaunchedEffect(Unit) {
                withFrameNanos { }
                headingFocusRequester.requestFocus()
            }
            Text(
                text = title,
                modifier = Modifier
                    .focusRequester(headingFocusRequester)
                    .focusable()
                    .semantics { heading() }
                    .testTag("recommendation_disclosure_heading")
            )
        },
        text = { Text(stringResource(R.string.recommendation_disclosure_body)) },
        confirmButton = {
            TextButton(
                onClick = onAgree,
                modifier = Modifier.testTag("recommendation_disclosure_agree")
            ) { Text(stringResource(R.string.recommendation_disclosure_agree)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDecline,
                modifier = Modifier.testTag("recommendation_disclosure_decline")
            ) { Text(stringResource(R.string.recommendation_disclosure_decline)) }
        }
    )
}

/** Honest, one-shot visible state for the cross-source search. */
@Composable
fun GlobalSearchStatus(
    isLoading: Boolean,
    hasError: Boolean,
    resultsEmpty: Boolean,
    modifier: Modifier = Modifier
) {
    if (!resultsEmpty) return
    val message = when {
        isLoading -> stringResource(R.string.a11y_search_loading)
        hasError -> stringResource(R.string.a11y_search_error)
        else -> stringResource(R.string.a11y_search_empty)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag("global_search_status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(28.dp)
                    .clearAndSetSemantics { },
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Explore header (spec-22 T3): brand row with [🔍] search toggle + [🔄]
 * refresh, and an expandable text-search field. State is hoisted so snapshot
 * tests can pin both collapsed and expanded without a ViewModel. ✕ or the
 * system Back collapses the search, clears the query and resets the filters.
 * Genre filtering lives only in the feed sheet.
 *
 * v1.4 C5 (ADR-0033): the brand lockup renders through the canonical
 * [AppTabHeader] — one tab-header model across all four tabs.
 */
@Composable
fun HomeHeader(
    searchExpanded: Boolean,
    searchQuery: String,
    onToggleSearch: () -> Unit,
    onRefresh: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val searchFieldLabel = stringResource(R.string.a11y_search_books)
    BackHandler(enabled = searchExpanded) { onCloseSearch() }
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) focusRequester.requestFocus()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        AppTabHeader(
            title = stringResource(R.string.app_name),
            showBrandMark = true,
            actions = {
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(AppDimens.TouchTarget).testTag("home_refresh")
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.a11y_refresh_catalogue)
                    )
                }
                IconButton(
                    onClick = onToggleSearch,
                    modifier = Modifier.size(AppDimens.TouchTarget).testTag("home_search_toggle")
                ) {
                    Icon(
                        imageVector = if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = stringResource(
                            if (searchExpanded) R.string.a11y_close_search else R.string.a11y_open_search
                        )
                    )
                }
            }
        )

        AnimatedVisibility(
            visible = searchExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    label = { Text(searchFieldLabel) },
                    placeholder = { Text(stringResource(R.string.home_search_placeholder)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        // ✕ collapses search and resets the filters (US-2).
                        IconButton(onClick = onCloseSearch, modifier = Modifier.testTag("home_search_close")) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = stringResource(R.string.a11y_clear_close_search)
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("home_search_input"),
                    shape = RoundedCornerShape(AppDimens.RadiusPanel),
                    colors = OutlinedTextFieldDefaults.colors(
                        // MD3: input fills sit on the highest tonal container.
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    singleLine = true
                )
            }
        }
    }
}

/**
 * spec-18 T3 (#114) — the Огляд «За тривалістю» section: two horizontal
 * cover rows — «Короткі — до 5 годин» (under 5 h) and «Довгі — від 10 годин»
 * (10 h and up). The bucketing itself is the pure
 * [com.slukhayka.audiobooks.data.duration.DurationBuckets] module; this
 * composable only renders what it is handed, so the snapshot seam pins it
 * from fixture data. Hidden entirely when both rows are empty.
 * Cards are the same cover-first canonical [PosterCard] as every Огляд row;
 * tapping opens the book page.
 *
 * spec-28 (#195): the headers are human-named shelves, not filter labels
 * (US-15) — the numbers state the REAL bucket bounds (DurationBuckets:
 * short < 5 h, long >= 10 h), so the label never lies about the content
 * (ADR-0014).
 */
@Composable
fun DurationSection(
    shortBooks: List<CatalogBook>,
    longBooks: List<CatalogBook>,
    onBookClick: (String) -> Unit,
    onOpenClick: (CatalogBook) -> Unit = { onBookClick(it.id) },
    actionState: CatalogCardActionState = CatalogCardActionState.Idle,
    onOpenBrowser: () -> Unit = {},
    onPreflight: (CatalogBook) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (shortBooks.isEmpty() && longBooks.isEmpty()) return
    Column(modifier = modifier.testTag("duration_section")) {
        if (shortBooks.isNotEmpty()) {
            AppSectionHeader(title = stringResource(R.string.home_short_books))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.testTag("duration_short_row")
            ) {
                items(shortBooks, key = { it.id }) { book ->
                    PosterCard(
                        title = book.title,
                        coverUrl = book.coverImageUrl,
                        onClick = { onOpenClick(book) },
                        duration = if (book.totalDurationSeconds > 0L) {
                            MainViewModel.formatTime(book.totalDurationSeconds)
                        } else {
                            null
                        },
                        preflightKey = book.id,
                        onPreflight = { onPreflight(book) },
                        testTag = "catalog_book_${book.id}",
                        actionHost = { CatalogCardStatus(book.id, actionState, onOpenBrowser) }
                    )
                }
            }
        }
        if (longBooks.isNotEmpty()) {
            AppSectionHeader(title = stringResource(R.string.home_long_books))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.testTag("duration_long_row")
            ) {
                items(longBooks, key = { it.id }) { book ->
                    PosterCard(
                        title = book.title,
                        coverUrl = book.coverImageUrl,
                        onClick = { onOpenClick(book) },
                        duration = if (book.totalDurationSeconds > 0L) {
                            MainViewModel.formatTime(book.totalDurationSeconds)
                        } else {
                            null
                        },
                        preflightKey = book.id,
                        onPreflight = { onPreflight(book) },
                        testTag = "catalog_book_${book.id}",
                        actionHost = { CatalogCardStatus(book.id, actionState, onOpenBrowser) }
                    )
                }
            }
        }
    }
}

/**
 * spec-28 (#192) — the cross-source «Новинки» rail: 4read's new arrivals
 * plus every other source's new feed, merged by Work with a source badge
 * per card. Public and stateless (pure `@Composable` inputs) so the
 * snapshot seam can pin the rail from fixture data.
 */
@Composable
fun NewArrivalsRail(
    results: List<GlobalSearchResult>,
    onBookClick: (GlobalSearchResult) -> Unit,
    actionState: CatalogCardActionState = CatalogCardActionState.Idle,
    onOpenBrowser: () -> Unit = {},
    onPreflight: (GlobalSearchResult) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.testTag("new_arrivals_rail")) {
        AppSectionHeader(title = stringResource(R.string.home_new_arrivals))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(results, key = { it.key }) { result ->
                // spec-28 (#192): the merged rail card carries a chip per
                // Source that carries the Work — provenance is the rail's
                // reason to exist (Work-dedup would hide it otherwise).
                // v1.4 C4 (ADR-0033): MetadataChip, not the old pill.
                Column(
                    modifier = Modifier.width(PosterWidth),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PosterCard(
                        result = result,
                        onClick = { onBookClick(result) },
                        preflightKey = result.key,
                        onPreflight = { onPreflight(result) },
                        actionHost = { CatalogCardStatus(result.key, actionState, onOpenBrowser) }
                    )
                    if (result.sources.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AppDimens.SpaceXs, Alignment.CenterHorizontally),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            result.sources.forEach { source ->
                                MetadataChip(source = source.sourceName)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** #402 — personal, locally-derived shelf above the general discovery feed. */
@Composable
fun PeopleNewArrivalsRail(
    results: List<GlobalSearchResult>,
    newCount: Int,
    onBookClick: (GlobalSearchResult) -> Unit,
    onMarkSeen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.testTag("people_new_arrivals_rail")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onMarkSeen)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.home_people_new_arrivals),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            TextButton(
                onClick = onMarkSeen,
                modifier = Modifier.testTag("people_new_arrivals_badge")
            ) { Text(stringResource(R.string.home_people_new_count, newCount)) }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(results, key = { it.key }) { result ->
                PosterCard(result = result, onClick = { onBookClick(result) })
            }
        }
    }
}

/**
 * Card of the on-device «Рекомендовано для вас» row (spec-19 Track A):
 * title + author, with the reason chip («схоже на X») underneath — the
 * engine explains every pick (Q3).
 */
@Composable
fun RecommendedBookCard(
    rec: com.slukhayka.audiobooks.data.recommend.RecommendationEngine.Recommendation,
    onClick: () -> Unit,
    actionState: CatalogCardActionState = CatalogCardActionState.Idle,
    onOpenBrowser: () -> Unit = {},
    onPreflight: () -> Unit = {},
    onFeedback: (String) -> Unit = {}
) {
    // v1.4 C2 (ADR-0033): the recommendation rejoins the poster rhythm — the
    // same canonical 120×168 PosterCard as every other shelf, its ⋮ menu as a
    // cover overlay. The reason line renders through the caption slot; the
    // per-Source badge keeps its own phrasing (#486).
    var menuExpanded by remember { mutableStateOf(false) }
    var feedbackExpanded by remember { mutableStateOf(false) }
    val reasonLine = if (rec.isExploration && rec.sourceLabel != null) {
        stringResource(R.string.home_recommendation_source_badge, rec.sourceLabel)
    } else {
        stringResource(R.string.home_cycle_similar, rec.reasonTitle)
    }
    com.slukhayka.audiobooks.ui.components.PosterCard(
        title = rec.candidate.title,
        coverUrl = rec.candidate.coverImageUrl,
        genre = rec.candidate.genre,
        author = rec.candidate.author.takeIf { it.isNotBlank() },
        onClick = onClick,
        caption = reasonLine,
        preflightKey = rec.candidate.id,
        onPreflight = onPreflight,
        testTag = "recommended_${rec.candidate.id}",
        actionHost = { CatalogCardStatus(rec.candidate.id, actionState, onOpenBrowser) },
        overlay = {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(AppDimens.TouchTarget)
                    .testTag("recommendation_menu_${rec.candidate.id}")
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(
                        R.string.a11y_recommendation_actions,
                        rec.candidate.title
                    )
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_not_recommend)) },
                    onClick = {
                        menuExpanded = false
                        feedbackExpanded = true
                    }
                )
            }
            DropdownMenu(expanded = feedbackExpanded, onDismissRequest = { feedbackExpanded = false }) {
                FeedbackMenuItem(
                    stringResource(R.string.a11y_hide_recommended_work, rec.candidate.title)
                ) {
                    onFeedback(com.slukhayka.audiobooks.data.db.RecommendationPreferenceEntity.HIDE_WORK)
                    feedbackExpanded = false
                }
                FeedbackMenuItem(
                    stringResource(R.string.a11y_reduce_similar_recommendations, rec.candidate.title)
                ) {
                    onFeedback(com.slukhayka.audiobooks.data.db.RecommendationPreferenceEntity.REDUCE_SIMILAR)
                    feedbackExpanded = false
                }
                if (rec.candidate.author.isNotBlank()) {
                    FeedbackMenuItem(
                        stringResource(R.string.a11y_hide_recommended_author, rec.candidate.author)
                    ) {
                        onFeedback(com.slukhayka.audiobooks.data.db.RecommendationPreferenceEntity.HIDE_AUTHOR)
                        feedbackExpanded = false
                    }
                }
            }
        }
    )
}

@Composable
private fun FeedbackMenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

/**
 * spec-28 (#198) — the Огляд five-chip navigation row (ТОП 100 / Виконавці /
 * Автори / Серії / Колекції), as [NavigationChip]s per ADR-0018: filled,
 * outline-free — the «перейти» form, never the filter form. Public and
 * stateless (pure callbacks) so the snapshot seam pins the real row.
 */
@Composable
fun CatalogNavRow(
    onTop100Click: () -> Unit,
    onPeopleClick: (com.slukhayka.audiobooks.ui.PeopleKind) -> Unit,
    onSeriesClick: () -> Unit,
    onCollectionsClick: () -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            NavigationChip(title = "ТОП 100", onClick = onTop100Click)
        }
        item {
            NavigationChip(
                title = "Виконавці",
                onClick = {
                    onPeopleClick(com.slukhayka.audiobooks.ui.PeopleKind("Виконавці", "https://4read.org/readers.html"))
                }
            )
        }
        item {
            NavigationChip(
                title = "Автори",
                onClick = {
                    onPeopleClick(com.slukhayka.audiobooks.ui.PeopleKind("Автори", "https://4read.org/avtors.html"))
                }
            )
        }
        // spec-28 (#189): «Серії» — a pushed index of every series aggregated
        // from the catalogue sections (the «Цикли» row), deduplicated by URL.
        // Tapping a series opens the existing series page.
        item {
            NavigationChip(title = "Серії", onClick = onSeriesClick)
        }
        // spec-28 (#190): «Колекції» — a pushed index of every matched smart
        // collection; tapping a book resolves-and-plays exactly like the
        // inline collection cards.
        item {
            NavigationChip(title = "Колекції", onClick = onCollectionsClick)
        }
    }
}

/** First-run empty catalogue: no mocks, just clear actions (spec #8 T1/T6). */
@Composable
fun EmptyCatalogState(
    onRefreshClick: () -> Unit,
    onImportClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.home_empty_catalog_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.home_empty_catalog_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(18.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRefreshClick,
                modifier = Modifier.heightIn(min = 48.dp).testTag("catalog_empty_refresh"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(AppDimens.RadiusCardLg)
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.a11y_refresh_catalogue), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onImportClick,
                modifier = Modifier.heightIn(min = 48.dp).testTag("catalog_empty_import"),
                shape = RoundedCornerShape(AppDimens.RadiusCardLg),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Icon(imageVector = Icons.Default.FileUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.home_import_file), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/**
 * One row of the endless merged feed: a Work without Source chrome. Tapping
 * resolves the Work's first Edition and import-and-plays it (the same path as
 * global-search cards); provenance remains in the underlying metadata.
 * Pure `@Composable` — pinned by the snapshot seam from fixture rows.
 */
@Composable
fun WorkFeedCard(
    row: WorkFeedRow,
    onClick: () -> Unit,
    onPlayClick: () -> Unit = onClick,
    actionState: CatalogCardActionState = CatalogCardActionState.Idle,
    onCancelAction: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onPreflight: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    LaunchedEffect(row.workId) { onPreflight() }
    val rowState = actionState.takeIf { state ->
        when (state) {
            is CatalogCardActionState.Checking -> state.target.cardKey == row.workId
            is CatalogCardActionState.Completed -> state.target.cardKey == row.workId
            is CatalogCardActionState.BrowserRequired -> state.target.cardKey == row.workId
            is CatalogCardActionState.Failed -> state.target.cardKey == row.workId
            is CatalogCardActionState.Cancelled -> state.target.cardKey == row.workId
            CatalogCardActionState.Idle -> false
        }
    }
    val checking = rowState is CatalogCardActionState.Checking
    val openDescription = stringResource(R.string.a11y_open_work, row.title)
    val listenDescription = stringResource(R.string.a11y_catalog_card_listen, row.title)

    // v1.4 C3 (ADR-0033): the feed row IS the canonical BookRow — the old
    // 56×80 bordered-card body is gone. Language chips ride the badges slot;
    // the action status stays under the row via the footnote slot.
    BookRow(
        title = row.title,
        modifier = modifier,
        coverUrl = row.coverImageUrl,
        author = row.author.takeIf { it.isNotBlank() },
        stats = EditionDurationPolicy.summarize(
            listOfNotNull(row.durationSeconds, row.durationMaxSeconds)
        )?.let { feedDuration ->
            when (feedDuration) {
                is EditionDurationSummary.Single ->
                    MainViewModel.formatTime(feedDuration.seconds)
                is EditionDurationSummary.Range ->
                    "${MainViewModel.formatTime(feedDuration.shortestSeconds)}–" +
                        MainViewModel.formatTime(feedDuration.longestSeconds)
            }
        },
        badges = {
            // Spec-45 (#405) T7 (#495): the rendition's known languages — one
            // EN/UA chip per language, sorted; unknown renders nothing (US3).
            row.languages
                .split(',')
                .mapNotNull { LanguageCode.normalize(it.trim()) }
                .distinct()
                .sorted()
                .forEach { lang ->
                    MetadataChip(language = lang)
                    Spacer(modifier = Modifier.width(4.dp))
                }
        },
        trailing = {
            if (checking) {
                IconButton(
                    onClick = onCancelAction,
                    modifier = Modifier.size(AppDimens.TouchTarget)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.catalog_card_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                IconButton(
                    onClick = onPlayClick,
                    modifier = Modifier.size(AppDimens.TouchTarget)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = listenDescription,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        contentDescription = openDescription,
        onClick = if (checking) null else onClick,
        testTag = "work_feed_${row.workId}",
        footnote = {
            val statusText = when (val state = rowState) {
                is CatalogCardActionState.Checking -> stringResource(R.string.catalog_card_checking)
                is CatalogCardActionState.BrowserRequired -> stringResource(R.string.catalog_card_browser_required)
                is CatalogCardActionState.Failed -> stringResource(
                    when {
                        state.reason == CatalogCardFailure.AUDIO_REFUSED -> R.string.catalog_card_audio_refused
                        state.action == CatalogCardAction.OPEN -> R.string.catalog_card_open_error
                        else -> R.string.catalog_card_play_error
                    }
                )
                is CatalogCardActionState.Cancelled -> stringResource(R.string.catalog_card_cancelled)
                else -> null
            }
            if (statusText != null) {
                Column(
                    modifier = Modifier
                        .padding(start = 68.dp, bottom = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (rowState is CatalogCardActionState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (rowState is CatalogCardActionState.BrowserRequired) {
                        TextButton(
                            onClick = onOpenBrowser,
                            modifier = catalogBrowserReturnFocusModifier(row.workId)
                                .testTag("catalog_card_open_browser_${row.workId}")
                        ) {
                            Text(stringResource(R.string.catalog_card_open_browser))
                        }
                    }
                }
            }
        }
    )
}

/**
 * spec-42 T1 (#302) — the endless feed's compact sticky toolbar. It exposes
 * one current-value sort control and one selected/unselected filter control.
 * Genre ids are committed immediately as an OR-set through the local facet
 * seam; the sheet has no draft state and repeated taps toggle one value.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkFeedFilters(
    selectedGenreIds: Set<String>,
    sortByTitle: Boolean,
    genres: List<GenreFacetOption>,
    onGenresChange: (Set<String>) -> Unit,
    onSortChange: (Boolean) -> Unit,
    selectedDurationBucketIds: Set<String> = emptySet(),
    onDurationBucketsChange: (Set<String>) -> Unit = {},
    onOpenFilters: (() -> Unit)? = null,
    filterTriggerModifier: Modifier = Modifier,
    // Spec-45 (#405) T6 (#494): the «Мова» chip — one tap cycles the
    // content-language preference (US8); the chip reads the current state.
    contentLanguages: Set<String>? = null,
    onOpenContentLanguages: () -> Unit = {}
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints {
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val compact = maxWidth < 400.dp * fontScale.coerceAtLeast(1f) || fontScale > 1.3f
        val sortLabel = stringResource(if (sortByTitle) R.string.feed_sort_title else R.string.feed_sort_newest)
        val filtersLabel = stringResource(R.string.feed_filters)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth().testTag("work_feed_toolbar")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Box {
                    OutlinedButton(
                        onClick = { sortExpanded = true },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .then(if (compact) Modifier.width(56.dp) else Modifier)
                            .semantics { contentDescription = sortLabel }
                            .testTag("feed_sort")
                    ) {
                        if (compact) {
                            Icon(Icons.Default.Sort, contentDescription = null)
                        } else {
                            Text(sortLabel, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                    }
                    DropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.feed_sort_newest)) },
                            onClick = {
                                if (sortByTitle) onSortChange(false)
                                sortExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.feed_sort_title)) },
                            onClick = {
                                if (!sortByTitle) onSortChange(true)
                                sortExpanded = false
                            }
                        )
                    }
                }
                FilterChip(
                    selected = selectedGenreIds.isNotEmpty() || selectedDurationBucketIds.isNotEmpty(),
                    onClick = {
                        if (onOpenFilters != null) onOpenFilters() else showFilterSheet = true
                    },
                    label = {
                        if (compact) Icon(Icons.Default.Tune, null, Modifier.size(24.dp))
                        else Text(filtersLabel, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
                    },
                    leadingIcon = if (compact) null else { {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    } },
                    modifier = filterTriggerModifier
                        .height(48.dp)
                        .then(if (compact) Modifier.width(56.dp) else Modifier)
                        .semantics { contentDescription = filtersLabel }
                        .testTag("feed_filters")
                )
                contentLanguages?.let { languages ->
                    ContentLanguageChip(languages, onOpenContentLanguages)
                }
            }
        }
    }

    if (showFilterSheet) {
        WorkFeedFilterSheet(
            selectedGenreIds = selectedGenreIds,
            genres = genres,
            onGenresChange = onGenresChange,
            onDismiss = { showFilterSheet = false },
            selectedDurationBucketIds = selectedDurationBucketIds,
            onDurationBucketsChange = onDurationBucketsChange
        )
    }
}

/** Modal catalogue-facet surface, kept outside the Home underlay by its owner. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkFeedFilterSheet(
    selectedGenreIds: Set<String>,
    genres: List<GenreFacetOption>,
    onGenresChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    selectedDurationBucketIds: Set<String> = emptySet(),
    onDurationBucketsChange: (Set<String>) -> Unit = {}
) {
    val paneTitle = stringResource(R.string.a11y_work_feed_filter_pane)
    val headingFocusRequester = remember { FocusRequester() }
    val filterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(filterSheetState) {
        snapshotFlow { filterSheetState.currentValue }
            .first { it == SheetValue.Expanded }
        withFrameNanos { }
        headingFocusRequester.requestFocus()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = filterSheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .testTag("work_feed_filter_sheet")
            .accessibilityPane(paneTitle)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val genreListMaxHeight = maxHeight * 0.55f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
            ) {
                Text(
                    text = stringResource(R.string.feed_filters),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .focusRequester(headingFocusRequester)
                        .focusable()
                        .testTag("work_feed_filter_heading")
                        .semantics { heading() }
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(text = stringResource(R.string.feed_genres), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .heightIn(max = genreListMaxHeight)
                        .verticalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = selectedGenreIds.isEmpty(),
                        onClick = { onGenresChange(emptySet()) },
                        label = { Text(stringResource(R.string.feed_all_genres)) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("feed_genre_all")
                    )
                    genres.forEach { genre ->
                        FilterChip(
                            selected = genre.id in selectedGenreIds,
                            onClick = {
                                onGenresChange(
                                    if (genre.id in selectedGenreIds) selectedGenreIds - genre.id
                                    else selectedGenreIds + genre.id
                                )
                            },
                            label = { Text(genre.label) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("feed_genre_${genre.id}")
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(text = stringResource(R.string.feed_duration), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EditionDurationPolicy.buckets.forEach { bucket ->
                        FilterChip(
                            selected = bucket.wireName in selectedDurationBucketIds,
                            onClick = {
                                onDurationBucketsChange(
                                    if (bucket.wireName in selectedDurationBucketIds) {
                                        selectedDurationBucketIds - bucket.wireName
                                    } else {
                                        selectedDurationBucketIds + bucket.wireName
                                    }
                                )
                            },
                            label = { Text(EditionDurationPolicy.labelFor(bucket)) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("feed_duration_${bucket.wireName}")
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            onGenresChange(emptySet())
                            onDurationBucketsChange(emptySet())
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("feed_filter_reset")
                    ) { Text(stringResource(R.string.feed_reset_all)) }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("feed_filter_done")
                    ) { Text(stringResource(R.string.feed_done)) }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

/** The card shape the Огляд rows render for a real library book row. */
private fun AudiobookEntity.asCatalogBook() = CatalogBook(
    id = id,
    title = title,
    author = author,
    url = sourceUrl,
    coverImageUrl = coverImageUrl,
    totalDurationSeconds = totalDurationSeconds,
    workId = workId,
    mergeKey = mergeKey,
    narrator = narrator
)
