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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.catalog.CatalogSeries
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.duration.ChapterDurationProbe
import com.slukhayka.audiobooks.data.duration.DurationEnrichment
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.update.UpdateChecker
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import com.slukhayka.audiobooks.ui.DurationBooks
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.EmptyState
import com.slukhayka.audiobooks.ui.components.BookCoverSemantics
import com.slukhayka.audiobooks.ui.components.NavigationChip
import com.slukhayka.audiobooks.ui.components.UpdateBanner
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.components.genreAccentColor
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.durationBooksFrom
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
    onOpenWebSource: (() -> Unit)? = null
) {
    // ADR-0008: module flows are read directly — no forwarding StateFlow on
    // the ViewModel. Cold flows need an initial value; the catalogue StateFlows
    // carry their own.
    val allBooks by libraryEntries.allBooks.collectAsState(initial = emptyList())
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedGenre by viewModel.selectedGenreFilter.collectAsState()
    val sections by sourceCatalog.catalogSections.collectAsState()
    val isCatalogLoading by sourceCatalog.isCatalogLoading.collectAsState()
    val catalogGenres by sourceCatalog.catalogGenres.collectAsState()
    // Spec-10 T4: aggregated global search across all verified sources.
    val globalResults by viewModel.globalSearchResults.collectAsState()
    val isGlobalSearchLoading by viewModel.isGlobalSearchLoading.collectAsState()
    val globalSearchError by viewModel.globalSearchError.collectAsState()
    // Spec-23 T4: the endless merged feed (Paging 3) over the persisted
    // Works/Editions catalogue — pages through the whole catalogue, one card
    // per Work, dedup inherited from merge-on-write. Filter/sort states live
    // in the ViewModel (they rebuild the Pager); the feed is collected here.
    val workFeedItems = viewModel.workFeed.collectAsLazyPagingItems()
    val feedSourceFilter by viewModel.feedSourceFilter.collectAsState()
    val feedGenreFilter by viewModel.feedGenreFilter.collectAsState()
    val feedSortByTitle by viewModel.feedSortByTitle.collectAsState()
    // Spec-19 Track A: the on-device «Рекомендовано для вас» row — semantic
    // similarity of catalogue descriptions to favourite/completed/recent
    // signals, computed locally, with a per-card reason chip.
    val recommendedBooks by viewModel.recommendedBooks.collectAsState()
    val recommendationSettings by viewModel.recommendationSettings.collectAsState()
    var showRecommendationDisclosure by rememberSaveable { mutableStateOf(false) }
    val recommendationDisclosureTriggerFocusRequester = remember { FocusRequester() }

    // Spec-39 T1 (#261): «Ваші цикли» — derived purely from the local base
    // (library rows + Listening State + every known Work), no network and no
    // loading states; recomputed only when an input actually changes.
    val recentProgress by libraryEntries.recentProgress.collectAsState(initial = emptyList())
    val allWorks by sourceCatalog.allWorks.collectAsState(initial = emptyList())

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
    LaunchedEffect(Unit) {
        sourceCatalog.refreshUnifiedCatalog()
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
    var searchExpanded by rememberSaveable { mutableStateOf(false) }

    // Spec-27 (#205, BUG-007): the search-mode filter chips are the LIVE
    // catalogue genres — never a hardcoded list (the old one duplicated the
    // sources' genre sidebar and shipped an EN «Cyberpunk»). «Усі» stays as
    // the clear-filter chip; the row appears once the homepage sync delivers
    // the genre sidebar.
    val genres = remember(catalogGenres) { listOf("Усі") + catalogGenres.map { it.title } }

    val filteredBooks = allBooks.filter { book ->
        val matchesSearch = searchQuery.isBlank() ||
            book.title.contains(searchQuery, ignoreCase = true) ||
            book.author.contains(searchQuery, ignoreCase = true)

        // Any selected chip filters the in-library results by the book's
        // genre text; «Усі» clears the filter. The special-case branches
        // (Завантажені/Короткі/Cyberpunk/…) died with the hardcoded list.
        val matchesGenre = when (selectedGenre) {
            "Усі", "All" -> true
            else -> book.genre.contains(selectedGenre, ignoreCase = true)
        }

        matchesSearch && matchesGenre
    }

    // Search/genre mode: a plain result list, no rows.
    val inSearchMode = searchQuery.isNotBlank() || selectedGenre != "Усі"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .accessibilityModalBackground(showRecommendationDisclosure)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("home_screen"),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
        // Header & collapsible search (spec-22 T3) — the field and chips
        // expand from the header's [🔍] and close via ✕ or the Back gesture.
        item {
            HomeHeader(
                searchExpanded = searchExpanded,
                searchQuery = searchQuery,
                selectedGenre = selectedGenre,
                genres = genres,
                onToggleSearch = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    searchExpanded = !searchExpanded
                    if (!searchExpanded) {
                        if (searchQuery.isNotBlank()) viewModel.updateSearchQuery("")
                        if (selectedGenre != "Усі") viewModel.selectGenreFilter("Усі")
                    }
                },
                onRefresh = { scope.launch { sourceCatalog.fetchCatalogSections() } },
                onSearchQueryChange = viewModel::updateSearchQuery,
                onCloseSearch = {
                    searchExpanded = false
                    if (searchQuery.isNotBlank()) viewModel.updateSearchQuery("")
                    if (selectedGenre != "Усі") viewModel.selectGenreFilter("Усі")
                },
                onSelectGenre = { genre ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.selectGenreFilter(genre)
                }
            )
        }

        if (inSearchMode) {
            // ---- Search / genre result list -------------------------------
            // In-library matches first (local filter, instant), then the
            // spec-10 T4 global section (all sources, imported on tap).
            item {
                Text(
                    text = "У вашій медіатеці (${filteredBooks.size})",
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
                        title = "Нічого не знайдено",
                        body = "Спробуйте змінити запит або фільтр.",
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                        }
                    )
                }
            }
            items(filteredBooks, key = { it.id }) { book ->
                AudiobookListItem(
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
                        text = "Усі джерела (${globalResults.size})",
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
                }
                items(globalResults, key = { it.key }) { result ->
                    GlobalSearchResultCard(
                        result = result,
                        onClick = { viewModel.openGlobalSearchResult(result) }
                    )
                }
            }
        } else {
            // ---- Netflix feed ---------------------------------------------
            // spec-28 (#203): the block order is fixed by the spec (curated
            // content above the endless feed, «Весь каталог» always last) and
            // pinned by HomeFeedOrderSnapshotTest. The body lives in
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
                catalogGenres = catalogGenres,
                collections = collections,
                newArrivals = newArrivals,
                recommendedBooks = recommendedBooks,
                personalCycles = personalCycles,
                similarCycles = similarCycles,
                shortBooks = durationBooks.short.map { it.asCatalogBook() },
                longBooks = durationBooks.long.map { it.asCatalogBook() },
                workFeedItems = workFeedItems,
                feedSourceFilter = feedSourceFilter,
                feedGenreFilter = feedGenreFilter,
                feedSortByTitle = feedSortByTitle,
                onRefreshCatalog = { scope.launch { sourceCatalog.fetchCatalogSections() } },
                onGoToLibrary = { viewModel.selectTab(com.slukhayka.audiobooks.ui.SelectedTab.LIBRARY) },
                onOpenTop100 = { viewModel.openTop100() },
                onOpenPeople = { viewModel.openPeople(it) },
                onOpenSeriesIndex = { viewModel.openSeriesIndex() },
                onOpenCollectionsIndex = { viewModel.openCollectionsIndex() },
                onOpenGenre = { title, url -> viewModel.openGenre(title, url) },
                onOpenSeries = { title, url -> viewModel.openSeries(title, url) },
                onPlayGlobalSearchResult = { viewModel.openGlobalSearchResult(it) },
                onOpenRecommendedBook = { viewModel.openRecommendedBook(it) },
                onOpenWorkFeedRow = { viewModel.openWorkFeedRow(it) },
                onBookClick = onBookClick,
                onSetFeedSourceFilter = { viewModel.setFeedSourceFilter(it) },
                onSetFeedGenreFilter = { viewModel.setFeedGenreFilter(it) },
                onSetFeedSortByTitle = { viewModel.setFeedSortByTitle(it) },
                onOpenWebSource = onOpenWebSource,
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
                            message = "Рекомендацію оновлено",
                            actionLabel = "Скасувати",
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
 * refresh, and an expandable search field with genre/mood chips. State is
 * hoisted so snapshot tests can pin both collapsed and expanded without a
 * ViewModel. ✕ or the system Back collapses the search, clears the query
 * and resets the filter to «Усі».
 */
@Composable
fun HomeHeader(
    searchExpanded: Boolean,
    searchQuery: String,
    selectedGenre: String,
    genres: List<String>,
    onToggleSearch: () -> Unit,
    onRefresh: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onSelectGenre: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val searchFieldLabel = stringResource(R.string.a11y_search_books)
    BackHandler(enabled = searchExpanded) { onCloseSearch() }
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) focusRequester.requestFocus()
    }

    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Слухайка",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
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
        }

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
                    placeholder = { Text("Пошук книги або автора...") },
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

                Spacer(modifier = Modifier.height(14.dp))

                // Genre/mood filter chips — revealed with the search field.
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(genres) { genre ->
                        val isSelected = selectedGenre == genre
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectGenre(genre) },
                            label = { Text(genre) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                labelColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = MaterialTheme.colorScheme.outlineVariant,
                                selectedBorderColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("home_genre_chip_$genre")
                        )
                    }
                }
            }
        }
    }
}

