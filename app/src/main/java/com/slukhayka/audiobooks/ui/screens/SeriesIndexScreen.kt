package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.catalog.CatalogSeries
import com.slukhayka.audiobooks.ui.MainViewModel
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.components.CycleCard
import com.slukhayka.audiobooks.ui.components.IndexEmptyState
import com.slukhayka.audiobooks.ui.components.IndexScreenScaffold
import com.slukhayka.audiobooks.ui.theme.*

/**
 * spec-28 (#189) — the «Серії» index: every series aggregated from the
 * catalogue sections (the «Цикли» row), deduplicated by URL. Pushed from the
 * Огляд nav row; tapping a series opens the existing series page
 * ([SeriesScreen]) with its books and universe context. No new data source —
 * the screen only indexes what the catalogue parser already produces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesIndexScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit,
    onSeriesClick: (CatalogSeries) -> Unit,
    restoreFocusSeriesUrl: String? = null,
    onSeriesFocusRestored: (String) -> Unit = {},
    gridState: LazyGridState = rememberLazyGridState()
) {
    val series by viewModel.seriesIndex.collectAsState()

    // spec-28 (#202): the chrome is the shared index scaffold — title, back
    // arrow, insets and container colour live in one place; only the content
    // differs per screen.
    // v1.4 E6 (ADR-0033): the honest count rides the scaffold's subtitle
    // (R10), never a free-standing list row.
    IndexScreenScaffold(
        title = stringResource(R.string.series_index_title),
        onBackClick = onBackClick,
        subtitle = pluralStringResource(R.plurals.series_count, series.size, series.size)
    ) { padding ->
        SeriesIndexContent(
            series = series,
            onSeriesClick = onSeriesClick,
            restoreFocusSeriesUrl = restoreFocusSeriesUrl,
            onSeriesFocusRestored = onSeriesFocusRestored,
            gridState = gridState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

/**
 * The «Серії» index body: a browsable two-column grid of [CatalogSeriesCard]s
 * under a count line, or a no-series placeholder. Public and stateless (pure
 * `@Composable` inputs — no ViewModel) so the snapshot seam pins both the
 * populated grid and the empty state from fixture data.
 */
@Composable
fun SeriesIndexContent(
    series: List<CatalogSeries>,
    onSeriesClick: (CatalogSeries) -> Unit,
    modifier: Modifier = Modifier,
    restoreFocusSeriesUrl: String? = null,
    onSeriesFocusRestored: (String) -> Unit = {},
    gridState: LazyGridState = rememberLazyGridState()
) {
    if (series.isEmpty()) {
        // No-series state: the shared index placeholder, never a crash — the
        // catalogue may simply not have synced yet (spec-28 #202).
        IndexEmptyState(
            message = "Серії з'являться після завантаження каталогу.",
            modifier = modifier.testTag("series_index_screen")
        )
        return
    }

    val returnFocusRequester = remember { FocusRequester() }
    LaunchedEffect(restoreFocusSeriesUrl, series) {
        val url = restoreFocusSeriesUrl ?: return@LaunchedEffect
        val seriesIndex = series.indexOfFirst { it.url == url }
        if (seriesIndex < 0) return@LaunchedEffect
        // The count moved into the scaffold's subtitle (v1.4 E6); cards
        // start at item zero now.
        gridState.scrollToItem(seriesIndex)
        withFrameNanos { }
        if (runCatching { returnFocusRequester.requestFocus() }.getOrDefault(false)) {
            onSeriesFocusRestored(url)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = modifier.testTag("series_index_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = AppDimens.SpaceAboveMiniPlayer),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // The count lives in the scaffold's subtitle (v1.4 E6, ADR-0033).
        items(series, key = { it.url }) { s ->
            // The card is a fixed-width poster (the same shape as the Огляд
            // «Цикли» row); center it inside its grid cell.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // v1.4 C2 (ADR-0033): the canonical CycleCard.
                CycleCard(
                    title = s.title,
                    coverUrl = s.coverImageUrl,
                    onClick = { onSeriesClick(s) },
                    modifier = if (s.url == restoreFocusSeriesUrl) {
                        Modifier.focusRequester(returnFocusRequester)
                    } else {
                        Modifier
                    },
                    testTag = "catalog_series_${s.url.hashCode()}"
                )
            }
        }
    }
}
