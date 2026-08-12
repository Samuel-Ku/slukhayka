package com.example.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.AudiobookEntity
import com.example.data.db.PlaybackProgressEntity
import com.example.ui.MainViewModel
import com.example.ui.components.AppSectionHeader
import com.example.ui.components.EmptyState
import com.example.ui.displayAuthor
import com.example.ui.theme.*

/**
 * Слухати tab (spec-9): the listening panel — the app's first screen. Built
 * only from data that already exists or comes straight from 4read: the hero
 * resume card, recently-listened, downloaded books and one or two "new on
 * 4read" rows. On a fresh install the tab shows a placeholder hero and two
 * CTAs instead of raw emptiness.
 *
 * The blocks are pure composables ([ListenHeroCard], [RecentlyListenedRow],
 * [ListenEmptyState]) so snapshot tests can render them without a
 * `MainViewModel`.
 */
@Composable
fun ListenScreen(
    viewModel: MainViewModel,
    onBookClick: (String) -> Unit,
    onPlayClick: (AudiobookEntity) -> Unit,
    onBrowseClick: () -> Unit,
    onImportClick: () -> Unit,
    onOpenWebSource: (() -> Unit)? = null
) {
    val allBooks by viewModel.allBooks.collectAsState()
    val downloadedBooks by viewModel.downloadedBooks.collectAsState()
    val recentProgress by viewModel.recentProgress.collectAsState()
    val sections by viewModel.catalogSections.collectAsState()
    val nextInSeries by viewModel.nextInSeries.collectAsState()
    // Spec-10 T5: per-source «Нове з кожного джерела» rows.
    val sourceFeeds by viewModel.sourceFeeds.collectAsState()
    val isFeedsLoading by viewModel.isFeedsLoading.collectAsState()

    // Load the per-source feeds once the Listen surface composes; the
    // repository's TTL cache makes re-compositions free, and a failing source
    // hides only its own row.
    LaunchedEffect(Unit) {
        viewModel.loadSourceFeeds()
    }

    val heroBook = recentProgress.firstOrNull()?.let { mostRecent ->
        allBooks.find { it.id == mostRecent.bookId }
    }

    // Refresh the "continue the series" suggestion whenever the hero book
    // changes (keyed on its id so position updates don't refetch).
    LaunchedEffect(heroBook?.id) {
        viewModel.loadNextInSeries(heroBook)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("listen_screen"),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        // Fresh install: placeholder hero + clear next actions.
        if (allBooks.isEmpty()) {
            item {
                ListenEmptyState(
                    onBrowseClick = onBrowseClick,
                    onImportClick = onImportClick
                )
            }
            return@LazyColumn
        }

        // Hero: Продовжити слухати.
        if (recentProgress.isNotEmpty()) {
            val mostRecent = recentProgress.first()
            val recentBook = allBooks.find { it.id == mostRecent.bookId }
            if (recentBook != null) {
                item {
                    ListenHeroCard(
                        book = recentBook,
                        progress = mostRecent,
                        onResumeClick = { onPlayClick(recentBook) },
                        onBookClick = { onBookClick(recentBook.id) }
                    )
                }
            }
        }

        // Continue-the-series (spec-9 T4): the next volume of the hero book's
        // cycle. Hidden when there is none or the lookup failed.
        val continueSeriesBook = heroBook?.let { hb -> nextInSeries?.takeIf { it.id != hb.id } }
        if (continueSeriesBook != null) {
            item { AppSectionHeader(title = "Продовжити серію") }
            item {
                ContinueSeriesRow(
                    seriesTitle = heroBook.seriesTitle.orEmpty(),
                    book = continueSeriesBook,
                    onClick = { onBookClick(continueSeriesBook.id) },
                    onPlayClick = { onPlayClick(continueSeriesBook) }
                )
            }
        }

        // Нещодавно слухали.
        val recentItems = recentProgress
            .take(6)
            .mapNotNull { p -> allBooks.find { it.id == p.bookId }?.let { it to p } }
        if (recentItems.isNotEmpty()) {
            item { AppSectionHeader(title = "Нещодавно слухали") }
            // Keys are prefixed per section: LazyColumn keys must be unique
            // across the WHOLE list, and the same book can legitimately appear
            // in both "Нещодавно слухали" and "Завантажено" (observed
            // on-device: duplicate key "..." crashed the Listen tab for any
            // downloaded-and-listened book).
            items(recentItems, key = { "recent-${it.first.id}" }) { (book, progress) ->
                RecentlyListenedRow(
                    book = book,
                    progress = progress,
                    onClick = { onBookClick(book.id) },
                    onPlayClick = { onPlayClick(book) }
                )
            }
        }

        // Завантажено.
        if (downloadedBooks.isNotEmpty()) {
            item { AppSectionHeader(title = "Завантажено") }
            items(downloadedBooks, key = { "downloaded-${it.id}" }) { book ->
                AudiobookListItem(
                    book = book,
                    onClick = { onBookClick(book.id) },
                    onPlayClick = { onPlayClick(book) }
                )
            }
        }

        // Нове на 4read: at most two rows (spec-9 — a couple, not ten).
        val newSections = sections.filter { it.books.isNotEmpty() }.take(2)
        newSections.forEach { section ->
            item { AppSectionHeader(title = section.title) }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(section.books, key = { it.id }) { book ->
                        CatalogBookCard(book = book, onClick = { onBookClick(book.id) })
                    }
                }
            }
        }

        // Spec-10 T5: «Нове з кожного джерела» — one row per verified source
        // (4read is excluded: its «Нове на 4read» rows above already carry its
        // new arrivals). A source that fails to load contributes no row.
        if (sourceFeeds.isNotEmpty()) {
            sourceFeeds.forEach { feed ->
                item {
                    SourceFeedRow(
                        feed = feed,
                        onBookClick = { book -> viewModel.playFromSource(feed.sourceId, book.url) },
                        // Spec-13 T4: a stale-session feed row (e.g. «Нове з
                        // Sluhay» without a live challenge) renders a CTA that
                        // opens the source's browser surface.
                        onOpenWebSource = onOpenWebSource
                    )
                }
            }
        } else if (isFeedsLoading) {
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

        // Spec-13 T3: the WebView-source browser entry point — a compact
        // "more books on Sluhay →" row (per #73 decisions, NOT a tab). Shown
        // only when the host surface provided the callback.
        if (onOpenWebSource != null) {
            item {
                OpenWebSourceRow(
                    displayName = "Sluhay",
                    onClick = onOpenWebSource
                )
            }
        }
    }
}

