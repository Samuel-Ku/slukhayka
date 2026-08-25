package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.universe.SeriesRef
import com.slukhayka.audiobooks.data.universe.SeriesUniverseContext
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.IndexScreenScaffold
import com.slukhayka.audiobooks.ui.components.SecondaryLoadingState
import com.slukhayka.audiobooks.ui.components.SecondaryMessageState
import com.slukhayka.audiobooks.ui.library.ukPlural
import com.slukhayka.audiobooks.ui.theme.*

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
    val loadFailed by viewModel.seriesLoadFailed.collectAsState()
    // Spec-25 (#171): the universe of the opened series (header block).
    val seriesUniverse by viewModel.selectedSeriesUniverse.collectAsState()

    val currentSeries = series ?: return

    IndexScreenScaffold(title = currentSeries.title, onBackClick = onBackClick) { padding ->
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
                        SecondaryLoadingState(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp)
                        )
                    }
                }

                loadFailed -> {
                    item {
                        SecondaryMessageState(
                            message = stringResource(R.string.secondary_series_error),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            isError = true
                        )
                    }
                }

                books.isEmpty() -> {
                    item {
                        SecondaryMessageState(
                            message = stringResource(R.string.secondary_series_empty),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp)
                        )
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
                        // Spec-27 (#186) BUG-005/006: the count is honest (the
                        // series page really has N books) and pluralized
                        // correctly — «1 книга у циклі», «2 книги», «5 книг».
                        Text(
                            text = "${books.size} ${ukPlural(books.size, "книга", "книги", "книг")} у циклі",
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
@OptIn(ExperimentalLayoutApi::class)
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
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() }
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                context.precedes?.let { ref ->
                    AssistChip(
                        onClick = { onOpenSeries(ref) },
                        label = { Text("Передує: «${ref.title}»") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
                context.follows?.let { ref ->
                    AssistChip(
                        onClick = { onOpenSeries(ref) },
                        label = { Text("Продовжує: «${ref.title}»") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
        }
    }
}
