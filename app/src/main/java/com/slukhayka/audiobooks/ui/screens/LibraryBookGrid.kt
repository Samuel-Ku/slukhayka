package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.availability.AvailabilityView
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookDownloadCount
import com.slukhayka.audiobooks.ui.SubmissionBadge
import com.slukhayka.audiobooks.ui.components.PosterWidth
import com.slukhayka.audiobooks.ui.library.LibraryGridEntry
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * #1206 — the narrowest container that may hold the TWO-column tile grid.
 *
 * Two tiles no narrower than the canonical poster — `PosterWidth` (120 dp,
 * `PosterCard`) taken as the tile's MINIMUM, not as the width the library tile
 * is drawn at: that tile stretches to its grid cell — plus the column gap and
 * the grid's own side padding, read from the very `AppDimens` tokens the grid
 * lays out with, so the floor cannot drift from the layout it guards:
 * 2 × 120 + 12 + 32 = **284 dp**.
 */
internal val LibraryGridMinWidth: Dp =
    PosterWidth * 2 + AppDimens.SpaceMd + AppDimens.PageSides * 2

/**
 * #1206 — may the book area draw TILES, or must it fall back to the LIST?
 *
 * A wide window at its 600 dp breakpoint gives the Library's list pane only
 * 0.4 of its width — 239.6 dp — and two tile columns cannot live there: the
 * grid would force each cell to ≈98 dp, i.e. 22 dp UNDER the canonical poster,
 * and the titles would ellipsise into the «column of truncated titles»
 * `WindowLayout` warns about. So below [LibraryGridMinWidth] the listener's
 * grid choice degrades to the list — the same rows the list mode draws —
 * rather than being drawn squeezed or cropped.
 *
 * The container decides, not the window: the same screen is a full window on a
 * phone and a fraction of one inside a wide window's pane, and only the width
 * it is actually handed can answer.
 *
 * [gridMode] is the listener's stored choice. It is never upgraded: a window
 * that grows back over the floor shows the grid again, and a listener who
 * chose the list never gets tiles.
 */
internal fun libraryGridShowsTiles(availableWidth: Dp, gridMode: Boolean): Boolean =
    gridMode && availableWidth >= LibraryGridMinWidth

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
 *
 * [entries] arrive already built, because the screen remembers them for its
 * focus-return and availability queues. That is sound only for the shape the
 * screen builds: on «Книги» (`browsing = false`) the entry list does not depend
 * on the view mode, so the mode can still be decided here, after it. A caller
 * that ever wants `browsing = true` — where the mode picks a shelf instead of
 * tiles — must build its entries from the EFFECTIVE mode, not from [gridMode].
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
        // #1206 — the ONE container-dependent decision on this screen: how much
        // width the book area was actually handed. `maxWidth` is the incoming
        // constraint, so it answers for the list pane of a wide window exactly
        // as it answers for a whole phone window.
        val showsTiles = libraryGridShowsTiles(availableWidth = maxWidth, gridMode = gridMode)
        Column(modifier = Modifier.fillMaxSize()) {
            // #1206 — the degradation is STATED, not silent. The chip in the
            // filter sheet keeps saying «Сітка»: it is the listener's stored
            // preference, and the grid comes back as soon as the pane can
            // afford it — so the pane has to say why it is showing rows
            // instead, and it has to say it HERE, where the rows are, rather
            // than in a sheet the listener has already closed.
            //
            // The note sits ABOVE the grid rather than as a leading grid item
            // on purpose: `LibraryScreen` maps a grid INDEX back to a
            // `LibraryGridEntry` for its availability queue and its focus
            // return, so an extra leading item would shift every one of them.
            if (gridMode && !showsTiles) {
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
                        .testTag("library_grid_degraded_note")
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (showsTiles) 2 else 1),
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
                    // #1206 — the EFFECTIVE mode, not the listener's stored one:
                    // a pane under the floor draws the dense rows, which is what
                    // the list mode draws, so the degradation is the list itself
                    // and not a third presentation.
                    gridMode = showsTiles,
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
