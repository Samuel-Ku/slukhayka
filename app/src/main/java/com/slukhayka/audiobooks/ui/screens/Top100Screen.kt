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

    IndexScreenScaffold(title = "ТОП 100 АудіоКниг", onBackClick = onBackClick) { padding ->
        LaunchedEffect(restoreFocusBookId, books, isLoading, loadFailed) {
            val bookId = restoreFocusBookId ?: return@LaunchedEffect
            if (isLoading || loadFailed) return@LaunchedEffect
            val bookIndex = books.indexOfFirst { it.id == bookId }
            if (bookIndex < 0) return@LaunchedEffect
            // The count row is item zero; ranked books start at item one.
            listState.scrollToItem(bookIndex + 1)
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
                            message = stringResource(R.string.secondary_top100_error),
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
                            message = stringResource(R.string.secondary_top100_empty),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp)
                        )
                    }
                }

                else -> {
                    item {
                        Text(
                            // Spec-27 (#204) BUG-006: правильна множина —
                            // «1 найкраща книга», «2 найкращі книги»,
                            // «5 найкращих книг».
                            text = "${books.size} ${ukPlural(books.size, "найкраща книга", "найкращі книги", "найкращих книг")}",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
                        Top100Row(
                            rank = index + 1,
                            book = book,
                            onClick = { onBookClick(book.id) },
                            onPlayClick = {
                                viewModel.playAudiobook(book)
                                viewModel.setShowFullPlayer(true)
                            },
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

/** One ranked row: rank badge + cover + title/author/duration. */
@Composable
fun Top100Row(
    rank: Int,
    book: AudiobookEntity,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusPanel))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusPanel))
            .clickable { onClick() }
            .semantics(mergeDescendants = true) { }
            .testTag("top100_rank_$rank"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rank badge: gold for the podium, neutral afterwards.
            val podium = rank <= 3
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

            Spacer(modifier = Modifier.width(12.dp))

            BookCoverImage(
                book = book,
                semantics = BookCoverSemantics.Decorative,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(AppDimens.RadiusCover)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (book.displayAuthor.isNotBlank()) {
                    Text(
                        text = book.displayAuthor,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Real duration from the page's \"Триває:\" — shown only when known.
                if (book.totalDurationSeconds > 0L) {
                    Text(
                        text = MainViewModel.formatTime(book.totalDurationSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = onPlayClick,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.secondary_play_book, book.title),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}
