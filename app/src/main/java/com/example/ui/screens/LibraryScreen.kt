package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.AudiobookEntity
import com.example.data.db.BookmarkEntity
import com.example.ui.MainViewModel
import com.example.ui.components.BookCoverImage
import com.example.ui.components.EmptyState
import com.example.ui.displayAuthor
import com.example.ui.library.LibraryBook
import com.example.ui.library.LibraryFilter
import com.example.ui.library.LibrarySort
import com.example.ui.library.filterAndSortLibrary
import com.example.ui.library.formatRemainingTime
import com.example.ui.theme.*

/**
 * Wayfinder #39 — Медіатека as one unified library. Local files and 4read
 * books live side by side; quick filters (Усі · Слухаю · Завершені ·
 * Завантажені · Локальні · 4read · Обрані), six sort modes, client-side
 * search and a grid/list toggle sit above a single book card that always
 * shows author, series+volume, progress, remaining time, download status and
 * a small source badge. Закладки and Статистика remain as sub-tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: MainViewModel,
    onBookClick: (String) -> Unit,
    onPlayClick: (AudiobookEntity) -> Unit,
    onBrowseClick: () -> Unit
) {
    val libraryBooks by viewModel.libraryBooks.collectAsState()
    val allBookmarks by viewModel.allBookmarks.collectAsState()
    val allBooks by viewModel.allBooks.collectAsState()
    val listeningStats by viewModel.listeningStats.collectAsState()
    val cacheSizeFormatted by viewModel.cacheSizeFormatted.collectAsState()

    // Spec #8 ticket T7: system file picker (SAF) → one picked audio file = one book.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importLocalAudioFile(uri)
    }

    // Spec #8 Block 4: SAF tree picker → recursively import every audio file
    // in the picked folder (files grouped into books by their sub-folder).
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.importLocalAudioFolder(uri)
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

    var activeTab by remember { mutableStateOf(0) } // 0 = Книги, 1 = Закладки, 2 = Статистика
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var sort by remember { mutableStateOf(LibrarySort.RECENTLY_LISTENED) }
    var query by remember { mutableStateOf("") }
    var gridMode by remember { mutableStateOf(false) }

    val visibleBooks = remember(libraryBooks, filter, sort, query) {
        filterAndSortLibrary(libraryBooks, filter, sort, query)
    }
    val offlineCount = remember(libraryBooks) { libraryBooks.count { it.book.isDownloaded } }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("library_screen")
        ) {
            // Top Header — the title gets its own full-width line so the
            // import buttons can never squeeze «Медіатека» into a wrap.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Медіатека",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Локальні книги та 4read — в одному місці",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))

                    // Import a local audio file / folder (spec #8 T7 + Block 4).
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LocalAudioImportButton(
                            onClick = {
                                importLauncher.launch(arrayOf("audio/*", "application/ogg", "application/mpeg"))
                            }
                        )
                        LocalFolderImportButton(
                            onClick = { folderLauncher.launch(null) }
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
                    placeholder = { Text("Пошук у медіатеці…") },
                    leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(imageVector = Icons.Default.Clear, contentDescription = "Очистити пошук")
                            }
                        }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(AppDimens.RadiusCard)
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(LibraryFilter.entries) { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f.label) },
                            modifier = Modifier.testTag("library_filter_${f.name.lowercase()}")
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var sortMenu by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = { sortMenu = true },
                        modifier = Modifier.testTag("library_sort_button")
                    ) {
                        Icon(imageVector = Icons.Default.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(sort.label)
                        Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        LibrarySort.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label) },
                                onClick = {
                                    sort = s
                                    sortMenu = false
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { gridMode = !gridMode },
                        modifier = Modifier.testTag("library_view_toggle")
                    ) {
                        Icon(
                            imageVector = if (gridMode) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = if (gridMode) "Показати списком" else "Показати сіткою"
                        )
                    }
                }

                // Compact storage row (cache size + clear), kept slim per the
                // design system: no nested card, just an icon-and-text row.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SdCard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Пам'ять пристрою: $cacheSizeFormatted · $offlineCount аудіокниг offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { viewModel.clearAllAudioCache() },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            text = "Очистити",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            when (activeTab) {
                0 -> {
                    when {
                        libraryBooks.isEmpty() -> {
                            EmptyState(
                                icon = Icons.Default.MenuBook,
                                title = "Медіатека порожня",
                                body = "Додайте власні аудіокниги з пристрою або знайдіть нові на 4read."
                            ) {
                                Button(
                                    onClick = { importLauncher.launch(arrayOf("audio/*", "application/ogg", "application/mpeg")) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    shape = RoundedCornerShape(AppDimens.RadiusCard),
                                    modifier = Modifier.height(48.dp).testTag("library_empty_import")
                                ) {
                                    Text("Додати свої файли")
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = onBrowseClick,
                                    shape = RoundedCornerShape(AppDimens.RadiusCard),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Text("Знайти книгу")
                                }
                            }
                        }

                        visibleBooks.isEmpty() -> EmptyState(
                            icon = Icons.Default.Search,
                            title = "Нічого не знайдено",
                            body = "Спробуйте інший фільтр або змініть пошуковий запит."
                        )

                        else -> LazyVerticalGrid(
                            columns = if (gridMode) GridCells.Fixed(2) else GridCells.Fixed(1),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(visibleBooks, key = { it.book.id }) { entry ->
                                LibraryBookCard(
                                    book = entry,
                                    grid = gridMode,
                                    onClick = { onBookClick(entry.book.id) }
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
                                    onDeleteClick = { viewModel.deleteBookmark(bookmark.id) }
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
        )
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
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag("library_book_item_${book.book.id}"),
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
            contentDescription = book.book.title,
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
                    .clip(RoundedCornerShape(AppDimens.RadiusProgress)),
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
                        contentDescription = "Завантажено",
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
            contentDescription = book.book.title,
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
                    .clip(RoundedCornerShape(AppDimens.RadiusProgress)),
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
                        contentDescription = "Завантажено",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/** Small unobtrusive source badge: «Локальна» or «4read». */
@Composable
private fun SourceBadge(book: LibraryBook) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(AppDimens.RadiusXs)
    ) {
        Text(
            text = if (book.isLocal) "Локальна" else "4read",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * The "Додати аудіо" button that opens the SAF file picker (spec #8 T7).
 * Extracted so the import affordance is unit-testable without a ViewModel.
 */
@Composable
fun LocalAudioImportButton(
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        shape = RoundedCornerShape(AppDimens.RadiusCardLg),
        modifier = Modifier.testTag("import_audio_button")
    ) {
        Icon(
            imageVector = Icons.Default.FileUpload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Додати аудіо",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/**
 * The "Папку" button that opens the SAF tree picker (spec #8 Block 4).
 * Extracted so the import affordance is unit-testable without a ViewModel.
 */
@Composable
fun LocalFolderImportButton(
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(AppDimens.RadiusCardLg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.testTag("import_folder_button")
    ) {
        Icon(
            imageVector = Icons.Default.CreateNewFolder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Папку",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun ListeningStatsCard(listeningStats: List<com.example.data.db.ListeningStatEntity>, totalBooks: Int) {
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
                    text = "At ${MainViewModel.formatTime(bookmark.timestampSeconds)}: ${bookmark.note}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onJumpClick) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Jump to bookmark",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete bookmark",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
