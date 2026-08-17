package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.catalog.CatalogGenre
import com.slukhayka.audiobooks.data.catalog.CatalogSection
import com.slukhayka.audiobooks.data.catalog.CatalogSectionId
import com.slukhayka.audiobooks.data.collections.CollectionMatcher
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.recommend.RecommendationEngine
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.ui.PeopleKind
import com.slukhayka.audiobooks.ui.components.NavigationChip

/**
 * spec-28 (#203) — the Огляд feed body, extracted from HomeScreen as a
 * stateless `LazyListScope` emitter so the block order (spec-28 lines
 * 150-153) is testable and snapshot-pinned:
 *
 * search → 5-chip nav row → genres → [Колекції] → Рекомендовано для вас →
 * «Новинки» → За тривалістю → 4read sections → «Більше книг на Sluhay» CTA
 * → «Весь каталог» (always last). Curated content sits above the endless
 * feed; the feed is the final element of the screen.
 */
fun LazyListScope.homeFeedContent(
    isCatalogLoading: Boolean,
    hasLibraryBooks: Boolean,
    sections: List<CatalogSection>,
    catalogGenres: List<CatalogGenre>,
    collections: List<CollectionMatcher.MatchedCollection>,
    newArrivals: List<GlobalSearchResult>,
    recommendedBooks: List<RecommendationEngine.Recommendation>,
    shortBooks: List<CatalogBook>,
    longBooks: List<CatalogBook>,
    workFeedItems: LazyPagingItems<WorkFeedRow>,
    feedSourceFilter: String?,
    feedGenreFilter: String?,
    feedSortByTitle: Boolean,
    onRefreshCatalog: () -> Unit,
    onGoToLibrary: () -> Unit,
    onOpenTop100: () -> Unit,
    onOpenPeople: (PeopleKind) -> Unit,
    onOpenSeriesIndex: () -> Unit,
    onOpenCollectionsIndex: () -> Unit,
    onOpenGenre: (title: String, url: String) -> Unit,
    onOpenSeries: (title: String, url: String) -> Unit,
    onPlayGlobalSearchResult: (GlobalSearchResult) -> Unit,
    onOpenRecommendedBook: (candidateId: String) -> Unit,
    onOpenWorkFeedRow: (WorkFeedRow) -> Unit,
    onBookClick: (String) -> Unit,
    onSetFeedSourceFilter: (String?) -> Unit,
    onSetFeedGenreFilter: (String?) -> Unit,
    onSetFeedSortByTitle: (Boolean) -> Unit,
    onOpenWebSource: (() -> Unit)? = null
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Завантажуємо каталог...",
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
        CatalogRowHeader(title = "Каталог")
    }
    item {
        CatalogNavRow(
            onTop100Click = onOpenTop100,
            onPeopleClick = onOpenPeople,
            onSeriesClick = onOpenSeriesIndex,
            onCollectionsClick = onOpenCollectionsIndex
        )
    }

    // Genre navigation ("Аудіокниги жанру:") — chips that open the
    // genre's own book list, mirroring the site's primary sidebar nav.
    if (catalogGenres.isNotEmpty()) {
        item {
            CatalogRowHeader(title = "Жанри")
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(catalogGenres, key = { it.url }) { genre ->
                    // The genre row NAVIGATES (opens the genre list) —
                    // NavigationChip per ADR-0018; the genre FILTERS
                    // inside «Весь каталог» stay FilterChips.
                    NavigationChip(
                        title = genre.title,
                        onClick = { onOpenGenre(genre.title, genre.url) }
                    )
                }
            }
        }
    }

    // Spec-16: «Колекції» — one horizontal cover row per matched
    // curated collection (Нобелівські лауреати, Шевченківська
    // премія, Букер), reusing the uniform cover-card look of the
    // other Огляд rows. Tapping a card resolves the Work like any
    // other global-search card (import-and-play). Empty collections
    // are already absent from the flow; when all are empty the whole
    // block disappears.
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
                        onClick = { onOpenRecommendedBook(rec.candidate.id) }
                    )
                }
            }
        }
    }

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

    // Spec-23 T4: the endless merged feed — every Work in the
    // persisted catalogue, paged via Paging 3. It supersedes the
    // spec-15 T1 ephemeral union: the same merge key / one card per
    // Work, but scrolling pages through the whole catalogue instead
    // of stopping at the session snapshot. Filters (source / genre /
    // sort) rebuild the Pager; the row header shows the live count.
    // spec-28 (#203): always the LAST element of Огляд — the curated
    // shelves above never drown in the endless list.
    item {
        CatalogRowHeader(title = "Весь каталог")
    }
    item {
        WorkFeedFilters(
            sourceFilter = feedSourceFilter,
            genreFilter = feedGenreFilter,
            sortByTitle = feedSortByTitle,
            genres = catalogGenres.map { it.title },
            onSourceChange = onSetFeedSourceFilter,
            onGenreChange = onSetFeedGenreFilter,
            onSortToggle = { onSetFeedSortByTitle(!feedSortByTitle) }
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
                text = "Не вдалося завантажити ще: ${append.error.message.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
        }
        else -> Unit
    }
}