/**
 * Spec-13 T3 — compact «більше книг на Sluhay →» entry row to the source's
 * browser surface. One line, not a storefront: the WebView is a secondary
 * discovery surface, not a tab (#73).
 */
@Composable
fun OpenWebSourceRow(
    displayName: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
            .clickable { onClick() }
            .testTag("open_web_source_sluhay"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Більше книг на $displayName",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The next volume of the current book's cycle (spec-9 T4): a compact card
 * with the volume number and a one-tap play action.
 */
@Composable
fun ContinueSeriesRow(
    seriesTitle: String,
    book: AudiobookEntity,
    onClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(AppDimens.RadiusCardLg))
            .clickable { onClick() }
            .testTag("continue_series_${book.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.example.ui.components.BookCoverImage(
                book = book,
                contentDescription = book.title,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(AppDimens.RadiusInner)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (seriesTitle.isBlank()) "Наступна частина циклу" else "Наступна частина: $seriesTitle",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                book.seriesIndex?.let { index ->
                    if (index > 0) {
                        Text(
                            text = "Частина $index",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            IconButton(
                onClick = onPlayClick,
                modifier = Modifier
                    .size(AppDimens.TouchTarget)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * The big "Продовжити слухати" hero: cover, title, author, current chapter,
 * % progress and remaining time, a large resume button and a small action to
 * open the book page.
 */
@Composable
fun ListenHeroCard(
    book: AudiobookEntity,
    progress: PlaybackProgressEntity,
    onResumeClick: () -> Unit,
    onBookClick: () -> Unit
) {
    val totalSec = book.totalDurationSeconds
    val positionSec = progress.currentPositionSeconds.coerceAtLeast(0L)
    // Local imports have no duration metadata: percent/remaining would be
    // meaningless (0/1 -> 100%), so fall back to the bare position instead.
    val hasDuration = totalSec > 0
    val progressFraction = if (hasDuration) (positionSec.toFloat() / totalSec.toFloat()).coerceIn(0f, 1f) else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusHero)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "ПРОДОВЖИТИ СЛУХАТИ",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                com.example.ui.components.BookCoverImage(
                    book = book,
                    contentDescription = book.title,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(AppDimens.RadiusCardLg)),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = book.displayAuthor,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Розділ ${progress.currentChapterIndex + 1} із ${book.totalChapters.coerceAtLeast(1)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (hasDuration) {
                            "${(progressFraction * 100).toInt()}% · ${formatRemaining((totalSec - positionSec).coerceAtLeast(0L))}"
                        } else {
                            MainViewModel.formatTime(positionSec)
                        },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = onResumeClick,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        colors = IconButtonDefaults.iconButtonColors()
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Продовжити",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    IconButton(
                        onClick = onBookClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "До книги",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(AppDimens.RadiusProgress)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

/** One recently-listened book with its resume progress. */
@Composable
fun RecentlyListenedRow(
    book: AudiobookEntity,
    progress: PlaybackProgressEntity,
    onClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val totalSec = book.totalDurationSeconds
    val positionSec = progress.currentPositionSeconds.coerceAtLeast(0L)
    // Duration-less local imports: keep the bar empty instead of a full bar.
    val hasDuration = totalSec > 0
    val progressFraction = if (hasDuration) (positionSec.toFloat() / totalSec.toFloat()).coerceIn(0f, 1f) else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
            .clickable { onClick() }
            .testTag("recently_listened_${book.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.example.ui.components.BookCoverImage(
                    book = book,
                    contentDescription = book.title,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(AppDimens.RadiusInner)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Розділ ${progress.currentChapterIndex + 1} · ${MainViewModel.formatTime(positionSec)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onPlayClick,
                    modifier = Modifier
                        .size(AppDimens.TouchTarget)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(AppDimens.RadiusProgress)),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

/**
 * Fresh-install state: a placeholder hero card ("your current book will appear
 * here") plus two CTAs — browse the catalogue or import your own files.
 */
@Composable
fun ListenEmptyState(
    onBrowseClick: () -> Unit,
    onImportClick: () -> Unit
) {
    EmptyState(
        icon = Icons.Default.PlayCircle,
        title = "Продовжити слухати",
        body = "Тут з'явиться ваша поточна книга, щойно ви почнете слухати."
    ) {
        Button(
            onClick = onBrowseClick,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(AppDimens.RadiusCard),
            modifier = Modifier
                .fillMaxWidth()
                .height(AppDimens.TouchTarget)
        ) {
            Icon(imageVector = Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Переглянути каталог", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onImportClick,
            shape = RoundedCornerShape(AppDimens.RadiusCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .height(AppDimens.TouchTarget)
        ) {
            Icon(
                imageVector = Icons.Default.FileUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Імпортувати з пристрою", color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** "Залишилося X год Y хв" remaining-time label for the hero card. */
internal fun formatRemaining(totalSeconds: Long): String {
    val hrs = totalSeconds / 3600
    val mins = (totalSeconds % 3600) / 60
    return when {
        hrs > 0 && mins > 0 -> "Залишилося $hrs год $mins хв"
        hrs > 0 -> "Залишилося $hrs год"
        mins > 0 -> "Залишилося $mins хв"
        else -> "Залишилося менше хвилини"
    }
}
