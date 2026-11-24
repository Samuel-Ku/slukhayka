package com.slukhayka.audiobooks.ui.library

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * #1285 — the UI adapter that performs the focus/scroll effects for the return
 * anchor. The RULES live in [LibraryProjection.indexOfReturnAnchor]: the caller
 * resolves the entry there once and this composable only asks the grid and the
 * focus system to act on it.
 *
 * The anchor is the Work, not the narration id. A listener who opens a Work,
 * starts another narration of it and comes back must land on the same card even
 * though the card now SHOWS a different primary narration; the projection
 * resolves that membership. When the Work is not in the current projection
 * (filtered out, deleted, hidden by the search) the heading is focused instead —
 * the filter is never reset and no card receives an invented focus.
 *
 * [anchorIndex] is resolved ONCE by the caller from
 * [LibraryProjection.indexOfReturnAnchor] and handed to BOTH this effect and
 * the renderer (as the anchor's shown-book id): attachment and scroll target
 * then agree by construction. The renderer must not look the token up itself —
 * in the changed-primary scenario the token's narration is no longer drawn, so
 * a requester attached to it would never be initialised and `requestFocus()`
 * would throw.
 *
 * [headingFocusRequester] must be attached to the screen heading.
 */
@Composable
fun LibraryReturnFocusEffect(
    anchorIndex: Int,
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
        anchorIndex,
        restoreFocusBookId,
        activeTab,
        modalVisible,
        hasBooks
    ) {
        val bookId = restoreFocusBookId ?: return@LaunchedEffect
        // TalkBack: requesting focus while the details are open or another tab
        // is active would steal it; the token is consumed only once the list is
        // the visible surface again.
        if (activeTab != 0 || modalVisible) return@LaunchedEffect
        when {
            anchorIndex >= 0 -> {
                gridState.scrollToItem(anchorIndex)
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
