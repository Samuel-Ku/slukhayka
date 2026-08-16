package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.universe.SeriesRef
import com.example.data.universe.SeriesUniverseContext
import com.example.ui.MainViewModel
import com.example.ui.theme.*

/**
 * Full-screen list of every book in a 4read.org series (cycle) — spec #8
 * ticket T8. Opened from the "Цикли" row of the Explore screen; the book list
 * is fetched (and upserted) from the series page, then rendered as the
 * standard [AudiobookListItem] rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit
) {
    val series by viewModel.selectedSeries.collectAsState()
    val books by viewModel.seriesBooks.collectAsState()
    val isLoading by viewModel.isSeriesLoading.collectAsState()
    // Spec-25 (#171): the universe of the opened series (header block).
    val seriesUniverse by viewModel.selectedSeriesUniverse.collectAsState()

    val currentSeries = series ?: return

    Scaffold(
        topBar = {
            // Host Scaffold in MainActivity already consumed the status bar
            // (innerPadding.top); don't let this inner TopAppBar add it again.
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = currentSeries.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("series_screen"),
            contentPadding = PaddingValues(bottom = 120.dp, top = 8.dp)
        ) {
            when {
                isLoading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                books.isEmpty() -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.MenuBook,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Не вдалося завантажити книги циклу. Перевірте з'єднання.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                else -> {
                    // Spec-25 (#171): the universe block — name, position in
                    // the universe, tappable precedes/follows chips. Absent
                    // for an unseeded series (silent).
                    seriesUniverse?.let { universe ->
                        item {
                            SeriesUniverseHeader(
                                context = universe,
                                onOpenSeries = { ref ->
                                    viewModel.openSeries(ref.title, ref.url.orEmpty())
                                }
                            )
                        }
                    }
                    item {
                        Text(
                            text = "${books.size} книг у циклі",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    items(books, key = { it.id }) { book ->
                        AudiobookListItem(
                            book = book,
                            onClick = { onBookClick(book.id) },
                            onPlayClick = {
                                viewModel.playAudiobook(book)
                                viewModel.setShowFullPlayer(true)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Spec-25 (#171) — the series screen's universe header: the universe name,
 * the series' position inside it and the tappable «Передує: …» /
 * «Продовжує: …» chips that jump between related series in reading order.
 * Public (not private) so the snapshot seam can pin the block with fixture
 * data; renders nothing itself for a single-series universe beyond the name.
 */
@Composable
fun SeriesUniverseHeader(
    context: SeriesUniverseContext,
    onOpenSeries: (SeriesRef) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("series_universe_header")
    ) {
        Text(
            text = "Всесвіт: «${context.universeName}»",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary
        )
        if (context.totalInUniverse > 1) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Цикл ${context.position} з ${context.totalInUniverse}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (context.precedes != null || context.follows != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                context.precedes?.let { ref ->
                    AssistChip(
                        onClick = { onOpenSeries(ref) },
                        label = { Text("Передує: «${ref.title}»") }
                    )
                }
                context.follows?.let { ref ->
                    AssistChip(
                        onClick = { onOpenSeries(ref) },
                        label = { Text("Продовжує: «${ref.title}»") }
                    )
                }
            }
        }
    }
}
