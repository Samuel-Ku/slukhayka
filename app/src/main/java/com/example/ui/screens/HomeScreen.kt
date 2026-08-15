package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.catalog.CatalogBook
import com.example.data.catalog.CatalogSection
import com.example.data.catalog.CatalogSeries
import com.example.data.db.AudiobookEntity
import com.example.data.db.PlaybackProgressEntity
import com.example.ui.MainViewModel
import com.example.ui.theme.*

/**
 * Explore tab (UI/UX 2026): A curated, high-performance feed featuring:
 * - Smart Resume Session card with remaining time & gradient progress
 * - Shimmer skeleton state during catalogue synchronization
 * - Mood & Context filters (⚡ Short reads, 🔥 Top hits, Genres)
 * - Rich metadata badges (duration, rating, offline status)
 * - Tactile haptic feedback on actions
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onBookClick: (String) -> Unit,
    onPlayClick: (AudiobookEntity) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val allBooks by viewModel.allBooks.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedGenre by viewModel.selectedGenreFilter.collectAsState()
    val recentProgress by viewModel.recentProgress.collectAsState()
    val sections by viewModel.catalogSections.collectAsState()
    val isCatalogLoading by viewModel.isCatalogLoading.collectAsState()

    val filterChips = listOf(
        "Усі",
        "⚡ Короткі (< 2 год)",
        "🔥 Топ тижня",
        "Завантажені",
        "Фантастика",
        "Cyberpunk",
        "Детективи",
        "Класика",
        "Антиутопія"
    )

    val filteredBooks = allBooks.filter { book ->
        val matchesSearch = searchQuery.isBlank() ||
            book.title.contains(searchQuery, ignoreCase = true) ||
            book.author.contains(searchQuery, ignoreCase = true) ||
            book.narrator.contains(searchQuery, ignoreCase = true)

        val matchesGenre = when (selectedGenre) {
            "Усі", "All" -> true
            "Завантажені", "Downloaded" -> book.isDownloaded
            "⚡ Короткі (< 2 год)" -> book.totalDurationSeconds in 1..7200
            "🔥 Топ тижня" -> book.rating >= 4.8f || book.isFavorite
            "Фантастика" -> book.genre.contains("фантастика", ignoreCase = true) || book.genre.contains("sci-fi", ignoreCase = true)
            "Cyberpunk" -> book.genre.contains("cyberpunk", ignoreCase = true) || book.genre.contains("киберпанк", ignoreCase = true) || book.genre.contains("кіберпанк", ignoreCase = true)
            "Детективи" -> book.genre.contains("детектив", ignoreCase = true)
            "Класика" -> book.genre.contains("классика", ignoreCase = true) || book.genre.contains("класика", ignoreCase = true)
            "Антиутопія" -> book.genre.contains("антиутопия", ignoreCase = true) || book.genre.contains("антиутопія", ignoreCase = true)
            else -> book.genre.contains(selectedGenre, ignoreCase = true)
        }

        matchesSearch && matchesGenre
    }

    val inSearchMode = searchQuery.isNotBlank() || selectedGenre != "Усі"

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
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Brand Header with Refresh Action
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CyberPrimary.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CyberPrimary.copy(alpha = 0.5f)),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Headphones,
                                    contentDescription = null,
                                    tint = CyberPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "4Read Audio",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 21.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                color = CyberTextPrimary
                            )
                            Text(
                                text = "Українські аудіокниги",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberPrimary
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.refreshCatalog()
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(CyberSurfaceVariant)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Оновити",
                            tint = CyberPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Modern Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    placeholder = { 
                        Text(
                            "Пошук книги, автора чи диктора...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberTextSecondary.copy(alpha = 0.7f)
                        ) 
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = CyberPrimary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { 
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.updateSearchQuery("") 
                            }) {
                                Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", tint = CyberTextSecondary)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_search_input"),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CyberCardBg,
                        unfocusedContainerColor = CyberCardBg,
                        focusedBorderColor = CyberPrimary,
                        unfocusedBorderColor = CyberCardBorder.copy(alpha = 0.6f)
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Mood & Context Filter Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(filterChips) { filter ->
                        val isSelected = selectedGenre == filter
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.selectGenreFilter(filter)
                            },
                            label = { 
                                Text(
                                    text = filter,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                ) 
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyberPrimary,
                                selectedLabelColor = CyberOnPrimary,
                                containerColor = CyberCardBg,
                                labelColor = CyberTextPrimary
                            ),
                            shape = RoundedCornerShape(14.dp),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = CyberCardBorder.copy(alpha = 0.5f),
                                selectedBorderColor = CyberPrimary
                            )
                        )
                    }
                }
            }
        }

        if (inSearchMode) {
            // ---- Search / genre result list -------------------------------
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "Знайдено за запитом" else selectedGenre,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
                        color = CyberTextPrimary
                    )
                    Surface(
                        color = CyberPrimary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "${filteredBooks.size} книг",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = CyberPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            if (filteredBooks.isEmpty()) {
                item {
                    EmptyStateMessage("Нічого не знайдено за вашим фільтром.")
                }
            }
            items(filteredBooks, key = { it.id }) { book ->
                AudiobookListItem(
                    book = book,
                    onClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onBookClick(book.id) 
                    },
                    onPlayClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPlayClick(book) 
                    }
                )
            }
        } else {
            // ---- Netflix & Curated Feed -----------------------------------
            // Shimmer skeleton when catalog is loading on empty state
            if (isCatalogLoading && allBooks.isEmpty() && sections.isEmpty()) {
                item {
                    CatalogShimmerFeed()
                }
            }

            // Empty catalogue actionable fallback
            if (!isCatalogLoading && sections.isEmpty() && allBooks.isEmpty()) {
                item {
                    EmptyCatalogState(
                        onRefreshClick = { 
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.refreshCatalog() 
                        },
                        onImportClick = { viewModel.selectTab(com.example.ui.SelectedTab.LIBRARY) }
                    )
                }
            }

            // Smart Continue Listening Session Card
            if (recentProgress.isNotEmpty()) {
                val mostRecent = recentProgress.first()
                val recentBook = allBooks.find { it.id == mostRecent.bookId }
                if (recentBook != null) {
                    item {
                        SmartContinueListeningSection(
                            book = recentBook,
                            progress = mostRecent,
                            onBookClick = { 
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onBookClick(recentBook.id) 
                            },
                            onResumeClick = { 
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPlayClick(recentBook) 
                            }
                        )
                    }
                }
            }

            // Catalogue rows from 4read.org
            sections.forEach { section ->
                if (section.books.isNotEmpty()) {
                    item {
                        CatalogRowHeader(title = section.title)
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(section.books, key = { it.id }) { book ->
                                CatalogBookCard(
                                    book = book,
                                    onClick = { 
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onBookClick(book.id) 
                                    }
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
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(section.series, key = { it.url }) { series ->
                                CatalogSeriesCard(
                                    series = series,
                                    onClick = { 
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        viewModel.openSeries(series.title, series.url) 
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Full local library section
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ВСЯ БІБЛІОТЕКА",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = CyberTextPrimary
                    )
                    Surface(
                        color = CyberSurfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "${filteredBooks.size}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = CyberPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            if (filteredBooks.isEmpty() && !isCatalogLoading) {
                item {
                    EmptyStateMessage("Бібліотека порожня. Знайдіть книгу через пошук або додайте власний аудіофайл у Бібліотеці.")
                }
            }
            items(filteredBooks, key = { it.id }) { book ->
                AudiobookListItem(
                    book = book,
                    onClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onBookClick(book.id) 
                    },
                    onPlayClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPlayClick(book) 
                    }
                )
            }
        }
    }
}

/** Section heading for a modern feed row */
@Composable
fun CatalogRowHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(CyberPrimary)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                letterSpacing = 0.5.sp
            ),
            color = CyberTextPrimary
        )
    }
}

