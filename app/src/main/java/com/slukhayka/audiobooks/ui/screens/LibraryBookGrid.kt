package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.availability.AvailabilityView
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookDownloadCount
import com.slukhayka.audiobooks.ui.SubmissionBadge
import com.slukhayka.audiobooks.ui.library.LibraryGridEntry
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * #1206 — the Медіатека's book area: the section header and the books, in the
 * shape the screen draws them.
 *
 * The screen and the snapshot test both go through this one composable, for the
 * same reason [libraryGridContent] exists: a golden must not be able to
 * document a layout the app does not ship.
 *
 * The book area is the one place on the screen whose shape depends on the
 * CONTAINER it is given rather than on the window: inside a wide window's list
 * pane (see `WideDetailPane`) it is handed a fraction of the window, not the
 * whole of it. So this composable — and not the screen's caller — owns the
 * decision of how many columns its own width may hold.
 */
@Composable
internal fun LibraryBookGrid(
    entries: List<LibraryGridEntry>,
    browsing: Boolean,
    gridMode: Boolean,
    availability: Map<String, AvailabilityView>,
    downloadCounts: Map<String, BookDownloadCount>,
    restoreFocusBookId: String?,
    bookReturnFocusRequester: FocusRequester,
    awaitingSubmissionBookIds: Set<String>,
    watchingSubmissionBookIds: Set<String>,
    deferredPublicationBookIds: Set<String>,
    onBookClick: (String) -> Unit,
    onPlayClick: (AudiobookEntity) -> Unit,
    onRecheck: (String) -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
    modifier: Modifier = Modifier,
    abandonedBookIds: Set<String> = emptySet(),
    submissionBadges: Map<String, SubmissionBadge> = emptyMap()
) {
    LazyVerticalGrid(
        columns = if (gridMode) GridCells.Fixed(2) else GridCells.Fixed(1),
        state = gridState,
        // #962 — the grid takes what is LEFT of the column rather than claiming
        // everything: `fillMaxSize` inside a Column is measured against the
        // whole window, so it both over-reported its height and pushed the rows
        // it did lay out past the bottom edge. `weight(1f)` — handed in by the
        // caller — is the honest ask, "the rest of the screen", and it is what
        // makes the list scrollable in a 411 dp landscape window.
        modifier = modifier
            .fillMaxWidth()
            .testTag("library_grid"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = AppDimens.SpaceAboveMiniPlayer
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        libraryGridContent(
            entries = entries,
            // #885 — must match the shape `libraryGridEntries` built above
            // (always the dense rows on «Книги»), otherwise the renderer falls
            // back to the wall of cards and none of the row work shows up.
            browsing = browsing,
            gridMode = gridMode,
            availability = availability,
            downloadCounts = downloadCounts,
            restoreFocusBookId = restoreFocusBookId,
            bookReturnFocusRequester = bookReturnFocusRequester,
            awaitingSubmissionBookIds = awaitingSubmissionBookIds,
            submissionBadges = submissionBadges,
            watchingSubmissionBookIds = watchingSubmissionBookIds,
            deferredPublicationBookIds = deferredPublicationBookIds,
            abandonedBookIds = abandonedBookIds,
            onBookClick = onBookClick,
            onPlayClick = onPlayClick,
            onRecheck = onRecheck
        )
    }
}
