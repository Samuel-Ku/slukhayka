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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import com.example.ui.MainViewModel
import com.example.ui.components.EmptyState
import com.example.ui.displayAuthor
import com.example.ui.theme.*

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
    onBookClick: (String) -> Unit,
    onPlayClick: (AudiobookEntity) -> Unit
) {
    val allBooks by viewModel.allBooks.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedGenre by viewModel.selectedGenreFilter.collectAsState()
    val sections by viewModel.catalogSections.collectAsState()
    val isCatalogLoading by viewModel.isCatalogLoading.collectAsState()
    val catalogGenres by viewModel.catalogGenres.collectAsState()
    // Spec-10 T4: aggregated global search across all verified sources.
    val globalResults by viewModel.globalSearchResults.collectAsState()
    val isGlobalSearchLoading by viewModel.isGlobalSearchLoading.collectAsState()
    // Spec-15 T1: the deduplicated «Увесь каталог» union (all sources, one
    // card per Work with a badge per carried source).
    val unifiedCatalog by viewModel.unifiedCatalog.collectAsState()
    val isUnifiedCatalogLoading by viewModel.isUnifiedCatalogLoading.collectAsState()

    // Spec-15 T1: enumerate the union once per Огляд composition; the
    // repository caches it for the session (and re-fetches session-bound
    // sources on every refresh).
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.loadUnifiedCatalog() }

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

    // Search/genre mode: a plain result list, no rows.
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
                            text = "4Read Audio",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(AppDimens.RadiusHero),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Українські аудіокниги",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    placeholder = { Text("Пошук книги або автора...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.primary
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
                            )
                        )
                    }
                }
            }
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
                        onRefreshClick = { viewModel.refreshCatalog() },
                        onImportClick = { viewModel.selectTab(com.example.ui.SelectedTab.LIBRARY) }
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
                                viewModel.openPeople(com.example.ui.PeopleKind("Виконавці", "https://4read.org/readers.html"))
                            }
                        )
                    }
                    item {
                        GenreChip(
                            title = "Автори",
                            onClick = {
                                viewModel.openPeople(com.example.ui.PeopleKind("Автори", "https://4read.org/avtors.html"))
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

            // Spec-15 T1: the deduplicated «Увесь каталог» union — every
            // source's catalogue in one Netflix-style row, one card per Work
            // with a badge per carried source. Tapping a card imports from the
            // found source and plays (playFromSource); ephemeral, cached for
            // the session.
            if (unifiedCatalog.isNotEmpty()) {
                item {
                    CatalogRowHeader(title = "Увесь каталог")
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(unifiedCatalog, key = { it.key }) { result ->
                            UnifiedCatalogCard(
                                result = result,
                                onClick = { viewModel.playGlobalSearchResult(result) }
                            )
                        }
                    }
                }
            } else if (isUnifiedCatalogLoading) {
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

/** Remote-cover image with the same dark typographic fallback as BookCoverImage. */
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
                    colors = listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
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
            com.example.ui.components.BookCoverImage(
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

            IconButton(
                onClick = onPlayClick,
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
