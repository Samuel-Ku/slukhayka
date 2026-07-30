package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.AudiobookEntity
import com.example.data.db.BookmarkEntity
import com.example.data.db.ChapterEntity
import com.example.ui.MainViewModel
import com.example.ui.components.BookmarkDialog
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit
) {
    val book by viewModel.selectedBook.collectAsState()
    val chapters by viewModel.selectedBookChapters.collectAsState()
    val bookmarks by viewModel.selectedBookBookmarks.collectAsState()
    val playerState by viewModel.playerState.collectAsState()

    var activeTab by remember { mutableStateOf(0) } // 0 = Chapters, 1 = Bookmarks
    var showAddBookmarkDialog by remember { mutableStateOf(false) }

    val currentBook = book ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentBook.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (currentBook.isDownloaded) {
                            viewModel.removeOfflineDownload(currentBook.id)
                        } else {
                            viewModel.downloadBookOffline(currentBook.id)
                        }
                    }) {
                        Icon(
                            imageVector = if (currentBook.isDownloaded) Icons.Default.CloudDone else Icons.Default.CloudDownload,
                            contentDescription = "Offline Download",
                            tint = if (currentBook.isDownloaded) CyberSecondary else CyberTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CyberBg)
            )
        },
        containerColor = CyberBg
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("book_detail_screen"),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            // Book Header Section
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Card(
                        modifier = Modifier
                            .width(180.dp)
                            .height(240.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.dp, CyberPrimary.copy(alpha = 0.4f), RoundedCornerShape(20.dp)),
                        elevation = CardDefaults.cardElevation(8.dp)
                    ) {
                        com.example.ui.components.BookCoverImage(
                            book = currentBook,
                            contentDescription = currentBook.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = currentBook.title,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        ),
                        color = CyberTextPrimary,
                        modifier = Modifier.testTag("book_detail_title")
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "By ${currentBook.author}",
                        style = MaterialTheme.typography.titleMedium,
                        color = CyberPrimary
                    )

                    Text(
                        text = "Narrated by ${currentBook.narrator}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Tags row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = CyberCardBg,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CyberCardBorder)
                        ) {
                            Text(
                                text = currentBook.genre,
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberTextPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        Surface(
                            color = CyberCardBg,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CyberCardBorder)
                        ) {
                            Text(
                                text = "${currentBook.totalChapters} Ch. • ${MainViewModel.formatTime(currentBook.totalDurationSeconds)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberTextSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        Surface(
                            color = CyberSecondary.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "4read.org Source",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Description
                    Text(
                        text = currentBook.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.playAudiobook(currentBook)
                                viewModel.setShowFullPlayer(true)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberPrimary),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(50.dp)
                                .testTag("play_book_button")
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = CyberOnPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (playerState.currentBook?.id == currentBook.id && playerState.isPlaying) "Playing" else "Play",
                                fontWeight = FontWeight.Bold,
                                color = CyberOnPrimary
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                if (currentBook.isDownloaded) {
                                    viewModel.removeOfflineDownload(currentBook.id)
                                } else {
                                    viewModel.downloadBookOffline(currentBook.id)
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (currentBook.isDownloaded) CyberSecondary else CyberCardBorder
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (currentBook.isDownloaded) CyberSecondary else CyberTextPrimary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("download_offline_button")
                        ) {
                            Icon(
                                imageVector = if (currentBook.isDownloaded) Icons.Default.CloudDone else Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (currentBook.isDownloaded) "Offline" else "Download",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        OutlinedButton(
                            onClick = { showAddBookmarkDialog = true },
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CyberCardBorder),
                            modifier = Modifier
                                .height(50.dp)
                                .testTag("bookmark_button")
                        ) {
                            Icon(imageVector = Icons.Default.BookmarkAdd, contentDescription = null, tint = CyberPrimary)
                        }
                    }
                }
            }

            // Tab row (Chapters vs Bookmarks)
            item {
                TabRow(
                    selectedTabIndex = activeTab,
                    containerColor = CyberBg,
                    contentColor = CyberPrimary,
                    divider = { HorizontalDivider(color = CyberCardBorder) }
                ) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        text = { Text("Chapters (${chapters.size})", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        text = { Text("Bookmarks (${bookmarks.size})", fontWeight = FontWeight.Bold) }
                    )
                }
            }

            // Chapter list
            if (activeTab == 0) {
                itemsIndexed(chapters) { index, chapter ->
                    val isPlayingThis = playerState.currentBook?.id == currentBook.id &&
                            playerState.currentChapterIndex == index

                    ChapterRowItem(
                        chapter = chapter,
                        index = index,
                        isPlaying = isPlayingThis,
                        onPlayClick = {
                            viewModel.playAudiobook(currentBook, chapterIndex = index)
                            viewModel.setShowFullPlayer(true)
                        }
                    )
                }
            } else {
                // Bookmarks list
                if (bookmarks.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No bookmarks added yet for this book.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CyberTextSecondary
                            )
                        }
                    }
                } else {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        BookmarkRowItem(
                            bookmark = bookmark,
                            onJumpClick = { viewModel.jumpToBookmark(bookmark) },
                            onDeleteClick = { viewModel.deleteBookmark(bookmark.id) }
                        )
                    }
                }
            }
        }
    }

    if (showAddBookmarkDialog) {
        val currentChapterTitle = if (chapters.isNotEmpty() && playerState.currentChapterIndex in chapters.indices) {
            chapters[playerState.currentChapterIndex].title
        } else "Chapter 1"

        BookmarkDialog(
            timestampSeconds = playerState.currentPositionMs / 1000L,
            chapterTitle = currentChapterTitle,
            onDismiss = { showAddBookmarkDialog = false },
            onSave = { note ->
                viewModel.addBookmarkAtCurrentPosition(note)
            }
        )
    }
}

@Composable
fun ChapterRowItem(
    chapter: ChapterEntity,
    index: Int,
    isPlaying: Boolean,
    onPlayClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (isPlaying) CyberPrimary else CyberCardBorder,
                RoundedCornerShape(12.dp)
            )
            .clickable { onPlayClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) CyberPrimary.copy(alpha = 0.1f) else CyberCardBg
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isPlaying) CyberPrimary else CyberSurfaceVariant,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isPlaying) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = CyberOnPrimary, modifier = Modifier.size(20.dp))
                    } else {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = CyberTextPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 15.sp
                    ),
                    color = if (isPlaying) CyberPrimary else CyberTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Duration: ${MainViewModel.formatTime(chapter.durationSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberTextSecondary
                )
            }

            IconButton(onClick = onPlayClick) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                    contentDescription = "Play Chapter",
                    tint = CyberPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
fun BookmarkRowItem(
    bookmark: BookmarkEntity,
    onJumpClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, CyberCardBorder, RoundedCornerShape(12.dp)),
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
                    text = bookmark.chapterTitle,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = CyberTextPrimary
                )
                Text(
                    text = "At ${MainViewModel.formatTime(bookmark.timestampSeconds)}: ${bookmark.note}",
                    style = MaterialTheme.typography.bodyMedium,
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
