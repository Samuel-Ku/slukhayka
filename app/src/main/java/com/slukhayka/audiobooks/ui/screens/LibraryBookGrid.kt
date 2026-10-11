package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
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
 * whole of it. The column COUNT is not decided here — [libraryGridColumns]
 * (#1205/#1217) is its one carrier, and this composable both draws it and ASKS
 * it how many columns the container it was handed will get, instead of keeping
 * a second copy of that arithmetic.
 *
 * [entries] arrive already built, because the screen remembers them for its
 * focus-return and availability queues. That is sound only for the shape the
 * screen builds: on «Книги» (`browsing = false`) the entry list does not depend
 * on the view mode, so the mode can still be read here, after it. A caller that
 * ever wants `browsing = true` — where the mode picks a shelf instead of tiles
 * — must build its entries from the same mode it passes here.
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
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    abandonedBookIds: Set<String> = emptySet(),
    submissionBadges: Map<String, SubmissionBadge> = emptyMap()
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // #1206 — the ONE container-dependent fact on this screen: how much
        // width the book area was actually handed. `maxWidth` is the incoming
        // constraint, so it answers for the list pane of a wide window exactly
        // as it answers for a whole phone window.
        val columns = libraryGridColumns(gridMode)
        val density = LocalDensity.current
        // `calculateCrossAxisCellSizes` is the very call `LazyVerticalGrid`
        // makes to place its columns, with the same content width (the grid's
        // own side paddings taken out) and the same spacing — so ASKING it is
        // how this composable knows whether a second column fits, instead of
        // keeping a second copy of the rule that could drift from the shipped
        // one. The snapshot test cross-checks the answer against the columns
        // the layout really drew.
        val columnCount = with(columns) {
            with(density) {
                calculateCrossAxisCellSizes(
                    availableSize = (maxWidth - AppDimens.PageSides * 2)
                        .roundToPx()
                        .coerceAtLeast(0),
                    spacing = AppDimens.SpaceMd.roundToPx()
                ).size
            }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            // #1206 — the chip in the filter sheet says «Сітка», and the pane
            // must not quietly contradict it: it draws TILES either way, and
            // when the container can only hold one column it says so. That is
            // the whole of this note — a reason, not a degradation. The policy
            // (#1217) is that a narrow pane keeps the tiles at the canonical
            // poster's width instead of squeezing two columns under it or
            // falling back to the list.
            //
            // It sits ABOVE the grid rather than as a leading grid item on
            // purpose: `LibraryScreen` maps a grid INDEX back to a
            // `LibraryGridEntry` for its availability queue and its focus
            // return, so an extra leading item would shift every one of them.
            if (gridMode && columnCount < 2) {
                Text(
                    text = stringResource(R.string.lib_grid_pane_too_narrow),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = AppDimens.PageSides,
                            end = AppDimens.PageSides,
                            top = AppDimens.SpaceSm
                        )
                        .testTag("library_grid_single_column_note")
                )
            }
            LazyVerticalGrid(
                columns = columns,
                state = gridState,
                // #962 — the grid takes what is LEFT of the column rather than
                // claiming everything: `fillMaxSize` inside a Column is measured
                // against the whole window, so it both over-reported its height
                // and pushed the rows it did lay out past the bottom edge.
                // `weight(1f)` — handed in by the caller — is the honest ask,
                // "the rest of the screen", and it is what makes the list
                // scrollable in a 411 dp landscape window.
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("library_grid"),
                contentPadding = PaddingValues(
                    start = AppDimens.PageSides,
                    end = AppDimens.PageSides,
                    top = 8.dp,
                    bottom = AppDimens.SpaceAboveMiniPlayer
                ),
                horizontalArrangement = Arrangement.spacedBy(AppDimens.SpaceMd),
                verticalArrangement = Arrangement.spacedBy(AppDimens.SpaceMd)
            ) {
                libraryGridContent(
                    entries = entries,
                    // #885 — must match the shape the caller built its entries
                    // with (always the dense rows on «Книги»), otherwise the
                    // renderer falls back to the wall of cards and none of the
                    // row work shows up.
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
    }
}
