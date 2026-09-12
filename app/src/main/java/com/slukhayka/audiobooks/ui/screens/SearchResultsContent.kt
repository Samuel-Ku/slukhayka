package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionState
import com.slukhayka.audiobooks.ui.components.BookRow
import com.slukhayka.audiobooks.ui.components.EmptyState

/**
 * #737 / ADR-0041 — the search surface's two sections, extracted from
 * [HomeScreen] as a stateless `LazyListScope` emitter so their order and
 * their honest empty states are testable without a ViewModel:
 *
 * 1. «У вашій медіатеці» — the listener's own rows, always first, offline.
 * 2. «Усі джерела» — the live Source Catalog results, BELOW the local ones;
 *    this is the only surface that renders a live source card directly.
 *
 * A source hit is imported through the ordinary door on tap (the caller's
 * [onOpenGlobalResult] rides the catalog-card coordinator). The big
 * «Нічого не знайдено» state appears exactly once, and only when the whole
 * search is honestly empty — not under a local header while live results
 * are already on screen.
 */
fun LazyListScope.searchResultsContent(
    localBooks: List<AudiobookEntity>,
    globalResults: List<GlobalSearchResult>,
    liveSearchActive: Boolean,
    isGlobalLoading: Boolean,
    globalError: Boolean,
    onOpenLocalBook: (AudiobookEntity) -> Unit,
    onPlayLocalBook: (AudiobookEntity) -> Unit,
    onOpenGlobalResult: (GlobalSearchResult) -> Unit,
    catalogCardActionState: CatalogCardActionState,
    onOpenCatalogBrowser: () -> Unit,
    onPreflightGlobalResult: (GlobalSearchResult) -> Unit
) {
    val localEmpty = localBooks.isEmpty()
    // The live lane has settled on a real, honest zero (no spinner, no error).
    val liveSettledEmpty = liveSearchActive && globalResults.isEmpty() && !isGlobalLoading && !globalError
    // Nothing to show anywhere: without this the local and live empty states
    // would stack into two "nothing found" messages.
    val nothingAnywhere = localEmpty && (!liveSearchActive || liveSettledEmpty)

    item(key = "library_results_header") {
        SearchSectionHeader(
            text = stringResource(R.string.home_library_results, localBooks.size),
            testTag = "search_library_header"
        )
    }
    if (localEmpty) {
        if (!nothingAnywhere) {
            item(key = "library_results_empty") {
                Text(
                    text = stringResource(R.string.home_library_no_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("search_library_empty")
                )
            }
        }
    } else {
        items(localBooks, key = { it.id }) { book ->
            BookRow(
                book = book,
                onClick = { onOpenLocalBook(book) },
                onPlayClick = { onPlayLocalBook(book) }
            )
        }
    }

    if (liveSearchActive) {
        item(key = "all_sources_header") {
            SearchSectionHeader(
                text = stringResource(R.string.home_all_sources, globalResults.size),
                testTag = "search_sources_header"
            )
        }
        if (globalResults.isEmpty() && !nothingAnywhere) {
            item(key = "all_sources_status") {
                GlobalSearchStatus(
                    isLoading = isGlobalLoading,
                    hasError = globalError,
                    resultsEmpty = true
                )
            }
        }
        items(globalResults, key = { it.key }) { result ->
            GlobalSearchResultCard(
                result = result,
                onClick = { onOpenGlobalResult(result) },
                actionState = catalogCardActionState,
                onOpenBrowser = onOpenCatalogBrowser,
                onPreflight = { onPreflightGlobalResult(result) }
            )
        }
    }

    if (nothingAnywhere) {
        item(key = "search_no_results") {
            EmptyState(
                icon = Icons.Default.SearchOff,
                title = stringResource(R.string.home_search_no_results),
                body = stringResource(R.string.home_search_no_results_hint),
                modifier = Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                }
            )
        }
    }
}

@Composable
private fun SearchSectionHeader(text: String, testTag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(testTag)
            .semantics { heading() }
    )
}
