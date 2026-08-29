package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.catalog.CatalogSection
import com.slukhayka.audiobooks.data.catalog.CatalogSectionId
import com.slukhayka.audiobooks.data.collections.CollectionMatcher
import com.slukhayka.audiobooks.data.db.GenreFacetOption
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.recommend.RecommendationEngine
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.PeopleKind
import com.slukhayka.audiobooks.ui.components.NavigationChip

/**
 * spec-28 (#203) — the Огляд feed body, extracted from HomeScreen as a
 * stateless `LazyListScope` emitter so the block order (spec-28 lines
 * 150-153, amended by spec-39) is testable and snapshot-pinned:
 *
 * search → quick navigation → «Для вас» → «Відкрити нове» → sticky controls
 * → endless Work feed. The two group headings carry more visual weight than
 * their individual shelf headings; curated content stays above the feed.
 * Curated content sits above the endless feed; the feed is the final
 * element of the screen. The inline «Колекції» blocks (ADR-0017 closing
 * pass, #203) sit AFTER the 4read sections: they now have a dedicated index
 * screen (US-12, #190), so they must not occupy the marquee position and
 * push the first curated shelf below the fold. The spec-39 shelf takes the
 * position right after the genres — the most personal content leads — and
 * the editorial 4read «Цикли» row hides while it does (a recorded amendment
 * of the spec-28 order line, spec-39 Р1/Р7).
 */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.homeFeedContent(
    isCatalogLoading: Boolean,
    hasLibraryBooks: Boolean,
    sections: List<CatalogSection>,
    genreFacetOptions: List<GenreFacetOption>,
    collections: List<CollectionMatcher.MatchedCollection>,
    newArrivals: List<GlobalSearchResult>,
    recommendedBooks: List<RecommendationEngine.Recommendation>,
    recommendationsReady: Boolean = true,
    personalCycles: List<com.slukhayka.audiobooks.ui.library.PersonalCycle>,
    similarCycles: List<com.slukhayka.audiobooks.ui.library.SimilarCycle> = emptyList(),
    shortBooks: List<CatalogBook>,
    longBooks: List<CatalogBook>,
    workFeedItems: LazyPagingItems<WorkFeedRow>,
    feedGenreFilters: Set<String>,
    feedSortByTitle: Boolean,
    onRefreshCatalog: () -> Unit,
    onGoToLibrary: () -> Unit,
    onOpenTop100: () -> Unit,
    onOpenPeople: (PeopleKind) -> Unit,
    onOpenSeriesIndex: () -> Unit,
    onOpenCollectionsIndex: () -> Unit,
    onOpenSeries: (title: String, url: String) -> Unit,
    onPlayGlobalSearchResult: (GlobalSearchResult) -> Unit,
    onOpenRecommendedBook: (candidateId: String) -> Unit,
    onOpenWorkFeedRow: (WorkFeedRow) -> Unit,
    onBookClick: (String) -> Unit,
    onSetFeedGenreFilters: (Set<String>) -> Unit,
    onSetFeedSortByTitle: (Boolean) -> Unit,
    feedDurationFilters: Set<String> = emptySet(),
    onSetFeedDurationFilters: (Set<String>) -> Unit = {},
    onOpenFeedFilters: (() -> Unit)? = null,
    feedFilterTriggerModifier: Modifier = Modifier,
    onOpenWebSource: (() -> Unit)? = null,
    onRecommendationFeedback: (RecommendationEngine.Recommendation, String) -> Unit = { _, _ -> },
    showRecommendationConsent: Boolean = false,
    onOpenRecommendationConsent: () -> Unit = {},
    onDeclineRecommendationConsent: () -> Unit = {},
    recommendationDisclosureTriggerModifier: Modifier = Modifier
) {
    // Loading spinner while the catalogue syncs on a fresh start.
    if (isCatalogLoading && !hasLibraryBooks && sections.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                    }
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.a11y_catalogue_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // Empty catalogue (first run, no network): actionable state.
    if (!isCatalogLoading && sections.isEmpty() && !hasLibraryBooks) {
        item {
            EmptyCatalogState(
                onRefreshClick = onRefreshCatalog,
                onImportClick = onGoToLibrary
            )
        }
    }

    // Catalogue navigation — the site's header menu: ТОП 100,
    // Виконавці (narrators) and Автори (authors), plus the spec-28
    // «Серії» (#189) and «Колекції» (#190) indexes. ADR-0018: these
    // NAVIGATE, so they are NavigationChips (filled, no outline) —
    // never filter-shaped chips.
    item {
        CatalogRowHeader(title = "Швидкі переходи")
    }
    item {
        CatalogNavRow(
            onTop100Click = onOpenTop100,
            onPeopleClick = onOpenPeople,
            onSeriesClick = onOpenSeriesIndex,
            onCollectionsClick = onOpenCollectionsIndex
        )
    }

    val hasForYouContent = personalCycles.isNotEmpty() ||
        similarCycles.isNotEmpty() || recommendedBooks.isNotEmpty() || showRecommendationConsent
    item { OverviewGroupHeader(title = "Для вас") }
    if (!hasForYouContent) {
        item {
            Text(
                text = if (recommendationsReady) {
                    "Персональних добірок поки немає."
                } else {
                    "Готуємо персональні добірки…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }

    // Spec-39 T1 (#261): «Ваші цикли» — the listener's own cycles, built
    // purely from the local base (no network, no loading states). It starts
    // the «Для вас» group; while it renders, the editorial 4read «Цикли»
    // shelf below is skipped (gradual replacement, spec-39 Р1).
    if (personalCycles.isNotEmpty()) {
        item {
            CatalogRowHeader(title = "Ваші цикли")
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(personalCycles, key = { it.url }) { cycle ->
                    PersonalCycleCard(
                        cycle = cycle,
                        onClick = { onOpenSeries(cycle.title, cycle.url) }
                    )
                }
            }
        }
    }

    // Spec-39 T2 (#262): «схожі цикли» — the recommendation engine's top
    // picks lifted to serial identity, own cycles excluded. Same card form
    // as «Ваші цикли», the magnet line is the reason chip («схоже на X»).
    // Best-effort: an empty tier renders nothing at all.
    if (similarCycles.isNotEmpty()) {
        item {
            CatalogRowHeader(title = "Схожі цикли")
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(similarCycles, key = { it.url }) { cycle ->
                    SimilarCycleCard(
                        cycle = cycle,
                        onClick = { onOpenSeries(cycle.title, cycle.url) }
                    )
                }
            }
        }
    }

    // Spec-19 Track A: «Рекомендовано для вас» — on-device, local
    // only. Each card carries a reason chip («схоже на X»); tapping
    // opens the book page through the same identity resolution as
    // any other Огляд row (import the Work, then the native page).
    if (recommendedBooks.isNotEmpty()) {
        item {
            CatalogRowHeader(title = "Рекомендовано для вас")
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(recommendedBooks, key = { it.candidate.id }) { rec ->
                    RecommendedBookCard(
                        rec = rec,
                        onClick = { onOpenRecommendedBook(rec.candidate.id) },
                        onFeedback = { kind -> onRecommendationFeedback(rec, kind) }
                    )
                }
            }
        }
    }
    if (showRecommendationConsent) {
        item(key = "recommendation_consent_card") {
            ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Допомогти рекомендаціям ставати кращими?", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Локальні рекомендації вже працюють приватно. За окремою згодою майбутнє спільне навчання використовуватиме лише тижневе оновлення п’яти ваг — без книг та історії.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDeclineRecommendationConsent) { Text("Не зараз") }
                        TextButton(
                            onClick = onOpenRecommendationConsent,
                            modifier = recommendationDisclosureTriggerModifier
                        ) { Text("Докладніше") }
                    }
                }
            }
        }
    }

    // Editorial and catalogue shelves form the second top-level group.
    item { OverviewGroupHeader(title = "Відкрити нове") }

    // spec-28 (#192): «Новинки» — the ONE cross-source new-arrivals
    // rail (4read's «Новинки» section + every other source's feed,
    // merged by Work, a source badge per card), re-homed from
    // Слухати. Tapping a card resolves-and-plays exactly like the
    // global-search cards. The «Новинки» catalogue section is skipped
    // below so 4read's new arrivals appear exactly once on the screen.
    if (newArrivals.isNotEmpty()) {
        item {
            NewArrivalsRail(
                results = newArrivals,
                onBookClick = { result -> onPlayGlobalSearchResult(result) }
            )
        }
    }

    // spec-18 T3: «За тривалістю» — «Короткі» and «Довгі» cover rows
    // fed by the bucketed duration rows. Hidden entirely when no book
    // has a known duration yet; the rows grow as durations arrive.
    item {
        DurationSection(
            shortBooks = shortBooks,
            longBooks = longBooks,
            onBookClick = onBookClick
        )
    }

    // Catalogue rows parsed from the 4read.org homepage. Spec-9: the
    // Continue-Listening card moved to the Слухати tab. Spec-28
    // (#192): the «Новинки» section is superseded by the cross-source
    // rail above — 4read's new arrivals must appear exactly once.
    sections.forEach { section ->
        // Spec-28 (#197): the skip matches the typed section id — a
        // rename of the section title in the parser can never render
        // 4read's new arrivals twice (rail + section row).
        if (section.id == CatalogSectionId.NEW_ARRIVALS) return@forEach
        // Spec-39 Р1: while «Ваші цикли» renders above, the editorial
        // 4read «Цикли» row is noise — skipped by typed id, same doctrine.
        if (section.id == CatalogSectionId.SERIES && personalCycles.isNotEmpty()) return@forEach
        if (section.books.isNotEmpty()) {
            item {
                CatalogRowHeader(title = section.title)
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(section.books, key = { it.id }) { book ->
                        CatalogBookCard(
                            book = book,
                            onClick = { onBookClick(book.id) }
                        )
                    }
                }
            }
        }
        if (section.series.isNotEmpty()) {
            item {
                CatalogRowHeader(title = section.title)
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(section.series, key = { it.url }) { series ->
                        CatalogSeriesCard(
                            series = series,
                            onClick = { onOpenSeries(series.title, series.url) }
                        )
                    }
                }
            }
        }
    }

    // Spec-16: «Колекції» — one horizontal cover row per matched curated
    // collection (Нобелівські лауреати, Шевченківська премія, Букер),
    // reusing the uniform cover-card look of the other Огляд rows. Tapping
    // a card resolves the Work like any other global-search card
    // (import-and-play). ADR-0017 closing pass (#203): these blocks render
    // AFTER the 4read sections — the «Колекції» chip in the nav row opens
    // the dedicated index (US-12), and the marquee «Рекомендовано для вас»
    // must be the first curated shelf per the spec order line. Empty
    // collections are already absent from the flow; when all are empty the
    // whole block disappears.
    if (collections.isNotEmpty()) {
        collections.forEach { collection ->
            item {
                CatalogRowHeader(title = collection.name)
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(collection.books, key = { it.key }) { result ->
                        CollectionBookCard(
                            result = result,
                            onClick = { onPlayGlobalSearchResult(result) }
                        )
                    }
                }
            }
        }
    }

    // spec-28 (#192): «Більше книг на Sluhay» moved from Слухати to
    // Огляд as a compact exit CTA, not a content shelf. Debug builds
    // open the in-app browser surface; release hides the row (the
    // same debug-gating as on Listen, spec-13 T3/T2).
    if (onOpenWebSource != null) {
        item {
            OpenWebSourceRow(
                displayName = "Sluhay",
                onClick = onOpenWebSource
            )
        }
    }

    // spec-42 T1 (#302): compact controls are the one home for feed sort and
    // genre. The persisted, Room-backed Work Pager remains the LAST element
    // of Огляд, so the curated shelves above never drown in the endless list.
    stickyHeader(key = "work_feed_controls") {
        WorkFeedFilters(
            selectedGenreIds = feedGenreFilters,
            selectedDurationBucketIds = feedDurationFilters,
            sortByTitle = feedSortByTitle,
            genres = genreFacetOptions,
            onGenresChange = onSetFeedGenreFilters,
            onDurationBucketsChange = onSetFeedDurationFilters,
            onSortChange = onSetFeedSortByTitle,
            onOpenFilters = onOpenFeedFilters,
            filterTriggerModifier = feedFilterTriggerModifier
        )
    }
    if (workFeedItems.itemCount == 0 && workFeedItems.loadState.refresh is LoadState.Loading) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    // Paging-compose 3.3 removed LazyListScope.items(LazyPagingItems);
    // iterate the paged index with the official itemKey/itemContentType
    // helpers (placeholders disabled, so rows are non-null).
    items(
        count = workFeedItems.itemCount,
        key = workFeedItems.itemKey { it.workId },
        contentType = workFeedItems.itemContentType { "WorkFeedRow" }
    ) { index ->
        workFeedItems[index]?.let { row ->
            WorkFeedCard(
                row = row,
                onClick = { onOpenWorkFeedRow(row) }
            )
        }
    }
    when (val append = workFeedItems.loadState.append) {
        is LoadState.Loading -> item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        is LoadState.Error -> item {
            Text(
                text = stringResource(R.string.a11y_catalogue_page_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        else -> Unit
    }
}
