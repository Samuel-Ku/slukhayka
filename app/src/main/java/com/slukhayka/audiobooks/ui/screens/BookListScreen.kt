package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.components.BookRow
import com.slukhayka.audiobooks.ui.components.EmptyState
import com.slukhayka.audiobooks.ui.components.IndexScreenScaffold
import com.slukhayka.audiobooks.ui.theme.*

/**
 * Shared full-screen book list used by the series, genre and person-book
 * screens: a back button, the list's title (with the count as its subtitle,
 * v1.4 E6), the canonical [BookRow] rows and a friendly empty state. Keeping
 * the layout here means each catalogue screen only wires its own state to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookListScreen(
    title: String,
    countLabel: String?,
    emptyMessage: String,
    isLoading: Boolean,
    books: List<AudiobookEntity>,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onPlayClick: (AudiobookEntity) -> Unit,
    testTag: String,
    errorMessage: String? = null,
    restoreFocusBookId: String? = null,
    onBookFocusRestored: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    // #400 — optional action slot in the TopAppBar (e.g. person bookmark button)
    headerAction: @Composable () -> Unit = {}
) {
    val returnFocusRequester = remember { FocusRequester() }

    // v1.4 E6 (ADR-0033): the count rides the scaffold's subtitle (R10),
    // never a free-standing list row.
    IndexScreenScaffold(
        title = title,
        onBackClick = onBackClick,
        actions = headerAction,
        subtitle = countLabel
    ) { padding ->
        LaunchedEffect(restoreFocusBookId, books, isLoading, errorMessage) {
            val bookId = restoreFocusBookId ?: return@LaunchedEffect
            if (isLoading || errorMessage != null) return@LaunchedEffect
            val bookIndex = books.indexOfFirst { it.id == bookId }
            if (bookIndex < 0) return@LaunchedEffect
            // The count moved into the scaffold's subtitle (v1.4 E6); rows
            // start at item zero now.
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
                .testTag(testTag),
            contentPadding = PaddingValues(bottom = AppDimens.SpaceAboveMiniPlayer, top = 8.dp)
        ) {
            when {
                isLoading -> {
                    item {
                        // v1.4 C4 (ADR-0033): the canonical full empty state
                        // with the spinner on the icon slot — a live indicator,
                        // not a static glyph; the label is the title and the
                        // real progress-bar node keeps its contentDescription.
                        val loadingLabel = stringResource(R.string.secondary_loading)
                        EmptyState(
                            icon = Icons.Filled.Info,
                            title = loadingLabel,
                            body = "",
                            iconContent = {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .semantics { contentDescription = loadingLabel }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp)
                        )
                    }
                }

                errorMessage != null -> {
                    item {
                        EmptyState(
                            icon = Icons.Filled.Warning,
                            title = errorMessage,
                            body = "",
                            stateDescription = stringResource(R.string.secondary_state_error),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp)
                        )
                    }
                }

                books.isEmpty() -> {
                    item {
                        EmptyState(
                            icon = Icons.Filled.Info,
                            title = emptyMessage,
                            body = "",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp)
                        )
                    }
                }

                else -> {
                    // The count lives in the scaffold's subtitle (v1.4 E6,
                    // ADR-0033).
                    items(books, key = { it.id }) { book ->
                        BookRow(
                            book = book,
                            onClick = { onBookClick(book.id) },
                            onPlayClick = { onPlayClick(book) },
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
