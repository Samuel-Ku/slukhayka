package com.slukhayka.audiobooks.ui.screens

import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.catalog.CatalogPerson
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkKey
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.data.personbookmarks.PersonBookmarks
import com.slukhayka.audiobooks.data.availability.AvailabilityStatus
import com.slukhayka.audiobooks.data.availability.AvailabilityView
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import com.slukhayka.audiobooks.ui.bookPersonPath
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.BookCoverSemantics
import com.slukhayka.audiobooks.ui.components.BookCoverImage
import com.slukhayka.audiobooks.ui.SubmissionBadge
import com.slukhayka.audiobooks.ui.components.BookRow
import com.slukhayka.audiobooks.ui.components.EmptyState
import com.slukhayka.audiobooks.ui.components.MetadataChip
import com.slukhayka.audiobooks.ui.components.RestoreFocusAfterModal
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.library.LibraryBook
import com.slukhayka.audiobooks.ui.library.LibraryFilter
import com.slukhayka.audiobooks.ui.library.LibraryGridEntry
import com.slukhayka.audiobooks.ui.library.libraryGridEntries
import com.slukhayka.audiobooks.ui.library.LibrarySort
import com.slukhayka.audiobooks.ui.library.clearCacheConfirmText
import com.slukhayka.audiobooks.ui.library.SHEET_FILTERS
import com.slukhayka.audiobooks.ui.library.filterAndSortLibrary
import com.slukhayka.audiobooks.ui.library.workBookCards
import com.slukhayka.audiobooks.ui.library.formatRemainingTime
import com.slukhayka.audiobooks.ui.library.ukPlural
import com.slukhayka.audiobooks.ui.library.stringRemainingTimeUnits
import com.slukhayka.audiobooks.ui.screens.collections.CollectionDetailContent
import com.slukhayka.audiobooks.ui.screens.collections.MyCollectionsBlock
import com.slukhayka.audiobooks.ui.theme.*
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalClipboardManager
import com.slukhayka.audiobooks.data.ingest.sharedSubmissionUrlOf

