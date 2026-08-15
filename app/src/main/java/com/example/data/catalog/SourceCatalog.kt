package com.example.data.catalog

import android.util.Log
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookEntity
import com.example.data.db.ChapterEntity
import com.example.data.db.EditionEntity
import com.example.data.db.WorkEntity
import com.example.data.imports.LibraryImport
import com.example.data.merge.MergeKey
import com.example.data.source.FourReadAdapter
import com.example.data.source.GlobalSearchResult
import com.example.data.source.HttpFetcher
import com.example.data.source.SourceAdapter
import com.example.data.source.SourceBook
import com.example.data.source.mergeGlobalSearchResults
import com.example.data.source.sourceDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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
    private val fourReadFetcher: HttpFetcher = HttpFetcher(referer = "https://4read.org/")
) {

    private val fourReadAdapter: SourceAdapter =
        sourceAdapters.firstOrNull { it.sourceId == "4read" } ?: FourReadAdapter()

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
    private val feedAdapters: List<SourceAdapter> = sourceAdapters.filterNot { it.sourceId == "4read" }

    // Spec-15 T1: the unified catalogue union. 4read is excluded the same way
    // as the feeds — its full catalogue is natively browsed through the Огляд
    // sections (spec #8), so the union covers the other verified sources and
    // merges their catalogue enumeration into Work cards via MergeKey.
    private val catalogueAdapters: List<SourceAdapter> = feedAdapters

    private val _unifiedCatalog = MutableStateFlow<List<GlobalSearchResult>>(emptyList())
    val unifiedCatalog: StateFlow<List<GlobalSearchResult>> = _unifiedCatalog.asStateFlow()

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

    private val _sourceFeeds = MutableStateFlow<List<SourceNewFeed>>(emptyList())
    val sourceFeeds: StateFlow<List<SourceNewFeed>> = _sourceFeeds.asStateFlow()

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
            feeds
        } finally {
            _isFeedsLoading.value = false
        }
    }

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
     */
    suspend fun searchAllSources(query: String): List<GlobalSearchResult> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()
            if (cleanQuery.isBlank()) return@withContext emptyList()

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
            mergeGlobalSearchResults(matched)
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
                    val mergeKey = MergeKey.keyFor(detail.title, detail.author, detail.narrator)
                    val alreadyKnown = mergeKey.isNotBlank() && dao.findByMergeKey(mergeKey) != null
                    libraryImport.importBookFromSource(sourceId, detail)
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
        val failed: Int
    )

    // ---------------------------------------------------------------------
    // Chapter materialisation (ADR-0002 #139): a catalogue-only Work's
    // chapters live on its source page and are materialised on demand.
    // A raw DAO read historically returned zero chapters for any book whose
    // page had never been opened/played (183 of 214 books on-device), and
    // callers that relied on it — the Download button — silently did nothing.
    // ---------------------------------------------------------------------

    /**
     * Returns a book's chapters, fetching them from the live 4read page when
     * Room holds none (catalogue Works seed chapter-less). This is the
     * fallback chapter fetch Offline Downloads and the playback stack route
     * through — never a raw Room read alone.
     */
    suspend fun getChaptersList(bookId: String): List<ChapterEntity> {
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
        if (chapters.isEmpty() && sourceUrl.isNotBlank() && sourceUrl.contains("4read.org")) {
            // Spec-14 T5: the adapter owns the page parse; the catalog only
            // persists what the seam's SourceBookDetail carries.
            val detail = fourReadAdapter.fetchBookPage(sourceUrl)
            if (detail.chapters.isNotEmpty()) {
                val realChapters = detail.chapters.mapIndexed { index, chapter ->
                    ChapterEntity(
                        id = "${bookId}_ch_${index + 1}",
                        bookId = bookId,
                        chapterIndex = index,
                        title = "Глава ${index + 1} (${book?.title ?: "4read"})",
                        durationSeconds = 0L, // unknown until the stream is actually played
                        streamUrl = chapter.streamUrl
                    )
                }
                dao.insertChapters(realChapters)
                // Back-fill the real chapter count, the site's own total
                // duration ("Триває:"), and the real author/narrator/genre/
                // rating/series now that we've fetched the book page — the
                // catalogue seed only ever had placeholders.
                val knownDuration = detail.totalDurationSeconds ?: book?.totalDurationSeconds ?: 0L
                dao.updateBookStats(bookId, realChapters.size, knownDuration)
                val author = detail.author.ifBlank { null }
                val narrator = detail.narrator.ifBlank { null }
                val genres = detail.genres.joinToString(" · ").ifBlank { null }
                val rating = detail.rating?.toFloat()
                val seriesTitle = detail.series?.name
                val seriesIndex = detail.series?.position
                val seriesUrl = detail.series?.url
                if (author != null || narrator != null || genres != null ||
                    rating != null || seriesTitle != null || seriesUrl != null
                ) {
                    dao.updateBookMetadata(
                        bookId,
                        author = author,
                        narrator = narrator,
                        genre = genres,
                        rating = rating,
                        seriesTitle = seriesTitle,
                        seriesIndex = seriesIndex,
                        seriesUrl = seriesUrl
                    )
                }
                // Cover via a targeted UPDATE, not a REPLACE insert: the row
                // carries freshly back-filled metadata above, and a full-row
                // re-insert with the stale seed entity would clobber it back
                // to the placeholders ("4read.org" etc.).
                if (!detail.coverImageUrl.isNullOrBlank()) {
                    dao.updateCoverImageUrl(bookId, detail.coverImageUrl)
                }
                return realChapters
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
        return chapters
    }

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
        seriesIndex: Int? = null
    ): WorkWriteResult {
        val mergeKey = MergeKey.keyFor(title, author, narrator)
        val existing = if (mergeKey.isNotBlank()) dao.findWorkByMergeKey(mergeKey) else null
        val work = existing ?: if (mergeKey.isNotBlank()) {
            WorkEntity(
                id = mergeKey,
                mergeKey = mergeKey,
                title = title.trim(),
                author = author.trim(),
                narrator = narrator.trim(),
                seriesTitle = seriesTitle,
                seriesIndex = seriesIndex,
                coverImageUrl = coverImageUrl,
                addedAt = System.currentTimeMillis()
            ).also { dao.upsertWork(it) }
        } else {
            val id = "w-$sourceId-${stableIdOf(sourceUrl)}"
            WorkEntity(
                id = id,
                mergeKey = "",
                title = title.trim(),
                author = author.trim(),
                narrator = narrator.trim(),
                seriesTitle = seriesTitle,
                seriesIndex = seriesIndex,
                coverImageUrl = coverImageUrl,
                addedAt = System.currentTimeMillis()
            ).also { dao.upsertWork(it) }
        }
        val editionId = "${work.id}|$sourceId|${stableIdOf(sourceUrl)}"
        val editionAlreadyKnown = dao.getEditionsForWorkSync(work.id).any { it.id == editionId }
        dao.upsertEdition(
            EditionEntity(
                id = editionId,
                workId = work.id,
                sourceId = sourceId,
                sourceUrl = sourceUrl,
                streamOnly = streamOnly,
                coverImageUrl = coverImageUrl,
                durationSeconds = durationSeconds,
                addedAt = System.currentTimeMillis()
            )
        )
        return WorkWriteResult(work = work, workCreated = existing == null, editionCreated = !editionAlreadyKnown)
    }

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
     */
    suspend fun hydrateFourReadCatalog(): HydrationResult = withContext(Dispatchers.IO) {
        val sourceId = "4read"
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

        homepageBooks.forEach { write(it) }

        for (url in categoryUrls) {
            val html = try {
                fourReadFetcher.getText(url)
            } catch (e: Exception) {
                ""
            }
            if (html.isBlank()) {
                failed++
                continue
            }
            val books = try {
                CatalogParser.parseSeriesPage(html)
            } catch (e: Exception) {
                emptyList()
            }
            if (books.isEmpty()) {
                failed++
                continue
            }
            books.forEach { write(it) }
        }

        HydrationResult(sourceId, found = found, imported = imported, merged = merged, failed = failed)
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
     * Book ids deleted by the user (spec #8 T3). The 4read homepage re-lists
     * deleted books, so without a tombstone the next catalogue sync would
     * resurrect them in Room and in the Explore rows (code-review MEDIUM).
     * Since v11 (wayfinder #55 Q8) the tombstone is the durable `tombstones`
     * table — a delete survives restarts and is cleared only when the user
     * explicitly imports the book again.
     */
    private suspend fun tombstonedBookIds(): Set<String> = dao.getTombstoneBookIds().toSet()

    /**
     * Syncs the Explore catalogue from the live 4read.org homepage: parses the
     * sections, upserts every book into Room (so rows stay playable even if a
     * later parse fails) and publishes the sections to [catalogSections].
     * Never throws: network/parse failures degrade to an empty catalogue.
     * This is the explicit sync call the composition root invokes when the app
     * wants catalogue sync — constructing the module performs no network I/O.
     */
    suspend fun fetchCatalogSections(): List<CatalogSection> =
        withContext(Dispatchers.IO) {
            _isCatalogLoading.value = true
            try {
                val html = fourReadFetcher.getText("https://4read.org/")
                if (html.isBlank()) return@withContext emptyList()
                _catalogGenres.value = CatalogParser.parseGenreNav(html)
                val tombstones = tombstonedBookIds()
                val sections = CatalogParser.parseHomepage(html)
                    .map { section ->
                        section.copy(books = section.books.filter { it.id !in tombstones })
                    }
                    .filter { it.books.isNotEmpty() || it.series.isNotEmpty() }
                sections.forEach { section ->
                    section.books.forEach { book -> libraryImport.upsertCatalogBook(book) }
                }
                _catalogSections.value = sections
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
    suspend fun fetchSeriesBooks(seriesUrl: String): List<AudiobookEntity> =
        withContext(Dispatchers.IO) {
            seriesBooksCache[seriesUrl]?.let { return@withContext it }
            val html = fourReadFetcher.getText(seriesUrl)
            if (html.isBlank()) return@withContext emptyList()
            val books = CatalogParser.parseSeriesPage(html)
                .filter { it.id !in tombstonedBookIds() }
                .map { book -> libraryImport.upsertCatalogBook(book) }
            seriesBooksCache[seriesUrl] = books
            books
        }

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
        fetchSeriesBooks(genreUrl)

    /**
     * ТОП 100 АудіоКниг (`/top-100.html`): ranked `linek` cards, not posters.
     * Upserted into Room (like series/genre pages) so every entry is playable
     * and opens its own detail. Cached per session; rank is the list order.
     */
    private var top100Cache: List<AudiobookEntity>? = null
    suspend fun fetchTop100(): List<AudiobookEntity> =
        withContext(Dispatchers.IO) {
            top100Cache?.let { return@withContext it }
            val html = fourReadFetcher.getText("https://4read.org/top-100.html")
            if (html.isBlank()) return@withContext emptyList()
            val books = CatalogParser.parseTop100(html)
                .filter { it.id !in tombstonedBookIds() }
                .map { libraryImport.upsertCatalogBook(it) }
            top100Cache = books
            books
        }

    /** Виконавці/Автори index pages, cached per URL for the session. */
    private val peopleCache = java.util.concurrent.ConcurrentHashMap<String, List<CatalogPerson>>()
    suspend fun fetchPeople(url: String): List<CatalogPerson> = withContext(Dispatchers.IO) {
        peopleCache[url]?.let { return@withContext it }
        val html = fourReadFetcher.getText(url)
        if (html.isBlank()) return@withContext emptyList()
        val people = CatalogParser.parsePeopleList(html)
        peopleCache[url] = people
        people
    }

    /**
     * Books narrated/written by one person. The `/xfsearch/<kind>/<name>/`
     * page is a poster grid, so the series-page fetch applies unchanged.
     * The person's name is URL-encoded (the site serves raw Cyrillic paths).
     */
    suspend fun fetchPersonBooks(path: String): List<AudiobookEntity> {
        val encoded = "https://4read.org" + android.net.Uri.encode(path, "/")
        return fetchSeriesBooks(encoded)
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
                .filter { it.id !in tombstonedBookIds() }
                .map { libraryImport.upsertCatalogBook(it) }
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
    suspend fun upsertCatalogBook(book: CatalogBook): AudiobookEntity =
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
