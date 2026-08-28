package com.slukhayka.audiobooks.ui.screens

import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.res.stringResource
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.personbookmarks.PersonBookmarks
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.library.ukPlural

/**
 * Full-screen list of every book narrated (or written) by one person —
 * `/xfsearch/chitaet|<avtor>/<name>/`. Opened from the Виконавці/Автори index;
 * the person's page is a poster grid, so it feeds the shared [BookListScreen].
 *
 * #400: receives [PersonBookmarks] for direct Flow collection (ADR-0008).
 */
@Composable
fun PersonBooksScreen(
    viewModel: MainViewModel,
    // #400: person bookmarks module — Flows read directly, actions via
    // composition scope (ADR-0008).
    personBookmarks: PersonBookmarks,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    restoreFocusBookId: String? = null,
    onBookFocusRestored: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    val person by viewModel.selectedPerson.collectAsState()
    val books by viewModel.personBooks.collectAsState()
    val isLoading by viewModel.isPersonLoading.collectAsState()
    val loadFailed by viewModel.personLoadFailed.collectAsState()

    val currentPerson = person ?: return

    // #400: bookmark Flows collected directly — no forwarding in MainViewModel.
    val bookmarkedAuthors by personBookmarks.bookmarkedAuthors()
        .collectAsState(initial = emptyList())
    val bookmarkedNarrators by personBookmarks.bookmarkedNarrators()
        .collectAsState(initial = emptyList())
    val isBookmarked = remember(bookmarkedAuthors, bookmarkedNarrators, currentPerson) {
        val all = bookmarkedAuthors + bookmarkedNarrators
        all.any { it.displayName.equals(currentPerson.name, ignoreCase = true) }
    }

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
        testTag = "person_books_screen",
        restoreFocusBookId = restoreFocusBookId,
        onBookFocusRestored = onBookFocusRestored,
        listState = listState
    )
}
