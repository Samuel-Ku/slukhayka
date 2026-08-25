package com.slukhayka.audiobooks.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.library.ukPlural

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
    val loadFailed by viewModel.personLoadFailed.collectAsState()

    val currentPerson = person ?: return

    BookListScreen(
        title = currentPerson.name,
        // Spec-27 (#204) BUG-006: правильна множина — «1 книга», «2 книги»,
        // «5 книг».
        countLabel = "${books.size} ${ukPlural(books.size, "книга", "книги", "книг")}",
        emptyMessage = stringResource(R.string.secondary_person_books_empty),
        errorMessage = if (loadFailed) {
            stringResource(R.string.secondary_person_books_error)
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
        testTag = "person_books_screen"
    )
}
