package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.BookRow
import com.slukhayka.audiobooks.ui.components.IndexScreenScaffold
import com.slukhayka.audiobooks.ui.components.SecondaryLoadingState
import com.slukhayka.audiobooks.ui.components.SecondaryMessageState
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.theme.*

/**
 * #738 / ADR-0041 — the library rating replaces the source's ТОП-100 chart:
 * a ranked list of the listener's OWN Works, ordered by the honest combined
 * average (ADR-0022) over source ratings and listener ratings. A Work without
 * any vote is absent, never a fabricated zero; the whole read is local, so the
 * screen is stable offline and never captures a chart through a browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryRatingScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    restoreFocusBookId: String? = null,
    onBookFocusRestored: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    val rows by viewModel.libraryRating.collectAsState()
    val isLoading by viewModel.isLibraryRatingLoading.collectAsState()
    val loadFailed by viewModel.libraryRatingLoadFailed.collectAsState()
    val returnFocusRequester = remember { FocusRequester() }

    // v1.4 E6 (ADR-0033): the honest count rides the scaffold's subtitle
    // (R10), rendered only when it is real (ADR-0014).
    IndexScreenScaffold(
        title = stringResource(R.string.library_rating_title),
        onBackClick = onBackClick,
        subtitle = if (!isLoading && !loadFailed && rows.isNotEmpty()) {
            pluralStringResource(R.plurals.library_rating_count, rows.size, rows.size)
        } else {
            null
        }
    ) { padding ->
        LaunchedEffect(restoreFocusBookId, rows, isLoading, loadFailed) {
            val bookId = restoreFocusBookId ?: return@LaunchedEffect
            if (isLoading || loadFailed) return@LaunchedEffect
            val bookIndex = rows.indexOfFirst { it.book.id == bookId }
            if (bookIndex < 0) return@LaunchedEffect
            listState.scrollToItem(bookIndex)
            withFrameNanos { }
            if (runCatching { returnFocusRequester.requestFocus() }.getOrDefault(false)) {
                onBookFocusRestored(bookId)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("library_rating_screen"),
            contentPadding = PaddingValues(bottom = AppDimens.SpaceAboveMiniPlayer, top = 8.dp)
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
                            message = stringResource(R.string.secondary_library_rating_error),
                            modifier = Modifier.fillMaxWidth().padding(48.dp),
                            isError = true
                        )
                    }
                }

                rows.isEmpty() -> {
                    item {
                        SecondaryMessageState(
                            message = stringResource(R.string.secondary_library_rating_empty),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp)
                        )
                    }
                }

                else -> {
                    itemsIndexed(rows, key = { _, row -> row.book.id }) { index, row ->
                        // v1.4 C3 (ADR-0033): the canonical flat row — rank
                        // badge in the leading slot, ▶ as a separate 48 dp target.
                        val rank = index + 1
                        val podium = rank <= 3
                        val votes = pluralStringResource(R.plurals.library_rating_votes, row.count, row.count)
                        BookRow(
                            title = row.book.title,
                            book = row.book,
                            author = row.book.displayAuthor.takeIf { it.isNotBlank() },
                            // ADR-0014: the real average and its real vote count,
                            // rounded only here for display.
                            stats = "${formatRatingAverage(row.average)} · $votes",
                            onClick = { onBookClick(row.book.id) },
                            leading = {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (podium) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceContainerHigh
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$rank",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                                        color = if (podium) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            trailing = {
                                IconButton(
                                    onClick = {
                                        viewModel.playAudiobook(row.book)
                                        viewModel.setShowFullPlayer(true)
                                    },
                                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = stringResource(R.string.secondary_play_book, row.book.title),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            },
                            testTag = "library_rating_rank_$rank",
                            modifier = if (row.book.id == restoreFocusBookId) {
                                Modifier.focusRequester(returnFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                    }
                }
            }
        }
    }
}

/** ADR-0022: the rule never rounds; the surface does, for display only. */
internal fun formatRatingAverage(average: Double): String =
    String.format(java.util.Locale.getDefault(), "%.1f", average)
