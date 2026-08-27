package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.components.IndexScreenScaffold
import com.slukhayka.audiobooks.ui.components.SecondaryLoadingState
import com.slukhayka.audiobooks.ui.components.SecondaryMessageState
import com.slukhayka.audiobooks.ui.theme.*

/**
 * Shared full-screen book list used by the series, genre and person-book
 * screens: a back button, the list's title, a small count label, the standard
 * [AudiobookListItem] rows and a friendly empty state. Keeping the layout here
 * means each catalogue screen only wires its own state to it.
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
    listState: LazyListState = rememberLazyListState()
) {
    val returnFocusRequester = remember { FocusRequester() }

    IndexScreenScaffold(
        title = title,
        onBackClick = onBackClick
    ) { padding ->
        LaunchedEffect(restoreFocusBookId, books, isLoading, errorMessage) {
            val bookId = restoreFocusBookId ?: return@LaunchedEffect
            if (isLoading || errorMessage != null) return@LaunchedEffect
            val bookIndex = books.indexOfFirst { it.id == bookId }
            if (bookIndex < 0) return@LaunchedEffect
            val countOffset = if (countLabel != null) 1 else 0
            listState.scrollToItem(bookIndex + countOffset)
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

                errorMessage != null -> {
                    item {
                        SecondaryMessageState(
                            message = errorMessage,
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
                            message = emptyMessage,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp)
                        )
                    }
                }

                else -> {
                    if (countLabel != null) {
                        item {
                            Text(
                                text = countLabel,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                    }
                    items(books, key = { it.id }) { book ->
                        AudiobookListItem(
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
