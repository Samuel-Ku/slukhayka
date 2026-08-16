package com.slukhayka.audiobooks.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.catalog.CatalogSection
import com.slukhayka.audiobooks.data.catalog.CatalogSeries
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.duration.ChapterDurationProbe
import com.slukhayka.audiobooks.data.duration.DurationEnrichment
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import com.slukhayka.audiobooks.ui.DurationBooks
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.EmptyState
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
    onBookClick: (String) -> Unit,
    onPlayClick: (AudiobookEntity) -> Unit
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

    // Spec-16 T2: the «Колекції» block — curated lists matched against the
    // union, recomputed on every union refresh (same trigger). The flow
    // already excludes empty collections; the block itself hides when all are
    // empty.
    val collections by sourceCatalog.smartCollections.collectAsState()

    // Spec-15 T1: refresh the ephemeral union once per Огляд composition —
    // the ViewModel still needs it for the recommendation enrichment, even
    // though the browse surface is now the spec-23 T4 persisted feed.
    // ADR-0008: the module call is made directly from the composition scope;
    // the embedding pass stays orchestrated by the ViewModel (single-flight).
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        sourceCatalog.refreshUnifiedCatalog()
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

    // Spec-22 T3: the search bar and filter chips are collapsible — the
    // header shows brand + [🔍] + [🔄], and the field + chips expand on
    // demand with auto-focus. Closing (✕ or Back) clears the query and
    // resets the filter to «Усі».
    val haptic = LocalHapticFeedback.current
    var searchExpanded by rememberSaveable { mutableStateOf(false) }

    val genres = listOf("Усі", "Фантастика", "Cyberpunk", "Детективи", "Класика", "Антиутопія", "Короткі", "Завантажені")

    val filteredBooks = allBooks.filter { book ->
        val matchesSearch = searchQuery.isBlank() ||
            book.title.contains(searchQuery, ignoreCase = true) ||
            book.author.contains(searchQuery, ignoreCase = true)

        val matchesGenre = when (selectedGenre) {
            "Усі", "All" -> true
            "Завантажені", "Downloaded" -> book.isDownloaded
            // Spec-22 T3 mood chip: a short book = up to ~3 hours.
            "Короткі" -> book.totalDurationSeconds in 1..3 * 3600
            "Фантастика" -> book.genre.contains("фантастика", ignoreCase = true) || book.genre.contains("sci-fi", ignoreCase = true)
            "Cyberpunk" -> book.genre.contains("cyberpunk", ignoreCase = true) || book.genre.contains("киберпанк", ignoreCase = true) || book.genre.contains("кіберпанк", ignoreCase = true)
            "Детективи" -> book.genre.contains("детектив", ignoreCase = true)
            "Класика" -> book.genre.contains("классика", ignoreCase = true) || book.genre.contains("класика", ignoreCase = true)
            "Антиутопія" -> book.genre.contains("антиутопия", ignoreCase = true) || book.genre.contains("антиутопія", ignoreCase = true)
            else -> book.genre.contains(selectedGenre, ignoreCase = true)
        }

        matchesSearch && matchesGenre
    }

    // Search/genre mode: a plain result list, no rows.
    val inSearchMode = searchQuery.isNotBlank() || selectedGenre != "Усі"

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
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (filteredBooks.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.SearchOff,
                        title = "Нічого не знайдено",
                        body = "Спробуйте змінити запит або фільтр."
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
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                if (isGlobalSearchLoading && globalResults.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (!isGlobalSearchLoading && globalResults.isEmpty() && searchQuery.trim().length >= 2) {
                    item {
                        Text(
                            text = "В інших джерелах нічого не знайдено.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
                items(globalResults, key = { it.key }) { result ->
                    GlobalSearchResultCard(
                        result = result,
                        onClick = { viewModel.playGlobalSearchResult(result) }
                    )
                }
            }
        } else {
            // ---- Netflix feed ---------------------------------------------
            // Loading spinner while the catalogue syncs on a fresh start.
            if (isCatalogLoading && allBooks.isEmpty() && sections.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Завантажуємо каталог...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Empty catalogue (first run, no network): actionable state.
            if (!isCatalogLoading && sections.isEmpty() && allBooks.isEmpty()) {
                item {
                    EmptyCatalogState(
                        onRefreshClick = { scope.launch { sourceCatalog.fetchCatalogSections() } },
                        onImportClick = { viewModel.selectTab(com.slukhayka.audiobooks.ui.SelectedTab.LIBRARY) }
                    )
                }
            }

            // Catalogue navigation — the site's header menu: ТОП 100,
            // Виконавці (narrators) and Автори (authors).
            item {
                CatalogRowHeader(title = "Каталог")
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        GenreChip(
                            title = "ТОП 100",
                            onClick = { viewModel.openTop100() }
                        )
                    }
                    item {
                        GenreChip(
                            title = "Виконавці",
                            onClick = {
                                viewModel.openPeople(com.slukhayka.audiobooks.ui.PeopleKind("Виконавці", "https://4read.org/readers.html"))
                            }
                        )
                    }
                    item {
                        GenreChip(
                            title = "Автори",
                            onClick = {
                                viewModel.openPeople(com.slukhayka.audiobooks.ui.PeopleKind("Автори", "https://4read.org/avtors.html"))
                            }
                        )
                    }
                }
            }

            // Genre navigation ("Аудіокниги жанру:") — chips that open the
            // genre's own book list, mirroring the site's primary sidebar nav.
            if (catalogGenres.isNotEmpty()) {
                item {
                    CatalogRowHeader(title = "Жанри")
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(catalogGenres, key = { it.url }) { genre ->
                            GenreChip(
                                title = genre.title,
                                onClick = { viewModel.openGenre(genre.title, genre.url) }
                            )
                        }
                    }
                }
            }

            // Spec-16: «Колекції» — one horizontal cover row per matched
            // curated collection (Нобелівські лауреати, Шевченківська
            // премія, Букер), reusing the uniform cover-card look of the
            // other Огляд rows. Tapping a card resolves the Work like any
            // other global-search card (import-and-play). Empty collections
            // are already absent from the flow; when all are empty the whole
            // block disappears.
            if (collections.isNotEmpty()) {
                collections.forEach { collection ->
                    item {
                        CatalogRowHeader(title = collection.name)
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(collection.books, key = { it.key }) { result ->
                                CollectionBookCard(
                                    result = result,
                                    onClick = { viewModel.playGlobalSearchResult(result) }
                                )
                            }
                        }
                    }
                }
            }

            // Spec-23 T4: the endless merged feed — every Work in the
            // persisted catalogue, paged via Paging 3. It supersedes the
            // spec-15 T1 ephemeral union: the same merge key / one card per
            // Work, but scrolling pages through the whole catalogue instead
            // of stopping at the session snapshot. Filters (source / genre /
            // sort) rebuild the Pager; the row header shows the live count.
            item {
                CatalogRowHeader(title = "Весь каталог")
            }
            item {
                WorkFeedFilters(
                    sourceFilter = feedSourceFilter,
                    genreFilter = feedGenreFilter,
                    sortByTitle = feedSortByTitle,
                    genres = catalogGenres.map { it.title },
                    onSourceChange = viewModel::setFeedSourceFilter,
                    onGenreChange = viewModel::setFeedGenreFilter,
                    onSortToggle = { viewModel.setFeedSortByTitle(!feedSortByTitle) }
                )
            }
            if (workFeedItems.itemCount == 0 && workFeedItems.loadState.refresh is LoadState.Loading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            // Paging-compose 3.3 removed LazyListScope.items(LazyPagingItems);
            // iterate the paged index with the official itemKey/contentType
            // helpers (placeholders disabled, so rows are non-null).
            items(
                count = workFeedItems.itemCount,
                key = workFeedItems.itemKey { it.workId },
                contentType = workFeedItems.itemContentType { "WorkFeedRow" }
            ) { index ->
                workFeedItems[index]?.let { row ->
                    WorkFeedCard(
                        row = row,
                        onClick = { viewModel.openWorkFeedRow(row) }
                    )
                }
            }
            when (val append = workFeedItems.loadState.append) {
                is LoadState.Loading -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                is LoadState.Error -> item {
                    Text(
                        text = "Не вдалося завантажити ще: ${append.error.message.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                else -> Unit
            }

            // Spec-19 Track A: «Рекомендовано для вас» — on-device, local
            // only. Each card carries a reason chip («схоже на X»); tapping
            // opens the book page through the same identity resolution as
            // any other Огляд row (import the Work, then the native page).
            if (recommendedBooks.isNotEmpty()) {
                item {
                    CatalogRowHeader(title = "Рекомендовано для вас")
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(recommendedBooks, key = { it.candidate.id }) { rec ->
                            RecommendedBookCard(
                                rec = rec,
                                onClick = { viewModel.openRecommendedBook(rec.candidate.id) }
                            )
                        }
                    }
                }
            }

            // spec-18 T3: «За тривалістю» — «Короткі» and «Довгі» cover rows
            // fed by the bucketed duration rows. Hidden entirely when no book
            // has a known duration yet; the rows grow as durations arrive.
            item {
                DurationSection(
                    shortBooks = durationBooks.short.map { it.asCatalogBook() },
                    longBooks = durationBooks.long.map { it.asCatalogBook() },
                    onBookClick = onBookClick
                )
            }

            // Catalogue rows parsed from the 4read.org homepage. Spec-9: the
            // Continue-Listening card moved to the Слухати tab.
            sections.forEach { section ->
                if (section.books.isNotEmpty()) {
                    item {
                        CatalogRowHeader(title = section.title)
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(section.books, key = { it.id }) { book ->
                                CatalogBookCard(
                                    book = book,
                                    onClick = { onBookClick(book.id) }
                                )
                            }
                        }
                    }
                }
                if (section.series.isNotEmpty()) {
                    item {
                        CatalogRowHeader(title = section.title)
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(section.series, key = { it.url }) { series ->
                                CatalogSeriesCard(
                                    series = series,
                                    onClick = { viewModel.openSeries(series.title, series.url) }
                                )
                            }
                        }
                    }
                }
            }

            // Spec-9: the full library list lives in Медіатека (Library tab),
            // not at the bottom of Огляд.
        }
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
                    Icon(Icons.Default.Refresh, contentDescription = "Оновити каталог")
                }
                IconButton(
                    onClick = onToggleSearch,
                    modifier = Modifier.size(AppDimens.TouchTarget).testTag("home_search_toggle")
                ) {
                    Icon(
                        imageVector = if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = if (searchExpanded) "Закрити пошук" else "Пошук"
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
                    placeholder = { Text("Пошук книги або автора...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        // ✕ collapses search and resets the filters (US-2).
                        IconButton(onClick = onCloseSearch, modifier = Modifier.testTag("home_search_close")) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Закрити пошук")
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
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
    )
}

/**
 * spec-18 T3 (#114) — the Огляд «За тривалістю» section: two horizontal
 * cover rows — «Короткі» (under 5 h) and «Довгі» (10 h and up). The
 * bucketing itself is the pure [com.slukhayka.audiobooks.data.duration.DurationBuckets]
 * module; this composable only renders what it is handed, so the snapshot
 * seam pins it from fixture data. Hidden entirely when both rows are empty.
 * Cards are the same cover-first [CatalogBookCard] as every Огляд row;
 * tapping opens the book page.
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
            CatalogRowHeader(title = "Короткі")
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
            CatalogRowHeader(title = "Довгі")
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
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable { onClick() }
            .testTag("collection_book_${result.key.hashCode()}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CatalogCoverImage(
            coverImageUrl = result.coverImageUrl,
            title = result.title,
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
    onClick: () -> Unit
) {
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
            Text(
                text = rec.candidate.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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

/** Tappable genre chip for the "Жанри" row — opens the genre book list. */
@Composable
fun GenreChip(
    title: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(AppDimens.RadiusCardLg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.testTag("genre_chip_$title")
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

/** Wide cover card for a series (cycle) chip. */
@Composable
fun CatalogSeriesCard(
    series: CatalogSeries,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(132.dp)
            .clickable { onClick() }
            .testTag("catalog_series_${series.url.hashCode()}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CatalogCoverImage(
            coverImageUrl = series.coverImageUrl,
            title = series.title,
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
 * Remote-cover image with the same genre-tinted typographic fallback as
 * BookCoverImage (spec-22 T3). [genre] is optional — catalogue rows usually
 * carry no genre, so they keep the brand-accent gradient unchanged.
 */
@Composable
fun CatalogCoverImage(
    coverImageUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    genre: String? = null
) {
    val context = LocalContext.current
    var isError by remember(coverImageUrl) { mutableStateOf(false) }

    if (!coverImageUrl.isNullOrBlank() && !isError) {
        val request = remember(coverImageUrl) {
            ImageRequest.Builder(context)
                .data(coverImageUrl)
                .setHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36")
                .setHeader("Referer", "https://4read.org/")
                .crossfade(true)
                .allowHardware(false)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = title,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            onError = { isError = true }
        )
    } else {
        val fallbackAccent = genreAccentColor(genre)
        Box(
            modifier = modifier.background(
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
            color = MaterialTheme.colorScheme.onSurface
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
    onPlayClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusPanel))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusPanel))
            .clickable { onClick() }
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
                contentDescription = book.title,
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
                            contentDescription = "Downloaded",
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
                val chaptersLabel = if (book.totalChapters > 0) "${book.totalChapters} Chapters" else null
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
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
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
            contentDescription = "Відтворити",
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
