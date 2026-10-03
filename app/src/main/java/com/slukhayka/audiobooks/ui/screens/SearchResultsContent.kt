package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.slukhayka.audiobooks.ui.components.SectionHeaderLevel
import com.slukhayka.audiobooks.ui.components.AppSectionHeader
import com.slukhayka.audiobooks.ui.components.BookRow
import com.slukhayka.audiobooks.ui.components.EmptyState
import com.slukhayka.audiobooks.ui.components.MetadataChip
import com.slukhayka.audiobooks.ui.components.formatRowDuration
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * #737 / ADR-0041 — the search surface's two sections, extracted from
 * [HomeScreen] as a stateless `LazyListScope` emitter so their order and
 * their honest empty states are testable without a ViewModel:
 *
 * 1. «У вашій медіатеці» — the listener's own rows, always first, offline.
 * 2. «Знайдені книги» — the local index preview, supplemented by every source;
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
    onPreflightGlobalResult: (GlobalSearchResult) -> Unit,
    onRetrySearch: (() -> Unit)? = null
) {
    val localEmpty = localBooks.isEmpty()
    // The live lane has settled on a real, honest zero (no spinner, no error).
    val liveSettledEmpty = liveSearchActive && globalResults.isEmpty() && !isGlobalLoading && !globalError
    // Nothing to show anywhere: without this the local and live empty states
    // would stack into two "nothing found" messages.
    val nothingAnywhere = localEmpty && (!liveSearchActive || liveSettledEmpty)

    item(key = "library_results_header") {
        // #885 (wave 2) — one canonical header instead of a private twin.
        AppSectionHeader(
            title = stringResource(R.string.home_library_results, localBooks.size),
            level = SectionHeaderLevel.SECTION,
            modifier = Modifier.testTag("search_library_header")
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
            AppSectionHeader(
                title = stringResource(R.string.home_all_sources, globalResults.size),
                level = SectionHeaderLevel.SECTION,
                modifier = Modifier.testTag("search_sources_header")
            )
        }
        if (isGlobalLoading || globalError || (globalResults.isEmpty() && !nothingAnywhere)) {
            item(key = "all_sources_status") {
                GlobalSearchStatus(
                    isLoading = isGlobalLoading,
                    hasError = globalError,
                    resultsEmpty = globalResults.isEmpty()
                )
                if (globalError && onRetrySearch != null) {
                    TextButton(
                        onClick = onRetrySearch,
                        modifier = Modifier.padding(horizontal = 16.dp).testTag("search_retry")
                    ) { Text(stringResource(R.string.home_search_retry)) }
                }
            }
        }
        if (!isGlobalLoading && !globalError) {
            item(key = "search_scope_note") {
                Text(
                    text = stringResource(R.string.home_search_partial_catalogue),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("search_scope_note")
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
        items(globalResults, key = { it.key }) { result ->
            // #567 v1.4 C3/C4 (ADR-0033): the result row IS the canonical
            // BookRow, built here directly — the named wrapper that used to
            // carry this row (a sixth row style) is gone. Language and
            // provenance chips ride the badges slot; the honest action status
            // stays under the row through CatalogCardStatus.
            LaunchedEffect(result.key) { onPreflightGlobalResult(result) }
            BookRow(
                title = result.title,
                coverUrl = result.coverImageUrl,
                author = result.author.takeIf { it.isNotBlank() },
                // Spec-30 T2 (#217): the resolved duration when one is known
                // (the local database or the shared metadata cache).
                stats = result.durationSeconds?.takeIf { it > 0L }?.let { formatRowDuration(it) },
                badges = {
                    // Spec-45 (#405) T7 (#495): the card's rendition language —
                    // one EN/UA chip; unknown renders nothing (US3).
                    if (result.language.isNotBlank()) {
                        MetadataChip(language = result.language)
                        Spacer(modifier = Modifier.width(AppDimens.SpaceXs))
                    }
                    // Spec-10 T4: which source(s) carry a book.
                    result.sources.forEach { source ->
                        MetadataChip(source = source.sourceName)
                        Spacer(modifier = Modifier.width(AppDimens.SpaceXs))
                    }
                },
                contentDescription = stringResource(R.string.a11y_open_work, result.title),
                onClick = { onOpenGlobalResult(result) },
                testTag = "global_search_result_${result.key}"
            )
            CatalogCardStatus(result.key, catalogCardActionState, onOpenCatalogBrowser)
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