/** Section heading for a Netflix row (spec #8 ticket T6). */
@Composable
fun CatalogRowHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            .semantics { heading() }
    )
}

/**
 * spec-18 T3 (#114) — the Огляд «За тривалістю» section: two horizontal
 * cover rows — «Короткі — до 5 годин» (under 5 h) and «Довгі — від 10 годин»
 * (10 h and up). The bucketing itself is the pure
 * [com.slukhayka.audiobooks.data.duration.DurationBuckets] module; this
 * composable only renders what it is handed, so the snapshot seam pins it
 * from fixture data. Hidden entirely when both rows are empty.
 * Cards are the same cover-first [CatalogBookCard] as every Огляд row;
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
    modifier: Modifier = Modifier
) {
    if (shortBooks.isEmpty() && longBooks.isEmpty()) return
    Column(modifier = modifier.testTag("duration_section")) {
        if (shortBooks.isNotEmpty()) {
            CatalogRowHeader(title = "Короткі — до 5 годин")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.testTag("duration_short_row")
            ) {
                items(shortBooks, key = { it.id }) { book ->
                    CatalogBookCard(book = book, onClick = { onBookClick(book.id) })
                }
            }
        }
        if (longBooks.isNotEmpty()) {
            CatalogRowHeader(title = "Довгі — від 10 годин")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.testTag("duration_long_row")
            ) {
                items(longBooks, key = { it.id }) { book ->
                    CatalogBookCard(book = book, onClick = { onBookClick(book.id) })
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
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.testTag("new_arrivals_rail")) {
        CatalogRowHeader(title = "Новинки")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(results, key = { it.key }) { result ->
                UnifiedCatalogCard(
                    result = result,
                    onClick = { onBookClick(result) }
                )
            }
        }
    }
}

/**
 * Spec-13 T3 — compact «більше книг на Sluhay →» exit row to the source's
 * browser surface (spec-28 #192: re-homed from Слухати to Огляд as a
 * footer CTA, not a content shelf). One line, not a storefront.
 */
