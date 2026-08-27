package com.slukhayka.audiobooks.ui.screens

import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
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
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.BookCoverSemantics
import com.slukhayka.audiobooks.ui.components.BookCoverImage
import com.slukhayka.audiobooks.ui.components.EmptyState
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
    onBookClick: (String) -> Unit,
    onPlayClick: (AudiobookEntity) -> Unit,
    onBrowseClick: () -> Unit,
    restoreFocusBookId: String? = null,
    onBookFocusRestored: (String) -> Unit = {},
    restoreOverflowFocus: Boolean = false,
    onOverflowFocusRestored: () -> Unit = {}
) {
    val libraryBooks by viewModel.libraryBooks.collectAsState()
    // ADR-0008: module flows are read directly — no forwarding StateFlow on
    // the ViewModel. getAllListeningStats() builds the (cold) flow, so it is
    // remembered once per composition instead of re-created on every frame.
    val allBookmarks by libraryEntries.allBookmarks.collectAsState(initial = emptyList())
    val allBooks by libraryEntries.allBooks.collectAsState(initial = emptyList())
    val listeningStats by remember { listeningState.getAllListeningStats() }
        .collectAsState(initial = emptyList())
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
    var activeTab by remember { mutableStateOf(0) } // 0 = Книги, 1 = Закладки, 2 = Статистика
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var sort by remember { mutableStateOf(LibrarySort.RECENTLY_LISTENED) }
    var query by remember { mutableStateOf("") }
    var gridMode by remember { mutableStateOf(false) }
    // Spec-28 #193: the rare filters, sort and view toggle live in the sheet.
    var showFilterSheet by remember { mutableStateOf(false) }
    // Spec-28 #194: import is one «+ Додати» action opening a sheet; the
    // storage destination is reached from the ⋮ overflow menu.
    var showImportSheet by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val filterFocusRequester = remember { FocusRequester() }
    val importFocusRequester = remember { FocusRequester() }
    val libraryHeadingFocusRequester = remember { FocusRequester() }
    val bookReturnFocusRequester = remember { FocusRequester() }
    val overflowFocusRequester = remember { FocusRequester() }
    val libraryGridState = rememberLazyGridState()
    val modalVisible = showFilterSheet || showImportSheet || importPreview != null

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

    LaunchedEffect(restoreOverflowFocus, modalVisible) {
        if (!restoreOverflowFocus || modalVisible) return@LaunchedEffect
        withFrameNanos { }
        overflowFocusRequester.requestFocus()
        onOverflowFocusRestored()
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
            // Top Header — one row: title + subtitle, «+ Додати» (the import
            // sheet) and the ⋮ overflow (the storage destination). Collapsing
            // the two import buttons into one action and dropping the storage
            // row (spec-28 #194) lifts the first book above the fold.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Медіатека",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        modifier = Modifier
                            .focusRequester(libraryHeadingFocusRequester)
                            .focusable()
                            .testTag("library_heading")
                            .semantics { heading() }
                    )
                    Text(
                        // Spec-15 T6: one library for local files and every
                        // online source, not just 4read.
                        text = "Всі книги — в одному місці",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }

                // Spec-28 #194: import is one action — a sheet with the two
                // source options (files / folder). Per ADR-0018 the add-audio
                // picker is a sheet, not two competing buttons.
                Button(
                    onClick = { showImportSheet = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(AppDimens.RadiusCardLg),
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
                Spacer(modifier = Modifier.width(4.dp))

                // ⋮ overflow — the «Завантаження та пам'ять» destination
                // (spec-28 #194): storage info and the destructive delete,
                // off the main screen.
                Box {
                    IconButton(
                        onClick = { showOverflowMenu = true },
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .focusRequester(overflowFocusRequester)
                            .testTag("library_overflow_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(com.slukhayka.audiobooks.R.string.a11y_library_more_actions)
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Завантаження та пам'ять") },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.openStorageDestination()
                            },
                            modifier = Modifier.testTag("library_storage_menu_item")
                        )
                        // spec-40 #275 (t1): the silent listener profile —
                        // ⚙️ Профіль lives in the same overflow (a rare
                        // surface, off the main screen, ADR-0018).
                        DropdownMenuItem(
                            text = { Text("Профіль") },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.openProfileSettings()
                            },
                            modifier = Modifier.testTag("library_profile_menu_item")
                        )
                        // spec-38 T2 (#254): the network privacy route lives in
                        // the same overflow — a rare surface, off the main screen.
                        DropdownMenuItem(
                            text = { Text("Приватність мережі") },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.openPrivacySettings()
                            },
                            modifier = Modifier.testTag("library_privacy_menu_item")
                        )
                        DropdownMenuItem(
                            text = { Text("Персональні рекомендації") },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.openRecommendationSettings()
                            },
                            modifier = Modifier.testTag("library_recommendations_menu_item")
                        )
                    }
                }
            }

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
                    text = { Text("Книги (${libraryBooks.size})", fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    text = { Text("Закладки (${allBookmarks.size})", fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    text = { Text("Статистика", fontWeight = FontWeight.Bold) }
                )
            }

            if (activeTab == 0) {
                // Library chrome (wayfinder #39): search, quick filters, sort + view toggle.
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("library_search"),
                    label = { Text(stringResource(com.slukhayka.audiobooks.R.string.a11y_library_search)) },
                    placeholder = { Text("Назва, автор або серія") },
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
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
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
                                    }
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
                            contentPadding = PaddingValues(bottom = 120.dp, top = 12.dp)
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
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
                        item {
                            ListeningStatsCard(listeningStats = listeningStats, totalBooks = libraryBooks.size)
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
            Text("Додати свої файли")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onBrowseClick,
            shape = RoundedCornerShape(AppDimens.RadiusCard),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("library_empty_browse")
        ) {
            Text("Знайти книгу")
        }
    }
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
    modifier: Modifier = Modifier
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
            formatRemainingTime(book.remainingSeconds)
        )
    } else {
        stringResource(com.slukhayka.audiobooks.R.string.a11y_library_progress_unknown)
    }
    val availability = when {
        book.isLocal -> stringResource(com.slukhayka.audiobooks.R.string.a11y_library_local)
        book.book.isDownloaded -> stringResource(com.slukhayka.audiobooks.R.string.a11y_library_offline)
        else -> stringResource(com.slukhayka.audiobooks.R.string.a11y_library_online)
    }
    val sourceState = stringResource(
        com.slukhayka.audiobooks.R.string.a11y_library_source,
        book.sourceName
    )
    val state = listOf(progressState, availability, sourceState).joinToString(". ")
    val openLabel = stringResource(
        com.slukhayka.audiobooks.R.string.a11y_library_open_book,
        book.book.title
    )
    val performOpen = onClick
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
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
            LibraryBookGridContent(book)
        } else {
            LibraryBookRowContent(book)
        }
    }
}

@Composable
private fun LibraryBookRowContent(book: LibraryBook) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BookCoverImage(
            book = book.book,
            semantics = BookCoverSemantics.Decorative,
            modifier = Modifier
                .size(56.dp)
                .clip(MaterialTheme.shapes.medium),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.book.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (book.totalDurationSeconds > 0L) {
                    Text(
                        text = "Залишилось ${formatRemainingTime(book.remainingSeconds)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                SourceBadge(book)
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
private fun LibraryBookGridContent(book: LibraryBook) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (book.totalDurationSeconds > 0L) {
                    Text(
                        text = formatRemainingTime(book.remainingSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                SourceBadge(book)
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

/**
 * Small unobtrusive source badge: «Локальна» for local imports, else the
 * book's real source (4read, Sluhay, Sound-Books, …) — spec-15 T6, one badge
 * for the whole multi-source library.
 */
@Composable
private fun SourceBadge(book: LibraryBook) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(AppDimens.RadiusXs)
    ) {
        Text(
            text = book.sourceName,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
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
                                        Text("З'єднати")
                                    }
                                    TextButton(onClick = { onRejectMerge(book.id) }) {
                                        Text("Не це")
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
                Text("Скасувати")
            }
        }
    )
}
