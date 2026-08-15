package com.example.ui.screens

import androidx.compose.runtime.*
import com.example.ui.MainViewModel

/**
 * Full-screen list of every book narrated (or written) by one person —
 * `/xfsearch/chitaet|<avtor>/<name>/`. Opened from the Виконавці/Автори index;
 * the person's page is a poster grid, so it feeds the shared [BookListScreen].
 */
@Composable
fun PersonBooksScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit
) {
    val person by viewModel.selectedPerson.collectAsState()
    val books by viewModel.personBooks.collectAsState()
    val isLoading by viewModel.isPersonLoading.collectAsState()

    val currentPerson = person ?: return

    BookListScreen(
        title = currentPerson.name,
        countLabel = "${books.size} книг",
        emptyMessage = "Не вдалося завантажити книги. Перевірте з'єднання.",
        isLoading = isLoading,
        books = books,
        onBackClick = onBackClick,
        onBookClick = onBookClick,
        onPlayClick = { book ->
            viewModel.playAudiobook(book)
            viewModel.setShowFullPlayer(true)
        },
        testTag = "person_books_screen"
    )
}
