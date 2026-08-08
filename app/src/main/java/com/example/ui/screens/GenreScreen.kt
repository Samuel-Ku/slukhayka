package com.example.ui.screens

import androidx.compose.runtime.*
import com.example.ui.MainViewModel

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
    onBookClick: (String) -> Unit
) {
    val genre by viewModel.selectedGenre.collectAsState()
    val books by viewModel.genreBooks.collectAsState()
    val isLoading by viewModel.isGenreLoading.collectAsState()

    val currentGenre = genre ?: return

    BookListScreen(
        title = currentGenre.title,
        countLabel = "${books.size} книг у жанрі",
        emptyMessage = "Не вдалося завантажити книги жанру. Перевірте з'єднання.",
        isLoading = isLoading,
        books = books,
        onBackClick = onBackClick,
        onBookClick = onBookClick,
        onPlayClick = { book ->
            viewModel.playAudiobook(book)
            viewModel.setShowFullPlayer(true)
        },
        testTag = "genre_screen"
    )
}