/**
 * 2026 Cover-first card for horizontal catalogue rows:
 * High quality rounded cover, duration/rating badges, clear typography.
 */
@Composable
fun CatalogBookCard(
    book: CatalogBook,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .testTag("catalog_book_${book.id}"),
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .width(130.dp)
                .height(182.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CyberCardBg)
        ) {
            CatalogCoverImage(
                coverImageUrl = book.coverImageUrl,
                title = book.title,
                modifier = Modifier.fillMaxSize()
            )

            // Top gradient overlay for contrast
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                        )
                    )
            )

            // Rating badge if available
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .padding(6.dp)
                    .align(Alignment.TopEnd)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = Color(0xFFFFC107),
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "4.9",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            ),
            color = CyberTextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Wide cover card for a series / cycle */
@Composable
fun CatalogSeriesCard(
    series: CatalogSeries,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .testTag("catalog_series_${series.url.hashCode()}"),
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .width(150.dp)
                .height(88.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CyberCardBg)
        ) {
            CatalogCoverImage(
                coverImageUrl = series.coverImageUrl,
                title = series.title,
                modifier = Modifier.fillMaxSize()
            )

            // Gradient banner overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                        )
                    )
            )

            Surface(
                color = CyberPrimary.copy(alpha = 0.85f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .padding(6.dp)
                    .align(Alignment.BottomStart)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CollectionsBookmark,
                        contentDescription = null,
                        tint = CyberOnPrimary,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "Цикл",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = CyberOnPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = series.title,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            ),
            color = CyberPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 15.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Remote-cover image with polished fallback */
@Composable
fun CatalogCoverImage(
    coverImageUrl: String?,
    title: String,
    modifier: Modifier = Modifier
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
        Box(
            modifier = modifier.background(
                brush = Brush.verticalGradient(
                    colors = listOf(CyberSurface, CyberCardBg, CyberPrimary.copy(alpha = 0.25f))
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = null,
                    tint = CyberPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = title,
                    color = CyberTextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** 2026 Smart Continue Listening Card with exact duration remaining and progress wave */
@Composable
fun SmartContinueListeningSection(
    book: AudiobookEntity,
    progress: PlaybackProgressEntity,
    onBookClick: () -> Unit,
    onResumeClick: () -> Unit
) {
    val totalSec = book.totalDurationSeconds
    val currentSec = progress.currentPositionSeconds
    val progressFraction = if (totalSec > 0) (currentSec.toFloat() / totalSec.toFloat()).coerceIn(0f, 1f) else 0.05f
    val remainingSec = (totalSec - currentSec).coerceAtLeast(0L)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, CyberPrimary.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .clickable { onBookClick() },
        colors = CardDefaults.cardColors(containerColor = CyberCardBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            CyberPrimary.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PlayCircleFilled,
                        contentDescription = null,
                        tint = CyberPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ПРОДОВЖИТИ СЛУХАННЯ",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        ),
                        color = CyberPrimary
                    )
                }

                if (remainingSec > 0) {
                    Text(
                        text = "Залишилось: ${formatDurationUk(remainingSec)}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = CyberTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.example.ui.components.BookCoverImage(
                    book = book,
                    contentDescription = book.title,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp)),
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
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Розділ ${progress.currentChapterIndex + 1} • ${MainViewModel.formatTime(currentSec)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberTextSecondary
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = onResumeClick,
                    modifier = Modifier
                        .size(44.dp)
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

            Spacer(modifier = Modifier.height(10.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = CyberPrimary,
                trackColor = CyberSurfaceVariant
            )
        }
    }
}

/** 2026 Enhanced Audiobook List Item with badges */
@Composable
fun AudiobookListItem(
    book: AudiobookEntity,
    onClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, CyberCardBorder.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
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
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                com.example.ui.components.BookCoverImage(
                    book = book,
                    contentDescription = book.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = CyberPrimary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = book.genre.ifBlank { "Аудіокнига" },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            color = CyberPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (book.isDownloaded) {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = "Downloaded",
                            tint = CyberSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

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
                    text = book.author.ifBlank { "Невідомий автор" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = CyberTextSecondary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = CyberTextSecondary.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (book.totalDurationSeconds > 0) formatDurationUk(book.totalDurationSeconds) else "${book.totalChapters} розд.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = CyberTextSecondary
                    )
                    if (book.narrator.isNotBlank()) {
                        Text(
                            text = " • Читає: ${book.narrator}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = CyberTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
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

/** Animated shimmer placeholder feed for first load */
@Composable
fun CatalogShimmerFeed() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_trans"
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            CyberSurfaceVariant.copy(alpha = 0.5f),
            CyberCardBorder.copy(alpha = 0.8f),
            CyberSurfaceVariant.copy(alpha = 0.5f)
        ),
        start = Offset(10f, 10f),
        end = Offset(translateAnim.value, translateAnim.value)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Section title placeholder
        Box(
            modifier = Modifier
                .width(140.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(shimmerBrush)
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Cards row placeholder
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Column(modifier = Modifier.width(120.dp)) {
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .height(170.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(shimmerBrush)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmerBrush)
                    )
                }
            }
        }
    }
}

/** First-run empty catalogue actionable state */
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
            color = CyberPrimary.copy(alpha = 0.12f),
            modifier = Modifier.size(68.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = CyberPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Знайдіть свою першу книгу",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
            color = CyberTextPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Каталог українських аудіокниг оновлюється. Оновіть сторінку або імпортуйте власний файл.",
            style = MaterialTheme.typography.bodyMedium,
            color = CyberTextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onRefreshClick,
                colors = ButtonDefaults.buttonColors(containerColor = CyberPrimary),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Оновити каталог", fontWeight = FontWeight.Bold, color = CyberOnPrimary)
            }
            OutlinedButton(
                onClick = onImportClick,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberCardBorder)
            ) {
                Icon(imageVector = Icons.Default.FileUpload, contentDescription = null, tint = CyberPrimary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Імпортувати файл", color = CyberTextPrimary)
            }
        }
    }
}

/** Formats duration into compact Ukrainian string: e.g. "2 год 15 хв" or "45 хв" */
fun formatDurationUk(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    return when {
        hrs > 0 && mins > 0 -> "${hrs} год ${mins} хв"
        hrs > 0 -> "${hrs} год"
        mins > 0 -> "${mins} хв"
        else -> "${seconds} с"
    }
}
