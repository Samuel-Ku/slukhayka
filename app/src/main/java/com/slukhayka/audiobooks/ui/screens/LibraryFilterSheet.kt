package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import com.slukhayka.audiobooks.ui.theme.AppDimens
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.library.LibraryFilter
import com.slukhayka.audiobooks.ui.library.LibrarySort
import com.slukhayka.audiobooks.ui.library.SHEET_FILTERS
import com.slukhayka.audiobooks.ui.library.STATUS_FILTERS
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * spec-28 #193 — the Медіатека filter chrome, split into a visible segmented
 * status row (Усі / Нові / Слухаю / Завершені / Завантажені, one tap each)
 * and a filter sheet holding the rare filters (Обрані / Локальні / Онлайн)
 * plus sorting and the view toggle. Both are stateless: the screen owns the
 * single [LibraryFilter] / [LibrarySort] / grid state and hands it down, so
 * the snapshot harness pins the chrome without a `MainViewModel`.
 *
 * Chip styling follows the design guide: unselected chips sit one tonal step
 * above the surface (they read as affordances), the selected chip is filled
 * with the accent — the active non-default filter is visibly highlighted.
 */

/** The accent-highlighted chip style shared by every filter chip in the row and sheet. */
private val FilterChipAccentColors
    @Composable get() = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        labelColor = MaterialTheme.colorScheme.onSurface
    )

/** The row's own content padding — and the margin the reveal leaves a chip. */
private val StatusRowPadding = 16.dp

/**
 * #1165 — where the row's chips sit inside its viewport, in pixels.
 *
 * Recorded from the layout phase ([Modifier.onGloballyPositioned]) and read by
 * the reveal effect. Deliberately NOT snapshot state: nothing is drawn from it,
 * so recording a position must not invalidate composition — and the positions
 * themselves move with the scroll, which is why only the effect, running on a
 * selection change, reads them.
 */
private class StatusRowGeometry {
    private var viewportLeft = 0f
    private val chips = HashMap<LibraryFilter, ClosedFloatingPointRange<Float>>()
    private var trailing: ClosedFloatingPointRange<Float>? = null

    /** The scroll container itself: the chips are measured against its edge. */
    fun recordViewport(coordinates: LayoutCoordinates) {
        viewportLeft = coordinates.positionInRoot().x
    }

    fun recordStatus(filter: LibraryFilter, coordinates: LayoutCoordinates) {
        chips[filter] = inViewport(coordinates)
    }

    fun recordTrailing(coordinates: LayoutCoordinates) {
        trailing = inViewport(coordinates)
    }

    /**
     * The chip that CARRIES [selected]: its own status chip, or — when the
     * selection is a rare filter — the launcher at the row's end, which is the
     * control that draws that selection and therefore the one that must read in
     * full.
     */
    fun edgesOf(selected: LibraryFilter): ClosedFloatingPointRange<Float>? =
        chips[selected] ?: trailing?.takeIf { selected !in STATUS_FILTERS }

    private fun inViewport(coordinates: LayoutCoordinates): ClosedFloatingPointRange<Float> {
        val left = coordinates.positionInRoot().x - viewportLeft
        return left..(left + coordinates.size.width)
    }
}

/**
 * The visible segmented status row: five one-tap statuses, horizontally
 * scrollable so a narrow screen never wraps them onto a second line (design
 * guide §6.3). A plain `Row` + `horizontalScroll` (not a `LazyRow`) so every
 * chip is always composed — taps and snapshots see all five statuses.
 *
 * UI (v1.5 review): the FlowRow detour is gone. Wrapping «Завантажені» onto a
 * second line and «Фільтр» onto a third was exactly the stacked chrome the
 * design guide forbids; the partially visible edge chip is the scroll
 * affordance, not a broken chip.
 *
 * #1165 — that affordance has ONE exception: the chip that carries the
 * selection. The row is wider than the window, so a selected last chip would
 * sit half under the window edge — unreadable, and the active filter is exactly
 * the label the listener needs. The row therefore reveals the selected chip
 * (its own, or the [trailing] launcher while a rare filter is active) whenever
 * the selection changes, and leaves a row the listener scrolled by hand alone.
 * #390 fixed this same chip by eye and left no test, which is how the #885
 * redesign brought it back; `LibraryStatusRowVisibilityTest` is that test now.
 *
 * [trailing] carries the «Фільтр» launcher on the same line.
 */
