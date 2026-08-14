package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.AudiobookEntity
import com.example.data.db.BookmarkEntity
import com.example.ui.MainViewModel
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: MainViewModel,
    onBookClick: (String) -> Unit,
    onPlayClick: (AudiobookEntity) -> Unit
) {
    val downloadedBooks by viewModel.downloadedBooks.collectAsState()
    val favoriteBooks by viewModel.favoriteBooks.collectAsState()
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

    var activeTab by remember { mutableStateOf(0) } // 0 = Offline, 1 = Favorites, 2 = Bookmarks, 3 = Stats

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("library_screen")
    ) {
        // Top Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Моя Бібліотека",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp
                        ),
                        color = CyberTextPrimary
                    )
                    Text(
                        text = "Завантаження, обрані, закладки, статистика",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberTextSecondary
                    )
                }

                // Import a local audio file (spec #8 ticket T7).
                LocalAudioImportButton(
                    onClick = {
                        importLauncher.launch(arrayOf("audio/*", "application/ogg", "application/mpeg"))
                    }
                )
            }
        }

        // Tab Navigation Bar
        ScrollableTabRow(
            selectedTabIndex = activeTab,
            containerColor = CyberBg,
            contentColor = CyberPrimary,
            edgePadding = 16.dp,
            divider = { HorizontalDivider(color = CyberCardBorder) }
        ) {
            Tab(
                selected = activeTab == 0,
                onClick = { activeTab = 0 },
                text = { Text("Завантажені (${downloadedBooks.size})", fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = activeTab == 1,
                onClick = { activeTab = 1 },
                text = { Text("Обрані (${favoriteBooks.size})", fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = activeTab == 2,
                onClick = { activeTab = 2 },
                text = { Text("Закладки (${allBookmarks.size})", fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = activeTab == 3,
                onClick = { activeTab = 3 },
                text = { Text("Статистика", fontWeight = FontWeight.Bold) }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp, top = 12.dp)
        ) {
            when (activeTab) {
                0 -> {
                    // Offline Audio Cache & Storage Manager Card
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, CyberCardBorder, RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = CyberCardBg)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.SdCard,
                                            contentDescription = null,
                                            tint = CyberPrimary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Пам'ять пристрою: $cacheSizeFormatted",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = CyberTextPrimary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${downloadedBooks.size} аудіокниг збережено offline",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CyberTextSecondary
                                    )
                                }

                                Button(
                                    onClick = { viewModel.clearAllAudioCache() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteForever,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Очистити", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (downloadedBooks.isEmpty()) {
                        item {
                            EmptyStateMessage("Завантажені аудіокниги відсутні. Додайте їх для прослуховування без інтернету.")
                        }
                    } else {
                        items(downloadedBooks, key = { it.id }) { book ->
                            OfflineBookItem(
                                book = book,
                                onClick = { onBookClick(book.id) },
                                onPlayClick = { onPlayClick(book) },
                                onDeleteClick = { viewModel.removeOfflineDownload(book.id) }
                            )
                        }
                    }
                }

                1 -> {
                    if (favoriteBooks.isEmpty()) {
                        item {
                            EmptyStateMessage("У вас поки немає обраних книг. Натисніть ❤️ на сторінці книги, щоб додати.")
                        }
                    } else {
                        items(favoriteBooks, key = { it.id }) { book ->
                            AudiobookListItem(
                                book = book,
                                onClick = { onBookClick(book.id) },
                                onPlayClick = { onPlayClick(book) }
                            )
                        }
                    }
                }

                2 -> {
                    if (allBookmarks.isEmpty()) {
                        item {
                            EmptyStateMessage("Закладки відсутні. Додавайте закладки під час прослуховування плеєра.")
                        }
                    } else {
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

                3 -> {
                    item {
                        ListeningStatsCard(listeningStats = listeningStats, totalBooks = allBooks.size)
                    }
                }
            }
        }
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
        colors = ButtonDefaults.buttonColors(containerColor = CyberPrimary),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.testTag("import_audio_button")
    ) {
        Icon(
            imageVector = Icons.Default.FileUpload,
            contentDescription = null,
            tint = CyberOnPrimary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Додати аудіо",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = CyberOnPrimary
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
            color = CyberTextPrimary
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
                color = CyberPrimary,
                modifier = Modifier.weight(1f)
            )
            StatItemCard(
                title = "За тиждень",
                value = "$weekHours год",
                icon = Icons.Default.DateRange,
                color = CyberSecondary,
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
                color = Color(0xFFFF9800),
                modifier = Modifier.weight(1f)
            )
            StatItemCard(
                title = "Всього в каталозі",
                value = "$totalBooks книг",
                icon = Icons.AutoMirrored.Filled.MenuBook,
                color = Color(0xFF4CAF50),
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
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, CyberCardBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = CyberCardBg)
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
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                color = CyberTextPrimary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = CyberTextSecondary
            )
        }
    }
}

@Composable
fun OfflineBookItem(
    book: AudiobookEntity,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onClick() }
            // Test seam (GitHub issue #7 — emulator audio scenario): deterministic
            // compose-test selector for the offline-tab book row. Mirrors the
            // `book_item_<id>` convention already used by AudiobookListItem in
            // HomeScreen. Pure UI annotation; does not change runtime behaviour.
            .testTag("library_book_item_${book.id}")
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, CyberCardBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = CyberCardBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.example.ui.components.BookCoverImage(
                book = book,
                contentDescription = book.title,
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = CyberTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberTextSecondary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudDone,
                        contentDescription = null,
                        tint = CyberSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Офлайн доступно",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberSecondary
                    )
                }
            }

            IconButton(onClick = onPlayClick) {
                Icon(imageVector = Icons.Default.PlayCircleFilled, contentDescription = "Play", tint = CyberPrimary, modifier = Modifier.size(36.dp))
            }

            IconButton(onClick = onDeleteClick) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete offline cache", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun EmptyStateMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.BookmarkBorder,
                contentDescription = null,
                tint = CyberTextSecondary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextSecondary
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
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, CyberCardBorder, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = CyberCardBg)
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
                tint = CyberSecondary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bookTitle,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = CyberPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = bookmark.chapterTitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = CyberTextPrimary
                )
                Text(
                    text = "At ${MainViewModel.formatTime(bookmark.timestampSeconds)}: ${bookmark.note}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberTextSecondary
                )
            }

            IconButton(onClick = onJumpClick) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Jump to bookmark",
                    tint = CyberPrimary
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
