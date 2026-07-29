package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.example.data.db.PlaybackProgressEntity
import com.example.ui.MainViewModel
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onBookClick: (String) -> Unit,
    onPlayClick: (AudiobookEntity) -> Unit
) {
    val allBooks by viewModel.allBooks.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedGenre by viewModel.selectedGenreFilter.collectAsState()
    val recentProgress by viewModel.recentProgress.collectAsState()

    val featuredBook = allBooks.find { it.id == "2172-ybson-vylyam-neyromant" } ?: allBooks.firstOrNull()
    val genres = listOf("Усі", "Фантастика", "Cyberpunk", "Детективи", "Класика", "Антиутопія", "Завантажені")

    val filteredBooks = allBooks.filter { book ->
        val matchesSearch = searchQuery.isBlank() ||
                book.title.contains(searchQuery, ignoreCase = true) ||
                book.author.contains(searchQuery, ignoreCase = true)

        val matchesGenre = when (selectedGenre) {
            "Усі", "All" -> true
            "Завантажені", "Downloaded" -> book.isDownloaded
            "Фантастика" -> book.genre.contains("фантастика", ignoreCase = true) || book.genre.contains("sci-fi", ignoreCase = true)
            "Cyberpunk" -> book.genre.contains("cyberpunk", ignoreCase = true) || book.genre.contains("киберпанк", ignoreCase = true) || book.genre.contains("кіберпанк", ignoreCase = true)
            "Детективи" -> book.genre.contains("детектив", ignoreCase = true)
            "Класика" -> book.genre.contains("классика", ignoreCase = true) || book.genre.contains("класика", ignoreCase = true)
            "Антиутопія" -> book.genre.contains("антиутопия", ignoreCase = true) || book.genre.contains("антиутопія", ignoreCase = true)
            else -> book.genre.contains(selectedGenre, ignoreCase = true)
        }

        matchesSearch && matchesGenre
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_screen"),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        // Header & Search
        item {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = CyberPrimary,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Headphones,
                                    contentDescription = null,
                                    tint = CyberOnPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "4Read Audio",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 22.sp,
                                letterSpacing = 1.sp
                            ),
                            color = CyberTextPrimary
                        )
                    }

                    Surface(
                        color = CyberPrimary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberPrimary.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(CyberPrimary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "4read.org Online",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    placeholder = { Text("Search title, author, or Neuromancer...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = CyberPrimary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_search_input"),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CyberCardBg,
                        unfocusedContainerColor = CyberCardBg,
                        focusedBorderColor = CyberPrimary,
                        unfocusedBorderColor = CyberCardBorder
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Genre Filter Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(genres) { genre ->
                        val isSelected = selectedGenre == genre
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.selectGenreFilter(genre) },
                            label = { Text(genre) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyberPrimary,
                                selectedLabelColor = CyberOnPrimary,
                                containerColor = CyberCardBg,
                                labelColor = CyberTextPrimary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = CyberCardBorder,
                                selectedBorderColor = CyberPrimary
                            )
                        )
                    }
                }
            }
        }

        // Featured Hero Section
        if (featuredBook != null && searchQuery.isBlank() && (selectedGenre == "Усі" || selectedGenre == "All")) {
            item {
                FeaturedHeroCard(
                    book = featuredBook,
                    onBookClick = { onBookClick(featuredBook.id) },
                    onPlayClick = { onPlayClick(featuredBook) },
                    onDownloadClick = {
                        if (featuredBook.isDownloaded) {
                            viewModel.removeOfflineDownload(featuredBook.id)
                        } else {
                            viewModel.downloadBookOffline(featuredBook.id)
                        }
                    }
                )
            }
        }

        // Continue Listening Section
        if (recentProgress.isNotEmpty() && searchQuery.isBlank()) {
            val mostRecent = recentProgress.first()
            val recentBook = allBooks.find { it.id == mostRecent.bookId }
            if (recentBook != null) {
                item {
                    ContinueListeningSection(
                        book = recentBook,
                        progress = mostRecent,
                        onBookClick = { onBookClick(recentBook.id) },
                        onResumeClick = { onPlayClick(recentBook) }
                    )
                }
            }
        }

        // Section Title
        item {
            PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            Text(
                text = "Audiobook Catalog (${filteredBooks.size})",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                ),
                color = CyberTextPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Catalog Book Cards
        items(filteredBooks, key = { it.id }) { book ->
            AudiobookListItem(
                book = book,
                onClick = { onBookClick(book.id) },
                onPlayClick = { onPlayClick(book) }
            )
        }
    }
}

@Composable
fun FeaturedHeroCard(
    book: AudiobookEntity,
    onBookClick: () -> Unit,
    onPlayClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, CyberPrimary.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            .clickable { onBookClick() }
            .testTag("featured_hero_card"),
        colors = CardDefaults.cardColors(containerColor = CyberCardBg)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        ) {
            com.example.ui.components.BookCoverImage(
                book = book,
                contentDescription = book.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // Dark gradient overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                CyberBg.copy(alpha = 0.7f),
                                CyberBg
                            )
                        )
                    )
            )

            // Badges at Top Right & Left
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = CyberPrimary,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "★ FEATURED",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = CyberOnPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    color = CyberSecondary,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Rating: ${book.rating} ★",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = CyberOnSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Bottom Info & Controls
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "${book.author} • Narrated by ${book.narrator}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = onPlayClick,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberPrimary),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("featured_listen_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = CyberOnPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Listen Now",
                            fontWeight = FontWeight.Bold,
                            color = CyberOnPrimary
                        )
                    }

                    OutlinedButton(
                        onClick = onDownloadClick,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (book.isDownloaded) CyberSecondary else CyberCardBorder
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (book.isDownloaded) CyberSecondary else CyberTextPrimary
                        ),
                        modifier = Modifier.testTag("featured_download_button")
                    ) {
                        Icon(
                            imageVector = if (book.isDownloaded) Icons.Default.CloudDone else Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (book.isDownloaded) "Offline Ready" else "Download",
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ContinueListeningSection(
    book: AudiobookEntity,
    progress: PlaybackProgressEntity,
    onBookClick: () -> Unit,
    onResumeClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, CyberCardBorder, RoundedCornerShape(18.dp))
            .clickable { onBookClick() },
        colors = CardDefaults.cardColors(containerColor = CyberCardBg)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = CyberSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "CONTINUE LISTENING",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = CyberSecondary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                com.example.ui.components.BookCoverImage(
                    book = book,
                    contentDescription = book.title,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = CyberTextPrimary
                    )
                    Text(
                        text = "Chapter ${progress.currentChapterIndex + 1} • ${MainViewModel.formatTime(progress.currentPositionSeconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberTextSecondary
                    )
                }

                IconButton(
                    onClick = onResumeClick,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(CyberPrimary)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Resume",
                        tint = CyberOnPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }
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
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, CyberCardBorder, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .testTag("book_item_${book.id}"),
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
                    .size(68.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = book.genre,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = CyberPrimary
                    )
                    if (book.isDownloaded) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = "Downloaded",
                            tint = CyberSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = CyberTextPrimary
                )

                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = CyberTextSecondary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "${book.totalChapters} Chapters • ${MainViewModel.formatTime(book.totalDurationSeconds)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = CyberTextSecondary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onPlayClick,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(CyberSurfaceVariant)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = CyberPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
