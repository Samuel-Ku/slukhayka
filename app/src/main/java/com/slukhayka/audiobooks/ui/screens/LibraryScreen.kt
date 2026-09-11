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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
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
import com.slukhayka.audiobooks.ui.components.BookRow
import com.slukhayka.audiobooks.ui.components.EmptyState
import com.slukhayka.audiobooks.ui.components.MetadataChip
import com.slukhayka.audiobooks.ui.components.RestoreFocusAfterModal
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.library.LibraryBook
import com.slukhayka.audiobooks.ui.library.LibraryFilter
import com.slukhayka.audiobooks.ui.library.LibrarySort
import com.slukhayka.audiobooks.ui.library.clearCacheConfirmText
import com.slukhayka.audiobooks.ui.library.SHEET_FILTERS
import com.slukhayka.audiobooks.ui.library.filterAndSortLibrary
import com.slukhayka.audiobooks.ui.library.formatRemainingTime
import com.slukhayka.audiobooks.ui.library.stringRemainingTimeUnits
import com.slukhayka.audiobooks.ui.theme.*
import kotlin.math.roundToInt

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
    onBookFocusRestored: (String) -> Unit = {}
) {
    val libraryBooks by viewModel.libraryBooks.collectAsState()
    val libraryAvailability by viewModel.libraryAvailability.collectAsState()
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
    var activeTab by remember { mutableStateOf(0) } // 0 = Книги, 1 = Закладки, 2 = Статистика, 3 = Люди
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var sort by remember { mutableStateOf(LibrarySort.RECENTLY_LISTENED) }
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
    var gridMode by remember { mutableStateOf(false) }
    // Spec-28 #193: the rare filters, sort and view toggle live in the sheet.
    var showFilterSheet by remember { mutableStateOf(false) }
    // Spec-28 #194: import is one «+ Додати» action opening a sheet; the
    // storage destination is reached from Settings.
    var showImportSheet by remember { mutableStateOf(false) }
    // Spec-601 T3/T5 — the «Надіслати посилання» sheet: paste a YouTube/TG
    // link; publication happens only after the imported copy really plays.
    var showSubmissionSheet by remember { mutableStateOf(false) }
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

    LaunchedEffect(
        restoreFocusBookId,
        visibleBooks,
        libraryBooks,
        activeTab,
        modalVisible
    ) {
        val bookId = restoreFocusBookId ?: return@LaunchedEffect
        if (activeTab != 0 || modalVisible) return@LaunchedEffect
        val visibleIndex = visibleBooks.indexOfFirst { it.book.id == bookId }
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
            // title + subtitle through AppTabHeader, the 🔍 collapsible search
            // (the same gesture as Огляд) and «+ Додати» as header actions.
            // Import stays one action — a sheet with the two source options
            // (files / folder) per spec-28 #194.
            com.slukhayka.audiobooks.ui.components.AppTabHeader(
                title = "Медіатека",
                // Spec-15 T6: one library for local files and every
                // online source, not just 4read.
                subtitle = "Всі книги — в одному місці",
                headingTestTag = "library_heading",
                returnFocusRequester = libraryHeadingFocusRequester,
                actions = {
                    IconButton(
                        onClick = {
                            searchRequested = !searchExpanded
                            if (!searchRequested && query.isNotBlank()) query = ""
                        },
                        modifier = Modifier
                            .size(AppDimens.TouchTarget)
                            .testTag("library_search_toggle")
                    ) {
                        Icon(
                            imageVector = if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = stringResource(
                                if (searchExpanded) {
                                    com.slukhayka.audiobooks.R.string.a11y_close_search
                                } else {
                                    com.slukhayka.audiobooks.R.string.a11y_open_search
                                }
                            )
                        )
                    }
                    Button(
                        onClick = { showImportSheet = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(AppDimens.RadiusCardLg),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .focusRequester(importFocusRequester)
                            .testTag("library_add_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Додати",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            )

            // Sub-tabs: the unified book list, bookmarks, listening stats.
            ScrollableTabRow(
                selectedTabIndex = activeTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 16.dp,
                divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            ) {
                Tab(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    text = { Text(stringResource(R.string.lib_books_count, libraryBooks.size), fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    text = { Text(stringResource(R.string.lib_bookmarks_count, allBookmarks.size), fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    text = { Text(stringResource(R.string.lib_statistics), fontWeight = FontWeight.Bold) }
                )
                // #401: bookmarked people tab.
                Tab(
                    selected = activeTab == 3,
                    onClick = { activeTab = 3 },
                    text = { Text("Люди (${bookmarkedPeople.size})", fontWeight = FontWeight.Bold) }
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

                // Spec-28 #193: the five one-tap statuses as a segmented row.
                LibraryStatusRow(selected = filter, onSelect = { filter = it })

                // Spec-28 #193: the rare filters (Обрані / Локальні / Онлайн),
                // sort and view toggle collapse into the filter sheet. The
                // launcher chip turns accent and names the active rare filter,
                // so a non-default filter stays visible at a glance.
                val isSheetFilterActive = filter in SHEET_FILTERS
                FilterChip(
                    selected = isSheetFilterActive,
                    onClick = { showFilterSheet = true },
                    label = { Text(if (isSheetFilterActive) filter.label else "Фільтр") },
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
                        // #560: the chip row sits flush on the launcher without
                        // this gap — the canonical spacer keeps the vertical rhythm.
                        .padding(top = AppDimens.SpaceXs)
                        .padding(horizontal = 16.dp)
                        .heightIn(min = 48.dp)
                        .focusRequester(filterFocusRequester)
                        .testTag("library_filter_button")
                )

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
                            items(visibleBooks, key = { it.book.id }) { entry ->
                                LibraryBookCard(
                                    book = entry,
                                    grid = gridMode,
                                    onClick = { onBookClick(entry.book.id) },
                                    modifier = if (entry.book.id == restoreFocusBookId) {
                                        Modifier.focusRequester(bookReturnFocusRequester)
                                    } else {
                                        Modifier
                                    },
                                    availability = libraryAvailability[entry.book.mergeKey],
                                    onRecheck = { viewModel.recheckAvailability(entry.book.id) }
                                )
                            }
                        }
                    }
                }

                1 -> {
                    if (allBookmarks.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.BookmarkBorder,
                            title = "Закладок немає",
                            body = "Додавайте закладки під час прослуховування в плеєрі."
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = AppDimens.SpaceAboveMiniPlayer, top = 12.dp)
                        ) {
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

                // #401: bookmarked people tab — authors and narrators the
                // listener follows. Each row opens the person's books page.
                3 -> {
                    if (bookmarkedPeople.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.People,
                            title = "Закладок на людей немає",
                            body = "Додавайте закладки на авторів або виконавців зі сторінки книги."
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = AppDimens.SpaceAboveMiniPlayer, top = 8.dp)
                        ) {
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
            SubmissionSheet(
                state = submissionState,
                remainingToday = submissionRemaining,
                onSubmit = viewModel::submitLink,
                onDismiss = {
                    showSubmissionSheet = false
                    viewModel.dismissSubmission()
                }
            )
        }
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
        title = "Медіатека порожня",
        body = "Додайте власні аудіокниги з пристрою або знайдіть нові в каталозі."
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
    onRecheck: (() -> Unit)? = null
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
    val progressState = if (book.totalDurationSeconds > 0L) {
        stringResource(
            com.slukhayka.audiobooks.R.string.a11y_library_progress,
            (book.percent * 100f).roundToInt(),
            formatRemainingTime(book.remainingSeconds, stringRemainingTimeUnits())
        )
    } else {
        stringResource(com.slukhayka.audiobooks.R.string.a11y_library_progress_unknown)
    }
    val sourceAvailability = when {
        book.isLocal -> stringResource(com.slukhayka.audiobooks.R.string.a11y_library_local)
        book.book.isDownloaded -> stringResource(com.slukhayka.audiobooks.R.string.a11y_library_offline)
        else -> stringResource(com.slukhayka.audiobooks.R.string.a11y_library_online)
    }
    val sourceState = stringResource(
        com.slukhayka.audiobooks.R.string.a11y_library_source,
        book.sourceName
    )
    val availabilityState = availabilityLabel(availability)
    val state = listOfNotNull(progressState, sourceAvailability, sourceState, availabilityState)
        .joinToString(". ")
    val openLabel = stringResource(
        com.slukhayka.audiobooks.R.string.a11y_library_open_book,
        book.book.title
    )
    val performOpen = onClick
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
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
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        if (grid) {
            LibraryBookGridContent(book, availability, onRecheck)
        } else {
            LibraryBookRowContent(book, availability, onRecheck)
        }
    }
}

@Composable
private fun LibraryBookRowContent(
    book: LibraryBook,
    availability: com.slukhayka.audiobooks.data.availability.AvailabilityView? = null,
    onRecheck: (() -> Unit)? = null
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
        badges = {
            // C4: the canonical provenance chip — the local SourceBadge was
            // a pixel-duplicate of MetadataChip(source=…).
            MetadataChip(source = book.sourceName)
            if (book.book.isDownloaded) {
                Spacer(modifier = Modifier.width(AppDimens.SpaceXs))
                Icon(
                    imageVector = Icons.Default.CloudDone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(14.dp)
                )
            }
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
                        .padding(
                            start = AppDimens.PageSides + 64.dp + AppDimens.SpaceMd,
                            bottom = AppDimens.SpaceXs
                        )
                        .clickable(enabled = onRecheck != null) { onRecheck?.invoke() }
                )
            }
            if (book.totalDurationSeconds > 0L) {
                // Aligned under the text column (canonical footnote rhythm:
                // the honest remaining line, right under the progress hairline).
                Text(
                    text = stringResource(
                        R.string.library_remaining,
                        formatRemainingTime(book.remainingSeconds, stringRemainingTimeUnits())
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = AppDimens.PageSides + 64.dp + AppDimens.SpaceMd,
                        bottom = AppDimens.SpaceXs
                    )
                )
            }
        }
    )
}

@Composable
private fun LibraryBookGridContent(
    book: LibraryBook,
    availability: com.slukhayka.audiobooks.data.availability.AvailabilityView? = null,
    onRecheck: (() -> Unit)? = null
) {
    Column {
        BookCoverImage(
            book = book.book,
            semantics = BookCoverSemantics.Decorative,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f),
            contentScale = ContentScale.Crop
        )
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = book.book.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (book.book.displayAuthor.isNotBlank()) {
                Text(
                    text = book.book.displayAuthor,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            book.seriesLabel?.let { series ->
                Text(
                    text = series,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { book.percent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(AppDimens.RadiusProgress))
                    .clearAndSetSemantics { },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                availabilityLabel(availability)?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(enabled = onRecheck != null) { onRecheck?.invoke() }
                    )
                }
                if (book.totalDurationSeconds > 0L) {
                    Text(
                        text = formatRemainingTime(book.remainingSeconds, stringRemainingTimeUnits()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // C4: the canonical provenance chip (the local SourceBadge
                // was a pixel-duplicate of MetadataChip(source=…)).
                MetadataChip(source = book.sourceName)
                if (book.book.isDownloaded) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.CloudDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
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
