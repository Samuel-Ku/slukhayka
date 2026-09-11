package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.slukhayka.audiobooks.ui.library.ukPlural
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.BookCoverImage
import com.slukhayka.audiobooks.ui.components.BookCoverSemantics
import com.slukhayka.audiobooks.ui.components.BookRow
import com.slukhayka.audiobooks.ui.components.IndexScreenScaffold
import com.slukhayka.audiobooks.ui.components.SecondaryLoadingState
import com.slukhayka.audiobooks.ui.components.SecondaryMessageState
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.theme.*

/**
 * Full-screen ТОП 100 АудіоКниг (`/top-100.html`): a ranked list of the
 * site's top books. Each row shows the rank badge, the cover, title, author
 * and — when the page carried it — the real total duration. Books are
 * upserted into Room so tapping one opens its detail and it is playable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Top100Screen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    restoreFocusBookId: String? = null,
    onBookFocusRestored: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    val books by viewModel.top100Books.collectAsState()
    val isLoading by viewModel.isTop100Loading.collectAsState()
    val loadFailed by viewModel.top100LoadFailed.collectAsState()
    val returnFocusRequester = remember { FocusRequester() }

    // v1.4 E6 (ADR-0033): the honest count rides the scaffold's subtitle
    // (R10), rendered only when it is real (ADR-0014).
    IndexScreenScaffold(
        title = stringResource(R.string.top100_index_title),
        onBackClick = onBackClick,
        subtitle = if (!isLoading && !loadFailed && books.isNotEmpty()) {
            pluralStringResource(R.plurals.best_book_count, books.size, books.size)
        } else {
            null
        }
    ) { padding ->
        LaunchedEffect(restoreFocusBookId, books, isLoading, loadFailed) {
            val bookId = restoreFocusBookId ?: return@LaunchedEffect
            if (isLoading || loadFailed) return@LaunchedEffect
            val bookIndex = books.indexOfFirst { it.id == bookId }
            if (bookIndex < 0) return@LaunchedEffect
            // The count moved into the scaffold's subtitle (v1.4 E6);
            // ranked books start at item zero now.
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
                .testTag("top100_screen"),
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
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            SecondaryMessageState(
                                message = stringResource(R.string.secondary_top100_error),
                                modifier = Modifier.fillMaxWidth(),
                                isError = true
                            )
                        }
                    }
                }

                books.isEmpty() -> {
                    item {
                        SecondaryMessageState(
                            message = stringResource(R.string.secondary_top100_empty),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp)
                        )
                    }
                }

                else -> {
                    // The count lives in the scaffold's subtitle (v1.4 E6,
                    // ADR-0033; spec-27 #204 BUG-006 pluralization preserved).
                    itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
                        // v1.4 C3 (ADR-0033): the canonical flat row — rank
                        // badge in the leading slot, ▶ as a separate 48 dp
                        // target, divider instead of a card border.
                        val rank = index + 1
                        val podium = rank <= 3
                        BookRow(
                            title = book.title,
                            book = book,
                            author = book.displayAuthor.takeIf { it.isNotBlank() },
                            // Real duration shown only when known (ADR-0014).
                            stats = if (book.totalDurationSeconds > 0L) MainViewModel.formatTime(book.totalDurationSeconds) else null,
                            onClick = { onBookClick(book.id) },
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
                                        viewModel.playAudiobook(book)
                                        viewModel.setShowFullPlayer(true)
                                    },
                                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = stringResource(R.string.secondary_play_book, book.title),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            },
                            testTag = "top100_rank_$rank",
                            modifier = if (book.id == restoreFocusBookId) {
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
