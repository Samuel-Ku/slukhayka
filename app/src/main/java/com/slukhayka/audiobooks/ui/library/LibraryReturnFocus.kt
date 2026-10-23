package com.slukhayka.audiobooks.ui.library

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.withFrameNanos

/**
 * #1285 — the UI adapter that performs the focus/scroll effects for the return
 * anchor. The RULES live in [LibraryProjection.indexOfReturnAnchor]: this
 * composable only asks the projection which entry to land on and then asks the
 * grid and the focus system to do it.
 *
 * The anchor is the Work, not the narration id. A listener who opens a Work,
 * starts another narration of it and comes back must land on the same card even
 * though the card now SHOWS a different primary narration; [projection] resolves
 * that membership. When the Work is not in the current projection (filtered out,
 * deleted, hidden by the search) the heading is focused instead — the filter is
 * never reset and no card receives an invented focus.
 *
 * [bookFocusRequester] must be attached to the card the projection points at;
 * [headingFocusRequester] to the screen heading.
 */
@Composable
fun LibraryReturnFocusEffect(
    projection: LibraryProjection,
    entries: List<LibraryGridEntry>,
    restoreFocusBookId: String?,
    activeTab: Int,
    modalVisible: Boolean,
    hasBooks: Boolean,
    gridState: LazyGridState,
    bookFocusRequester: FocusRequester,
    headingFocusRequester: FocusRequester,
    onBookFocusRestored: (String) -> Unit
) {
    LaunchedEffect(
        restoreFocusBookId,
        entries,
        projection,
        activeTab,
        modalVisible,
        hasBooks
    ) {
        val bookId = restoreFocusBookId ?: return@LaunchedEffect
        // TalkBack: requesting focus while the details are open or another tab
        // is active would steal it; the token is consumed only once the list is
        // the visible surface again.
        if (activeTab != 0 || modalVisible) return@LaunchedEffect
        val visibleIndex = projection.indexOfReturnAnchor(entries, bookId)
        when {
            visibleIndex >= 0 -> {
                gridState.scrollToItem(visibleIndex)
                withFrameNanos { }
                bookFocusRequester.requestFocus()
                onBookFocusRestored(bookId)
            }
            hasBooks -> {
                withFrameNanos { }
                headingFocusRequester.requestFocus()
                onBookFocusRestored(bookId)
            }
        }
    }
}
