package com.slukhayka.audiobooks.data.catalog

import android.util.Log
import androidx.paging.PagingSource
import com.slukhayka.audiobooks.data.authors.AuthorIndex
import com.slukhayka.audiobooks.data.authors.AuthorSummary
import com.slukhayka.audiobooks.data.authors.RoomAuthorIndex
import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import com.slukhayka.audiobooks.data.metadata.FacetPageLimits
import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.facets.FacetDeltaSync
import com.slukhayka.audiobooks.data.facets.FacetSyncCursorStore
import com.slukhayka.audiobooks.data.facets.GenreFacetAssertion
import com.slukhayka.audiobooks.data.facets.GenreSourceFacetReplacement
import com.slukhayka.audiobooks.data.facets.LocalFacetDelta
import com.slukhayka.audiobooks.data.facets.LocalFacetWriter
import com.slukhayka.audiobooks.data.facets.RoomLocalFacetWriter
import com.slukhayka.audiobooks.data.facets.WorkFacetDelta
import com.slukhayka.audiobooks.data.facets.WorkFacetFilter
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.metadata.EditionDurationPolicy
import com.slukhayka.audiobooks.data.metadata.MetadataAssertions
import com.slukhayka.audiobooks.data.metadata.SearchCoverResolver
import com.slukhayka.audiobooks.data.metadata.SearchDurationResolver
import com.slukhayka.audiobooks.data.search.SearchCache
import com.slukhayka.audiobooks.data.source.FourReadAdapter
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceIds
import com.slukhayka.audiobooks.data.source.SourceAccessCandidate
import com.slukhayka.audiobooks.data.source.SourceAccessPolicy
import com.slukhayka.audiobooks.data.source.mergeGlobalSearchResults
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import com.slukhayka.audiobooks.data.source.streamOnlyFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ADR-0002 — Source Catalog: the deep module that owns browse and sync —
 * sections, genres, series listings, top-100, people, source feeds, the
 * unified catalogue union, global search and catalogue hydration, plus every
 * cache behind them (new-feed TTL cache, per-adapter catalogue cache,
 * series/top-100/people caches).
 *
 * DAG edge (ticket #138): Source Catalog → [LibraryImport] — the catalogue
 * doors persist through the shared import path ([LibraryImport.upsertCatalogBook]
 * for browse-upserts, [LibraryImport.importBookFromSource] for hydration).
 * Constructing the module performs NO network I/O — the composition root makes
 * one explicit sync call ([fetchCatalogSections] / [refreshUnifiedCatalog] /
 * [refreshSourceFeeds]) when the app wants sync.
 */
class SourceCatalog(
    private val dao: AudiobookDao,
    private val sourceAdapters: List<SourceAdapter>,
    private val libraryImport: LibraryImport,
    // The 4read transport/parser behind the Explore doors (homepage/series/
    // top-100/people). Same HttpFetcher the adapters use; the module owns no
    // HTTP client of its own. Injectable so the hydration tests serve canned
    // pages without network.
    private val fourReadFetcher: HttpFetcher = HttpFetcher(referer = "https://4read.org/"),
    // Spec-16: the curated smart-collection lists, loaded at the composition
    // root through the context seam (CollectionAssets). Empty in tests that
    // don't exercise collections; a list asset is pure data — adding one is
    // a JSON file, never a code change.
    private val collectionLists: List<com.slukhayka.audiobooks.data.collections.CollectionList> = emptyList(),
    // Spec-16 follow-up: LIVE collection sources (bestsellers/trending)
    // fetched over the shared HTTP transport on the union refresh. Best-effort
    // per source — a failing source contributes no collection, never breaks
    // the refresh. Empty in tests that don't exercise live lists.
    private val liveCollectionSources: List<com.slukhayka.audiobooks.data.collections.LiveCollectionSource> = emptyList(),
    // Spec-30 T2 (#217): the client-first duration resolver for search cards
    // (local DB → shared Firestore cache, fill-the-gap + mirror). Null in
    // tests that don't exercise durations — search then behaves exactly as
    // before (no duration on cards).
    private val durationResolver: SearchDurationResolver? = null,
    // Spec-30 T3 (#218): the client-first COVER resolver for search cards
    // (local row cover → shared Firestore cache, fill-the-gap + mirror via
    // the existing cover write path). Null in tests that don't exercise
    // covers — search then behaves exactly as before.
    private val coverResolver: SearchCoverResolver? = null,
    // Spec-33 T2 (#227): the shared search-result cache. Search consults it
    // first — a fresh hit returns the merged result without touching the
    // source adapters; a miss or a stale entry resolves live and writes the
    // result back best-effort. Null without Firebase keys (or in tests that
    // don't exercise the cache): search then behaves exactly as before.
    private val searchCache: SearchCache? = null,
    // Catalogue-hydration batch seam: a crawl runs each page's merge-on-write
    // block through this runner so N upserts land as ONE Room transaction —
    // one invalidation of the endless feed's PagingSource instead of two per
    // row. Row-by-row invalidations starve every freshly-switched feed
    // generation, which is exactly why the feed's filters looked dead while
    // the catalogue kept syncing. Identity by default; the composition root
    // supplies the real Room withTransaction.
    private val writeBatchRunner: suspend (suspend () -> Unit) -> Unit = { it() },
    private val sharedFacetStore: SharedBookMetaStore? = null,
    private val facetSyncCursorStore: FacetSyncCursorStore? = null,
    private val facetSyncNowMillis: () -> Long = System::currentTimeMillis
) {
    /** Frozen local-write seam consumed by the later shared delta lane. */
    val facetWriter: LocalFacetWriter = RoomLocalFacetWriter(dao)

    private val facetDeltaSync: FacetDeltaSync? =
        if (sharedFacetStore != null && facetSyncCursorStore != null) {
            FacetDeltaSync(sharedFacetStore, facetWriter, facetSyncCursorStore, facetSyncNowMillis)
        } else {
            null
        }

    /** One bounded chain per active Огляд composition; all interactions remain local. */
    suspend fun syncSharedFacets(
        pageSize: Int = FacetPageLimits.MAX_PAGE_SIZE,
        maxPages: Int = 20
    ): FacetDeltaSync.ChainResult =
        facetDeltaSync?.syncAvailablePages(pageSize, maxPages) ?: FacetDeltaSync.ChainResult(0, 0)

    /** Bounded local options for the filter sheet; never a Work materialization. */
    val genreFacetOptions = dao.observeGenreFacetOptions()

    /** Canonical cross-Source author read model; provider people pages never own it. */
    private val authorIndex: AuthorIndex = RoomAuthorIndex(dao)
    val authors = authorIndex.authors

    suspend fun searchAuthors(query: String, limit: Int = AuthorIndex.DEFAULT_SEARCH_LIMIT): List<AuthorSummary> =
        authorIndex.search(query, limit)

    suspend fun authorWorks(authorId: String): List<WorkEntity> = authorIndex.works(authorId)

    suspend fun authorForWork(workId: String): AuthorSummary? = authorIndex.authorForWork(workId)

    private val fourReadAdapter: SourceAdapter =
        sourceAdapters.firstOrNull { it.sourceId == SourceIds.FOUR_READ } ?: FourReadAdapter()

    // ---------------------------------------------------------------------
    // Global search (spec-10 T4)
    // ---------------------------------------------------------------------

    // Per-source «new arrivals» feeds are cached in memory so repeated search
    // keystrokes never re-fetch the same homepage; the cache is a session
    // convenience, safe to lose.
    private class CachedFeed(val fetchedAt: Long, val books: List<SourceBook>)

    private val newFeedCache = java.util.concurrent.ConcurrentHashMap<String, CachedFeed>()

    /** TTL of the in-memory per-source «new arrivals» feed cache (spec-10 T4). */
    private val newFeedTtlMs = 15 * 60 * 1000L

    /**
     * One «Нове з джерела» feed row (spec-10 T5): a source and its recent
     * books. For a session-bound source (spec-13 T4, WebView pattern) with no
     * live session the books stay empty and [sessionBound] is set — the UI
     * renders a «відкрити джерело, щоб оновити» CTA instead of dead data.
     */
    data class SourceNewFeed(
        val sourceId: String,
        val sourceName: String,
        val books: List<SourceBook>,
        val sessionBound: Boolean = false
    )

    // Spec-10 T5: per-source «Нове з кожного джерела» rows for the Listen
    // surface. 4read is excluded on purpose — its new-arrivals are already
    // rendered by the existing «Нове на 4read» rows (spec-9), which carry the
    // richer curated sections (incl. series) from the homepage parse.
    private val feedAdapters: List<SourceAdapter> = sourceAdapters.filterNot { it.sourceId == SourceIds.FOUR_READ }

    // Spec-15 T1: the unified catalogue union. 4read is excluded the same way
    // as the feeds — its full catalogue is natively browsed through the Огляд
    // sections (spec #8), so the union covers the other verified sources and
    // merges their catalogue enumeration into Work cards via MergeKey.
    private val catalogueAdapters: List<SourceAdapter> = feedAdapters

    private val _unifiedCatalog = MutableStateFlow<List<GlobalSearchResult>>(emptyList())
    val unifiedCatalog: StateFlow<List<GlobalSearchResult>> = _unifiedCatalog.asStateFlow()

    // Spec-16 T2: the matched smart collections — recomputed from the union
    // on every refreshUnifiedCatalog (the SAME trigger that recomputes the
    // union itself), so a newly enumerated book appears in its collection
    // after the next catalog refresh. Computed, never stored — no Room rows,
    // no schema change. Empty collections are absent from the flow.
    private val _smartCollections =
        MutableStateFlow<List<com.slukhayka.audiobooks.data.collections.CollectionMatcher.MatchedCollection>>(emptyList())
    val smartCollections: StateFlow<List<com.slukhayka.audiobooks.data.collections.CollectionMatcher.MatchedCollection>> =
        _smartCollections.asStateFlow()

    // Spec-39 T1 (#261): every locally known Work — the library ∪ synced
    // catalogue union rows. The honest Y of the «Ваші цикли» shelf counts
    // against this base; the flow is read-only, nothing here persists.
    val allWorks: kotlinx.coroutines.flow.Flow<List<WorkEntity>> = dao.observeWorks()

    // Spec-16 follow-up: the last fetched LIVE collections (static + live are
    // matched together; this flow lets tests pin what a live source actually
    // contributed). TTL-cached per source like the feeds — repeated refreshes
    // within the TTL reuse the session's lists instead of re-fetching.
    private class CachedLiveList(val fetchedAt: Long, val lists: List<com.slukhayka.audiobooks.data.collections.CollectionList>)
    private val liveCollectionCache = java.util.concurrent.ConcurrentHashMap<String, CachedLiveList>()
    private val _liveCollections = MutableStateFlow<List<com.slukhayka.audiobooks.data.collections.CollectionList>>(emptyList())
    val liveCollections: StateFlow<List<com.slukhayka.audiobooks.data.collections.CollectionList>> =
        _liveCollections.asStateFlow()

    private val _isUnifiedCatalogLoading = MutableStateFlow(false)
    val isUnifiedCatalogLoading: StateFlow<Boolean> = _isUnifiedCatalogLoading.asStateFlow()

    // Per-adapter catalogue enumeration cache (same shape/TTL as the new-feed
    // cache): repeated Огляд visits reuse the session's enumeration instead of
    // re-walking every category page. The merged union is recomputed from the
    // cached lists (cheap in-memory merge), never re-fetched.
    private val adapterCatalogCache = java.util.concurrent.ConcurrentHashMap<String, CachedFeed>()

    /**
     * Spec-15 T1 — the deduplicated «Увесь каталог» union: every verified
     * source's catalogue enumeration (category/genre pages) merged into one
     * Work card per book via [mergeGlobalSearchResults] (the same MergeKey
     * rule import and search use). Ephemeral — nothing is imported until the
     * user taps a card. Per-adapter results are cached for the session like
     * the feeds; a session-bound adapter (WebView pattern) always re-enumerates
     * so a fresh challenge session surfaces in the union immediately — never
     * a stale empty cache.
     */
    suspend fun refreshUnifiedCatalog(limit: Int = 60): List<GlobalSearchResult> =
        withContext(Dispatchers.IO) {
            _isUnifiedCatalogLoading.value = true
            try {
                val books = mutableListOf<SourceBook>()
                for (adapter in catalogueAdapters) {
                    books += catalogueFor(adapter, limit)
                }
                val merged = mergeGlobalSearchResults(books)
                _unifiedCatalog.value = merged
                // Spec-16 T2 + follow-up: the collections ride the same
                // recompute — the union is the match corpus, so a changed
                // union (or a changed live list) changes the collections with
                // it. Live sources are best-effort and TTL-cached; matchAll
                // drops empty collections.
                _liveCollections.value = liveCollectionsFor()
                _smartCollections.value =
                    com.slukhayka.audiobooks.data.collections.CollectionMatcher.matchAll(
                        collectionLists + _liveCollections.value,
                        merged
                    )
                merged
            } finally {
                _isUnifiedCatalogLoading.value = false
            }
        }

    /** TTL-cached catalogue enumeration for one adapter (mirrors newFeedFor). */
    private suspend fun catalogueFor(adapter: SourceAdapter, limit: Int): List<SourceBook> {
        // Session-bound sources re-enumerate on every refresh: a fresh
        // challenge session must surface immediately, never a stale cache.
        if (!adapter.sessionBound) {
            val now = System.currentTimeMillis()
            adapterCatalogCache[adapter.sourceId]?.let { cached ->
                if (now - cached.fetchedAt < newFeedTtlMs) return cached.books
            }
        }
        val books = try {
            adapter.fetchCatalog(limit)
        } catch (e: Exception) {
            emptyList()
        }
        adapterCatalogCache[adapter.sourceId] = CachedFeed(System.currentTimeMillis(), books)
        return books
    }

    /**
     * Spec-16 follow-up — fetches the live collections, best-effort per
     * source and TTL-cached for the session like the feeds (a live list is a
     * convenience, safe to serve stale for a refresh or two). A failing
     * source contributes nothing; the whole call never throws.
     */
    private suspend fun liveCollectionsFor(): List<com.slukhayka.audiobooks.data.collections.CollectionList> {
        if (liveCollectionSources.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val lists = mutableListOf<com.slukhayka.audiobooks.data.collections.CollectionList>()
        for (source in liveCollectionSources) {
            val cached = liveCollectionCache[source.sourceId]
            if (cached != null && now - cached.fetchedAt < newFeedTtlMs) {
                lists += cached.lists
                continue
            }
            val fetched = try {
                source.fetchLiveCollections()
            } catch (e: Exception) {
                emptyList()
            }
            liveCollectionCache[source.sourceId] = CachedLiveList(now, fetched)
            lists += fetched
        }
        return lists
    }

    private val _sourceFeeds = MutableStateFlow<List<SourceNewFeed>>(emptyList())
    val sourceFeeds: StateFlow<List<SourceNewFeed>> = _sourceFeeds.asStateFlow()

    // spec-28 (#192): the cross-source «Новинки» rail — 4read's «Новинки»
    // section (the same homepage posters) plus every other source's new feed,
    // merged into ONE rail by Work with a source badge per card. Replaces
    // both the «Нове на 4read» rows and the per-source «Нове з кожного
    // джерела» rows; published on the SAME triggers as its inputs
    // ([refreshSourceFeeds] and [fetchCatalogSections]), so the rail always
    // reflects the freshest of the two.
    private val _newArrivals = MutableStateFlow<List<GlobalSearchResult>>(emptyList())
    val newArrivals: StateFlow<List<GlobalSearchResult>> = _newArrivals.asStateFlow()

    private val _isFeedsLoading = MutableStateFlow(false)
    val isFeedsLoading: StateFlow<Boolean> = _isFeedsLoading.asStateFlow()

    /**
     * Spec-10 T5 — refreshes the per-source «Нове з кожного джерела» rows.
     * Best-effort per source: a failing source simply contributes no row,
     * never the whole surface. Reuses the same in-memory feed cache as the
     * global search, so repeated refreshes within the TTL are free.
     */
    suspend fun refreshSourceFeeds(): List<SourceNewFeed> = withContext(Dispatchers.IO) {
        _isFeedsLoading.value = true
        try {
            val feeds = feedAdapters.mapNotNull { adapter ->
                // Session-bound sources re-hydrate on every refresh (skip the
                // TTL cache): a fresh challenge session must surface the row
                // immediately, never a stale empty cache.
                val books = newFeedFor(adapter, skipCache = adapter.sessionBound).take(20)
                when {
                    books.isNotEmpty() ->
                        SourceNewFeed(adapter.sourceId, sourceDisplayName(adapter.sourceId), books)
                    adapter.sessionBound ->
                        // No live session (Cloudflare): the CTA row guides the
                        // user to open the source's browser surface.
                        SourceNewFeed(adapter.sourceId, sourceDisplayName(adapter.sourceId), emptyList(), sessionBound = true)
                    else -> null
                }
            }
            _sourceFeeds.value = feeds
            // The rail's own half is the feeds just computed; the section half
            // comes from the latest published catalogue (spec-28 #197: never a
            // re-read of a half this call did not produce — each caller passes
            // its own result).
            publishNewArrivals(catalogSections.value, feeds)
            feeds
        } finally {
            _isFeedsLoading.value = false
        }
    }

    /**
     * spec-28 (#192) — recomputes the cross-source «Новинки» rail from the
     * inputs handed in by the caller: 4read's «Новинки» section books (already
     * tombstone-filtered by the upsert pass) plus every other source's
     * new-feed books, merged by Work via [mergeGlobalSearchResults] — one card
     * per Work with a badge per source. Called at the end of both
     * [refreshSourceFeeds] and [fetchCatalogSections] so the rail tracks the
     * fresher of the two.
     *
     * spec-28 (#197): the inputs are PARAMETERS, not re-reads of the two
     * flows — each caller passes the half it just computed, so the rail is
     * always assembled from the update's own result and never from a stale
     * section/feed list read between updates. The 4read section is matched by
     * its typed [CatalogSectionId.NEW_ARRIVALS] id, never by title — a rename
     * in the parser cannot silently drop or duplicate 4read's new arrivals.
     */
    private fun publishNewArrivals(sections: List<CatalogSection>, feeds: List<SourceNewFeed>) {
        val fourReadBooks = sections
            .firstOrNull { it.id == CatalogSectionId.NEW_ARRIVALS }
            ?.books.orEmpty()
            .map { it.toSourceBook() }
        val otherBooks = feeds.flatMap { it.books }
        _newArrivals.value = mergeGlobalSearchResults(fourReadBooks + otherBooks)
    }

    private fun CatalogBook.toSourceBook(): SourceBook = SourceBook(
        title = title,
        author = author,
        url = url,
        coverImageUrl = coverImageUrl,
        seriesTitle = seriesTitle,
        seriesIndex = seriesIndex,
        sourceId = SourceIds.FOUR_READ
    )

    private suspend fun newFeedFor(adapter: SourceAdapter, skipCache: Boolean = false): List<SourceBook> {
        val now = System.currentTimeMillis()
        if (!skipCache) {
            newFeedCache[adapter.sourceId]?.let { cached ->
                if (now - cached.fetchedAt < newFeedTtlMs) return cached.books
            }
        }
        val books = try {
            adapter.fetchNew()
        } catch (e: Exception) {
            emptyList()
        }
        newFeedCache[adapter.sourceId] = CachedFeed(now, books)
        return books
    }

    /**
     * Spec-10 T4 — aggregated search across every verified source.
     *
     * Each adapter is queried through its `search()` endpoint (4read); sources
     * without a usable search endpoint (soundbooks, audiobookmp3, lihtar per
     * the T1 verdicts) are discovered by filtering their recent feed. Results
     * are merged by the Work-level [MergeKey] — one card per Work with all
     * matching sources (see [mergeGlobalSearchResults]). Ephemeral: nothing is
     * imported into Room until the user taps a result.
     *
     * Spec-33 T2 (#227): a FRESH shared-cache hit ([searchCache]) returns the
     * cached merged result without touching any source; a miss or a stale
     * entry resolves live and writes the result back best-effort.
     */
    suspend fun searchAllSources(query: String): List<GlobalSearchResult> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()
            if (cleanQuery.isBlank()) return@withContext emptyList()

            // Spec-33 T2 (#227): the shared cache answers first. A FRESH hit
            // returns the post-merge result (covers, narrators, durations
            // included — the exact shape the UI renders) without touching any
            // source adapter; a miss or a stale entry falls through to the
            // live resolution below, whose result is written back.
            searchCache?.getResults(cleanQuery)?.let { return@withContext it }

            val matched = mutableListOf<SourceBook>()
            for (adapter in sourceAdapters) {
                val direct = try {
                    adapter.search(cleanQuery)
                } catch (e: Exception) {
                    emptyList()
                }
                matched += direct
                if (direct.isEmpty()) {
                    matched += newFeedFor(adapter)
                        .filter { book ->
                            book.title.contains(cleanQuery, ignoreCase = true) ||
                                book.author.contains(cleanQuery, ignoreCase = true)
                        }
                        // A feed entry with a blank author can't form a merge
                        // key — fetch its book page once and use the real
                        // title/author/narrator so the Work-level merge with
                        // other sources actually composes.
                        .map { book -> if (book.author.isBlank()) enrichFeedMatch(adapter, book) else book }
                }
            }
            val merged = mergeGlobalSearchResults(matched)
            // Spec-30 T2 (#217): attach the resolved durations (local DB →
            // shared cache) to the visible cards. Best-effort and silent — a
            // resolver-less or failing path leaves the cards unchanged.
            // Spec-30 T3 (#218): attach the canonical covers the same way —
            // a locally known cover wins, the shared cache fills the gap and
            // mirrors hits into the local database (the existing cover write
            // path), and the source's own claim is the last resort.
            val resolved = durationResolver?.let { it.resolve(merged) }
                ?.let { coverResolver?.resolve(it) } ?: merged
            // Spec-33 T2 (#227): write the merged result back best-effort so
            // the next listener with the same query reads the cache instead
            // of re-resolving (US-1/US-2). Negatives are never written — the
            // seam's no-negative rule keeps the long tail of unique misses
            // unbounded-free; a failing write contributes nothing (US-11).
            searchCache?.putResults(cleanQuery, resolved)
            resolved
        }

    /**
     * Spec-10 — replaces a weak feed entry (blank author, possibly a
     * transliterated title) with the metadata parsed from its own book page.
     * Best-effort: any failure keeps the original entry.
     */
    private suspend fun enrichFeedMatch(adapter: SourceAdapter, book: SourceBook): SourceBook {
        return try {
            val detail = adapter.fetchBookPage(book.url)
            if (detail.title.isBlank() && detail.author.isBlank() && detail.narrator.isBlank()) {
                book
            } else {
                book.copy(
                    title = detail.title.ifBlank { book.title },
                    author = detail.author.ifBlank { book.author },
                    narrator = detail.narrator.ifBlank { book.narrator },
                    coverImageUrl = detail.coverImageUrl ?: book.coverImageUrl
                )
            }
        } catch (e: Exception) {
            book
        }
    }

    /**
     * Spec-15 T3 — the WebView catalogue hydration tool (debug-only by
     * construction: the browser surface that exposes it is debug-gated, T2).
     * Crawls a WebView-source's catalogue through the live session
     * ([SourceAdapter.fetchCatalog] — session cookies past Cloudflare),
     * fetches each book page with the same session and imports through the
     * shared [LibraryImport.importBookFromSource] path (MergeKey dedup —
     * re-hydration adds new books without duplicating existing Works).
     * Best-effort per book: a failing book simply counts as failed, never
     * aborts the crawl.
     */
    suspend fun hydrateWebSourceCatalog(sourceId: String, limit: Int = 40): HydrationResult =
        withContext(Dispatchers.IO) {
            val adapter = sourceAdapters.firstOrNull { it.sourceId == sourceId }
                ?: return@withContext HydrationResult(sourceId, found = 0, imported = 0, merged = 0, failed = 0)
            val catalog = try {
                adapter.fetchCatalog(limit)
            } catch (e: Exception) {
                emptyList()
            }
            var imported = 0
            var merged = 0
            var failed = 0
            for (book in catalog) {
                try {
                    val detail = adapter.fetchBookPage(book.url)
                    if (detail.title.isBlank() && detail.chapters.isEmpty()) {
                        failed++
                        continue
                    }
                    // Count honestly: a book whose Work already exists (same
                    // merge key) is a merge — the new source is attached to
                    // the existing card, never a duplicate Work.
                    // ADR-0010: the Work key is bibliographic — the narrator
                    // distinguishes Editions, never Works.
                    val mergeKey = MergeKey.keyFor(detail.title, detail.author)
                    val alreadyKnown = mergeKey.isNotBlank() && dao.findByMergeKey(mergeKey) != null
                    writeBatchRunner {
                        libraryImport.importBookFromSource(sourceId, detail)
                        // Spec-23 T3: the hydrated row ALSO lands in the persisted
                        // browse layer (works/editions) via merge-on-write — the
                        // endless feed's source of truth. Idempotent: the edition
                        // id is deterministic, so re-hydration never duplicates.
                        // The per-source policy rides along (stream-only flags),
                        // and playback already routes Referer/UA via headersFor.
                        writeWorkEdition(
                            sourceId = sourceId,
                            title = detail.title,
                            author = detail.author,
                            narrator = detail.narrator,
                            sourceUrl = book.url,
                            streamOnly = streamOnlyFor(sourceId),
                            coverImageUrl = detail.coverImageUrl,
                            durationSeconds = detail.totalDurationSeconds,
                            seriesTitle = detail.series?.name,
                            seriesIndex = detail.series?.position,
                            genreTexts = detail.genres.ifEmpty { listOf(book.genre) }
                        )
                    }
                    if (alreadyKnown) merged++ else imported++
                } catch (e: Exception) {
                    failed++
                }
            }
            HydrationResult(sourceId, found = catalog.size, imported = imported, merged = merged, failed = failed)
        }

    /**
     * Outcome of a [hydrateWebSourceCatalog] run: how many books the crawl
     * found, how many were imported as NEW Works, how many merged into
     * existing Works (same merge key — the new source was attached, no
     * duplicate), and how many failed to fetch/parse. The UI surfaces these
     * counts so the debug tool reports, never silently no-ops.
     */
    data class HydrationResult(
        val sourceId: String,
        val found: Int,
        val imported: Int,
        val merged: Int = 0,
        val failed: Int,
        val pages: Int = 0
    )

    // ---------------------------------------------------------------------
    // Chapter materialisation (ADR-0002 #139): a catalogue-only Work's
    // chapters live on its source page and are materialised on demand.
    // A raw DAO read historically returned zero chapters for any book whose
    // page had never been opened/played (183 of 214 books on-device), and
    // callers that relied on it — the Download button — silently did nothing.
    // ---------------------------------------------------------------------

    /**
     * One logical chapter paired with the physical track of the book's
     * selected Source (ADR-0007). Chapter → track is 1:1 by index; a null
     * track means this source cannot play that chapter (a topology mismatch)
     * — the player surfaces the absence instead of fabricating audio.
     */
    data class PlayableChapter(
        val chapter: ChapterEntity,
        val track: SourceTrackEntity?,
        /** Source identity used for Referer/cookie policy at playback time. */
        val sourceId: String? = null,
        val sourceUrl: String? = null
    )

    /**
     * Returns a book's chapters PAIRED with the best available Source. A
     * chapter-less 4read catalogue row does not trigger an implicit browser or
     * direct fetch; the listener must explicitly refresh the page in WebView.
     * This is the seam Offline Downloads and the playback stack route through
     * — never a raw Room read alone.
     */
    suspend fun getPlayableChapters(bookId: String): List<PlayableChapter> {
        var chapters = dao.getChaptersListForBook(bookId)
        val book = dao.getAudiobookById(bookId)
        val sourceUrl = book?.sourceUrl ?: ""

        // Only fall back to the live 4read page when the book has NO chapters
        // at all. Previously the condition was `chapters.isEmpty() || any
        // contains archive.org` which treated the intentionally-seeded
        // LibriVox/archive.org chapters as placeholders and re-inserted the
        // live page's chapters on EVERY play/refresh -- observed on-device as
        // 54 chapter rows for one 6-chapter seed book, scrambled order, and
        // the player picking up reasd.org streams instead of the seeded ones.
        if (chapters.isEmpty() && sourceUrl.isNotBlank() && sourceUrl.contains("4read.org") &&
            SourceAccessPolicy.modeFor(sourceIdForUrl(sourceUrl)) != com.slukhayka.audiobooks.data.source.SourceAccessMode.BROWSER
        ) {
            // Spec-14 T5: the adapter owns the page parse; the catalog only
            // persists what the seam's SourceBookDetail carries.
            val detail = fourReadAdapter.fetchBookPage(sourceUrl)
            if (detail.chapters.isNotEmpty()) {
                // ADR-0004 + ADR-0007: materialization (one id format, one
                // title fallback, duration conventions; Edition chapters +
                // Source tracks) comes from the one module.
                val edition = dao.getEditionForWork(bookId)
                val editionId = edition?.id ?: EditionId.forBook(book?.mergeKey ?: "", bookId, book?.narrator ?: "")
                if (edition == null) {
                    dao.insertEdition(
                        EditionEntity(
                            id = editionId,
                            workId = bookId,
                            narrator = book?.narrator ?: "",
                            totalChapters = detail.chapters.size,
                            totalDurationSeconds = detail.totalDurationSeconds ?: 0L
                        )
                    )
                }
                val source = dao.getSourcesForBookSync(bookId).firstOrNull { it.type == "4read" }
                    ?: SourceEntity(
                        id = "4read-$editionId",
                        bookId = bookId,
                        editionId = editionId,
                        type = "4read",
                        url = sourceUrl,
                        streamOnly = streamOnlyFor("4read"),
                        addedAt = System.currentTimeMillis()
                    ).also { dao.insertSources(listOf(it)) }
                val materialized = MetadataAssertions.materializeChaptersAndTracks(
                    editionId = editionId,
                    sourceId = source.id,
                    bookId = bookId,
                    bookTitle = book?.title ?: "4read",
                    chapters = detail.chapters
                )
                dao.insertChapters(materialized.chapters)
                dao.insertTracks(materialized.tracks)
                // Back-fill the real chapter count, the site's own total
                // duration ("Триває:"), and the real author/narrator/genre/
                // rating/series now that we've fetched the book page — the
                // catalogue seed only ever had placeholders. All claim
                // normalization goes through the one module (ADR-0004).
                val knownDuration = MetadataAssertions.durationDelta(
                    book?.totalDurationSeconds ?: 0L,
                    detail.totalDurationSeconds
                )
                dao.updateBookStats(bookId, materialized.chapters.size, knownDuration)
                val author = MetadataAssertions.normalizeClaimedText(detail.author)
                val narrator = MetadataAssertions.normalizeClaimedText(detail.narrator)
                val genres = detail.genres.joinToString(" · ").ifBlank { null }
                val rating = detail.rating?.toFloat()
                val seriesTitle = detail.series?.name
                val seriesIndex = detail.series?.position
                val seriesUrl = detail.series?.url
                if (author != null || narrator != null || genres != null || rating != null) {
                    dao.updateBookMetadata(
                        bookId,
                        author = author,
                        narrator = narrator,
                        genre = genres,
                        rating = rating
                    )
                }
                // ADR-0009: series belongs to the Work — applied only when the
                // page claims the series URL (the membership signal), so an
                // absent claim never clears the stored series.
                if (seriesUrl != null) {
                    dao.updateSeriesFields(bookId, seriesTitle, seriesUrl, seriesIndex)
                }
                // Cover via a targeted UPDATE, not a REPLACE insert: the row
                // carries freshly back-filled metadata above, and a full-row
                // re-insert with the stale seed entity would clobber it back
                // to the placeholders ("4read.org" etc.). Only a non-blank
                // claim ever updates the cover (ADR-0004).
                MetadataAssertions.coverDelta(detail.coverImageUrl)?.let { cover ->
                    dao.updateCoverImageUrl(bookId, cover)
                }
                chapters = materialized.chapters
            }
        }

        // Phase 2.5 hotfix (CR-002 / SF-003 / SF-005 / SF-006): when 4read fetch
        // returns no streams the previous code synthesised N chapters pointing
        // at unrelated archive.org MP3s (time_machine / war_of_the_worlds) so
        // that the chapter list was always populated. Users heard 19th-century
        // sci-fi while the UI showed their selected book. We refuse to fabricate
        // audio and surface an empty chapter list — the player / UI sees the
        // absence and shows a "no chapters available" message instead.
        if (chapters.isEmpty()) {
            Log.w(
                "SourceCatalog",
                "No chapters for bookId=$bookId and 4read fetch returned none; " +
                    "refusing to fabricate placeholder audio."
            )
        }

        // Pair each logical chapter with the best available Source according to
        // the static capability order. A local file wins when it really exists;
        // otherwise direct HTTP sources win, then legacy/unknown, and browser
        // sources are last. Browser-backed tracks are still usable after the
        // listener explicitly imported the page in WebView; the browser is not
        // opened as a side effect here.
        val editionId = dao.getEditionForWork(bookId)?.id
        val sources = dao.getSourcesForBookSync(bookId).filter { source ->
            editionId == null || source.editionId == null || source.editionId == editionId
        }
        val tracksBySource = sources.associateWith { dao.getTracksForSourceSync(it.id) }
        val orderedSources = SourceAccessPolicy.order(
            sources.map { source ->
                val tracks = tracksBySource[source].orEmpty()
                SourceAccessCandidate(
                    sourceId = source.type,
                    sourceName = sourceDisplayName(source.type),
                    url = source.url,
                    localAvailable = tracks.any { track ->
                        track.isDownloaded && track.localFilePath?.let { path ->
                            val file = File(path)
                            file.exists() && file.length() > 100
                        } == true
                    }
                )
            }
        )
        val selectedSource = orderedSources
            .mapNotNull { candidate ->
                sources.firstOrNull { it.type == candidate.sourceId && it.url == candidate.url }
            }
            .firstOrNull { source ->
                val tracks = tracksBySource[source].orEmpty()
                tracks.count { it.trackIndex in chapters.indices } == chapters.size
            }
            ?: orderedSources
                .mapNotNull { candidate ->
                    sources.firstOrNull { it.type == candidate.sourceId && it.url == candidate.url }
                }
                .firstOrNull { tracksBySource[it].orEmpty().isNotEmpty() }
            ?: sources.firstOrNull()
        val tracks = selectedSource?.let { tracksBySource[it].orEmpty() }.orEmpty()
        return chapters.mapIndexed { index, chapter ->
            PlayableChapter(
                chapter = chapter,
                track = tracks.firstOrNull { it.trackIndex == index },
                sourceId = selectedSource?.type,
                sourceUrl = selectedSource?.url
            )
        }
    }

    /**
     * The chapters of a book (logical list only) — the display read. The
     * playback/download paths use [getPlayableChapters] instead.
     */
    suspend fun getChaptersList(bookId: String): List<ChapterEntity> =
        getPlayableChapters(bookId).map { it.chapter }

    // ---------------------------------------------------------------------
    // Persisted catalogue: Works + Editions, merge-on-write (spec-23 T1).
    // The browse layer is distinct from the listening/library `audiobooks`
    // row: a Work is one book identity (normalized MergeKey), an Edition is
    // one source carrying it. Any catalogue write merges here — writing the
    // same book from two sources yields one Work with two Editions, and
    // re-hydration never duplicates. Dedup is on the WRITE path, not at read
    // time: the ephemeral read-time union (refreshUnifiedCatalog) is
    // superseded by this persisted layer for the T4 endless feed.
    // ---------------------------------------------------------------------

    /** Deterministic, stable-per-URL id fragment (hex of String.hashCode — specified, cross-process stable). */
    private fun stableIdOf(url: String): String = Integer.toHexString(url.hashCode())

    /**
     * Outcome of one merge-on-write: the Work (existing or fresh), whether the
     * Work row itself was created, and whether a NEW Edition was attached.
     * Hydration uses these flags to report honest per-run counts (works added
     * vs editions merged) instead of guessing at read time.
     */
    data class WorkWriteResult(
        val work: WorkEntity,
        val workCreated: Boolean,
        val editionCreated: Boolean
    )

    /**
     * Merge-on-write: normalizes the identity via the validated [MergeKey]
     * (title+author+narrator), finds the Work by its merge key, and either
     * creates the Work + its first Edition or attaches a new Edition to the
     * existing Work. Idempotent: the Edition id is deterministic
     * (`<workId>|<sourceId>|<url-hash>`), so re-writing the same source row
     * REPLACE-no-ops instead of duplicating. Unmergeable rows (blank key —
     * no identity to merge on) get their own Work with a stable per-source
     * id; they never merge, by definition.
     */
    suspend fun writeWorkEdition(
        sourceId: String,
        title: String,
        author: String,
        narrator: String,
        sourceUrl: String,
        streamOnly: Boolean = false,
        coverImageUrl: String? = null,
        durationSeconds: Long? = null,
        seriesTitle: String? = null,
        seriesIndex: Int? = null,
        genreTexts: List<String>? = null
    ): WorkWriteResult {
        // ADR-0010: the Work key is bibliographic (title|author) — the
        // narrator is a rendition (Edition) property, never part of the Work.
        val mergeKey = MergeKey.keyFor(title, author)
        val existing = if (mergeKey.isNotBlank()) dao.findWorkByMergeKey(mergeKey) else null
        val work = existing ?: if (mergeKey.isNotBlank()) {
            WorkEntity(
                id = mergeKey,
                mergeKey = mergeKey,
                // Spec-24 T1: the Work row stores the scrubbed title — the
                // merge key keeps the RAW claim so stored identities never
                // churn under the SEO-suffix scrub.
                title = MetadataAssertions.normalizeTitle(title),
                author = author.trim(),
                seriesTitle = seriesTitle,
                seriesIndex = seriesIndex,
                coverImageUrl = coverImageUrl,
                addedAt = System.currentTimeMillis()
            )
        } else {
            val id = "w-$sourceId-${stableIdOf(sourceUrl)}"
            WorkEntity(
                id = id,
                mergeKey = "",
                title = MetadataAssertions.normalizeTitle(title),
                author = author.trim(),
                seriesTitle = seriesTitle,
                seriesIndex = seriesIndex,
                coverImageUrl = coverImageUrl,
                addedAt = System.currentTimeMillis()
            )
        }
        val workSourceId = "${work.id}|$sourceId|${stableIdOf(sourceUrl)}"
        // Every persisted Work immediately participates in the canonical
        // cross-Source author index. This is idempotent and entirely local.
        authorIndex.indexWorks(listOf(work), sourceId)
        val sourceAlreadyKnown = dao.getWorkSourcesForWorkSync(work.id).any { it.id == workSourceId }
        val workSource = WorkSourceEntity(
            id = workSourceId,
            workId = work.id,
            sourceId = sourceId,
            sourceUrl = sourceUrl,
            streamOnly = streamOnly,
            coverImageUrl = coverImageUrl,
            durationSeconds = durationSeconds,
            addedAt = System.currentTimeMillis()
        )
        // #388 — use safe/transactional upsert. If the Work was just
        // created, both rows are written atomically; if it already
        // existed, a missing parent (race/tombstone) degrades gracefully.
        if (existing == null) {
            dao.upsertWorkWithSource(work, workSource)
        } else {
            dao.safeUpsertWorkSource(workSource)
        }
        if (genreTexts != null) {
            val facetObservedAt = System.currentTimeMillis()
            facetWriter.apply(
                listOf(
                    LocalFacetDelta(
                        WorkFacetDelta(
                            workId = work.id,
                            genres = genreTexts.map { raw ->
                                GenreFacetAssertion(raw, sourceId, facetObservedAt)
                            },
                            genreSourceReplacements = if (genreTexts.isEmpty()) {
                                listOf(GenreSourceFacetReplacement(sourceId, facetObservedAt, emptyList()))
                            } else {
                                emptyList()
                            },
                            updatedAt = facetObservedAt
                        )
                    )
                )
            )
        }
        return WorkWriteResult(work = work, workCreated = existing == null, editionCreated = !sourceAlreadyKnown)
    }

    // Spec-37 depth budget: a polite crawl deepens the catalogue across daily
    // runs instead of walking every listing page in one go.
    private val maxPagesPerListing = 5
    private val maxPagesPerRun = 40

    /**
     * Spec-23 T2 — hydrates 4read's full catalogue into the persisted
     * Works/Editions layer: the homepage (Новинки + Популярне posters), every
     * genre/category from the sidebar nav, and the homepage's series pages.
     * Every row lands merge-on-write ([writeWorkEdition]) with the source's
     * real policy (4read is downloadable — streamOnly = false), so added
     * Works are playable/downloadable and re-runs never duplicate. Best-effort
     * per page: a failing page counts as failed, never aborts the crawl.
     * The run reports honest counts — new Works (imported) vs writes into
     * already-known Works (merged) vs failed pages.
     *
     * Spec-37: category and series listings are PAGINATED on 4read (DLE
     * `/page/N/` blocks), so each listing is now followed page by page under
     * a double budget — at most [maxPagesPerListing] pages per listing
     * and [maxPagesPerRun] pages per run. A daily run therefore deepens
     * the catalogue gradually instead of hammering the source in one go;
     * merge-on-write keeps every run idempotent.
     */
    suspend fun hydrateFourReadCatalog(): HydrationResult = withContext(Dispatchers.IO) {
        val sourceId = SourceIds.FOUR_READ
        val homepage = try {
            fourReadFetcher.getText("https://4read.org/")
        } catch (e: Exception) {
            ""
        }
        if (homepage.isBlank()) {
            return@withContext HydrationResult(sourceId, found = 0, imported = 0, merged = 0, failed = 0)
        }

        // Category pages to crawl: the sidebar genre nav (plus the series
        // pages advertised on the homepage) — the enumeration surface beyond
        // the single default page.
        val categoryUrls = mutableListOf<String>()
        val homepageBooks = mutableListOf<CatalogBook>()
        try {
            homepageBooks += CatalogParser.parseHomepage(homepage)
                .flatMap { section -> section.books }
            categoryUrls += CatalogParser.parseGenreNav(homepage).map { it.url }
            categoryUrls += CatalogParser.parseHomepage(homepage)
                .flatMap { it.series }
                .mapNotNull { it.url }
        } catch (e: Exception) {
            // Unparseable homepage degrades to an empty crawl.
        }

        var found = 0
        var imported = 0
        var merged = 0
        var failed = 0
        var pages = 0
        val seen = mutableSetOf<String>()

        suspend fun write(book: CatalogBook) {
            if (!seen.add(book.url)) return
            found++
            try {
                val result = writeWorkEdition(
                    sourceId = sourceId,
                    title = book.title,
                    author = book.author,
                    narrator = "",
                    sourceUrl = book.url,
                    streamOnly = false,
                    coverImageUrl = book.coverImageUrl,
                    seriesTitle = book.seriesTitle,
                    seriesIndex = book.seriesIndex
                )
                if (result.workCreated) imported++ else merged++
            } catch (e: Exception) {
                failed++
            }
        }

        writeBatchRunner { homepageBooks.forEach { write(it) } }

        for (url in categoryUrls) {
            // Follow the listing's own pagination until a budget stops us:
            // the run-wide cap keeps one crawl polite to the source; the
            // per-listing cap keeps one huge genre from eating the budget.
            var currentUrl: String? = url
            val visitedUrls = mutableSetOf(url)
            while (currentUrl != null && pages < maxPagesPerRun) {
                val html = try {
                    fourReadFetcher.getText(currentUrl)
                } catch (e: Exception) {
                    ""
                }
                if (html.isBlank()) {
                    failed++
                    break
                }
                pages++
                val books = try {
                    CatalogParser.parseSeriesPage(html)
                } catch (e: Exception) {
                    emptyList()
                }
                if (books.isEmpty()) {
                    failed++
                    break
                }
                writeBatchRunner { books.forEach { write(it) } }
                currentUrl = CatalogParser.parseNextPageUrl(html)?.takeIf { next ->
                    visitedUrls.add(next) && visitedUrls.size <= maxPagesPerListing
                }
            }
            if (pages >= maxPagesPerRun) break
        }

        HydrationResult(sourceId, found = found, imported = imported, merged = merged, failed = failed, pages = pages)
    }

    // ---------------------------------------------------------------------
    // Endless merged feed (spec-23 T4): Paging 3 over the persisted Works/
    // Editions catalogue. One row per Work (dedup inherited from
    // merge-on-write, never re-implemented at read time), filtered by local
    // indexed facet relations so filters compose with paging. Sort is
    // either newest-first or by title — two thin DAO queries, no in-memory
    // sorting of paged slices.
    // ---------------------------------------------------------------------

    /** Endless feed, newest Works first. */
    fun pagedWorkFeedRecent(
        filter: WorkFacetFilter = WorkFacetFilter(),
        availabilityAtMillis: Long = System.currentTimeMillis()
    ): PagingSource<Int, WorkFeedRow> =
        dao.pagedWorksFeedRecent(
            filter.genreIds.toList(), if (filter.genreIds.isEmpty()) 0 else 1,
            filter.durationBucketIds.toList(), if (filter.durationBucketIds.isEmpty()) 0 else 1,
            filter.authorIds.toList(), if (filter.authorIds.isEmpty()) 0 else 1,
            availabilityAtMillis
        )

    /** Endless feed, sorted by title (stable tiebreak: newest first). */
    fun pagedWorkFeedByTitle(
        filter: WorkFacetFilter = WorkFacetFilter(),
        availabilityAtMillis: Long = System.currentTimeMillis()
    ): PagingSource<Int, WorkFeedRow> =
        dao.pagedWorksFeedByTitle(
            filter.genreIds.toList(), if (filter.genreIds.isEmpty()) 0 else 1,
            filter.durationBucketIds.toList(), if (filter.durationBucketIds.isEmpty()) 0 else 1,
            filter.authorIds.toList(), if (filter.authorIds.isEmpty()) 0 else 1,
            availabilityAtMillis
        )

    /** The Sources carrying one Work, in capability order then duration context. */
    suspend fun workSourcesForWork(
        workId: String,
        preferredDurationBucketIds: Set<String> = emptySet()
    ): List<WorkSourceEntity> {
        val sources = dao.getWorkSourcesForWorkSync(workId)
        val durationOrdered = if (preferredDurationBucketIds.isEmpty()) sources else sources.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<WorkSourceEntity>> { indexed ->
                    indexed.value.durationSeconds
                        ?.let(EditionDurationPolicy::bucketFor)
                        ?.wireName in preferredDurationBucketIds
                }.thenBy { it.index }
            )
            .map { it.value }
        return SourceAccessPolicy.order(
            durationOrdered.map { SourceAccessCandidate(it.sourceId, sourceDisplayName(it.sourceId), it.sourceUrl) }
        ).mapNotNull { candidate ->
            durationOrdered.firstOrNull { it.sourceId == candidate.sourceId && it.sourceUrl == candidate.url }
        }
    }

    /** Resolves an already imported rendition without collapsing its sibling Editions. */
    suspend fun libraryBookForEdition(editionId: String?): AudiobookEntity? {
        val edition = editionId?.let { dao.getEditionById(it) } ?: return null
        return dao.getAudiobookById(edition.workId)?.toAudiobookEntity()
    }

    /**
     * Spec-23 T5 — one source carrying a Work, for the book page's
     * «Джерела» section: display name, the Edition url and the source's
     * stream-only policy (from the persisted Edition row for post-merge
     * books; from the source policy for pre-merge library rows).
     */
    data class WorkSourceRow(
        val sourceId: String,
        val sourceName: String,
        val url: String,
        val streamOnly: Boolean
    )

    /**
     * Spec-23 T5 — every source that carries [bookId]'s Work, resolved from
     * the persisted `editions` rows (merge-on-write output), never guessed at
     * read time. A library row that predates the merge (no workId / no
     * editions yet) falls back to its own single source — still honest, one
     * row. Dedup keeps the list stable when the same source+url appears on
     * multiple rows.
     */
    suspend fun sourcesForBook(bookId: String): List<WorkSourceRow> {
        val book = dao.getAudiobookById(bookId) ?: return emptyList()
        val workSources = book.workId?.let { dao.getWorkSourcesForWorkSync(it) }.orEmpty()
        val rows = if (workSources.isNotEmpty()) {
            workSources.map { source ->
                WorkSourceRow(
                    sourceId = source.sourceId,
                    sourceName = sourceDisplayName(source.sourceId),
                    url = source.sourceUrl,
                    streamOnly = source.streamOnly
                )
            }
        } else {
            val id = sourceIdForUrl(book.sourceUrl)
            listOf(
                WorkSourceRow(
                    sourceId = id,
                    sourceName = sourceDisplayName(id),
                    url = book.sourceUrl,
                    streamOnly = streamOnlyFor(id)
                )
            )
        }
        val downloadedBySource = dao.getSourcesForBookSync(bookId)
            .associate { source ->
                (source.type to source.url) to dao.getTracksForSourceSync(source.id).any { it.isDownloaded }
            }
        return SourceAccessPolicy.order(
            rows.distinctBy { it.sourceId to it.url }.map {
                SourceAccessCandidate(
                    it.sourceId,
                    it.sourceName,
                    it.url,
                    // A downloaded physical track is local regardless of the
                    // remote source's name: an offline 4read copy must outrank
                    // a fresh DIRECT source just like any other valid file.
                    localAvailable = it.sourceId == "local" ||
                        downloadedBySource[it.sourceId to it.url] == true
                )
            }
        ).map { candidate ->
            rows.first { it.sourceId == candidate.sourceId && it.url == candidate.url }
        }
    }

    // ---------------------------------------------------------------------
    // Catalogue sections (spec #8 tickets T5/T6): rows for the Explore
    // screen, parsed from the 4read.org homepage and cached in memory.
    // ---------------------------------------------------------------------

    private val _catalogSections = MutableStateFlow<List<CatalogSection>>(emptyList())
    val catalogSections: StateFlow<List<CatalogSection>> = _catalogSections.asStateFlow()

    // Genre navigation from the homepage sidebar ("Аудіокниги жанру:"):
    // chips that open a genre book list, mirroring the site's own navigation.
    private val _catalogGenres = MutableStateFlow<List<CatalogGenre>>(emptyList())
    val catalogGenres: StateFlow<List<CatalogGenre>> = _catalogGenres.asStateFlow()

    private val _isCatalogLoading = MutableStateFlow(false)
    val isCatalogLoading: StateFlow<Boolean> = _isCatalogLoading.asStateFlow()

    /**
     * Syncs the Explore catalogue from the live 4read.org homepage: parses the
     * sections, upserts every book into Room (so rows stay playable even if a
     * later parse fails) and publishes the sections to [catalogSections].
     * Never throws: network/parse failures degrade to an empty catalogue.
     * This is the explicit sync call the composition root invokes when the app
     * wants catalogue sync — constructing the module performs no network I/O.
     *
     * ADR-0005: tombstone enforcement lives in the persistence layer — the
     * upsert returns nothing for a tombstoned Work, so the published sections
     * are assembled from what actually landed; sections emptied by skips are
     * not published (matching today's behaviour).
     */
    suspend fun fetchCatalogSections(): List<CatalogSection> =
        withContext(Dispatchers.IO) {
            _isCatalogLoading.value = true
            try {
                val html = fourReadFetcher.getText("https://4read.org/")
                if (html.isBlank()) return@withContext emptyList()
                _catalogGenres.value = CatalogParser.parseGenreNav(html)
                val sections = CatalogParser.parseHomepage(html).mapNotNull { section ->
                    val landed = section.books.mapNotNull { book -> libraryImport.upsertCatalogBook(book) }
                    if (landed.isEmpty() && section.series.isEmpty()) return@mapNotNull null
                    val landedIds = landed.map { it.id }.toSet()
                    section.copy(books = section.books.filter { it.id in landedIds })
                }
                _catalogSections.value = sections
                // The rail's own half is the sections just parsed; the feed
                // half comes from the latest published feeds (spec-28 #197).
                publishNewArrivals(sections, _sourceFeeds.value)
                sections
            } catch (e: Exception) {
                Log.w("SourceCatalog", "Catalogue sync failed", e)
                emptyList()
            } finally {
                _isCatalogLoading.value = false
            }
        }

    /**
     * Fetches the full book list of a series (cycle) page (spec #8 ticket T8)
     * and upserts the books into Room so they are playable. Returns the stored
     * DB entities. Results are cached per series URL for the session, so the
     * Слухати tab's "continue the series" block (spec-9 T4) can re-read a
     * series without a network round-trip on every recomposition.
     */
    suspend fun fetchSeriesBooksResult(
        seriesUrl: String
    ): CatalogFetchResult<List<AudiobookEntity>> =
        withContext(Dispatchers.IO) {
            seriesBooksCache[seriesUrl]?.let {
                return@withContext CatalogFetchResult.Success(it)
            }
            try {
                val html = fourReadFetcher.getText(seriesUrl)
                if (html.isBlank()) return@withContext CatalogFetchResult.Failure
                // ADR-0005: the upsert's persistence-layer guard drops tombstoned
                // Works — the published list is what actually landed.
                val books = CatalogParser.parseSeriesPage(html)
                    .mapNotNull { book -> libraryImport.upsertCatalogBook(book) }
                seriesBooksCache[seriesUrl] = books
                CatalogFetchResult.Success(books)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("SourceCatalog", "Series fetch failed for $seriesUrl", failure)
                CatalogFetchResult.Failure
            }
        }

    /** Backward-compatible best-effort list API for non-UI consumers. */
    suspend fun fetchSeriesBooks(seriesUrl: String): List<AudiobookEntity> =
        fetchSeriesBooksResult(seriesUrl).valueOrEmpty()

    /**
     * The next volume of the series a book belongs to (spec-9 T4). Returns
     * null when the book has no series, the series page cannot be loaded, or
     * the current book is the last volume — the UI then hides the block.
     * Network failures degrade to null, never to an exception.
     */
    suspend fun findNextInSeries(book: AudiobookEntity): AudiobookEntity? =
        withContext(Dispatchers.IO) {
            val url = book.seriesUrl ?: return@withContext null
            if (url.isBlank()) return@withContext null
            try {
                val seriesBooks = fetchSeriesBooks(url)
                nextInSeries(book.seriesIndex, book.id, seriesBooks)
            } catch (e: Exception) {
                Log.w("SourceCatalog", "Next-in-series lookup failed for ${book.id}", e)
                null
            }
        }

    /**
     * All books of a genre (category) page — `4read.org/<genre>/` — e.g.
     * `https://4read.org/fentezi/`. Genre pages reuse the poster markup of the
     * homepage, so the series-page parser and cache apply unchanged.
     */
    suspend fun fetchGenreBooks(genreUrl: String): List<AudiobookEntity> =
        fetchGenreBooksResult(genreUrl).valueOrEmpty()

    suspend fun fetchGenreBooksResult(
        genreUrl: String
    ): CatalogFetchResult<List<AudiobookEntity>> = fetchSeriesBooksResult(genreUrl)

    /**
     * ТОП 100 АудіоКниг (`/top-100.html`): ranked `linek` cards, not posters.
     * Upserted into Room (like series/genre pages) so every entry is playable
     * and opens its own detail. Cached per session; rank is the list order.
     */
    private var top100Cache: List<AudiobookEntity>? = null
    suspend fun fetchTop100Result(): CatalogFetchResult<List<AudiobookEntity>> =
        withContext(Dispatchers.IO) {
            top100Cache?.let { return@withContext CatalogFetchResult.Success(it) }
            try {
                val html = fourReadFetcher.getText("https://4read.org/top-100.html")
                if (html.isBlank()) return@withContext CatalogFetchResult.Failure
                // ADR-0005: the upsert's persistence-layer guard drops tombstoned
                // Works — the published list is what actually landed.
                val books = CatalogParser.parseTop100(html)
                    .mapNotNull { libraryImport.upsertCatalogBook(it) }
                top100Cache = books
                CatalogFetchResult.Success(books)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("SourceCatalog", "Top-100 fetch failed", failure)
                CatalogFetchResult.Failure
            }
        }

    suspend fun fetchTop100(): List<AudiobookEntity> = fetchTop100Result().valueOrEmpty()

    /** Виконавці/Автори index pages, cached per URL for the session. */
    private val peopleCache = java.util.concurrent.ConcurrentHashMap<String, List<CatalogPerson>>()
    suspend fun fetchPeopleResult(url: String): CatalogFetchResult<List<CatalogPerson>> =
        withContext(Dispatchers.IO) {
            peopleCache[url]?.let { return@withContext CatalogFetchResult.Success(it) }
            try {
                val html = fourReadFetcher.getText(url)
                if (html.isBlank()) return@withContext CatalogFetchResult.Failure
                val people = CatalogParser.parsePeopleList(html)
                peopleCache[url] = people
                CatalogFetchResult.Success(people)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("SourceCatalog", "People fetch failed for $url", failure)
                CatalogFetchResult.Failure
            }
        }

    suspend fun fetchPeople(url: String): List<CatalogPerson> =
        fetchPeopleResult(url).valueOrEmpty()

    /**
     * Books narrated/written by one person. The `/xfsearch/<kind>/<name>/`
     * page is a poster grid, so the series-page fetch applies unchanged.
     * The person's name is URL-encoded (the site serves raw Cyrillic paths).
     */
    suspend fun fetchPersonBooks(path: String): List<AudiobookEntity> {
        return fetchPersonBooksResult(path).valueOrEmpty()
    }

    suspend fun fetchPersonBooksResult(
        path: String
    ): CatalogFetchResult<List<AudiobookEntity>> {
        val encoded = "https://4read.org" + android.net.Uri.encode(path, "/")
        return fetchSeriesBooksResult(encoded)
    }

    /**
     * Related books from the book page's "Можливо, Тебе зацікавить:" section.
     * The posters are upserted into Room (like series/genre pages) so tapping
     * one opens its own detail screen and the book is playable.
     */
    suspend fun fetchRelatedBooks(bookId: String): List<AudiobookEntity> =
        withContext(Dispatchers.IO) {
            val book = dao.getAudiobookById(bookId) ?: return@withContext emptyList()
            val sourceUrl = book.sourceUrl
            if (!sourceUrl.contains("4read.org")) return@withContext emptyList()
            // Spec-14 T5: the module performs no 4read parsing or transport —
            // the adapter owns the page parse (incl. the related-book posters),
            // and the upsert shape is built from the seam's [RelatedBook] model.
            val detail = fourReadAdapter.fetchBookPage(sourceUrl)
            if (detail.related.isEmpty()) return@withContext emptyList()
            detail.related
                .map { related ->
                    CatalogBook(
                        id = fourReadAdapter.bookId(related.url),
                        title = related.title,
                        author = related.author,
                        url = related.url,
                        coverImageUrl = related.coverImageUrl
                    )
                }
                // ADR-0005: the upsert's persistence-layer guard drops
                // tombstoned Works — the published list is what actually landed.
                .mapNotNull { libraryImport.upsertCatalogBook(it) }
        }

    private val seriesBooksCache = java.util.concurrent.ConcurrentHashMap<String, List<AudiobookEntity>>()

    /**
     * Inserts the book if absent; otherwise returns the stored row. Series
     * metadata (spec-9 T1) is written on insert and back-filled on an existing
     * row when the parsed poster carries it, so a later homepage sync enriches
     * previously-known books without touching user state (favourite/download).
     * Delegates to the shared [LibraryImport.upsertCatalogBook] — the one
     * catalogue→Work write path.
     */
    /**
     * ADR-0005: nullable — a tombstoned Work lands nothing (null), so a
     * caller assembling a published list knows what actually arrived.
     */
    suspend fun upsertCatalogBook(book: CatalogBook): AudiobookEntity? =
        libraryImport.upsertCatalogBook(book)
}


/**
 * The next volume of a series, resolved from the series book list (spec-9 T4).
 * Prefers a volume-number match (`currentIndex + 1`); when the volume badge is
 * missing it falls back to the series page order. Returns null when the
 * current book is the last volume or is not in the list at all, so the UI can
 * hide the suggestion instead of guessing.
 */
internal fun nextInSeries(
    currentIndex: Int?,
    currentId: String,
    seriesBooks: List<AudiobookEntity>
): AudiobookEntity? {
    if (seriesBooks.isEmpty()) return null
    if (currentIndex != null) {
        val byIndex = seriesBooks.firstOrNull { it.seriesIndex == currentIndex + 1 }
        if (byIndex != null) return byIndex
    }
    val position = seriesBooks.indexOfFirst { it.id == currentId }
    if (position >= 0) return seriesBooks.getOrNull(position + 1)
    return null
}
