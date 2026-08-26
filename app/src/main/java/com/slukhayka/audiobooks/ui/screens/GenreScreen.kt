package com.slukhayka.audiobooks.ui.screens

import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.res.stringResource
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.library.ukPlural

/**
 * Full-screen list of every book in a 4read.org genre (category) — e.g.
 * `https://4read.org/fentezi/`. Opened from the \"Жанри\" chips row of the
 * Explore screen; the book list is fetched (and upserted) from the genre
 * page, then rendered via the shared [BookListScreen].
 */
@Composable
fun GenreScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    restoreFocusBookId: String? = null,
    onBookFocusRestored: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    val genre by viewModel.selectedGenre.collectAsState()
    val books by viewModel.genreBooks.collectAsState()
    val isLoading by viewModel.isGenreLoading.collectAsState()
    val loadFailed by viewModel.genreLoadFailed.collectAsState()

    val currentGenre = genre ?: return

    BookListScreen(
        title = currentGenre.title,
        // Spec-27 (#204) BUG-006: правильна множина — «1 книга у жанрі»,
        // «2 книги», «5 книг».
        countLabel = "${books.size} ${ukPlural(books.size, "книга", "книги", "книг")} у жанрі",
        emptyMessage = stringResource(R.string.secondary_genre_empty),
        errorMessage = if (loadFailed) {
            stringResource(R.string.secondary_genre_error)
        } else {
            null
        },
        isLoading = isLoading,
        books = books,
        onBackClick = onBackClick,
        onBookClick = onBookClick,
        onPlayClick = { book ->
            viewModel.playAudiobook(book)
            viewModel.setShowFullPlayer(true)
        },
        testTag = "genre_screen",
        restoreFocusBookId = restoreFocusBookId,
        onBookFocusRestored = onBookFocusRestored,
        listState = listState
    )
}