@Composable
fun OpenWebSourceRow(
    displayName: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
            .clickable { onClick() }
            .testTag("open_web_source_sluhay"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Більше книг на $displayName",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


/**
 * Cover-first card for the horizontal catalogue rows: a portrait cover with
 * the title underneath — the Netflix look.
 */
@Composable
fun CatalogBookCard(
    book: CatalogBook,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable { onClick() }
            .testTag("catalog_book_${book.id}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CatalogCoverImage(
            coverImageUrl = book.coverImageUrl,
            title = book.title,
            semantics = BookCoverSemantics.Decorative,
            modifier = Modifier
                .width(120.dp)
                .height(168.dp)
                .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusCardLg))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        // Spec-24 T1: the full book duration under the title (the cover card
        // shows no author today) — only when the duration is really known.
        if (book.totalDurationSeconds > 0L) {
            Text(
                text = MainViewModel.formatTime(book.totalDurationSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Spec-16 — cover-first card of a smart-collection row: the union card
 * (Work) with its cover and title, uniform with the other Огляд cover cards.
 * Tapping resolves the Work through the same identity as any global-search
 * card (import-and-play).
 */
@Composable
fun CollectionBookCard(
    result: com.slukhayka.audiobooks.data.source.GlobalSearchResult,
    onClick: () -> Unit
) {
    val openLabel = stringResource(R.string.a11y_open_work, result.title)
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClickLabel = openLabel, onClick = onClick)
            .testTag("collection_book_${result.key.hashCode()}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CatalogCoverImage(
            coverImageUrl = result.coverImageUrl,
            title = result.title,
            semantics = BookCoverSemantics.Decorative,
            modifier = Modifier
                .width(120.dp)
                .height(168.dp)
                .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusCardLg))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = result.title,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
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
    onFeedback: (String) -> Unit = {}
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var feedbackExpanded by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(200.dp)
            .testTag("recommended_${rec.candidate.id}"),
        shape = RoundedCornerShape(AppDimens.RadiusCardLg),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = rec.candidate.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
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
                            text = { Text("Не рекомендувати…") },
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
            }
            if (rec.candidate.author.isNotBlank()) {
                Text(
                    text = rec.candidate.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "Схоже на «${rec.reasonTitle}»",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
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

/** Wide cover card for a series (cycle) chip. */
@Composable
fun CatalogSeriesCard(
    series: CatalogSeries,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val openLabel = stringResource(R.string.a11y_open_series, series.title)
    Column(
        modifier = modifier
            .width(132.dp)
            .clickable(onClickLabel = openLabel, onClick = onClick)
            .testTag("catalog_series_${series.url.hashCode()}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CatalogCoverImage(
            coverImageUrl = series.coverImageUrl,
            title = series.title,
            semantics = BookCoverSemantics.Decorative,
            modifier = Modifier
                .width(132.dp)
                .height(78.dp)
                .clip(RoundedCornerShape(AppDimens.RadiusCard))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusCard))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = series.title,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Spec-39 T1/T2 (#261/#262) — one «Ваші цикли» card: the same landscape form
 * as the catalogue series card (visual unity with Огляд). Own cycles carry
 * the honest «Прослухано X із Y» line — rendered only from real numbers,
 * never as a placeholder (ADR-0014). Similar-tier cycles ([PersonalCycle]
 * with a [PersonalCycle.reasonTitle]) carry the engine's reason chip
 * («схоже на X») instead of progress — the listener owns nothing there.
 * Tapping opens the same series page as every other series entry.
 */
@Composable
fun PersonalCycleCard(
    cycle: com.slukhayka.audiobooks.ui.library.PersonalCycle,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(132.dp)
            .clickable { onClick() }
            .testTag("personal_cycle_${cycle.url.hashCode()}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CatalogCoverImage(
            coverImageUrl = cycle.coverImageUrl,
            title = cycle.title,
            semantics = BookCoverSemantics.Decorative,
            modifier = Modifier
                .width(132.dp)
                .height(78.dp)
                .clip(RoundedCornerShape(AppDimens.RadiusCard))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusCard))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = cycle.title,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        val subtitle = when {
            // Similar tier: the engine's reason explains the pick.
            cycle.reasonTitle != null -> "Схоже на «${cycle.reasonTitle}»"
            // The honest progress magnet — only when both numbers are real.
            cycle.totalCount > 0 -> "Прослухано ${cycle.listenedCount} із ${cycle.totalCount}"
            else -> null
        }
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                color = if (cycle.reasonTitle != null) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (cycle.reasonTitle != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Spec-39 T2 (#262) — one «Схожі цикли» card: the same landscape form as
 * [PersonalCycleCard], but the magnet line is the engine's reason chip
 * («схоже на X») instead of a progress count (ADR-0014: only real data).
 */
@Composable
fun SimilarCycleCard(
    cycle: com.slukhayka.audiobooks.ui.library.SimilarCycle,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(132.dp)
            .clickable { onClick() }
            .testTag("similar_cycle_" + cycle.url.hashCode()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CatalogCoverImage(
            coverImageUrl = cycle.coverImageUrl,
            title = cycle.title,
            semantics = BookCoverSemantics.Decorative,
            modifier = Modifier
                .width(132.dp)
                .height(78.dp)
                .clip(RoundedCornerShape(AppDimens.RadiusCard))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusCard))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = cycle.title,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "схоже на ${cycle.reasonTitle}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Remote-cover image with the same genre-tinted typographic fallback as
 * BookCoverImage (spec-22 T3). [genre] is optional — catalogue rows usually
 * carry no genre, so they keep the brand-accent gradient unchanged.
 */
@Composable
fun CatalogCoverImage(
    coverImageUrl: String?,
    title: String,
    semantics: BookCoverSemantics,
    modifier: Modifier = Modifier,
    genre: String? = null
) {
    val context = LocalContext.current
    var isError by remember(coverImageUrl) { mutableStateOf(false) }
    val resolvedContentDescription = when (semantics) {
        BookCoverSemantics.Decorative -> null
        is BookCoverSemantics.Meaningful -> semantics.description
    }

    if (!coverImageUrl.isNullOrBlank() && !isError) {
        val request = remember(coverImageUrl) {
            ImageRequest.Builder(context)
                .data(coverImageUrl)
                // Spec-38: UA rides the shared image loader's browser identity.
                .setHeader("Referer", "https://4read.org/")
                .crossfade(true)
                .allowHardware(false)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = resolvedContentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            onError = { isError = true }
        )
    } else {
        val fallbackAccent = genreAccentColor(genre)
        Box(
            modifier = modifier
                .clearAndSetSemantics {
                    resolvedContentDescription?.let { contentDescription = it }
                }
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            (fallbackAccent ?: MaterialTheme.colorScheme.primary)
                                .copy(alpha = if (fallbackAccent != null) 0.45f else 0.25f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
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
            text = "Знайдіть свою першу книгу",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Каталог українських аудіокниг ще завантажується. Оновіть, або додайте власний аудіофайл.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onRefreshClick,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(AppDimens.RadiusCardLg)
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Оновити каталог", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onImportClick,
                shape = RoundedCornerShape(AppDimens.RadiusCardLg),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Icon(imageVector = Icons.Default.FileUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Імпортувати файл", color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun AudiobookListItem(
    book: AudiobookEntity,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val availabilityState = stringResource(
        if (book.isDownloaded) R.string.a11y_available_offline
        else R.string.a11y_connection_required
    )
    val openLabel = stringResource(R.string.a11y_open_work, book.title)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics {
                stateDescription = availabilityState
            }
            .clip(RoundedCornerShape(AppDimens.RadiusPanel))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusPanel))
            .clickable(onClickLabel = openLabel, onClick = onClick)
            .testTag("book_item_${book.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.slukhayka.audiobooks.ui.components.BookCoverImage(
                book = book,
                semantics = BookCoverSemantics.Decorative,
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(AppDimens.RadiusCard)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // "4read Каталог" is the placeholder genre for catalogue
                    // books — skip it so every list row isn't labelled "4read".
                    if (book.genre.isNotBlank() && !book.genre.contains("4read", ignoreCase = true)) {
                        Text(
                            text = book.genre,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (book.isDownloaded) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = book.displayAuthor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Only the values we actually know — catalogue books start
                // with 0 chapters / 0 duration until their page is fetched.
                // Each part renders only when known, so a source that carries a
                // real duration but no chapter count (e.g. "Популярне") shows
                // just the duration, never "0 Chapters".
                val chaptersLabel = if (book.totalChapters > 0) {
                    pluralStringResource(R.plurals.chapter_count, book.totalChapters, book.totalChapters)
                } else {
                    null
                }
                val durationLabel = if (book.totalDurationSeconds > 0L) MainViewModel.formatTime(book.totalDurationSeconds) else null
                val statsLabel = when {
                    chaptersLabel != null && durationLabel != null -> "$chaptersLabel • $durationLabel"
                    chaptersLabel != null -> chaptersLabel
                    durationLabel != null -> durationLabel
                    else -> null
                }
                if (statsLabel != null) {
                    Text(
                        text = statsLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            val haptic = LocalHapticFeedback.current
            IconButton(
                onClick = {
                    // Spec-22 T3: a light tick on playback start.
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPlayClick()
                },
                modifier = Modifier
                    .size(AppDimens.TouchTarget)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.a11y_play_work, book.title),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

// Spec-23 T4: the endless merged feed's source chips — the adapter source
// ids as stored on `editions.sourceId` (must match writeWorkEdition callers).
private val WorkFeedSources = listOf("4read", "sluhay", "sluhayua", "soundbooks", "audiobookmp3", "lihtar")

/**
 * Spec-23 T4 — one row of the endless merged feed: a Work with its
 * «N джерел» badge (the T5 badge input). Tapping resolves the Work's first
 * Edition and import-and-plays it (the same path as global-search cards).
 * Pure `@Composable` — pinned by the snapshot seam from fixture rows.
 */
@Composable
fun WorkFeedCard(
    row: WorkFeedRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("work_feed_${row.workId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CatalogCoverImage(
            coverImageUrl = row.coverImageUrl,
            title = row.title,
            semantics = BookCoverSemantics.Decorative,
            modifier = Modifier
                .width(56.dp)
                .height(80.dp)
                .clip(RoundedCornerShape(AppDimens.RadiusCard))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (row.author.isNotBlank()) {
                Text(
                    text = row.author,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Spec-24 T1: the full book duration under the author — always
            // with seconds (Ч:ММ:СС), and only when the Edition's total is
            // really known (never a fabricated «0:00»).
            val feedDuration = row.durationSeconds?.takeIf { it > 0L }
            if (feedDuration != null) {
                Text(
                    text = MainViewModel.formatTime(feedDuration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Spec-23 T5: the «N джерел» badge appears only when more than one
            // source carries the Work — a single source needs no badge.
            if (row.sourceCount > 1) {
                Spacer(modifier = Modifier.height(4.dp))
                SourceBadgePill(label = editionBadgeLabel(row.sourceCount))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
    }
}

private fun editionBadgeLabel(count: Int): String = when {
    count <= 1 -> "1 джерело"
    count <= 4 -> "$count джерела"
    else -> "$count джерел"
}

/**
 * Spec-23 T4 — the endless feed's filter bar: source chips, genre chips and
 * a sort toggle. Filters rebuild the Pager in the ViewModel, so this stays a
 * pure `@Composable` over hoisted state — pinnable by the snapshot seam.
 */
@Composable
fun WorkFeedFilters(
    sourceFilter: String?,
    genreFilter: String?,
    sortByTitle: Boolean,
    genres: List<String>,
    onSourceChange: (String?) -> Unit,
    onGenreChange: (String?) -> Unit,
    onSortToggle: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            FilterChip(
                selected = !sortByTitle,
                onClick = { if (sortByTitle) onSortToggle() },
                label = { Text("Спочатку нові") },
                modifier = Modifier.testTag("feed_sort_recent")
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = sortByTitle,
                onClick = { if (!sortByTitle) onSortToggle() },
                label = { Text("За назвою") },
                modifier = Modifier.testTag("feed_sort_title")
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = sourceFilter == null,
                onClick = { onSourceChange(null) },
                label = { Text("Усі джерела") },
                modifier = Modifier.testTag("feed_source_all")
            )
            WorkFeedSources.forEach { id ->
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = sourceFilter == id,
                    onClick = { onSourceChange(if (sourceFilter == id) null else id) },
                    label = { Text(sourceDisplayName(id)) },
                    modifier = Modifier.testTag("feed_source_$id")
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            FilterChip(
                selected = genreFilter == null,
                onClick = { onGenreChange(null) },
                label = { Text("Усі жанри") },
                modifier = Modifier.testTag("feed_genre_all")
            )
            genres.forEach { genre ->
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = genreFilter == genre,
                    onClick = { onGenreChange(if (genreFilter == genre) null else genre) },
                    label = { Text(genre) },
                    modifier = Modifier.testTag("feed_genre_$genre")
                )
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
    coverImageUrl = coverImageUrl
)
