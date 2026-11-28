package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.metadata.MetadataAssertions
import com.slukhayka.audiobooks.data.search.SearchIndexNormalize
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
    onRetrySearch: (() -> Unit)? = null,
    searchQuery: String = ""
) {
    val queryTokens = SearchIndexNormalize.titleField(searchQuery).split(' ').filter(String::isNotBlank)
    fun fieldMatches(field: String): Boolean {
        val words = SearchIndexNormalize.titleField(field).split(' ')
        return queryTokens.isNotEmpty() && queryTokens.all { token -> words.any { it.startsWith(token) } }
    }
    val directCount = globalResults.count { result ->
        queryTokens.isEmpty() || result.correctedQuery != null ||
            fieldMatches(listOf(result.title, result.author, result.seriesTitle, result.narrator)
                .plus(result.narrators).joinToString(" "))
    }
    val onlyRelated = liveSearchActive && directCount == 0 && globalResults.isNotEmpty() &&
        !isGlobalLoading && !globalError && localBooks.isEmpty()
    val localEmpty = localBooks.isEmpty()
    // The live lane has settled on a real, honest zero (no spinner, no error).
    val liveSettledEmpty = liveSearchActive && globalResults.isEmpty() && !isGlobalLoading && !globalError
    // Nothing to show anywhere: without this the local and live empty states
    // would stack into two "nothing found" messages.
    val nothingAnywhere = localEmpty && (!liveSearchActive || liveSettledEmpty)

    if (nothingAnywhere || onlyRelated) {
        // F33 — one canonical empty state, not six blocks: no counted
        // «У вашій медіатеці (0)» / «Знайдені книги (0)» headers above a
        // second «нічого не знайдено», and no placeholder glyph — the
        // direction states an absence in type (Literata 20 + Meta), not with
        // an icon standing for something that is not there.
        item(key = "search_no_results") {
            EmptyState(
                title = stringResource(R.string.search1826_no_results),
                body = stringResource(R.string.search1826_no_results_hint),
                modifier = Modifier
                    .testTag("search_no_results")
                    .semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                    }
            )
        }
        if (nothingAnywhere) return
    }

    if (!onlyRelated) {
        item(key = "library_results_header") {
            // #885 (wave 2) — one canonical header instead of a private twin.
            AppSectionHeader(
                title = stringResource(R.string.home_library_results, localBooks.size),
                level = SectionHeaderLevel.SECTION,
                modifier = Modifier.testTag("search_library_header")
            )
        }
        if (localEmpty) {
            // `nothingAnywhere` already returned above, so this branch only runs
            // when live results or a live status exist beside an empty library.
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
        } else {
            items(localBooks, key = { it.id }) { book ->
                BookRow(
                    book = book,
                    onClick = { onOpenLocalBook(book) },
                    onPlayClick = { onPlayLocalBook(book) }
                )
            }
        }

    }
    if (liveSearchActive) {
        // #1553 — the count is a FACT, never a progress hint: while the live
        // search is in flight the header would read «Знайдені книги (0)» right
        // next to «Шукаємо в усіх джерелах» (ADR-0014; web already waits for
        // settlement). The status and the results below must stay visible.
        if (!isGlobalLoading && !onlyRelated) {
            item(key = "all_sources_header") {
                AppSectionHeader(
                    title = stringResource(R.string.home_all_sources, directCount),
                    level = SectionHeaderLevel.SECTION,
                    modifier = Modifier.testTag("search_sources_header")
                )
            }
        }
        if (isGlobalLoading || globalError || globalResults.isEmpty()) {
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
        val groups = globalResults.groupBy { result ->
            when {
                result.correctedQuery != null -> R.string.search1826_correction to result.correctedQuery.orEmpty()
                queryTokens.isEmpty() || fieldMatches(result.title) -> 0 to ""
                fieldMatches(result.author) -> R.string.search1826_author to result.author
                fieldMatches(result.seriesTitle) -> R.string.search1826_series to result.seriesTitle
                (result.narrators + result.narrator).any(::fieldMatches) -> R.string.search1826_narrator to (result.narrators + result.narrator).first(::fieldMatches)
                fieldMatches(listOf(result.title, result.author, result.seriesTitle, result.narrator)
                    .plus(result.narrators).joinToString(" ")) -> 0 to ""
                else -> R.string.search1826_related to ""
            }
        }
        for ((group, results) in groups) {
            if (group.first != 0) {
                item(key = "search_group_${group.first}_${group.second}") {
                    AppSectionHeader(
                        title = stringResource(group.first, group.second),
                        level = SectionHeaderLevel.SECTION,
                        modifier = Modifier.testTag("search_group_${group.first}_${group.second}")
                    )
                }
            }
            items(results, key = { "search_work_${it.key}" }) { result ->
                // #567 v1.4 C3/C4 (ADR-0033): the result row IS the canonical
                // BookRow, built here directly — the named wrapper that used to
                // carry this row (a sixth row style) is gone. Language and
                // provenance chips ride the badges slot; the honest action status
                // stays under the row through CatalogCardStatus.
                LaunchedEffect(result.key) { onPreflightGlobalResult(result) }
                val narrator = result.narrators.mapNotNull(MetadataAssertions::normalizeClaimedText)
                    .distinct().joinToString(", ").ifBlank {
                        MetadataAssertions.normalizeClaimedText(result.narrator).orEmpty()
                    }.takeIf { it.isNotBlank() }
                val knownLanguages = (listOf(result.language) + result.sources.map { it.language })
                    .filter { it.isNotBlank() }.distinct()
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
                        knownLanguages.forEach { language ->
                            MetadataChip(language = language)
                            Spacer(modifier = Modifier.width(AppDimens.SpaceXs))
                        }
                        // Spec-10 T4: which source(s) carry a book.
                        result.sources.forEach { source ->
                            MetadataChip(source = source.sourceName)
                            Spacer(modifier = Modifier.width(AppDimens.SpaceXs))
                        }
                    },
                    footnoteInColumn = true,
                    footnote = {
                        if (narrator != null) {
                            Text(
                                text = stringResource(
                                    R.string.book_detail_narrator_label,
                                    narrator
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    contentDescription = stringResource(R.string.a11y_open_work, result.title),
                    onClick = { onOpenGlobalResult(result) },
                    testTag = "global_search_result_${result.key}"
                )
                CatalogCardStatus(result.key, catalogCardActionState, onOpenCatalogBrowser)
            }
        }
    }

}