@Composable
fun LibraryStatusRow(
    selected: LibraryFilter,
    onSelect: (LibraryFilter) -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    trailing: (@Composable () -> Unit)? = null
) {
    val geometry = remember { StatusRowGeometry() }
    val revealMargin = with(LocalDensity.current) { StatusRowPadding.roundToPx() }

    LaunchedEffect(selected, scrollState) {
        // The viewport size is only known once the row has been measured, and
        // the chip edges are recorded in that same layout pass. The frame wait
        // then puts the read past the layout that follows a selection change —
        // the launcher's own label changes width there — so the edges below
        // belong to the selection being revealed.
        val viewport = snapshotFlow { scrollState.viewportSize }.first { it > 0 }
        withFrameNanos { }
        val edges = geometry.edgesOf(selected) ?: return@LaunchedEffect
        val shift = when {
            // Sticks out on the right: pull it back by the overhang.
            edges.endInclusive > viewport - revealMargin ->
                edges.endInclusive - (viewport - revealMargin)
            // Sticks out on the left: push it in.
            edges.start < revealMargin -> -(revealMargin - edges.start)
            // Readable in full already — never move a row the listener placed.
            else -> return@LaunchedEffect
        }
        scrollState.animateScrollTo(
            (scrollState.value + shift).roundToInt().coerceIn(0, scrollState.maxValue)
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides AppDimens.MinTouchTarget
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // #1165 — the viewport's own origin, so every chip can be read as a
            // position inside the row rather than somewhere in the window.
            .onGloballyPositioned { geometry.recordViewport(it) }
            .horizontalScroll(scrollState)
            .padding(horizontal = StatusRowPadding)
            .testTag("library_status_row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        STATUS_FILTERS.forEach { f ->
            val isSelected = selected == f
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(f) },
                label = { Text(stringResource(f.labelRes)) },
                colors = FilterChipAccentColors,
                // #885 (wave 3) — the prototype draws a status chip as a PILL
                // (`:1239-1241`, radius 999) and gives the SELECTED one no
                // border at all: the tonal fill alone marks it. M3's default
                // 8 dp shape was the only reason these read as a different
                // control from every other chip in the app.
                shape = RoundedCornerShape(AppDimens.RadiusPill),
                border = if (isSelected) {
                    // Selected is tonal WITHOUT a border, per the prototype.
                    null
                } else {
                    FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = false,
                        borderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                },
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .testTag("library_status_${f.name.lowercase()}")
                    .onGloballyPositioned { geometry.recordStatus(f, it) }
            )
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(4.dp))
            // #1165 — the launcher's own edges: while a rare filter is active
            // this chip IS the selection, so it is revealed like a status chip.
            Box(modifier = Modifier.onGloballyPositioned { geometry.recordTrailing(it) }) {
                trailing()
            }
        }
    }
    }
}

/**
 * The filter sheet (spec-28 #193): the rare filters (Обрані / Локальні /
 * Онлайн), the six sort modes and the list/grid view toggle, all in one
 * transient sheet. Selecting a rare filter here and selecting a status in the
 * row write to the same [LibraryFilter] — one filter is active at a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryFilterSheet(
    filter: LibraryFilter,
    sort: LibrarySort,
    gridMode: Boolean,
    onFilterChange: (LibraryFilter) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onGridModeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val headingFocusRequester = remember { FocusRequester() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // fix(accessibility): #371 — decorative drag handle must not create an extra
        // TalkBack focus stop and must never be voiced in Polish on a Polish-system
        // device. Hiding it from accessibility keeps the visual cue but removes the
        // "Uchwyt do przeciągania" node entirely.
        dragHandle = { BottomSheetDefaults.DragHandle(modifier = Modifier.clearAndSetSemantics {}) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.accessibilityPane(stringResource(R.string.a11y_library_filter_pane))
    ) {
        LibraryFilterSheetContent(
            filter = filter,
            sort = sort,
            gridMode = gridMode,
            onFilterChange = onFilterChange,
            onSortChange = onSortChange,
            onGridModeChange = onGridModeChange,
            headingFocusRequester = headingFocusRequester,
            includePaneSemantics = false,
            onClose = onDismiss
        )
    }
}

/**
 * The sheet body, extracted so the snapshot harness pins it without hosting a
 * `ModalBottomSheet` window.
 */
@Composable
fun LibraryFilterSheetContent(
    filter: LibraryFilter,
    sort: LibrarySort,
    gridMode: Boolean,
    onFilterChange: (LibraryFilter) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onGridModeChange: (Boolean) -> Unit,
    headingFocusRequester: FocusRequester? = null,
    includePaneSemantics: Boolean = true,
    onClose: (() -> Unit)? = null
) {
    val localHeadingFocusRequester = remember { FocusRequester() }
    val effectiveHeadingFocusRequester = headingFocusRequester ?: localHeadingFocusRequester
    LaunchedEffect(effectiveHeadingFocusRequester) {
        withFrameNanos { }
        effectiveHeadingFocusRequester.requestFocus()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (includePaneSemantics) {
                    Modifier.accessibilityPane(stringResource(R.string.a11y_library_filter_pane))
                } else Modifier
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .testTag("library_filter_sheet_content")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.lib_filter_sheet_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(effectiveHeadingFocusRequester)
                    .focusable()
                    .semantics { heading() }
                    .testTag("library_filter_sheet_heading")
            )
            if (onClose != null) {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.a11y_library_filter_close))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SheetSectionLabel(stringResource(R.string.lib_filter_section))
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SHEET_FILTERS.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { onFilterChange(f) },
                    label = { Text(stringResource(f.labelRes)) },
                    colors = FilterChipAccentColors,
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = filter == f,
                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                        selectedBorderColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("library_sheet_filter_${f.name.lowercase()}")
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(16.dp))
        SheetSectionLabel(stringResource(R.string.lib_sort_section))
        Spacer(modifier = Modifier.height(4.dp))
        Column(Modifier.selectableGroup()) {
            LibrarySort.entries.forEach { s ->
                val isSelected = sort == s
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = isSelected,
                            onClick = { onSortChange(s) },
                            role = Role.RadioButton
                        )
                        .testTag("library_sort_${s.name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Decorative: the containing row is the single radio target.
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        modifier = Modifier.clearAndSetSemantics { }
                    )
                    Text(
                        text = stringResource(s.labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(16.dp))
        SheetSectionLabel(stringResource(R.string.lib_view_section))
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ViewModeChip(
                selected = !gridMode,
                label = stringResource(R.string.lib_view_list),
                icon = { Icon(imageVector = Icons.Default.ViewList, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                onClick = { onGridModeChange(false) },
                tag = "library_view_list"
            )
            ViewModeChip(
                selected = gridMode,
                label = stringResource(R.string.lib_view_grid),
                icon = { Icon(imageVector = Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                onClick = { onGridModeChange(true) },
                tag = "library_view_grid"
            )
        }
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ViewModeChip(
    selected: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    tag: String
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = icon,
        colors = FilterChipAccentColors,
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = MaterialTheme.colorScheme.primary
        ),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(tag)
    )
}
