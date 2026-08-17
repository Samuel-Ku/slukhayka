package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.catalog.CatalogSeries
import com.slukhayka.audiobooks.ui.MainViewModel
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
    onSeriesClick: (CatalogSeries) -> Unit
) {
    val series by viewModel.seriesIndex.collectAsState()

    // spec-28 (#202): the chrome is the shared index scaffold — title, back
    // arrow, insets and container colour live in one place; only the content
    // differs per screen.
    IndexScreenScaffold(title = "Серії", onBackClick = onBackClick) { padding ->
        SeriesIndexContent(
            series = series,
            onSeriesClick = onSeriesClick,
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
    modifier: Modifier = Modifier
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

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.testTag("series_index_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "${series.size} серій",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        items(series, key = { it.url }) { s ->
            // The card is a fixed-width poster (the same shape as the Огляд
            // «Цикли» row); center it inside its grid cell.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CatalogSeriesCard(series = s, onClick = { onSeriesClick(s) })
            }
        }
    }
}
