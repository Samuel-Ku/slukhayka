package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * The visible segmented status row: five one-tap statuses, horizontally
 * scrollable so a narrow screen never wraps them onto a second line (design
 * guide §6.3). A plain `Row` + `horizontalScroll` (not a `LazyRow`) so every
 * chip is always composed — taps and snapshots see all five statuses.
 */
@Composable
fun LibraryStatusRow(
    selected: LibraryFilter,
    onSelect: (LibraryFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .testTag("library_status_row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        STATUS_FILTERS.forEach { f ->
            val isSelected = selected == f
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(f) },
                label = { Text(f.label) },
                colors = FilterChipAccentColors,
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("library_status_${f.name.lowercase()}")
            )
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
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .testTag("library_filter_sheet_content")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Фільтр та сортування",
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
        SheetSectionLabel("Фільтр")
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SHEET_FILTERS.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { onFilterChange(f) },
                    label = { Text(f.label) },
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
        SheetSectionLabel("Сортування")
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
                        text = s.label,
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
        SheetSectionLabel("Вигляд")
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ViewModeChip(
                selected = !gridMode,
                label = "Список",
                icon = { Icon(imageVector = Icons.Default.ViewList, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                onClick = { onGridModeChange(false) },
                tag = "library_view_list"
            )
            ViewModeChip(
                selected = gridMode,
                label = "Сітка",
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