/**
 * Wayfinder #39 — Медіатека as one unified library. Local files and 4read
 * books live side by side; the segmented status row (Усі / Нові / Слухаю /
 * Завершені / Завантажені, spec-28 #193) plus a filter sheet (Обрані /
 * Локальні / Онлайн, six sort modes, grid/list toggle) and client-side
 * search sit above a single book card that always shows author,
 * series+volume, progress, remaining time, download status and a small
 * source badge. Закладки and Статистика remain as sub-tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: MainViewModel,
    // ADR-0008 batch 1 (#154): the screen receives the modules it reads from
    // as parameters, wired from the composition root by the top-level app
    // composable — the injection idiom every other screen copies. Orchestration
    // (import, cache) and navigation stay on the ViewModel.
    libraryEntries: LibraryEntries,
    listeningState: ListeningStateStore,
    // #401: person bookmarks — Flows read directly (ADR-0008).
    personBookmarks: PersonBookmarks,
    onBookClick: (String) -> Unit,
    onPlayClick: (AudiobookEntity) -> Unit,
    onBrowseClick: () -> Unit,
    onPersonClick: (CatalogPerson) -> Unit = {},
    restoreFocusBookId: String? = null,
    onBookFocusRestored: (String) -> Unit = {},
    /** ADR-0049 / #860 — the gear opens Settings from THIS root. */
    onOpenSettings: () -> Unit = {}
) {
    val libraryBooks by viewModel.libraryBooks.collectAsState()
    val libraryAvailability by viewModel.libraryAvailability.collectAsState()
    val bookDownloadCounts by viewModel.bookDownloadCounts.collectAsState()
    // Spec-56 T2 (#729) — every problem Work joins the Source Watch
    // automatically, idempotently and without a listener action.
    LaunchedEffect(libraryBooks) { viewModel.ensureProblemWorksWatched() }
    // ADR-0008: module flows are read directly — no forwarding StateFlow on
    // the ViewModel. getAllListeningStats() builds the (cold) flow, so it is
    // remembered once per composition instead of re-created on every frame.
    val allBookmarks by libraryEntries.allBookmarks.collectAsState(initial = emptyList())
    val allBooks by libraryEntries.allBooks.collectAsState(initial = emptyList())
    val listeningStats by remember { listeningState.getAllListeningStats() }
        .collectAsState(initial = emptyList())
    // #401: bookmarked people Flows collected directly (ADR-0008).
    val bookmarkedAuthors by personBookmarks.bookmarkedAuthors()
        .collectAsState(initial = emptyList())
    val bookmarkedNarrators by personBookmarks.bookmarkedNarrators()
        .collectAsState(initial = emptyList())
    val bookmarkedPeople = remember(bookmarkedAuthors, bookmarkedNarrators) {
        (bookmarkedAuthors.map { it to PersonRole.AUTHOR } +
            bookmarkedNarrators.map { it to PersonRole.NARRATOR })
            .sortedByDescending { it.first.createdAt }
    }
    val context = LocalContext.current
    // ADR-0008: suspend module calls from user actions run on the composition
    // scope (same pattern as playerManager's call-through).
    val scope = rememberCoroutineScope()

    // Spec #8 ticket T7: system file picker (SAF) → one picked audio file = one book.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importLocalAudioFile(uri)
    }

    // Spec #8 Block 4: SAF tree picker → recursively import every audio file
    // in the picked folder (files grouped into books by their sub-folder).
    // The persisted grant (wayfinder #48) keeps this folder re-importable on
    // later launches without a re-pick, and the tree uri travels with the
    // imported books as `sourceTreeUri`.
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Log.w("LibraryScreen", "Persistable grant refused for $uri", e)
            }
            viewModel.importLocalAudioFolder(uri)
        }
    }

    // Import-result feedback (Block 4): one-shot Snackbar from the ViewModel.
    val snackbarHostState = remember { SnackbarHostState() }
    // Spec-53 T3 — awaiting-verdict badges + the quiet publication notice.
    val awaitingSubmissionBookIds by viewModel.awaitingSubmissionBookIds.collectAsState()
    // #837 — the honest per-book submission badge (state, not a promise).
    val submissionBadges by viewModel.submissionBadges.collectAsState()
    val watchingSubmissionBookIds by viewModel.watchingSubmissionBookIds.collectAsState()
    val deferredPublicationBookIds by viewModel.deferredPublicationBookIds.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.refreshAwaitingSubmissions()
        // Spec-53 T8 — the deferred queue runs on open: one pass, no retries.
        viewModel.processDeferredSubmissions()
        // Spec-53 T12 — and so does the next-day publication pass; it really
        // publishes only once the day (and its budget) has rolled over.
        viewModel.processDeferredPublications()
    }
    LaunchedEffect(Unit) {
        viewModel.submissionPublished.collect {
            snackbarHostState.showSnackbar(context.getString(com.slukhayka.audiobooks.R.string.submission_published_toast))
        }
    }
    val importMessage by viewModel.importMessage.collectAsState()
    LaunchedEffect(importMessage) {
        importMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeImportMessage()
        }
    }

    // wayfinder #29: the smart-import preview — scan → plan → confirm → apply.
    // The plan is pure data; confirming calls apply, dismissing leaves zero
    // trace. Merge suggestions render as review rows, never silent merges.
    val importPreview by viewModel.importPreview.collectAsState()
    // spec-54 T06 (#873) — the subsection, its filters and the sorting are the
    // listener's PLACE in the library: they must survive switching the list to
    // the grid, leaving the tab and coming back. `remember` alone lost them the
    // moment the tab left the composition (see TabSaveableHost, #871).
    var activeTab by rememberSaveable { mutableStateOf(0) } // 0 = Книги, 1 = Закладки, 2 = Статистика, 3 = Люди
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.RECENTLY_LISTENED) }
    var query by rememberSaveable { mutableStateOf("") }
    // v1.4 C5 (ADR-0033): the collapsible search — 🔍 in the tab header,
    // ✕/Back clears (US-2, the same gesture as Огляд); the always-visible
    // field is gone.
    var searchRequested by rememberSaveable { mutableStateOf(false) }
    val searchExpanded = searchRequested || query.isNotBlank()
    val librarySearchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) librarySearchFocusRequester.requestFocus()
    }
    BackHandler(enabled = searchExpanded) {
        searchRequested = false
        if (query.isNotBlank()) query = ""
    }
    // The list/grid choice is a VIEW of the same set: it must not reset the
    // filters or the sorting, and it must survive the same way they do.
    var gridMode by rememberSaveable { mutableStateOf(false) }
    // UI (v1.5 review): the ⋮ section menu replaced the four sub-tabs.
    var sectionMenuOpen by remember { mutableStateOf(false) }
    // #870 — the manual-add sheet: a format is not an Edition, and the policy
    // (not the sheet) refuses anything fiction-like.
    var manualAddOpen by rememberSaveable { mutableStateOf(false) }
    // The pinned status row scrolls itself to a rare filter the listener just
    // picked, so an active «Локальні» is never selected off-screen.
    val statusRowScrollState = rememberScrollState()
    BackHandler(enabled = activeTab != 0) { activeTab = 0 }
    // #867 — the triage queue is read when its subsection opens (and re-read
    // after every action), so the list is never a stale promise.
    LaunchedEffect(activeTab) {
        if (activeTab == 3) viewModel.refreshImportedEntries()
        if (activeTab == 4) {
            viewModel.refreshReadingYear()
            viewModel.refreshActiveReadings()
        }
    }
    val sectionTitle = when (activeTab) {
        1 -> stringResource(R.string.lib_section_saved)
        3 -> stringResource(R.string.lib_section_imported)
        4 -> stringResource(R.string.lib_section_year)
        else -> stringResource(R.string.lib_statistics)
    }
    val librarySubtitle = librarySizeLabel(libraryBooks)
    // Browsing the whole library vs narrowing it down: the sections (and the
    // «Продовжити» card) only make sense while nothing is filtering.
    val browsing = filter == LibraryFilter.ALL && query.isBlank()
    val continueBook = remember(libraryBooks) {
        libraryBooks.filter { it.isListening }.maxByOrNull { it.lastListenedAt }
    }
    // Spec-28 #193: the rare filters, sort and view toggle live in the sheet.
    var showFilterSheet by remember { mutableStateOf(false) }
    // Spec-28 #194: import is one «+ Додати» action opening a sheet; the
    // storage destination is reached from Settings.
    var showImportSheet by remember { mutableStateOf(false) }
    // Spec-601 T3/T5 — the «Надіслати посилання» sheet: paste a YouTube/TG
    // link; publication happens only after the imported copy really plays.
    var showSubmissionSheet by remember { mutableStateOf(false) }
    // Spec-53 T4 — a shared link opens the sheet prefilled; the clipboard
    // candidate is offered as a chip once the sheet is open.
    val sharedSubmissionUrl by viewModel.sharedSubmissionUrl.collectAsState()
    var submissionClipboardCandidate by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current
    LaunchedEffect(sharedSubmissionUrl) {
        if (sharedSubmissionUrl != null) showSubmissionSheet = true
    }
    LaunchedEffect(showSubmissionSheet) {
        if (showSubmissionSheet) {
            val text = runCatching { clipboardManager.getText()?.text }.getOrNull()
            submissionClipboardCandidate = sharedSubmissionUrlOf(text)
        }
    }
    val filterFocusRequester = remember { FocusRequester() }
    val importFocusRequester = remember { FocusRequester() }
    val libraryHeadingFocusRequester = remember { FocusRequester() }
    val bookReturnFocusRequester = remember { FocusRequester() }
    val libraryGridState = rememberLazyGridState()
    val modalVisible = showFilterSheet || showImportSheet || showSubmissionSheet || importPreview != null

    RestoreFocusAfterModal(
        modalVisible = showFilterSheet,
        returnFocusRequester = filterFocusRequester
    )
    RestoreFocusAfterModal(
        modalVisible = showImportSheet,
        returnFocusRequester = importFocusRequester
    )
    RestoreFocusAfterModal(
        modalVisible = importPreview != null,
        returnFocusRequester = importFocusRequester
    )

    val visibleBooks = remember(libraryBooks, filter, sort, query) {
        filterAndSortLibrary(libraryBooks, filter, sort, query)
    }

    // spec-54 T14 (#869) — the listener owns a WORK: the list renders ONE card
    // per Work, fronted by the narration they are furthest along in, and the
    // other narrations (with their own progress, bookmarks, downloads and
    // speed) stay reachable from the card's details.
    val workCards = remember(visibleBooks) { workBookCards(visibleBooks) }
    val shownCards = remember(workCards) { workCards.map { it.primary } }
    // #885 — the book shown in the «Продовжити» card must appear ONCE on the
    // screen: the a11y contract is one node per book, and the journey asserts
    // exactly one `library_book_item_<id>`. So the continue book is not repeated
    // as a row right under the very card that already offers it.
    val listedCards = remember(shownCards, continueBook) {
        shownCards.filterNot { it.book.id == continueBook?.book?.id }
    }

    // The grid as data (v1.5 review): the structure carries the resume card,
    // the section headers and the shelf, so a lazy-grid index must be mapped
    // back to a book through these entries — never through `visibleBooks`
    // (the two stopped aligning the moment the hero and the headers appeared).
    val denseTrailing = if (browsing) "" else libraryRemainingTotal(shownCards)
    val gridEntries = remember(browsing, gridMode, visibleBooks, continueBook, denseTrailing) {
        libraryGridEntries(
            // #885 — the prototype's «Книги» is a VERTICAL list of rows with a
            // progress line, not a wall of shelves: shelves belong to «Полиці»
            // (their own tab above). So the books tab always builds the dense
            // section+rows shape.
            browsing = false,
            gridMode = gridMode,
            visible = listedCards,
            continueBook = continueBook,
            denseTitle = if (query.isNotBlank()) "Пошук" else filter.label,
            denseTrailing = denseTrailing
        )
    }

    // Spec-56 T3 (#730) — tell the view model which Works are visible so their
    // availability checks jump the queue; the daily backlog waits.
    LaunchedEffect(activeTab, gridEntries) {
        if (activeTab != 0) return@LaunchedEffect
        snapshotFlow { libraryGridState.layoutInfo.visibleItemsInfo.map { it.index } }
            .collectLatest { indices ->
                val keys = indices
                    .mapNotNull { index ->
                        // The hero card is a book too — it must join the queue.
                        gridEntries.getOrNull(index)?.shownBook?.book?.mergeKey
                    }
                    .filter { it.isNotBlank() }
                viewModel.onAvailabilityVisible(keys)
            }
    }

    LaunchedEffect(
        restoreFocusBookId,
        gridEntries,
        libraryBooks,
        activeTab,
        modalVisible
    ) {
        val bookId = restoreFocusBookId ?: return@LaunchedEffect
        if (activeTab != 0 || modalVisible) return@LaunchedEffect
        // The book may be the hero card rather than a row — ask the entry.
        val visibleIndex = gridEntries.indexOfFirst { it.showsBook(bookId) }
        when {
            visibleIndex >= 0 -> {
                libraryGridState.scrollToItem(visibleIndex)
                withFrameNanos { }
                bookReturnFocusRequester.requestFocus()
                onBookFocusRestored(bookId)
            }
            libraryBooks.isNotEmpty() -> {
                withFrameNanos { }
                libraryHeadingFocusRequester.requestFocus()
                onBookFocusRestored(bookId)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LibraryModalUnderlay(
            modalVisible = modalVisible,
            modifier = Modifier.fillMaxSize()
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("library_screen")
                .accessibilityPane(stringResource(com.slukhayka.audiobooks.R.string.a11y_library_pane))
        ) {
            // Top Header — the canonical tab header (v1.4 C5, ADR-0033):
            // title + honest subtitle, the 🔍 collapsible search, «+ Додати»
            // and the ⋮ section menu.
            //
            // UI (v1.5 review): the four sub-tabs are gone. «Закладки /
            // Статистика / Люди» are not peers of «Книги» — a tab row stacked
            // above the status chips meant two competing filter systems and
            // ~150 dp of chrome before the first book. They live in the ⋮ menu
            // now (one tap, just as discoverable) and the screen became a
            // single scroll of books.
            com.slukhayka.audiobooks.ui.components.AppTabHeader(
                // spec-54 T06 (#873) — the root's name is the ONE resource the
                // bottom bar also uses; a hardcoded literal could drift from it.
                title = if (activeTab == 0) stringResource(R.string.nav_library) else sectionTitle,
                subtitle = if (activeTab == 0) librarySubtitle else null,
                headingTestTag = "library_heading",
                returnFocusRequester = libraryHeadingFocusRequester,
                actions = {
                    // #860 — the gear sits in the SAME place on every root.
                    com.slukhayka.audiobooks.ui.components.AppSettingsGear(onClick = onOpenSettings)
                    if (activeTab != 0) {
                        // A section is open: one explicit way back to the books
                        // (system back does the same).
                        IconButton(
                            onClick = { activeTab = 0 },
                            modifier = Modifier
                                .size(AppDimens.TouchTarget)
                                .testTag("library_section_back")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(
                                    com.slukhayka.audiobooks.R.string.a11y_library_back_to_books
                                )
                            )
                        }
                    } else {
                        LibraryHeaderActions(
                            bookmarksCount = allBookmarks.size,
                            peopleCount = bookmarkedPeople.size,
                            searchExpanded = searchExpanded,
                            menuOpen = sectionMenuOpen,
                            onToggleSearch = {
                                searchRequested = !searchExpanded
                                if (!searchRequested && query.isNotBlank()) query = ""
                            },
                            onMenuOpenChange = { sectionMenuOpen = it },
                            onOpenSection = { activeTab = it },
                            // spec-54 T06 (#873) — «Полиці» are the listener's
                            // existing collections, opened where they live.
                            onOpenShelves = { viewModel.openCollectionsIndex() },
                            onOpenManualAdd = { manualAddOpen = true },
                            onAdd = { showImportSheet = true },
                            importFocusRequester = importFocusRequester
                        )
                    }
                }
            )

            // #885 — the prototype («Нічна бібліотека») switches between
            // «Книги / Полиці / Збережене» with a visible control at the top,
            // instead of hiding two of the three behind the overflow menu.
            // «Мій рік» має окремий вид, тож на «Моїх книгах» його картка не
            // дублюється; цей слот займає найактуальніше — книга, яку слухають.
            if (activeTab == 0) {
                continueBook?.let { book ->
                    Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                        LibraryContinueCard(
                            book = book,
                            onOpen = { onBookClick(book.book.id) },
                            onPlay = { onPlayClick(book.book) }
                        )
                    }
                }
            }

            if (activeTab == 0 || activeTab == 1) {
                LibrarySectionTabs(
                    booksSelected = activeTab == 0,
                    savedSelected = activeTab == 1,
                    onBooks = { activeTab = 0 },
                    onShelves = { viewModel.openCollectionsIndex() },
                    onSaved = { activeTab = 1 }
                )
            }

            if (activeTab == 0) {
                // Library chrome (wayfinder #39): the collapsible search (v1.4
                // C5 — the same gesture as Огляд), quick filters, sort + view
                // toggle.
                AnimatedVisibility(
                    visible = searchExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .focusRequester(librarySearchFocusRequester)
                            .testTag("library_search"),
                        label = { Text(stringResource(com.slukhayka.audiobooks.R.string.a11y_library_search)) },
                        placeholder = { Text(stringResource(R.string.lib_search_placeholder)) },
                        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            {
                                IconButton(onClick = { query = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = stringResource(R.string.a11y_library_clear_search)
                                    )
                                }
                            }
                        } else null,
                        singleLine = true,
                        shape = RoundedCornerShape(AppDimens.RadiusCard)
                    )
                }

                // Spec-28 #193 + design guide §6.3: the five one-tap statuses
                // on ONE horizontally scrolled line — never wrapped onto a
                // second row. The rare-filter launcher (Обрані / Локальні /
                // Онлайн) rides the same line and keeps its accent + its own
                // name while active, so a non-default filter stays visible.
                val isSheetFilterActive = filter in SHEET_FILTERS
                LaunchedEffect(filter) {
                    // The rare-filter launcher lives at the far end of the row:
                    // scroll it into view while it is the active filter, and
                    // back to the statuses when a one-tap status takes over.
                    withFrameNanos { }
                    statusRowScrollState.animateScrollTo(
                        if (isSheetFilterActive) statusRowScrollState.maxValue else 0
                    )
                }
                LibraryStatusRow(
                    selected = filter,
                    onSelect = { filter = it },
                    scrollState = statusRowScrollState,
                    trailing = {
                        FilterChip(
                            selected = isSheetFilterActive,
                            onClick = { showFilterSheet = true },
                            label = {
                                Text(if (isSheetFilterActive) filter.label else "Фільтр")
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                labelColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSheetFilterActive,
                                borderColor = MaterialTheme.colorScheme.outlineVariant,
                                selectedBorderColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .focusRequester(filterFocusRequester)
                                .testTag("library_filter_button")
                        )
                    }
                )

                // Spec-51 (#690) — the listener's own collections, right in the
                // Library. Local-first, and honestly empty when there are none.
                val listenerCollections by viewModel.listenerCollections.collectAsState()
                var openCollectionId by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) { viewModel.refreshListenerCollections() }
                MyCollectionsBlock(
                    rows = listenerCollections.map { collection ->
                        com.slukhayka.audiobooks.ui.screens.collections.MyCollectionRow(
                            id = collection.id,
                            title = collection.title,
                            bookCount = collection.items.size,
                            // The cover of the FIRST real item, or null — the
                            // block then draws nothing rather than a fake.
                            coverUrl = collection.coverBookId
                                ?.let { coverId ->
                                    libraryBooks.firstOrNull { it.book.id == coverId }
                                        ?.book?.coverImageUrl
                                }
                        )
                    },
                    onOpen = { openCollectionId = it }
                )

                // Spec-51 (#691) — the listener's OWN published collections,
                // read back from the shared store. Rendered ONLY when a shared
                // store is configured (the gate), so an unconfigured build
                // shows no public surface at all.
                val publishedCollections by viewModel.publishedListenerCollections.collectAsState()
                var openPublishedDocumentId by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(viewModel.publicCollectionsAvailable) {
                    if (viewModel.publicCollectionsAvailable) {
                        viewModel.refreshMyPublishedCollections()
                    }
                }
                com.slukhayka.audiobooks.ui.screens.collections.PublishedCollectionsBlock(
                    rows = publishedCollections.map { published ->
                        com.slukhayka.audiobooks.ui.screens.collections.PublishedCollectionRow(
                            documentId = published.documentId,
                            title = published.title,
                            bookCount = published.bookIds.size,
                            pseudonym = published.pseudonym,
                            average = com.slukhayka.audiobooks.data.collections.CollectionRating.average(
                                published.ratingSum,
                                published.ratingCount
                            ),
                            ratingCount = published.ratingCount,
                            hidden = published.hidden
                        )
                    },
                    onOpen = { openPublishedDocumentId = it }
                )
                publishedCollections
                    .firstOrNull { it.documentId == openPublishedDocumentId }
                    ?.let { open ->
                        com.slukhayka.audiobooks.ui.screens.collections.CollectionPage(
                            title = open.title,
                            onClose = { openPublishedDocumentId = null },
                            testTag = "published_collection_page"
                        ) {
                            com.slukhayka.audiobooks.ui.screens.collections.PublicCollectionContent(
                                collection = open,
                                // "Already downloaded" means the reader HAS at
                                // least one of these books locally: a fork
                                // copies the composition, and offline it is
                                // only useful (and honest) when the books are
                                // really here. Otherwise the action is
                                // disabled rather than silently failing.
                                originalAvailableLocally = open.bookIds.any { bookId ->
                                    libraryBooks.any { it.book.id == bookId }
                                },
                                onSaveForYou = {
                                    viewModel.saveForkOfPublished(open.documentId)
                                    openPublishedDocumentId = null
                                },
                                // The listener's own published collection: no
                                // self-rating (#694) and the community verdict
                                // is shown as-is (#696).
                                isOwn = true,
                                onDelete = {
                                    viewModel.deleteOwnPublishedCollection(open.documentId)
                                    openPublishedDocumentId = null
                                }
                            )
                        }
                    }
                listenerCollections.firstOrNull { it.id == openCollectionId }?.let { open ->
                    com.slukhayka.audiobooks.ui.screens.collections.CollectionPage(
                        title = open.title,
                        onClose = { openCollectionId = null },
                        testTag = "own_collection_page"
                    ) {
                        com.slukhayka.audiobooks.ui.screens.collections.CollectionDetailContent(
                            collection = open,
                            onRemoveBook = { bookId ->
                                viewModel.removeBookFromCollection(open.id, bookId)
                            },
                            onDelete = {
                                viewModel.deleteListenerCollection(open.id)
                                openCollectionId = null
                            }
                        )
                    }
                }

                // Spec-28 #194: the storage line and «Видалити завантажені
                // файли» moved to the «Завантаження та пам'ять» destination
                // (⋮ overflow) — nothing destructive sits on the main screen.

            }

            when (activeTab) {
                0 -> {
                    when {
                        libraryBooks.isEmpty() -> {
                            LibraryEmptyState(
                                onImportClick = {
                                    importLauncher.launch(
                                        arrayOf("audio/*", "application/ogg", "application/mpeg")
                                    )
                                },
                                onBrowseClick = onBrowseClick
                            )
                        }

                        visibleBooks.isEmpty() -> EmptyState(
                            icon = Icons.Default.Search,
                            title = "Нічого не знайдено",
                            body = "Спробуйте інший фільтр або змініть пошуковий запит."
                        )

                        else -> LazyVerticalGrid(
                            columns = if (gridMode) GridCells.Fixed(2) else GridCells.Fixed(1),
                            state = libraryGridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = AppDimens.SpaceAboveMiniPlayer),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            libraryGridContent(
                                entries = gridEntries,
                                // #885 — must match the shape `libraryGridEntries`
                                // built above (always the dense rows on «Книги»),
                                // otherwise the renderer falls back to the wall of
                                // cards and none of the row work shows up.
                                browsing = false,
                                gridMode = gridMode,
                                availability = libraryAvailability,
                                downloadCounts = bookDownloadCounts,
                                restoreFocusBookId = restoreFocusBookId,
                                bookReturnFocusRequester = bookReturnFocusRequester,
                                awaitingSubmissionBookIds = awaitingSubmissionBookIds,
                                submissionBadges = submissionBadges,
                                watchingSubmissionBookIds = watchingSubmissionBookIds,
                                deferredPublicationBookIds = deferredPublicationBookIds,
                                onBookClick = onBookClick,
                                onPlayClick = onPlayClick,
                                onRecheck = { viewModel.recheckAvailability(it) }
                            )
                        }
                    }
                }

                1 -> {
                    // spec-54 T06 (#873) — «Збережене»: the people the listener
                    // follows AND the bookmarks they left, in ONE place, on the
                    // data that already exists (no new schema).
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = AppDimens.SpaceAboveMiniPlayer, top = 8.dp)
                    ) {
                        item {
                            Text(
                                text = stringResource(R.string.lib_section_people),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .testTag("library_saved_people_header")
                            )
                        }
                        if (bookmarkedPeople.isEmpty()) {
                            // #873 — an empty state carries a clear ACTION, not
                            // just an explanation.
                            item {
                                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    Text(
                                        text = stringResource(R.string.lib_saved_empty_people_title),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = stringResource(R.string.lib_saved_empty_people_body),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    OutlinedButton(
                                        onClick = onBrowseClick,
                                        modifier = Modifier
                                            .padding(top = 8.dp)
                                            .heightIn(min = 48.dp)
                                            .testTag("library_saved_people_browse")
                                    ) {
                                        Text(stringResource(R.string.lib_find_book))
                                    }
                                }
                            }
                        } else {
                            items(
                                bookmarkedPeople,
                                key = { "${it.second.storageValue}_${it.first.id}" }
                            ) { (entity, role) ->
                                BookmarkedPersonRow(
                                    displayName = entity.displayName,
                                    role = role,
                                    notifyEnabled = entity.notifyEnabled,
                                    onClick = {
                                        onPersonClick(
                                            CatalogPerson(
                                                name = entity.displayName,
                                                path = bookPersonPath(
                                                    if (role == PersonRole.AUTHOR) "avtor" else "chitaet",
                                                    entity.displayName
                                                ),
                                                bookCount = 0,
                                                role = role
                                            )
                                        )
                                    },
                                    onToggleNotify = { enabled ->
                                        val key = PersonBookmarkKey(role, entity.id)
                                        scope.launch {
                                            personBookmarks.setNotifyEnabled(key, enabled)
                                        }
                                    }
                                )
                            }
                        }
                        item {
                            Text(
                                text = stringResource(R.string.lib_section_bookmarks),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .testTag("library_saved_bookmarks_header")
                            )
                        }
                        if (allBookmarks.isEmpty()) {
                            item {
                                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    Text(
                                        text = stringResource(R.string.lib_saved_empty_bookmarks_title),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = stringResource(R.string.lib_saved_empty_bookmarks_body),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    OutlinedButton(
                                        onClick = onBrowseClick,
                                        modifier = Modifier
                                            .padding(top = 8.dp)
                                            .heightIn(min = 48.dp)
                                            .testTag("library_saved_bookmarks_browse")
                                    ) {
                                        Text(stringResource(R.string.lib_find_book))
                                    }
                                }
                            }
                        } else {
                            items(allBookmarks, key = { it.id }) { bookmark ->
                                val book = allBooks.find { it.id == bookmark.bookId }
                                GlobalBookmarkItem(
                                    bookmark = bookmark,
                                    bookTitle = book?.title ?: "Аудіокнига",
                                    onJumpClick = { viewModel.jumpToBookmark(bookmark) },
                                    onDeleteClick = {
                                        scope.launch { listeningState.deleteBookmark(bookmark.id) }
                                    }
                                )
                            }
                        }
                    }
                }

                2 -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = AppDimens.SpaceAboveMiniPlayer)
                    ) {
                        item {
                            ListeningStatsCard(listeningStats = listeningStats, totalBooks = libraryBooks.size)
                        }
                    }
                }

                4 -> {
                    // #876 — «Мій рік»: the yearly goal, counted in PASSES and
                    // reported per format, because pages, percent and seconds
                    // cannot be summed into one number (ADR-0046 §5).
                    val goal by viewModel.readingYear.collectAsState()
                    val activeReadings by viewModel.activeReadings.collectAsState()
                    val current = goal
                    if (current == null) {
                        EmptyState(
                            icon = Icons.Default.CalendarMonth,
                            title = stringResource(R.string.lib_year_empty_title),
                            body = stringResource(R.string.lib_year_empty_body)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("library_year"),
                            contentPadding = PaddingValues(bottom = AppDimens.SpaceAboveMiniPlayer, top = 8.dp)
                        ) {
                            item {
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text(
                                        text = stringResource(R.string.lib_year_heading, current.year),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = stringResource(R.string.lib_year_total, current.totalFinished),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    current.finishedByFormat.toSortedMap().forEach { (format, count) ->
                                        Text(
                                            text = stringResource(
                                                R.string.lib_year_format,
                                                readingFormatLabel(format),
                                                count
                                            ),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.lib_year_rule),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            // #876 — the passes still in the listener's hands:
                            // ONE journal record per action, in the format's own
                            // unit, and finishing belongs to that pass alone.
                            items(activeReadings, key = { it.id }) { pass ->
                                ReadingProgressRow(
                                    pass = pass,
                                    onRecord = { value ->
                                        viewModel.recordReadingProgress(pass.id, value)
                                    },
                                    onFinish = { viewModel.finishReading(pass.id) }
                                )
                            }
                        }
                    }
                }

                3 -> {
                    // ADR-0047 / #867 — «Імпортоване»: a TEMPORARY home for the
                    // links whose origin the data does not recover, not a second
                    // library. Two explicit actions per row, and the subsection
                    // empties once every row is decided.
                    val importedEntries by viewModel.importedEntries.collectAsState()
                    if (importedEntries.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.Inbox,
                            title = stringResource(R.string.lib_imported_empty_title),
                            body = stringResource(R.string.lib_imported_empty_body)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("library_imported_list"),
                            contentPadding = PaddingValues(bottom = AppDimens.SpaceAboveMiniPlayer, top = 8.dp)
                        ) {
                            item {
                                Text(
                                    text = stringResource(R.string.lib_imported_explain),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            items(importedEntries, key = { it.id }) { entry ->
                                val title = allBooks.firstOrNull { it.id == entry.id }?.title ?: entry.id
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text(text = title, style = MaterialTheme.typography.titleSmall)
                                    Row {
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.triageImported(
                                                    entry.id,
                                                    com.slukhayka.audiobooks.data.entries.LibraryEntryOriginPolicy.TriageAction.CONFIRM_PERSONAL
                                                )
                                            },
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .testTag("library_imported_confirm_${entry.id}")
                                        ) {
                                            Text(stringResource(R.string.lib_imported_confirm))
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.triageImported(
                                                    entry.id,
                                                    com.slukhayka.audiobooks.data.entries.LibraryEntryOriginPolicy.TriageAction.REMOVE_LINK
                                                )
                                            },
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .testTag("library_imported_remove_${entry.id}")
                                        ) {
                                            Text(stringResource(R.string.lib_imported_remove))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        }

        if (showFilterSheet) {
            LibraryFilterSheet(
                filter = filter,
                sort = sort,
                gridMode = gridMode,
                onFilterChange = { filter = it },
                onSortChange = { sort = it },
                onGridModeChange = { gridMode = it },
                onDismiss = { showFilterSheet = false }
            )
        }

        if (manualAddOpen) {
            ManualBookAddSheet(
                onAdd = { request ->
                    manualAddOpen = false
                    scope.launch { viewModel.addManualBook(request) }
                },
                onDismiss = { manualAddOpen = false }
            )
        }

        if (showImportSheet) {
            LibraryImportSheet(
                onImportFile = {
                    showImportSheet = false
                    importLauncher.launch(arrayOf("audio/*", "application/ogg", "application/mpeg"))
                },
                onImportFolder = {
                    showImportSheet = false
                    folderLauncher.launch(null)
                },
                onSubmitLink = {
                    showImportSheet = false
                    viewModel.dismissSubmission()
                    viewModel.refreshSubmissionRemaining()
                    showSubmissionSheet = true
                },
                onDismiss = { showImportSheet = false }
            )
        }

        importPreview?.let { preview ->
            ImportPreviewDialog(
                preview = preview,
                onAcceptMerge = viewModel::acceptMergeInPreview,
                onRejectMerge = viewModel::rejectMergeInPreview,
                onConfirm = viewModel::confirmImportPreview,
                onDismiss = viewModel::dismissImportPreview
            )
        }

        if (showSubmissionSheet) {
            val submissionState by viewModel.submissionState.collectAsState()
            val submissionRemaining by viewModel.submissionRemaining.collectAsState()
            val deferredLinks by viewModel.deferredSubmissions.collectAsState()
            val submissionPreview by viewModel.submissionPreview.collectAsState()
            val channelCard by viewModel.channelCard.collectAsState()
            val previewSelection by viewModel.previewSelection.collectAsState()
            val previewSeparateBooks by viewModel.previewSeparateBooks.collectAsState()
            val previewRun by viewModel.previewRun.collectAsState()
            SubmissionSheet(
                state = submissionState,
                remainingToday = submissionRemaining,
                deferredLinks = deferredLinks,
                onRetryDeferred = { viewModel.retryDeferredSubmission(it) },
                onRemoveDeferred = { viewModel.removeDeferredSubmission(it) },
                preview = submissionPreview,
                onPreview = { viewModel.loadSubmissionPreview(it) },
                onClearPreview = { viewModel.clearSubmissionPreview() },
                onSubmitWithEdits = { url, edits ->
                    viewModel.clearSubmissionPreview()
                    viewModel.submitLink(url, edits)
                },
                // Spec-53 T10 — the whole-channel selection card.
                channelCard = channelCard,
                isChannelLink = viewModel::isChannelLink,
                onOpenChannel = { viewModel.openChannelCard(it) },
                channelCallbacks = ChannelCardCallbacks(
                    onClose = { viewModel.closeChannelCard() },
                    onRetryLoad = { viewModel.openChannelCard(channelCard?.url.orEmpty()) },
                    onTabSelect = { viewModel.switchChannelTab(it) },
                    onLoadMore = { viewModel.loadMoreChannelItems() },
                    onToggleItem = { viewModel.toggleChannelItem(it) },
                    onSelectLastN = { viewModel.selectChannelLastN(it) },
                    onToggleIncludeSkipped = { viewModel.toggleChannelIncludeSkipped() },
                    onStartImport = { viewModel.startChannelImport() },
                    onStopImport = { viewModel.stopChannelImport() }
                ),
                // Spec-53 T11 — the playlist preview's selection.
                playlistSelection = PlaylistSelectionState(
                    selected = previewSelection,
                    separateBooks = previewSeparateBooks,
                    run = previewRun
                ),
                playlistCallbacks = PlaylistSelectionCallbacks(
                    onToggleEntry = { viewModel.togglePreviewEntry(it) },
                    onSelectAll = { viewModel.selectAllPreviewEntries() },
                    onSetSeparateBooks = { viewModel.setPreviewSeparateBooks(it) },
                    onAdd = { edits -> viewModel.addPreviewSelection(edits) },
                    onStop = { viewModel.stopPreviewRun() }
                ),
                onListen = { viewModel.listenToLastImported() },
                // Spec-53 T6 — the friendly dupe leads to the owned copy.
                onOpenBook = { bookId ->
                    showSubmissionSheet = false
                    viewModel.dismissSubmission()
                    onBookClick(bookId)
                },
                prefillUrl = sharedSubmissionUrl,
                clipboardCandidate = submissionClipboardCandidate,
                onSubmit = viewModel::submitLink,
                onDismiss = {
                    showSubmissionSheet = false
                    submissionClipboardCandidate = null
                    viewModel.consumeSharedSubmission()
                    viewModel.dismissSubmission()
                }
            )
        }
    }

/**
 * Owns the complete non-modal library layer, including transient snackbar
 * feedback, so a sheet or import dialog is the only TalkBack surface left.
 */
@Composable
internal fun LibraryModalUnderlay(
    modalVisible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .testTag("library_modal_underlay")
            .accessibilityModalBackground(modalVisible),
        content = content
    )
}

@Composable
fun LibraryEmptyState(
    onImportClick: () -> Unit,
    onBrowseClick: () -> Unit
) {
    EmptyState(
        icon = Icons.Default.MenuBook,
        title = stringResource(R.string.library_empty_title),
        body = stringResource(R.string.library_empty_body)
    ) {
        Button(
            onClick = onImportClick,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(AppDimens.RadiusCard),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("library_empty_import")
        ) {
            Text(stringResource(R.string.lib_add_own_files))
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onBrowseClick,
            shape = RoundedCornerShape(AppDimens.RadiusCard),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("library_empty_browse")
        ) {
            Text(stringResource(R.string.lib_find_book))
        }
    }
}

/**
 * ADR-0042 §1 (spec-56 T1) — the card label for a problem Work, or null when
 * the Work has available audio (no noise). A pure projection of the policy's
 * state; the time is formatted here so the policy stays JVM-testable.
 */
@Composable
private fun availabilityLabel(view: AvailabilityView?): String? {
    if (view == null) return null
    return when (view.status) {
        AvailabilityStatus.CHECKING ->
            stringResource(R.string.library_availability_checking)
        AvailabilityStatus.NOT_FOUND ->
            stringResource(R.string.library_availability_not_found, formatCheckedAt(view.observedAtMs))
        AvailabilityStatus.FOUND ->
            stringResource(R.string.library_availability_found, sourceDisplayName(view.sourceId))
        AvailabilityStatus.REFUSED ->
            stringResource(R.string.library_availability_refused)
    }
}

private fun formatCheckedAt(observedAtMs: Long): String =
    if (observedAtMs <= 0L) {
        ""
    } else {
        java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(observedAtMs))
    }

/**
 * The Медіатека top-bar actions (v1.5 review): the 🔍 collapsible search, the
 * ⋮ section menu that replaced the four sub-tabs, and the single «+ Додати»
 * import action in the top-end corner. Extracted so the screen and the
 * snapshot goldens render the very same corner instead of two lookalikes.
 */
@Composable
internal fun LibraryHeaderActions(
    bookmarksCount: Int,
    peopleCount: Int,
    searchExpanded: Boolean,
    menuOpen: Boolean,
    onToggleSearch: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    onOpenSection: (Int) -> Unit,
    /** spec-54 T06 (#873) — opens the listener's own collections (Полиці). */
    onOpenShelves: () -> Unit = {},
    /** #870 — «Додати книгу вручну»: any format, no fictitious Edition. */
    onOpenManualAdd: () -> Unit = {},
    onAdd: () -> Unit,
    importFocusRequester: FocusRequester
) {
    // ADR-0044 lowers OUR floor to 24 dp, but the header keeps 48 dp: the
    // vendor a11y gate that runs on the device (Google ATF, enabled inside
    // MainActivityAccessibilityTest) still reports a 48 dp touch target, and a
    // gate nobody can pass is worse than a slightly taller header. The design
    // win lives in the chip row, which is compact on purpose.
    LibraryHeaderActionsInner(
        bookmarksCount = bookmarksCount,
        peopleCount = peopleCount,
        searchExpanded = searchExpanded,
        menuOpen = menuOpen,
        onToggleSearch = onToggleSearch,
        onMenuOpenChange = onMenuOpenChange,
        onOpenSection = onOpenSection,
        onOpenShelves = onOpenShelves,
        onOpenManualAdd = onOpenManualAdd,
        onAdd = onAdd,
        importFocusRequester = importFocusRequester
    )
}

@Composable
internal fun LibraryHeaderActionsInner(
    bookmarksCount: Int,
    peopleCount: Int,
    searchExpanded: Boolean,
    menuOpen: Boolean,
    onToggleSearch: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    onOpenSection: (Int) -> Unit,
    onOpenShelves: () -> Unit = {},
    onOpenManualAdd: () -> Unit = {},
    onAdd: () -> Unit,
    importFocusRequester: FocusRequester
) {
    IconButton(
        onClick = onToggleSearch,
        modifier = Modifier
            .size(AppDimens.TouchTarget)
            .testTag("library_search_toggle")
    ) {
        Icon(
            imageVector = if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
            contentDescription = stringResource(
                if (searchExpanded) {
                    R.string.a11y_close_search
                } else {
                    R.string.a11y_open_search
                }
            )
        )
    }
    Box {
        IconButton(
            onClick = { onMenuOpenChange(true) },
            modifier = Modifier
                .size(AppDimens.TouchTarget)
                .testTag("library_sections_menu")
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.a11y_library_more_actions)
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { onMenuOpenChange(false) }
        ) {
            // spec-54 T06 (#873) — три підрозділи: Полиці (добірки живуть
            // там, де й жили), Збережене (люди + закладки на наявних даних)
            // і Статистика.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_section_shelves)) },
                onClick = {
                    onMenuOpenChange(false)
                    onOpenShelves()
                },
                modifier = Modifier.testTag("library_section_shelves")
            )
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.lib_saved_count, bookmarksCount + peopleCount))
                },
                onClick = {
                    onMenuOpenChange(false)
                    onOpenSection(1)
                },
                modifier = Modifier.testTag("library_section_saved")
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_section_year)) },
                onClick = {
                    onMenuOpenChange(false)
                    onOpenSection(4)
                },
                modifier = Modifier.testTag("library_section_year")
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_section_imported)) },
                onClick = {
                    onMenuOpenChange(false)
                    onOpenSection(3)
                },
                modifier = Modifier.testTag("library_section_imported")
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.manual_add_menu)) },
                onClick = {
                    onMenuOpenChange(false)
                    onOpenManualAdd()
                },
                modifier = Modifier.testTag("library_manual_add")
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_statistics)) },
                onClick = {
                    onMenuOpenChange(false)
                    onOpenSection(2)
                },
                modifier = Modifier.testTag("library_section_stats")
            )
            // The separate «Люди» entry is gone: people and bookmarks are ONE
            // subsection now («Збережене», вище).

        }
    }
    // UI (v1.5 review): the import action is the compact «+» icon in the
    // top-end corner — three equal 48 dp targets, no labelled pill stealing
    // the width the title needs.
    IconButton(
        onClick = onAdd,
        modifier = Modifier
            .size(AppDimens.TouchTarget)
            .focusRequester(importFocusRequester)
            .testTag("library_add_button")
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = stringResource(R.string.a11y_library_add),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * UI (v1.5 review) — «Продовжити»: the one book the listener is inside right
 * now, as the first thing on the screen, with the resume action as a single
 * wide CTA. It scrolls away with the list; it is content, not chrome.
 */
@Composable
internal fun LibraryContinueCard(
    book: LibraryBook,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val units = stringRemainingTimeUnits()
    val remaining = if (book.totalDurationSeconds > 0L) {
        formatRemainingTime(book.remainingSeconds, units)
    } else {
        null
    }
    val chapter = libraryChapterLabel(book)
    // The hero keeps the SAME contract as the list card (v1.5 review, found by
    // the device gate): the book's body is one contextual action tagged
    // `library_book_item_<id>`, and the resume CTA is a separate node. Merging
    // the whole card into one node both hid the CTA from TalkBack and removed
    // the book's only card node from the screen — the book had been lifted out
    // of its section, so nothing else carried the tag.
    val description = if (book.book.displayAuthor.isBlank()) {
        book.book.title
    } else {
        stringResource(
            R.string.a11y_library_entry_description,
            book.book.title,
            book.book.displayAuthor
        )
    }
    val state = libraryEntryStateDescription(book, availability = null)
    val openLabel = stringResource(R.string.a11y_library_open_book, book.book.title)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("library_continue_card"),
        shape = RoundedCornerShape(AppDimens.RadiusHero),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(AppDimens.SpaceLg)) {
            Text(
                text = "ПРОДОВЖИТИ",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(AppDimens.SpaceMd))
            Row(
                // The caller may hand in the route-return focus requester: this
                // row IS the book's card, so focus must be able to land here.
                modifier = modifier
                    .fillMaxWidth()
                    .focusProperties { canFocus = true }
                    .clickable(onClick = onOpen)
                    .testTag("library_book_item_${book.book.id}")
                    .clearAndSetSemantics {
                        contentDescription = description
                        stateDescription = state
                        role = Role.Button
                        onClick(label = openLabel) {
                            onOpen()
                            true
                        }
                    }
            ) {
                BookCoverImage(
                    book = book.book,
                    semantics = BookCoverSemantics.Decorative,
                    modifier = Modifier
                        .size(width = 84.dp, height = 112.dp)
                        .clip(RoundedCornerShape(AppDimens.RadiusCover)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(AppDimens.SpaceLg))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.book.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = book.book.displayAuthor,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = chapter ?: book.seriesLabel
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(AppDimens.SpaceSm))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(AppDimens.SpaceMd))
                    LinearProgressIndicator(
                        progress = { book.percent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(AppDimens.RadiusProgress)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                        // #292: the M3 stop dot sat at the track's end like a
                        // second, unexplained marker (device review, 2026-09-16).
                        drawStopIndicator = {}
                    )
                    Spacer(modifier = Modifier.height(AppDimens.SpaceXs))
                    // The remaining time lives in the CTA right below — repeating
                    // it here made the two labels fight for one line on a phone
                    // (on-device check, 2026-09-15: «Прослухано 1%» wrapped).
                    Text(
                        text = "Прослухано ${(book.percent * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.height(AppDimens.SpaceLg))
            Button(
                onClick = onPlay,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("library_continue_play"),
                shape = RoundedCornerShape(AppDimens.RadiusPanel)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(AppDimens.SpaceSm))
                Text(
                    text = if (remaining != null) "Слухати далі · $remaining" else "Слухати далі",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

/**
 * The stateless renderer of the Медіатека grid (v1.5 review).
 *
 * The screen and the snapshot test both go through this one function, so the
 * golden image can never document a layout the app does not ship. Every slot
 * keeps its own key and span: only books take a single grid cell.
 */
internal fun LazyGridScope.libraryGridContent(
    entries: List<LibraryGridEntry>,
    browsing: Boolean,
    gridMode: Boolean,
    availability: Map<String, AvailabilityView>,
    downloadCounts: Map<String, com.slukhayka.audiobooks.data.db.BookDownloadCount>,
    restoreFocusBookId: String?,
    bookReturnFocusRequester: FocusRequester,
    awaitingSubmissionBookIds: Set<String>,
    watchingSubmissionBookIds: Set<String>,
    deferredPublicationBookIds: Set<String>,
    submissionBadges: Map<String, SubmissionBadge> = emptyMap(),
    onBookClick: (String) -> Unit,
    onPlayClick: (AudiobookEntity) -> Unit,
    onRecheck: (String) -> Unit
) {
    val card: @Composable (LibraryBook, Boolean) -> Unit = { entry, asTile ->
        LibraryBookCard(
            book = entry,
            grid = asTile,
            awaitingPlayback = entry.book.id in awaitingSubmissionBookIds,
            watchingSource = entry.book.id in watchingSubmissionBookIds,
            deferredPublication = entry.book.id in deferredPublicationBookIds,
            submissionBadge = submissionBadges[entry.book.id] ?: SubmissionBadge.NONE,
            onListenNow = { onPlayClick(entry.book) },
            onClick = { onBookClick(entry.book.id) },
            modifier = if (entry.book.id == restoreFocusBookId) {
                Modifier.focusRequester(bookReturnFocusRequester)
            } else {
                Modifier
            },
            availability = availability[entry.book.mergeKey],
            onRecheck = { onRecheck(entry.book.id) },
            downloadCount = downloadCounts[entry.book.id]
        )
    }

    items(
        items = entries,
        key = { it.key },
        span = { entry ->
            if (entry is LibraryGridEntry.BookEntry) GridItemSpan(1) else GridItemSpan(maxLineSpan)
        }
    ) { gridEntry ->
        when (gridEntry) {
            is LibraryGridEntry.Continue -> LibraryContinueCard(
                book = gridEntry.book,
                // Same contract as a row: the book being returned to may live
                // here, and requesting focus on an unattached requester throws.
                modifier = if (gridEntry.book.book.id == restoreFocusBookId) {
                    Modifier.focusRequester(bookReturnFocusRequester)
                } else {
                    Modifier
                },
                onOpen = { onBookClick(gridEntry.book.book.id) },
                onPlay = { onPlayClick(gridEntry.book.book) }
            )

            is LibraryGridEntry.Section -> LibrarySectionHeader(
                title = gridEntry.title,
                count = gridEntry.count,
                trailingText = gridEntry.trailing
            )

            is LibraryGridEntry.Shelf -> LibraryShelf(gridEntry.books) { card(it, true) }

            is LibraryGridEntry.BookEntry -> {
                // Narrowed down (a filter or a search): dense rows.
                // Browsing the whole library: the rich card.
                if (!browsing && !gridMode) {
                    LibraryDenseRow(
                        book = gridEntry.book,
                        availability = availability[gridEntry.book.book.mergeKey],
                        onRecheck = { onRecheck(gridEntry.book.book.id) },
                        downloadCount = downloadCounts[gridEntry.book.book.id],
                        onOpen = { onBookClick(gridEntry.book.book.id) },
                        // Same contract as the card above: one requester, ONE
                        // node — the book we are returning to. Attaching it to
                        // every row made focus unresolvable, and the a11y
                        // journey timed out waiting for `Focused`.
                        bookReturnFocusRequester = if (
                            gridEntry.book.book.id == restoreFocusBookId
                        ) {
                            bookReturnFocusRequester
                        } else {
                            null
                        }
                    )
                } else {
                    card(gridEntry.book, gridMode)
                }
            }
        }
    }
}

/** «58 книг · 143 год 20 хв» — the honest size of the whole library. */
@Composable
private fun librarySizeLabel(books: List<LibraryBook>): String {
    val count = books.size
    val noun = ukPlural(count, one = "книга", few = "книги", many = "книг")
    val total = books.sumOf { it.totalDurationSeconds }
    return if (total > 0L) {
        "$count $noun · ${formatRemainingTime(total, stringRemainingTimeUnits())}"
    } else {
        "$count $noun"
    }
}

/** «Розділ 5 із 14» — a real projection of playback state, or null. */
private fun libraryChapterLabel(book: LibraryBook): String? {
    val index = book.progress?.currentChapterIndex ?: return null
    val total = book.book.totalChapters
    return if (total > 0) "Розділ ${index + 1} із $total" else null
}

/** «разом 23 год 55 хв» — the honest listening time left in a filtered set. */
@Composable
private fun libraryRemainingTotal(books: List<LibraryBook>): String {
    val seconds = books.sumOf { it.remainingSeconds }
    if (seconds <= 0L) return ""
    return "разом ${formatRemainingTime(seconds, stringRemainingTimeUnits())}"
}

/**
 * A section header inside the book grid: uppercase label + pluralised count
 * (design guide §6.3's section style), with no page padding of its own — the
 * grid already carries it.
 */
@Composable
internal fun LibrarySectionHeader(
    title: String,
    count: Int,
    trailingText: String = ""
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppDimens.SpaceSm, bottom = AppDimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // #885 — the prototype writes section titles as a sentence
                // («Читаю та слухаю зараз»), not as shouted caps: sentence case,
                // a step larger, no tracking.
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = "$count ${ukPlural(count, one = "книга", few = "книги", many = "книг")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (trailingText.isNotBlank()) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A full-width, horizontally scrolling shelf of cover tiles. */
@Composable
internal fun LibraryShelf(
    books: List<LibraryBook>,
    tile: @Composable (LibraryBook) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppDimens.SpaceMd),
        contentPadding = PaddingValues(end = AppDimens.PageSides)
    ) {
        items(books, key = { it.book.id }) { book ->
            Box(modifier = Modifier.width(124.dp)) { tile(book) }
        }
    }
}

/**
 * The dense row of a narrowed-down library (no cover, a timeline rail, the
 * honest time left on the right). Same a11y contract as [LibraryBookCard]:
 * one node for the row body, one real play node next to the offline badge.
 */
@Composable
internal fun LibraryDenseRow(
    book: LibraryBook,
    availability: AvailabilityView?,
    onRecheck: () -> Unit,
    downloadCount: com.slukhayka.audiobooks.data.db.BookDownloadCount?,
    onOpen: () -> Unit,
    // #885 — the books tab renders EVERY book as this row now, so the row must
    // carry the return-focus contract the card used to own; without it the
    // "come back from the book" logic requested focus on an unattached
    // requester and the a11y journey crashed.
    bookReturnFocusRequester: FocusRequester? = null
) {
    val units = stringRemainingTimeUnits()
    val remaining = if (book.totalDurationSeconds > 0L) {
        formatRemainingTime(book.remainingSeconds, units)
    } else {
        null
    }
    val state = libraryEntryStateDescription(book, availability)
    val openLabel = stringResource(R.string.a11y_library_open_book, book.book.title)
    val description = if (book.book.displayAuthor.isBlank()) {
        book.book.title
    } else {
        stringResource(
            R.string.a11y_library_entry_description,
            book.book.title,
            book.book.displayAuthor
        )
    }
    val progressLabel = when {
        book.isCompleted -> "готово"
        book.isNew -> "новий"
        else -> "${(book.percent * 100f).roundToInt()}%"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The timeline thread: one continuous hairline per row, a fill dot on
        // the in-progress book. Stacked rows read as a single queue.
        Box(
            modifier = Modifier
                .width(20.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            )
            Box(
                modifier = Modifier
                    .size(if (book.isListening) 10.dp else 7.dp)
                    .clip(CircleShape)
                    .background(
                        if (book.isListening) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    )
            )
        }
        Spacer(modifier = Modifier.width(AppDimens.SpaceMd))
        Column(
            modifier = Modifier
                // #885 — the card attaches the return-focus requester FIRST in
                // the chain; the row must do the same, otherwise the focus never
                // lands and the a11y journey times out waiting for `Focused`.
                .then(
                    if (bookReturnFocusRequester != null) {
                        Modifier.focusRequester(bookReturnFocusRequester)
                    } else {
                        Modifier
                    }
                )
                .weight(1f)
                .padding(vertical = AppDimens.SpaceMd)
                .focusProperties { canFocus = true }
                .clickable(onClick = onOpen)
                // #885 — the dense row IS the library book item: keep the
                        // long-standing contract tag the journeys click, so the
                        // accessibility and playback tests keep their anchor.
                        .testTag("library_book_item_${book.book.id}")
                .clearAndSetSemantics {
                    contentDescription = description
                    stateDescription = state
                    role = Role.Button
                    onClick(label = openLabel) {
                        onOpen()
                        true
                    }
                }
        ) {
            Text(
                text = book.book.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (book.isCompleted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = listOfNotNull(
                book.book.displayAuthor.takeIf { it.isNotBlank() },
                libraryChapterLabel(book) ?: book.seriesLabel
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // ADR-0042 §1 — a problem Work states it here too, and a tap asks
            // for a fresh bounded re-check (spec-56 T2).
            availabilityLabel(availability)?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = onRecheck != null) { onRecheck() }
                )
            }
            // #885 — the prototype shows a thin progress line under every row, so
            // the queue reads at a glance without opening the book.
            if (book.totalDurationSeconds > 0L && !book.isNew) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        .testTag("library_row_progress_${book.book.id}")
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(book.percent.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(AppDimens.SpaceMd))
        Column(horizontalAlignment = Alignment.End) {
            if (remaining != null) {
                Text(
                    text = remaining,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (book.isListening) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Text(
                text = progressLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LibraryInlineOfflineBadge(book, downloadCount)
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        modifier = Modifier.padding(start = 32.dp)
    )
}

/** Which offline marker a book carries (#397), or none. */
private enum class OfflineState { NONE, PARTIAL, FULL }

private fun offlineStateOf(
    book: LibraryBook,
    downloadCount: com.slukhayka.audiobooks.data.db.BookDownloadCount?
): OfflineState = when {
    downloadCount != null &&
        downloadCount.downloaded > 0 &&
        downloadCount.downloaded < downloadCount.total -> OfflineState.PARTIAL

    book.book.isDownloaded -> OfflineState.FULL
    else -> OfflineState.NONE
}

/**
 * v1.5 review — the offline marker laid over the cover's top-end corner: a
 * cloud-with-check once the whole book is on disk, a compact «7/12» while only
 * some Source Tracks are. A solid scrim pill (never translucent, never bare
 * artwork) keeps it readable on any cover; the card's own state description is
 * what TalkBack announces, so the badge stays decorative.
 */
@Composable
private fun LibraryCoverOfflineBadge(
    book: LibraryBook,
    downloadCount: com.slukhayka.audiobooks.data.db.BookDownloadCount?,
    modifier: Modifier = Modifier
) {
    val state = offlineStateOf(book, downloadCount)
    if (state == OfflineState.NONE) return
    when (state) {
        OfflineState.FULL -> Box(
            modifier = modifier
                .size(20.dp)
                .testTag("library_offline_badge_${book.book.id}"),
            contentAlignment = Alignment.Center
        ) {
            // UI (v1.5 review): the cloud sits straight on the artwork — a disc
            // with a border read as a second, redundant circle. Legibility now
            // comes from a soft radial darkening that has no visible edge.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                        ),
                        shape = CircleShape
                    )
            )
            Icon(
                imageVector = Icons.Default.CloudDone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(15.dp)
            )
        }

        OfflineState.PARTIAL -> Surface(
            shape = RoundedCornerShape(AppDimens.RadiusXs),
            // Text needs a solid backing, but no ring around it either.
            color = AppBadgeScrim,
            modifier = modifier.testTag("library_offline_badge_${book.book.id}")
        ) {
            Text(
                text = stringResource(
                    R.string.offline_partial_badge_compact,
                    downloadCount?.downloaded ?: 0,
                    downloadCount?.total ?: 0
                ),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        OfflineState.NONE -> Unit
    }
}

/**
 * The same marker where there is no cover to lay it on — the dense row keeps
 * it as a quiet icon beside the time left.
 */
@Composable
private fun LibraryInlineOfflineBadge(
    book: LibraryBook,
    downloadCount: com.slukhayka.audiobooks.data.db.BookDownloadCount?
) {
    when (offlineStateOf(book, downloadCount)) {
        OfflineState.NONE -> Unit
        OfflineState.PARTIAL -> Text(
            text = stringResource(
                R.string.offline_partial_badge,
                downloadCount?.downloaded ?: 0,
                downloadCount?.total ?: 0
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.testTag("library_partial_badge_${book.book.id}")
        )
        OfflineState.FULL -> Icon(
            imageVector = Icons.Default.CloudDone,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .size(15.dp)
                .testTag("library_inline_offline_badge_${book.book.id}")
        )
    }
}

/**
 * The honest a11y state of a library entry: progress + offline/online + source
 * + the availability verdict of a problem Work (ADR-0042 §1).
 */
@Composable
private fun libraryEntryStateDescription(
    book: LibraryBook,
    availability: AvailabilityView?
): String {
    val progressState = if (book.totalDurationSeconds > 0L) {
        stringResource(
            R.string.a11y_library_progress,
            (book.percent * 100f).roundToInt(),
            formatRemainingTime(book.remainingSeconds, stringRemainingTimeUnits())
        )
    } else {
        stringResource(R.string.a11y_library_progress_unknown)
    }
    val sourceAvailability = when {
        book.isLocal -> stringResource(R.string.a11y_library_local)
        book.book.isDownloaded -> stringResource(R.string.a11y_library_offline)
        else -> stringResource(R.string.a11y_library_online)
    }
    val sourceState = book.sourceName
        .takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.a11y_library_source, it) }
    return listOfNotNull(
        progressState,
        sourceAvailability,
        sourceState,
        availabilityLabel(availability)
    ).joinToString(". ")
}

/**
 * The unified book card (wayfinder #39): cover, title, author, series+volume,
 * progress, remaining time, download status and a small source badge. The
 * [grid] flag switches between the compact row (list view) and the cover-first
 * tile (grid view) — one card for the whole library, wherever the book lives.
 */
@Composable
fun LibraryBookCard(
    book: LibraryBook,
    grid: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    availability: com.slukhayka.audiobooks.data.availability.AvailabilityView? = null,
    onRecheck: (() -> Unit)? = null,
    downloadCount: com.slukhayka.audiobooks.data.db.BookDownloadCount? = null,
    /** Spec-53 T3 — the submission awaits its real playback verdict. */
    awaitingPlayback: Boolean = false,
    /** Spec-53 T5 — a TG card waits for a direct source (the preview has no audio). */
    watchingSource: Boolean = false,
    /** Spec-53 T12 — the real verdict landed, the day's budget had not. */
    deferredPublication: Boolean = false,
    /** #837 — the honest moderation badge of MY submission of this book. */
    submissionBadge: SubmissionBadge = SubmissionBadge.NONE,
    /** Spec-53 T3 — badge tap: open the book and start playing it. */
    onListenNow: (() -> Unit)? = null
) {
    val author = book.book.displayAuthor
    val description = if (author.isBlank()) {
        book.book.title
    } else {
        stringResource(
            com.slukhayka.audiobooks.R.string.a11y_library_entry_description,
            book.book.title,
            author
        )
    }
    // #837 — the moderation badge is SPOKEN, not just seen: the card clears
    // its descendants' semantics, so the honest state must ride the card's own
    // state description next to the offline state.
    val badgeState = if (submissionBadge != SubmissionBadge.NONE) {
        stringResource(submissionBadgeRes(submissionBadge))
    } else {
        null
    }
    val state = listOfNotNull(
        libraryEntryStateDescription(book, availability).takeIf { it.isNotBlank() },
        badgeState
    ).joinToString(", ")
    val openLabel = stringResource(
        com.slukhayka.audiobooks.R.string.a11y_library_open_book,
        book.book.title
    )
    val performOpen = onClick
    // UI (v1.5 review): the card BODY is one contextual action (unchanged
    // a11y contract), and the two actions a listener uses most — play now and
    // see the offline state — are real sibling nodes next to it, inside the
    // same surface. A card that swallowed its own play button would leave that
    // shortcut unreachable for TalkBack.
    Card(
        modifier = (if (grid) modifier else Modifier)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        if (grid) {
            Box(
                modifier = Modifier
                    .focusProperties { canFocus = true }
                    .clickable(onClick = performOpen)
                    .testTag("library_book_item_${book.book.id}")
                    .clearAndSetSemantics {
                        contentDescription = description
                        stateDescription = state
                        role = Role.Button
                        onClick(label = openLabel) {
                            performOpen()
                            true
                        }
                    }
            ) {
                LibraryBookGridContent(book, availability, onRecheck, downloadCount)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = modifier
                        .weight(1f)
                        // Route-return focus must also work on touch-only devices.
                        .focusProperties { canFocus = true }
                        .clickable(onClick = performOpen)
                        .testTag("library_book_item_${book.book.id}")
                        .clearAndSetSemantics {
                            contentDescription = description
                            stateDescription = state
                            role = Role.Button
                            onClick(label = openLabel) {
                                performOpen()
                                true
                            }
                        }
                ) {
                    LibraryBookRowContent(
                        book,
                        availability,
                        onRecheck,
                        downloadCount = downloadCount,
                        awaitingPlayback = awaitingPlayback,
                        submissionBadge = submissionBadge,
                        watchingSource = watchingSource,
                        deferredPublication = deferredPublication,
                        onListenNow = onListenNow
                    )
                }
                // UI (v1.5 review, on-device): a play disc on every row put the
                // screen's accent on eight identical circles and stole it from
                // the «Продовжити» CTA. The row opens the book; the hero CTA
                // resumes; a new book starts on its own page.
                Spacer(modifier = Modifier.width(AppDimens.SpaceMd))
            }
        }
    }
}

@Composable
private fun LibraryBookRowContent(
    book: LibraryBook,
    availability: com.slukhayka.audiobooks.data.availability.AvailabilityView? = null,
    onRecheck: (() -> Unit)? = null,
    // #397 — the offline marker is laid over the cover's top-end corner.
    downloadCount: com.slukhayka.audiobooks.data.db.BookDownloadCount? = null,
    awaitingPlayback: Boolean = false,
    watchingSource: Boolean = false,
    deferredPublication: Boolean = false,
    submissionBadge: SubmissionBadge = SubmissionBadge.NONE,
    onListenNow: (() -> Unit)? = null
) {
    // v1.4 E3 (ADR-0033): the library list row IS the canonical BookRow —
    // the old bespoke 56 dp Row (a fifth row style) is gone. The card's
    // own a11y contract (tag, content/state description, role) still rides
    // on the Card wrapper above; the inner row only carries the visuals.
    BookRow(
        title = book.book.title,
        book = book.book,
        author = book.book.displayAuthor.takeIf { it.isNotBlank() },
        // The series line is a known fact about the edition, so it rides
        // the stats slot (label line under the author).
        stats = book.seriesLabel,
        progress = book.percent,
        coverBadge = {
            LibraryCoverOfflineBadge(
                book = book,
                downloadCount = downloadCount,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
            )
        },
        footnoteInColumn = true,
        badges = {
            // C4: the canonical provenance chip — the local SourceBadge was
            // a pixel-duplicate of MetadataChip(source=…).
            if (book.sourceName.isNotBlank()) MetadataChip(source = book.sourceName)
            if (awaitingPlayback && onListenNow != null) {
                Spacer(modifier = Modifier.width(AppDimens.SpaceXs))
                Text(
                    text = stringResource(com.slukhayka.audiobooks.R.string.submission_awaiting_badge),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onListenNow)
                        .testTag("submission_awaiting_badge_${book.book.id}")
                )
            }
            if (watchingSource) {
                // Spec-53 T5 — an honest badge on the TG card: no audio yet,
                // a direct source is being watched for (spec-49 reports it).
                Spacer(modifier = Modifier.width(AppDimens.SpaceXs))
                Text(
                    text = stringResource(R.string.submission_watching_source),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.testTag("submission_watching_badge_${book.book.id}")
                )
            }
            if (deferredPublication) {
                // Spec-53 T12 — the copy really played; the day's budget was
                // gone, so the publication is promised for tomorrow instead
                // of being refused. No second playback will be needed.
                Spacer(modifier = Modifier.width(AppDimens.SpaceXs))
                Text(
                    text = stringResource(R.string.submission_deferred_publication_badge),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.testTag("submission_deferred_publication_badge_${book.book.id}")
                )
            }
            if (submissionBadge != SubmissionBadge.NONE) {
                // #837 — the honest state of MY submission of this book: a
                // queued candidate, an approved publication, or the curator's
                // rejection. Never a promise, never before the fact.
                Spacer(modifier = Modifier.width(AppDimens.SpaceXs))
                Text(
                    text = stringResource(submissionBadgeRes(submissionBadge)),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = when (submissionBadge) {
                        SubmissionBadge.REJECTED -> MaterialTheme.colorScheme.error
                        SubmissionBadge.IN_SHARED_BASE -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.secondary
                    },
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .wrapContentWidth()
                        .testTag(
                            "submission_badge_${submissionBadge.name.lowercase()}_${book.book.id}"
                        )
                )
            }
            // #397 — the offline state (cloud / «7 із 12») is not a title
            // badge any more: it rides next to the play action, where it can
            // never be pushed off-screen by a long title.
        },
        footnote = {
            availabilityLabel(availability)?.let { label ->
                // ADR-0042 §1 — the honest availability state of a problem
                // Work, under the progress hairline; a clean Work has none.
                // Spec-56 T2: a tap asks for a fresh, bounded re-check.
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(bottom = AppDimens.SpaceXs)
                        .clickable(enabled = onRecheck != null) { onRecheck?.invoke() }
                )
            }
            // Always rendered, so every row is the same height: an unknown
            // duration shows the honest «—» instead of a missing line.
            Text(
                text = stringResource(
                    R.string.library_remaining,
                    formatRemainingTime(book.remainingSeconds, stringRemainingTimeUnits())
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(bottom = AppDimens.SpaceXs)
            )
        }
    )
}

@Composable
private fun LibraryBookGridContent(
    book: LibraryBook,
    availability: com.slukhayka.audiobooks.data.availability.AvailabilityView? = null,
    onRecheck: (() -> Unit)? = null,
    downloadCount: com.slukhayka.audiobooks.data.db.BookDownloadCount? = null
) {
    Column {
        Box {
            BookCoverImage(
                book = book.book,
                semantics = BookCoverSemantics.Decorative,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
                contentScale = ContentScale.Crop
            )
            LibraryCoverOfflineBadge(
                book = book,
                downloadCount = downloadCount,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
            )
        }
        // UI (v1.5 review, on-device): every tile reserves the SAME slots, so a
        // shelf or a grid row has one height instead of a ragged staircase.
        // A tile shorter than its neighbour looked like a bug, not like a book
        // with less metadata. What varies now is only the text inside a slot.
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = book.book.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                // An empty string still occupies its line — that is the point.
                text = book.book.displayAuthor,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = book.seriesLabel.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { book.percent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(AppDimens.RadiusProgress))
                    .clearAndSetSemantics { },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                // #292 carried to the shelf: the M3 stop dot read as a stray
                // amber marker crowding the provenance chip right below it
                // (device review, 2026-09-16).
                drawStopIndicator = {}
            )
            // The provenance chip sits directly under this bar. 4 dp merged
            // the two amber elements into one smudge; the chip needs air.
            Spacer(modifier = Modifier.height(10.dp))
            // One reserved line: the verdict of a problem Work, or the source.
            // The remaining time lives in the list rows — on a 124 dp tile it
            // only ellipsised into noise.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                availabilityLabel(availability)?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = onRecheck != null) { onRecheck?.invoke() }
                    )
                }
                // C4: the canonical provenance chip (the local SourceBadge
                // was a pixel-duplicate of MetadataChip(source=…)).
                if (book.sourceName.isNotBlank()) MetadataChip(source = book.sourceName)
                // #397 — the offline marker rides the artwork (LibraryCoverOfflineBadge).
            }
        }
    }
}

@Composable
fun ListeningStatsCard(listeningStats: List<com.slukhayka.audiobooks.data.db.ListeningStatEntity>, totalBooks: Int) {
    val todayIso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
    val todayStat = listeningStats.find { it.dateIso == todayIso }
    val todayMinutes = ((todayStat?.listenedSeconds ?: 0L) / 60L)

    val totalWeekSeconds = listeningStats.take(7).sumOf { it.listenedSeconds }
    val weekHours = String.format(java.util.Locale.US, "%.1f", totalWeekSeconds / 3600f)

    val streakDays = listeningStats.takeWhile { it.listenedSeconds > 0 }.size.coerceAtLeast(if (todayMinutes > 0) 1 else 0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "Статистика прослуховування",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatItemCard(
                title = "Сьогодні",
                value = "$todayMinutes хв",
                icon = Icons.Default.Today,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            StatItemCard(
                title = "За тиждень",
                value = "$weekHours год",
                icon = Icons.Default.DateRange,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatItemCard(
                title = "Серія днів",
                value = "$streakDays дн поспіль",
                icon = Icons.Default.Whatshot,
                color = AppStatStreak,
                modifier = Modifier.weight(1f)
            )
            StatItemCard(
                title = "Всього в бібліотеці",
                value = "$totalBooks книг",
                icon = Icons.AutoMirrored.Filled.MenuBook,
                color = AppStatLibrary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun StatItemCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(AppDimens.RadiusPanel))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusPanel)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun GlobalBookmarkItem(
    bookmark: BookmarkEntity,
    bookTitle: String,
    onJumpClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val bookmarkTime = MainViewModel.formatTime(bookmark.timestampSeconds)
    val jumpLabel = stringResource(
        com.slukhayka.audiobooks.R.string.a11y_library_jump_bookmark,
        bookTitle,
        bookmark.chapterTitle,
        bookmarkTime
    )
    val deleteLabel = stringResource(
        com.slukhayka.audiobooks.R.string.a11y_library_delete_bookmark,
        bookTitle,
        bookmark.chapterTitle,
        bookmarkTime
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusCardLg)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Bookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bookTitle,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = bookmark.chapterTitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    // Spec-27 (#204): «На 2:35:44: …» — never EN «At».
                    text = "На ${MainViewModel.formatTime(bookmark.timestampSeconds)}: ${bookmark.note}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onJumpClick,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = jumpLabel }
                    .testTag("bookmark_jump_${bookmark.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = deleteLabel }
                    .testTag("bookmark_delete_${bookmark.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Spec-27 (#184) BUG-001 — the destructive-action confirm: deletes every
 * downloaded file, quoting the exact scope (book count + bytes) so the
 * listener knows what is about to happen. The confirm button is the error
 * color (the destructive tone, never a neutral primary); dismissing leaves
 * every file untouched. Extracted so the dialog is snapshot-testable without
 * a [MainViewModel].
 */
@Composable
fun ClearCacheConfirmDialog(
    bookCount: Int,
    bytes: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val title = stringResource(R.string.storage_delete_dialog_title)
    val confirmFocusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .testTag("clear_cache_dialog")
            .accessibilityPane(title),
        title = {
            LaunchedEffect(Unit) {
                withFrameNanos { }
                confirmFocusRequester.requestFocus()
            }
            Text(
                title,
                modifier = Modifier
                    .focusRequester(confirmFocusRequester)
                    .focusable()
                    .testTag("clear_cache_dialog_heading")
                    .semantics { heading() }
            )
        },
        text = { Text(clearCacheConfirmText(bookCount, bytes)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("clear_cache_confirm")
            ) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * The smart-import preview dialog (wayfinder #29): scan → plan → confirm →
 * apply. Shows the planned books (grouping + natural order) and the #54
 * merge suggestions as review rows — the plan is pure data, nothing is
 * written until [onConfirm]. Dismissing leaves zero trace.
 */
@Composable
fun ImportPreviewDialog(
    preview: com.slukhayka.audiobooks.ui.MainViewModel.ImportPreviewState,
    onAcceptMerge: (String) -> Unit,
    onRejectMerge: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val mergedCount = preview.plan.books.count { it.mergedIntoBookId != null }
    val headingFocusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .accessibilityPane(stringResource(R.string.a11y_library_import_preview_pane))
            .testTag("library_import_preview_dialog"),
        title = {
            LaunchedEffect(headingFocusRequester) {
                withFrameNanos { }
                headingFocusRequester.requestFocus()
            }
            Text(
                stringResource(R.string.a11y_library_import_preview_title),
                modifier = Modifier
                    .focusRequester(headingFocusRequester)
                    .focusable()
                    .semantics { heading() }
                    .testTag("library_import_preview_heading")
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Знайдено ${preview.plan.books.size} книг — нічого не записано, поки не підтвердите.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                preview.plan.books.forEach { book ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = book.title.ifBlank { "Без назви" },
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${book.chapters.size} файл(ів)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val suggestion = book.suggestion
                            if (suggestion != null && book.mergedIntoBookId == null) {
                                Text(
                                    text = "Схоже на «${suggestion.existingTitle}» (${suggestion.reason})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { onAcceptMerge(book.id) }) {
                                        Text(stringResource(R.string.lib_import_connect))
                                    }
                                    TextButton(onClick = { onRejectMerge(book.id) }) {
                                        Text(stringResource(R.string.lib_import_not_this))
                                    }
                                }
                            } else if (book.mergedIntoBookId != null) {
                                Text(
                                    text = "Буде з'єднано з «${suggestion?.existingTitle ?: "книгою в бібліотеці"}»",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("library_import_preview_confirm")
            ) {
                Text(
                    if (mergedCount > 0) {
                        "Імпортувати (${preview.plan.books.size - mergedCount} нових, $mergedCount з'єднати)"
                    } else {
                        "Імпортувати ${preview.plan.books.size}"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * #401 — one bookmarked person row in the Медіатека "Люди" tab.
 * Always bookmarked (this list only shows bookmarked people);
 * long-press toggles notifyEnabled without deleting the bookmark.
 */
@Composable
fun BookmarkedPersonRow(
    displayName: String,
    role: PersonRole,
    notifyEnabled: Boolean,
    onClick: () -> Unit,
    onToggleNotify: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var currentNotifyEnabled by remember(notifyEnabled) { mutableStateOf(notifyEnabled) }

    Box(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .defaultMinSize(minHeight = 48.dp)
                .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusCardLg))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showContextMenu = true }
                )
                .semantics(mergeDescendants = true) {
                    stateDescription = if (currentNotifyEnabled) "Закладка, повідомлення увімкнені" else "Закладка, повідомлення вимкнені"
                }
                .testTag("bookmarked_person_${displayName.hashCode()}"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (role == PersonRole.AUTHOR) "Автор" else "Виконавець",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (currentNotifyEnabled) "Повідомлення увімкнені" else "Повідомлення вимкнені",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (currentNotifyEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = Icons.Default.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (currentNotifyEnabled) "Вимкнути повідомлення" else "Увімкнути повідомлення") },
                onClick = {
                    currentNotifyEnabled = !currentNotifyEnabled
                    onToggleNotify(currentNotifyEnabled)
                    showContextMenu = false
                }
            )
        }
    }
}

/** #837 — the card badge's honest label. */
private fun submissionBadgeRes(badge: SubmissionBadge): Int = when (badge) {
    SubmissionBadge.PENDING_MODERATION -> R.string.submission_badge_pending_moderation
    SubmissionBadge.IN_SHARED_BASE -> R.string.submission_badge_in_shared_base
    SubmissionBadge.REJECTED -> R.string.submission_badge_rejected
    SubmissionBadge.NONE -> R.string.submission_badge_pending_moderation
}

/** #876 — the per-format label of the reading year. */
@androidx.compose.runtime.Composable
private fun readingFormatLabel(
    format: com.slukhayka.audiobooks.data.entries.ReadingFormat
): String = when (format) {
    com.slukhayka.audiobooks.data.entries.ReadingFormat.AUDIO ->
        stringResource(R.string.lib_format_audio)
    com.slukhayka.audiobooks.data.entries.ReadingFormat.PAPER ->
        stringResource(R.string.manual_add_format_paper)
    com.slukhayka.audiobooks.data.entries.ReadingFormat.EBOOK ->
        stringResource(R.string.manual_add_format_ebook)
}

/** #876 — one active pass with its own value field and two honest actions. */
@androidx.compose.runtime.Composable
private fun ReadingProgressRow(
    pass: com.slukhayka.audiobooks.data.entries.Readthrough,
    onRecord: (Int) -> Unit,
    onFinish: () -> Unit
) {
    var raw by androidx.compose.runtime.saveable.rememberSaveable(pass.id) {
        androidx.compose.runtime.mutableStateOf("")
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(
                R.string.lib_year_format,
                readingFormatLabel(pass.format),
                pass.units.value
            ),
            style = MaterialTheme.typography.titleSmall
        )
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = raw,
                onValueChange = { input -> raw = input.filter { it.isDigit() } },
                label = { Text(stringResource(R.string.lib_year_record_hint)) },
                singleLine = true,
                modifier = Modifier
                    .width(160.dp)
                    .testTag("reading_value_${pass.id}")
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { raw.toIntOrNull()?.let(onRecord) },
                enabled = raw.toIntOrNull() != null,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("reading_record_${pass.id}")
            ) {
                Text(stringResource(R.string.lib_year_record))
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onFinish,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("reading_finish_${pass.id}")
            ) {
                Text(stringResource(R.string.lib_year_finish))
            }
        }
    }
}


/** #885 — Книги | Полиці | Збережене, the prototype's top-level switch. */
@Composable
private fun LibrarySectionTabs(
    booksSelected: Boolean,
    savedSelected: Boolean,
    onBooks: () -> Unit,
    onShelves: () -> Unit,
    onSaved: () -> Unit,
) {
    val items = listOf(
        Triple(stringResource(R.string.lib_section_books), booksSelected, "library_tab_books" to onBooks),
        Triple(stringResource(R.string.lib_section_shelves), false, "library_tab_shelves" to onShelves),
        Triple(stringResource(R.string.lib_section_saved), savedSelected, "library_tab_saved" to onSaved),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (label, selected, action) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(end = 20.dp)
                    .testTag(action.first)
                    .clickable(onClick = action.second)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Box(
                    modifier = Modifier
                        .height(3.dp)
                        .width(if (selected) 24.dp else 0.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                )
            }
        }
    }
}
