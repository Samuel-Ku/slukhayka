package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.R
import com.example.data.HASH_BUFFER_SIZE
import com.example.data.catalog.CatalogBook
import com.example.data.catalog.CatalogGenre
import com.example.data.catalog.CatalogParser
import com.example.data.catalog.CatalogPerson
import com.example.data.catalog.CatalogSection
import com.example.data.db.*
import com.example.data.contentHashOf
import com.example.data.imports.FolderRescan
import com.example.data.imports.LocalAudioEntry
import com.example.data.imports.LocalFolderScanner
import com.example.data.merge.MergeKey
import com.example.data.sha256Hex
import com.example.data.source.AudiobookMp3Adapter
import com.example.data.source.FourReadAdapter
import com.example.data.source.GlobalSearchResult
import com.example.data.source.HttpFetcher
import com.example.data.source.LihtarAdapter
import com.example.data.source.SluhayAdapter
import com.example.data.source.SluhayuaAdapter
import com.example.data.source.SoundBooksAdapter
import com.example.data.source.SourceAdapter
import com.example.data.source.SourceBook
import com.example.data.source.SourceBookDetail
import com.example.data.source.headersFor
import com.example.data.source.mergeGlobalSearchResults
import com.example.data.source.sourceDisplayName
import com.example.data.source.sourceIdForUrl
import com.example.data.source.streamOnlyFor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class AudiobookRepository(
    private val dao: AudiobookDao,
    private val context: Context? = null,
    /**
     * Whether construction should kick off the background seed + 4read catalogue
     * sync. Production leaves this `true`; JVM unit tests (GitHub issue #6) set
     * it to `false` so a fixture-driven test never performs network I/O and
     * never races the seeder for the same rows.
     */
    private val autoSyncOnInit: Boolean = true,
    // Spec-10 T4 + spec-13 T2: injectable for repository-seam tests (fake
    // adapters, no network). Default = every verified source behind the seam;
    // sluhay (WebView-pattern, spec-13) joins now that its adapter parses the
    // captured page + fetches the inline playlist with the source Referer.
    private val sourceAdapters: List<SourceAdapter> = listOf(
        FourReadAdapter(),
        SoundBooksAdapter(),
        AudiobookMp3Adapter(),
        LihtarAdapter(),
        SluhayuaAdapter(),
        // Spec-13 T4: the «Нове з Sluhay» feed hydrates the homepage through
        // the live WebView session — the adapter carries the session's cookies
        // from the WebView jar (cf_clearance etc.). The lambda only runs on
        // fetchNew (Android-side), so JVM fixture tests stay free of WebView.
        SluhayAdapter(cookieProvider = {
            runCatching {
                android.webkit.CookieManager.getInstance().getCookie("https://sluhay.com/")
            }.getOrNull().orEmpty()
        })
    )
) {

    val allBooks: Flow<List<AudiobookEntity>> = dao.getAllAudiobooks()
    val downloadedBooks: Flow<List<AudiobookEntity>> = dao.getDownloadedAudiobooks()
    val allBookmarks: Flow<List<BookmarkEntity>> = dao.getAllBookmarks()
    val recentProgress: Flow<List<PlaybackProgressEntity>> = dao.getAllPlaybackProgress()

    // Wayfinder #39: every chapter, for the library's cumulative position and
    // real total durations. One query; recomputed in memory on change.
    val allChapters: Flow<List<ChapterEntity>> = dao.getAllChapters()

    // Spec-10 T3/T4 + spec-11 T3: every verified server-fetch source behind
    // the adapter seam. sluhay/sluhayknigi (Cloudflare, WebView-pattern) are
    // NOT here — they need the WebView-pattern workstream (wayfinder #70). The
    // 4read parser lives behind the seam too; the legacy 4read fetch paths
    // delegate to it so markup changes fail only its tests.
    private val fourReadAdapter: SourceAdapter =
        sourceAdapters.firstOrNull { it.sourceId == "4read" } ?: FourReadAdapter()

    /**
     * Shared transport for the Explore catalogue doors (homepage/series/
     * top-100/people fetches — spec #8, a separate feature from the import
     * seam). Same HttpFetcher the adapters use; the repository owns no HTTP
     * client of its own (spec-14 T5).
     */
    private val fourReadFetcher: HttpFetcher = HttpFetcher(referer = "https://4read.org/")

    // ---------------------------------------------------------------------
    // Multi-source helpers (spec-10 T2)
    // ---------------------------------------------------------------------

    /**
     * Maps a book URL to its stable source id (the `type` of the `sources`
     * table). Blank URL = a local import. Spec-15 T6: delegates to the pure
     * [sourceIdForUrl] so the library model badges its cards with the same id.
     */
    fun sourceTypeOfUrl(url: String): String = sourceIdForUrl(url)

    /**
     * Spec-13 T2 — per-source stream headers for a book's chapter URL (the
     * player attaches these to the MediaItem so the CDN Referer gate is met
     * per book, never globally). Empty for sources that serve plain GETs.
     */
    fun streamHeadersFor(book: AudiobookEntity, streamUrl: String): Map<String, String> =
        headersFor(sourceTypeOfUrl(book.sourceUrl), streamUrl)

    /**
     * The playback-position key of a book: its current (primary) source type.
     * Local imports have a blank sourceUrl, hence key "local".
     */
    fun sourceKeyFor(book: AudiobookEntity): String = sourceTypeOfUrl(book.sourceUrl)

    fun observeSources(bookId: String): Flow<List<SourceEntity>> = dao.getSourcesForBook(bookId)
    suspend fun getSourcesForBook(bookId: String): List<SourceEntity> = dao.getSourcesForBookSync(bookId)

    /**
     * Spec-10 T2 — the multi-source import core. Turns a parsed source book
     * (from a [SourceAdapter]) into a Work row plus a Source row. When a book
     * with the same merge key (normalized title|author|narrator) already
     * exists, the new source is attached to it and the existing Work is
     * returned — one library card, several sources, no duplicates.
     */
    suspend fun importBookFromSource(sourceId: String, detail: SourceBookDetail): AudiobookEntity =
        withContext(Dispatchers.IO) {
            val mergeKey = MergeKey.keyFor(detail.title, detail.author, detail.narrator)
            val existing = if (mergeKey.isNotBlank()) dao.findByMergeKey(mergeKey) else null
            val bookId = existing?.id ?: adapterBookId(sourceId, detail.url)

            if (existing == null) {
                // Spec-14 T2/T3: the shared import path persists the enriched
                // profile the seam now provides (genres → genre, rating,
                // series) — every import door's card agrees with the source.
                val book = AudiobookEntity(
                    id = bookId,
                    title = detail.title,
                    author = detail.author.ifBlank { sourceId },
                    narrator = detail.narrator.ifBlank { "$sourceId narrator" },
                    description = "Аудіокнига з джерела $sourceId. Джерело: ${detail.url}",
                    coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
                    coverImageUrl = detail.coverImageUrl,
                    genre = detail.genres.joinToString(" · ").ifBlank { "Каталог" },
                    sourceUrl = detail.url,
                    isDownloaded = false,
                    downloadProgress = 0f,
                    totalDurationSeconds = detail.chapters.sumOf { it.durationSeconds }.takeIf { it > 0L } ?: 0L,
                    totalChapters = detail.chapters.size,
                    rating = detail.rating?.toFloat() ?: 0f,
                    seriesTitle = detail.series?.name,
                    seriesIndex = detail.series?.position,
                    seriesUrl = detail.series?.url,
                    mergeKey = mergeKey
                )
                dao.insertAudiobooks(listOf(book))
                dao.insertChapters(
                    detail.chapters.mapIndexed { index, ch ->
                        ChapterEntity(
                            id = "${bookId}_ch${index + 1}",
                            bookId = bookId,
                            chapterIndex = index,
                            title = ch.title.ifBlank { "Глава ${index + 1}" },
                            durationSeconds = ch.durationSeconds,
                            streamUrl = ch.streamUrl
                        )
                    }
                )
                dao.insertSources(listOf(sourceRow(sourceId, bookId, detail.url)))
                book
            } else {
                // Merge: attach the new source unless it is already known.
                val known = dao.getSourcesForBookSync(existing.id).any { it.url == detail.url }
                if (!known) {
                    dao.insertSources(listOf(sourceRow(sourceId, existing.id, detail.url)))
                }
                existing
            }
        }

    private fun sourceRow(sourceId: String, bookId: String, url: String) = SourceEntity(
        id = "$sourceId-$bookId",
        bookId = bookId,
        type = sourceId,
        url = url,
        streamOnly = streamOnlyFor(sourceId),
        addedAt = System.currentTimeMillis()
    )

    /**
     * Spec-10 T6 — whether the book's primary source is stream-only (its ToS
     * permits streaming but not downloading). The gate is derived from the
     * book's primary [AudiobookEntity.sourceUrl] so it covers every book,
     * including those imported before the `sources` table existed.
     */
    fun isStreamOnly(book: AudiobookEntity): Boolean = streamOnlyFor(sourceTypeOfUrl(book.sourceUrl))

    /**
     * Spec-14 T5 — the book id for an import door: the source's adapter owns
     * its id scheme ([SourceAdapter.bookId]), so no import door derives ids
     * itself and no scheme can diverge. The generic "<sourceId>-<slug>"
     * fallback only covers a source without a registered adapter (defensive;
     * every live source has one).
     */
    private fun adapterBookId(sourceId: String, url: String): String =
        sourceAdapters.firstOrNull { it.sourceId == sourceId }?.bookId(url)
            ?: genericSourceBookId(sourceId, url)

    private fun genericSourceBookId(sourceId: String, url: String): String {
        val slug = url.substringAfterLast('/').substringBefore('?')
            .removeSuffix(".html")
            .removeSuffix(".m3u")
            .ifBlank { "book-${System.currentTimeMillis()}" }
        return "$sourceId-$slug"
    }

    /**
     * Spec-15 T5 — what ONE source says about a Work, for the labelled
     * per-source blocks on the book detail page. Built from that source's own
     * page through its adapter ([SourceBookDetail]); the aggregate profile of
     * the Work stays on the primary [AudiobookEntity].
     */
    data class SourceProfile(
        val sourceId: String,
        val sourceName: String,
        val url: String,
        val description: String = "",
        val rating: Double? = null,
        val narrator: String = "",
        val genres: List<String> = emptyList()
    )

    /**
     * Spec-15 T5 — the per-source aggregation of a Work's detail: for every
     * Source row carrying the book, fetch that source's page through its own
     * adapter and render what IT says (description, rating, narrator, genres).
     * Best-effort per source — a failing source simply contributes no block,
     * never a blank page. Uses the existing adapter seam, no third parser.
     */
    suspend fun fetchSourceProfiles(bookId: String): List<SourceProfile> =
        withContext(Dispatchers.IO) {
            val sources = dao.getSourcesForBookSync(bookId)
            sources.mapNotNull { source ->
                val adapter = sourceAdapters.firstOrNull { it.sourceId == source.type }
                    ?: return@mapNotNull null
                try {
                    val detail = adapter.fetchBookPage(source.url)
                    // A page that yielded nothing (blank title AND no chapters)
                    // is a failure, not an empty block.
                    if (detail.title.isBlank() && detail.chapters.isEmpty()) return@mapNotNull null
                    SourceProfile(
                        sourceId = source.type,
                        sourceName = sourceDisplayName(source.type),
                        url = source.url,
                        description = detail.description,
                        rating = detail.rating,
                        narrator = detail.narrator,
                        genres = detail.genres
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }

    // ---------------------------------------------------------------------
    // Global search (spec-10 T4)
    // ---------------------------------------------------------------------

    // Per-source «new arrivals» feeds are cached in memory so repeated search
    // keystrokes never re-fetch the same homepage; the cache is a session
    // convenience, safe to lose.
    private class CachedFeed(val fetchedAt: Long, val books: List<SourceBook>)

    private val newFeedCache = java.util.concurrent.ConcurrentHashMap<String, CachedFeed>()

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
                if (now - cached.fetchedAt < NEW_FEED_TTL_MS) return cached.books
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
                if (now - cached.fetchedAt < NEW_FEED_TTL_MS) return cached.books
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
     * Spec-10 T4 — import-and-play entry point for a search result: fetch the
     * book page from the chosen source, import the Work (merging into an
     * existing card when the merge key matches), return the stored book. Null
     * when the source is unknown or the page yields nothing playable.
     */
    suspend fun importFromSourceUrl(sourceId: String, url: String): AudiobookEntity? =
        withContext(Dispatchers.IO) {
            val adapter = sourceAdapters.firstOrNull { it.sourceId == sourceId }
                ?: return@withContext null
            try {
                val detail = adapter.fetchBookPage(url)
                if (detail.chapters.isEmpty()) return@withContext null
                importBookFromSource(sourceId, detail)
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Spec-13 T3 — import a WebView-source book from its CAPTURED page HTML.
     * The page HTML comes from the live browser session (past the Cloudflare
     * challenge — server-fetch would 403); the adapter's captured-page path
     * parses metadata + the inline Playerjs playlist and fetches the playlist
     * with the source Referer. Null when the source is unknown, the page is
     * unparseable or yields nothing playable.
     */
    suspend fun importWebSourcePage(sourceId: String, url: String, html: String): AudiobookEntity? =
        withContext(Dispatchers.IO) {
            val adapter = sourceAdapters.firstOrNull { it.sourceId == sourceId } as? SluhayAdapter
                ?: return@withContext null
            try {
                val detail = adapter.detailFromCapturedHtml(html, url)
                if (detail.chapters.isEmpty()) return@withContext null
                importBookFromSource(sourceId, detail)
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Spec-15 T3 — the WebView catalogue hydration tool (debug-only by
     * construction: the browser surface that exposes it is debug-gated, T2).
     * Crawls a WebView-source's catalogue through the live session
     * ([SourceAdapter.fetchCatalog] — session cookies past Cloudflare),
     * fetches each book page with the same session and imports through the
     * shared [importBookFromSource] path (MergeKey dedup — re-hydration adds
     * new books without duplicating existing Works). Best-effort per book: a
     * failing book simply counts as failed, never aborts the crawl.
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
                    importBookFromSource(sourceId, detail)
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
    // Catalogue sections (spec #8 tickets T5/T6): rows for the Explore
    // screen, parsed from the 4read.org homepage and cached in memory.
    //
    // Declared BEFORE the init block on purpose: init launches
    // fetchCatalogSections() on an IO coroutine, and with an idle dispatcher
    // the coroutine can start undispatched — i.e. run synchronously while the
    // constructor is still on the stack. Fields declared after init would
    // still be null at that point and the sync would crash with an NPE
    // (observed on-device: cold-start crash in fetchCatalogSections).
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
     * Book ids deleted this session (spec #8 T3). The 4read homepage re-lists
     * deleted books, so without a tombstone the next catalogue sync would
     * resurrect them in Room and in the Explore rows (code-review MEDIUM).
     */
    private val deletedCatalogBookIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        if (autoSyncOnInit) {
            CoroutineScope(Dispatchers.IO).launch {
                // Spec #8 ticket T1: a fresh install starts with an empty
                // catalogue (the mock seed books are gone); the catalogue
                // fills from the live 4read.org homepage.
                fetchCatalogSections()
            }
        }
    }

    /**
     * Syncs the Explore catalogue from the live 4read.org homepage: parses the
     * sections, upserts every book into Room (so rows stay playable even if a
     * later parse fails) and publishes the sections to [catalogSections].
     * Never throws: network/parse failures degrade to an empty catalogue.
     */
    suspend fun fetchCatalogSections(): List<CatalogSection> = withContext(Dispatchers.IO) {
        _isCatalogLoading.value = true
        try {
            val html = fourReadFetcher.getText("https://4read.org/")
            if (html.isBlank()) return@withContext emptyList()
            _catalogGenres.value = CatalogParser.parseGenreNav(html)
            val sections = CatalogParser.parseHomepage(html)
                .map { section ->
                    section.copy(books = section.books.filter { it.id !in deletedCatalogBookIds })
                }
                .filter { it.books.isNotEmpty() || it.series.isNotEmpty() }
            sections.forEach { section ->
                section.books.forEach { book -> upsertCatalogBook(book) }
            }
            _catalogSections.value = sections
            sections
        } catch (e: Exception) {
            Log.w("AudiobookRepo", "Catalogue sync failed", e)
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
    suspend fun fetchSeriesBooks(seriesUrl: String): List<AudiobookEntity> = withContext(Dispatchers.IO) {
        seriesBooksCache[seriesUrl]?.let { return@withContext it }
        val html = fourReadFetcher.getText(seriesUrl)
        if (html.isBlank()) return@withContext emptyList()
        val books = CatalogParser.parseSeriesPage(html)
            .filter { it.id !in deletedCatalogBookIds }
            .map { book -> upsertCatalogBook(book) }
        seriesBooksCache[seriesUrl] = books
        books
    }

    /**
     * The next volume of the series a book belongs to (spec-9 T4). Returns
     * null when the book has no series, the series page cannot be loaded, or
     * the current book is the last volume — the UI then hides the block.
     * Network failures degrade to null, never to an exception.
     */
    suspend fun findNextInSeries(book: AudiobookEntity): AudiobookEntity? = withContext(Dispatchers.IO) {
        val url = book.seriesUrl ?: return@withContext null
        if (url.isBlank()) return@withContext null
        try {
            val seriesBooks = fetchSeriesBooks(url)
            nextInSeries(book.seriesIndex, book.id, seriesBooks)
        } catch (e: Exception) {
            Log.w("AudiobookRepo", "Next-in-series lookup failed for ${book.id}", e)
            null
        }
    }

    /**
     * All books of a genre (category) page — `4read.org/<genre>/` — e.g.
     * `https://4read.org/fentezi/`. Genre pages reuse the poster markup of the
     * homepage, so the series-page parser and cache apply unchanged.
     */
    suspend fun fetchGenreBooks(genreUrl: String): List<AudiobookEntity> = fetchSeriesBooks(genreUrl)

    /**
     * ТОП 100 АудіоКниг (`/top-100.html`): ranked `linek` cards, not posters.
     * Upserted into Room (like series/genre pages) so every entry is playable
     * and opens its own detail. Cached per session; rank is the list order.
     */
    private var top100Cache: List<AudiobookEntity>? = null
    suspend fun fetchTop100(): List<AudiobookEntity> = withContext(Dispatchers.IO) {
        top100Cache?.let { return@withContext it }
        val html = fourReadFetcher.getText("https://4read.org/top-100.html")
        if (html.isBlank()) return@withContext emptyList()
        val books = CatalogParser.parseTop100(html)
            .filter { it.id !in deletedCatalogBookIds }
            .map { upsertCatalogBook(it) }
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
    suspend fun fetchRelatedBooks(bookId: String): List<AudiobookEntity> = withContext(Dispatchers.IO) {
        val book = dao.getAudiobookById(bookId) ?: return@withContext emptyList()
        val sourceUrl = book.sourceUrl
        if (!sourceUrl.contains("4read.org")) return@withContext emptyList()
        // Spec-14 T5: the repository performs no 4read parsing or transport —
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
            .filter { it.id !in deletedCatalogBookIds }
            .map { upsertCatalogBook(it) }
    }

    private val seriesBooksCache = java.util.concurrent.ConcurrentHashMap<String, List<AudiobookEntity>>()

    /**
     * Inserts the book if absent; otherwise returns the stored row. Series
     * metadata (spec-9 T1) is written on insert and back-filled on an existing
     * row when the parsed poster carries it, so a later homepage sync enriches
     * previously-known books without touching user state (favourite/download).
     * `internal` so JVM tests can drive the parser→entity mapping without a
     * network round-trip.
     */
    internal suspend fun upsertCatalogBook(book: CatalogBook): AudiobookEntity {
        val existing = dao.getAudiobookById(book.id)
        if (existing != null) {
            var updated = existing
            // Legacy placeholder cleanup: catalogue books were once seeded with
            // a fabricated 4:00:00 (14400s) and 5 chapters. Treat that exact
            // value as unknown so it never renders as real; the real duration
            // is back-filled from the book page (refreshBookCoverAndDetails).
            // The stored chapter count may already be REAL (a page fetch with
            // no parseable duration keeps 14400s but writes the true chapter
            // count), so only the duration is reset — never the chapters.
            if (existing.totalDurationSeconds == 14400L) {
                dao.updateBookStats(book.id, existing.totalChapters, 0L)
                updated = updated.copy(totalDurationSeconds = 0L)
            }
            // Enrich with a real duration this source carries (e.g. the ТОП 100
            // page's "Триває:") — never clobber a known value with 0.
            if (book.totalDurationSeconds > 0L && updated.totalDurationSeconds != book.totalDurationSeconds) {
                dao.updateBookStats(book.id, updated.totalChapters, book.totalDurationSeconds)
                updated = updated.copy(totalDurationSeconds = book.totalDurationSeconds)
            }
            if (book.seriesUrl != null &&
                (updated.seriesUrl != book.seriesUrl ||
                    updated.seriesTitle != book.seriesTitle ||
                    updated.seriesIndex != book.seriesIndex)
            ) {
                dao.updateSeriesFields(book.id, book.seriesTitle, book.seriesUrl, book.seriesIndex)
                updated = updated.copy(
                    seriesTitle = book.seriesTitle,
                    seriesUrl = book.seriesUrl,
                    seriesIndex = book.seriesIndex
                )
            }
            // Return the known updated shape instead of re-querying: the
            // row may be deleted concurrently and `!!` on a re-query would
            // crash the whole catalogue sync.
            return updated
        }
        val newBook = AudiobookEntity(
            id = book.id,
            title = book.title,
            author = book.author.ifBlank { "4read.org" },
            narrator = "4read Voice Narrator",
            description = "Аудіокнига з каталогу 4read.org. Джерело: ${book.url}",
            coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
            coverImageUrl = book.coverImageUrl,
            genre = "4read Каталог",
            sourceUrl = book.url,
            isDownloaded = false,
            downloadProgress = 0f,
            // The catalogue homepage doesn't know the chapter count or total
            // duration — they're back-filled from the real chapter list once
            // the book page is fetched (see getChaptersList). Sources that DO
            // carry a real duration (ТОП 100's "Триває:") keep it; unknown is
            // 0, never a fabricated "5 Ch. • 4:00:00".
            totalDurationSeconds = book.totalDurationSeconds,
            totalChapters = 0,
            rating = 0f,
            seriesTitle = book.seriesTitle,
            seriesUrl = book.seriesUrl,
            seriesIndex = book.seriesIndex
        )
        dao.insertAudiobooks(listOf(newBook))
        return newBook
    }

    /**
     * Cascading book deletion (spec #8 tickets T2/T3): removes local audio
     * files, chapters, bookmarks, playback progress and finally the book
     * itself. The entities have no FK constraints, so the cascade is
     * coordinated here.
     */
    suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        deletedCatalogBookIds.add(bookId)
        dao.getChaptersListForBook(bookId).forEach { chapter ->
            chapter.localFilePath?.let { path ->
                try {
                    val file = File(path)
                    if (file.exists()) file.delete()
                } catch (e: Exception) {
                    Log.w("AudiobookRepo", "Failed to delete file $path", e)
                }
            }
        }
        dao.deleteChaptersForBook(bookId)
        dao.deleteBookmarksForBook(bookId)
        dao.deletePlaybackProgressForBook(bookId)
        dao.deleteSourcesForBook(bookId)
        dao.deletePlaybackEventsForBook(bookId)
        dao.deleteAudiobook(bookId)
    }

    /**
     * Level-1 deletion — "прибрати з медіатеки" (wayfinder #28): the Room rows
     * (book, chapters, bookmarks, progress) are removed but downloaded audio
     * files stay on disk, so nothing physical is lost. The book can be
     * re-added from the catalogue.
     */
    suspend fun removeFromLibrary(bookId: String) = withContext(Dispatchers.IO) {
        deletedCatalogBookIds.add(bookId)
        dao.deleteChaptersForBook(bookId)
        dao.deleteBookmarksForBook(bookId)
        dao.deletePlaybackProgressForBook(bookId)
        dao.deleteSourcesForBook(bookId)
        dao.deletePlaybackEventsForBook(bookId)
        dao.deleteAudiobook(bookId)
    }

    /** Per-book preferred playback speed (wayfinder #26); null clears the preference. */
    suspend fun setPreferredSpeed(bookId: String, speed: Float?) = dao.updatePreferredSpeed(bookId, speed)

    /** Real chapter duration discovered during playback (replaces unknown 0). */
    suspend fun updateChapterDuration(chapterId: String, durationSeconds: Long) =
        dao.updateChapterDuration(chapterId, durationSeconds)

    /** Real chapter count / total duration once the book's chapters are known. */
    suspend fun updateBookStats(bookId: String, totalChapters: Int, totalDurationSeconds: Long) =
        dao.updateBookStats(bookId, totalChapters, totalDurationSeconds)

    /** Back-fills real page metadata (author/narrator/genre/rating/series). */
    suspend fun updateBookMetadata(
        bookId: String,
        author: String? = null,
        narrator: String? = null,
        genre: String? = null,
        rating: Float? = null,
        seriesTitle: String? = null,
        seriesIndex: Int? = null,
        seriesUrl: String? = null
    ) = dao.updateBookMetadata(bookId, author, narrator, genre, rating, seriesTitle, seriesIndex, seriesUrl)

    /** Last-pause marker for the smart rewind (wayfinder #25); null clears it. */
    suspend fun updatePausedAt(bookId: String, pausedAt: Long?, sourceKey: String = "") =
        dao.updatePausedAt(bookId, pausedAt, sourceKey)

    /**
     * Appends one row to the durable playback-failure ledger (wayfinder #52).
     * Called from the player's failure path; write failures here are logged,
     * never thrown back into playback.
     */
    suspend fun recordPlaybackFailure(
        bookId: String,
        chapterIndex: Int,
        errorCodeName: String,
        streamUrl: String,
        audioEngineMode: String
    ) = withContext(Dispatchers.IO) {
        dao.insertPlaybackFailure(
            PlaybackFailureEntity(
                timestamp = System.currentTimeMillis(),
                bookId = bookId,
                chapterIndex = chapterIndex,
                errorCodeName = errorCodeName,
                streamUrl = streamUrl,
                audioEngineMode = audioEngineMode
            )
        )
    }

    // ---------------------------------------------------------------------
    // Local audio import (spec #8 ticket T7): one picked file = one book.
    // ---------------------------------------------------------------------

    /**
     * Copies a user-picked audio file (SAF content Uri) into private app
     * storage and creates a single-chapter book whose chapter points at the
     * local file.
     */
    suspend fun importLocalAudioFile(uri: Uri): AudiobookEntity = withContext(Dispatchers.IO) {
        val ctx = context ?: throw IllegalStateException("importLocalAudioFile called without Context")
        val displayName = queryDisplayName(ctx, uri) ?: "Аудіокнига"
        val input = ctx.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("Не вдалося відкрити файл $uri")
        importLocalAudioStream(displayName, input)
    }

    /**
     * Creates a single-chapter book from an audio stream. Kept separate from
     * [importLocalAudioFile] so JVM tests can drive it with a plain stream
     * without a content resolver (spec #8 ticket T7).
     *
     * Dedupe (wayfinder #48): if the copied bytes already exist in the
     * library, the fresh copy is deleted and the existing book is returned —
     * importing the same file twice never duplicates storage.
     */
    suspend fun importLocalAudioStream(displayName: String, stream: java.io.InputStream): AudiobookEntity =
        withContext(Dispatchers.IO) {
            val base = sanitizeLocalBaseName(displayName)
            val dest = copyLocalAudioStream(base, localFileExtension(displayName), stream)
            val existing = dao.getChapterByContentHash(dest.sha256Hex)
            if (existing != null) {
                File(dest.path).delete()
                return@withContext dao.getAudiobookById(existing.bookId)
                    ?: throw java.io.IOException("Дублікат файлу, але книгу не знайдено")
            }
            insertLocalBook(
                title = base,
                author = LOCAL_FILE_AUTHOR,
                description = "Імпортований аудіофайл: $displayName",
                chapters = listOf(LocalChapterInput(title = base, filePath = dest.path, contentHash = dest.sha256Hex))
            )
        }

    /**
     * Folder import (spec #8 Block 4): walks the SAF tree picked via
     * `OpenDocumentTree` (recursively collecting mp3/m4a/ogg audio files) and
     * delegates the grouping/insertion to the testable [importAudioEntries]
     * core.
     */
    suspend fun importLocalAudioFolder(treeUri: Uri): LocalImportResult = withContext(Dispatchers.IO) {
        val ctx = context ?: throw IllegalStateException("importLocalAudioFolder called without Context")
        val entries = LocalFolderScanner.scan(ctx, treeUri)
        importAudioEntries(entries, treeUri.toString())
    }

    /**
     * Core of the local import (T7 single-file + Block 4 folder): groups the
     * scanned files and materialises them as books in Room.
     *
     * Grouping rule: files at the root of the picked tree become one
     * single-chapter book each (exactly like the single-file import); every
     * sub-folder becomes one multi-chapter book whose chapters are its audio
     * files sorted naturally by file name (track1 → track2 → … → track10).
     * Unreadable files are skipped without failing the whole import.
     *
     * Dedupe (wayfinder #48): a file whose bytes already exist in the library
     * is never copied again — the fresh copy is deleted on the spot and
     * counted in [LocalImportResult.duplicateFiles].
     */
    suspend fun importAudioEntries(entries: List<LocalAudioEntry>, sourceTreeUri: String? = null): LocalImportResult =
        withContext(Dispatchers.IO) {
            var booksImported = 0
            var filesImported = 0
            var skippedFiles = 0
            var duplicateFiles = 0
            // Hashes seen earlier in THIS run (same-folder repeated files), so
            // dedupe is consistent even before the folder's chapters hit the DB.
            val seenHashes = mutableSetOf<String>()

            // Copy-then-hash; when the bytes already exist, delete the copy
            // and report a duplicate instead of a new chapter. `baseName` is
            // the copied-file stem; `chapterTitle` is what users see.
            suspend fun copyUnlessDuplicate(
                baseName: String,
                chapterTitle: String,
                extension: String,
                openStream: () -> java.io.InputStream
            ): LocalChapterInput? {
                val dest = try {
                    copyLocalAudioStream(baseName, extension, openStream())
                } catch (e: Exception) {
                    Log.w("AudiobookRepo", "Local import failed", e)
                    skippedFiles++
                    return null
                }
                if (!seenHashes.add(dest.sha256Hex) || dao.getChapterByContentHash(dest.sha256Hex) != null) {
                    File(dest.path).delete()
                    duplicateFiles++
                    return null
                }
                return LocalChapterInput(title = chapterTitle, filePath = dest.path, contentHash = dest.sha256Hex)
            }

            // 1) Loose files at the tree root → one single-chapter book each.
            for (entry in entries.filter { it.parentFolder.isNullOrBlank() }) {
                val base = sanitizeLocalBaseName(entry.fileName)
                val chapter = copyUnlessDuplicate(base, base, localFileExtension(entry.fileName), entry.openStream)
                    ?: continue
                insertLocalBook(
                    title = base,
                    author = LOCAL_FILE_AUTHOR,
                    description = "Імпортований аудіофайл: ${entry.fileName}",
                    chapters = listOf(chapter),
                    sourceTreeUri = sourceTreeUri
                )
                booksImported++
                filesImported++
            }

            // 2) Each sub-folder → one book; files become naturally-sorted chapters.
            for ((folder, files) in entries.filter { !it.parentFolder.isNullOrBlank() }.groupBy { it.parentFolder }) {
                if (folder.isNullOrBlank()) continue
                // Title from the last path segment so a relative path like
                // "SeriesA/Кобзар" still yields a clean "Кобзар" book name.
                val bookTitle = sanitizeLocalBaseName(folder.substringAfterLast('/')).ifBlank { "Аудіокнига" }
                val chapters = mutableListOf<LocalChapterInput>()
                for (entry in files.sortedWith(Comparator { a, b -> compareNatural(a.fileName, b.fileName) })) {
                    val chapterTitle = sanitizeLocalBaseName(entry.fileName).ifBlank { entry.fileName }
                    val chapter = copyUnlessDuplicate("$bookTitle-$chapterTitle", chapterTitle, localFileExtension(entry.fileName), entry.openStream)
                        ?: continue
                    chapters.add(chapter)
                    filesImported++
                }
                if (chapters.isNotEmpty()) {
                    insertLocalBook(
                        title = bookTitle,
                        author = LOCAL_FOLDER_AUTHOR,
                        description = "Імпортовано з папки «$folder» — ${chapters.size} файл(ів)",
                        chapters = chapters,
                        sourceTreeUri = sourceTreeUri
                    )
                    booksImported++
                }
            }

            LocalImportResult(
                booksImported = booksImported,
                filesImported = filesImported,
                skippedFiles = skippedFiles,
                duplicateFiles = duplicateFiles
            )
        }

    /** Strips the extension and unsafe characters from a file/folder display name. */
    private fun sanitizeLocalBaseName(displayName: String): String {
        val cleanBase = displayName.substringBeforeLast('.').trim().ifBlank { displayName }
        return cleanBase
            .replace(Regex("""[^\p{L}\p{N} _\-]"""), "")
            .ifBlank { "audiobook-${System.currentTimeMillis()}" }
    }

    /** Original extension of an audio file (lowercased), defaulting to mp3. */
    private fun localFileExtension(fileName: String): String =
        fileName.substringAfterLast('.', "").ifBlank { "mp3" }.lowercase().take(5)

    /** Copies a stream into the private local-imports dir under a unique name. */
    private fun copyLocalAudioStream(baseName: String, extension: String, stream: java.io.InputStream): CopiedLocalFile {
        val ctx = context ?: throw IllegalStateException("local import requires Context")
        val audioDir = File(ctx.filesDir, LOCAL_AUDIO_DIR)
        if (!audioDir.exists()) audioDir.mkdirs()
        // Unique suffix (counter-based, unlike the old timestamp-only one) so
        // rapid folder imports never collide within the same millisecond. The
        // original extension is preserved so ExoPlayer detects the container.
        val destFile = File(audioDir, "$baseName-${localImportSeq.incrementAndGet()}.$extension")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        stream.use { input ->
            destFile.outputStream().use { output ->
                val buffer = ByteArray(HASH_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
            }
        }
        return CopiedLocalFile(path = destFile.absolutePath, sha256Hex = sha256Hex(digest.digest()))
    }

    /** Creates one local book with the given chapters (title, localFilePath). */
    // ---------------------------------------------------------------------
    // Wayfinder #42: re-scanning a previously imported local folder
    // ---------------------------------------------------------------------

    /** Outcome of one [rescanLocalFolder] run — what changed in the tree. */
    data class RescanReport(
        val treeUri: String,
        val newChapters: Int = 0,
        val newBooks: Int = 0,
        val missingFiles: Int = 0,
        val movedFiles: Int = 0,
        val duplicateFiles: Int = 0
    )

    /**
     * Re-scans one previously imported SAF tree (wayfinder #42): walks it,
     * hashes every stream (no copies — files the library already knows never
     * touch disk), diffs against the stored chapters by content hash, applies
     * new files as chapters/books, and reports missing/moved/duplicate files.
     * The library entry and its private copies survive every outcome
     * (wayfinder #59): nothing here ever deletes a row or a file.
     */
    suspend fun rescanLocalFolder(treeUri: String): RescanReport = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext RescanReport(treeUri)
        val entries = runCatching { LocalFolderScanner.scan(ctx, Uri.parse(treeUri)) }.getOrElse {
            Log.w("AudiobookRepo", "Re-scan could not open tree $treeUri", it)
            return@withContext RescanReport(treeUri)
        }
        if (entries.isEmpty()) return@withContext RescanReport(treeUri)
        rescanAudioEntries(entries, treeUri)
    }

    /**
     * Testable core of the re-scan (wayfinder #42): hashes every stream (no
     * copies — files the library already knows never touch disk), diffs
     * against the stored chapters by content hash, applies new files as
     * chapters/books, and reports missing/moved/duplicate files. The library
     * entry and its private copies survive every outcome (wayfinder #59):
     * nothing here ever deletes a row or a file.
     */
    suspend fun rescanAudioEntries(entries: List<LocalAudioEntry>, treeUri: String): RescanReport =
        withContext(Dispatchers.IO) {
        // Hash every file once — pure stream read, the re-scan baseline.
        val scanned = entries.mapNotNull { entry ->
            val hash = runCatching { contentHashOf(entry.openStream()) }.getOrNull()
            if (hash.isNullOrBlank()) null else FolderRescan.RescanFile(entry.fileName, entry.parentFolder, hash)
        }
        if (scanned.isEmpty()) return@withContext RescanReport(treeUri)

        val libraryHashSet = allChapters.first().mapNotNull { it.contentHash }.toSet()
        val existingBooks = dao.getAudiobooksBySourceTree(treeUri)
        var report = RescanReport(treeUri)

        // Same grouping as the import: root files are single-chapter books,
        // each sub-folder is one multi-chapter book by its last path segment.
        val groups = scanned.groupBy { file ->
            file.parentFolder?.let { "folder:$it" } ?: "root:${sanitizeLocalBaseName(file.fileName)}"
        }
        for ((groupKey, files) in groups) {
            val isRoot = groupKey.startsWith("root:")
            val title = if (isRoot) groupKey.removePrefix("root:")
            else files.first().parentFolder?.substringAfterLast('/')?.let { sanitizeLocalBaseName(it) }.orEmpty()
            val book = existingBooks.firstOrNull { it.title == title }

            if (book == null) {
                // A book the library doesn't know from this tree yet: copy its
                // files through the shared dedupe core, then create the book.
                val newInputs = mutableListOf<LocalChapterInput>()
                for (file in files) {
                    val entry = entries.first { it.fileName == file.fileName && it.parentFolder == file.parentFolder }
                    copyNewLocalChapter(entry, sanitizeLocalBaseName(file.fileName), file.contentHash)?.let { newInputs.add(it) }
                }
                if (newInputs.isEmpty()) {
                    report = report.copy(duplicateFiles = report.duplicateFiles + files.size)
                    continue
                }
                val created = insertLocalBook(
                    title = title,
                    author = LOCAL_FOLDER_AUTHOR,
                    description = "Імпортовано з папки «${files.first().parentFolder ?: title}» — ${newInputs.size} файл(ів)",
                    chapters = newInputs,
                    sourceTreeUri = treeUri
                )
                report = report.copy(
                    newBooks = report.newBooks + 1,
                    newChapters = report.newChapters + newInputs.size,
                    duplicateFiles = report.duplicateFiles + (files.size - newInputs.size)
                )
                updateFingerprintFor(created.id)
                continue
            }

            // Known book: diff its chapters against this group's live files.
            val chapters = dao.getChaptersListForBook(book.id)
            val diff = FolderRescan.computeDiff(chapters, libraryHashSet, files)
            report = report.copy(
                missingFiles = report.missingFiles + diff.missingChapters.size,
                movedFiles = report.movedFiles + diff.movedFiles.size,
                duplicateFiles = report.duplicateFiles + diff.duplicateFiles.size
            )
            if (diff.newFiles.isNotEmpty()) {
                val newInputs = mutableListOf<LocalChapterInput>()
                for (file in diff.newFiles) {
                    val entry = entries.first { it.fileName == file.fileName && it.parentFolder == file.parentFolder }
                    copyNewLocalChapter(entry, sanitizeLocalBaseName(file.fileName), file.contentHash)?.let { newInputs.add(it) }
                }
                if (newInputs.isNotEmpty()) {
                    val merged = chapters.map { ch ->
                        LocalChapterInput(title = ch.title, filePath = ch.localFilePath ?: ch.streamUrl, contentHash = ch.contentHash.orEmpty())
                    } + newInputs
                    rewriteBookChapters(book.id, merged)
                    report = report.copy(newChapters = report.newChapters + newInputs.size)
                    updateFingerprintFor(book.id)
                } else {
                    report = report.copy(duplicateFiles = report.duplicateFiles + diff.newFiles.size)
                }
            }
        }
        report
    }

    /**
     * Re-scans every previously imported local tree, best-effort per tree:
     * one dead SAF grant (moved folder) fails that tree alone, never the rest.
     */
    suspend fun rescanAllLocalFolders(): List<RescanReport> = withContext(Dispatchers.IO) {
        dao.getImportedSourceTrees().map { tree ->
            runCatching { rescanLocalFolder(tree) }.getOrElse {
                Log.w("AudiobookRepo", "Re-scan failed for $tree", it)
                RescanReport(tree)
            }
        }
    }

    /** Copies a NEW local file to private storage, deduped against the library. */
    private suspend fun copyNewLocalChapter(
        entry: LocalAudioEntry,
        chapterTitle: String,
        contentHash: String
    ): LocalChapterInput? {
        // The diff classified it new, but a concurrent import may have landed
        // the same bytes — never copy twice.
        if (dao.getChapterByContentHash(contentHash) != null) return null
        val base = sanitizeLocalBaseName(entry.fileName)
        val dest = try {
            copyLocalAudioStream("$base-re${localImportSeq.incrementAndGet()}", localFileExtension(entry.fileName), entry.openStream())
        } catch (e: Exception) {
            Log.w("AudiobookRepo", "Re-scan copy failed for ${entry.fileName}", e)
            return null
        }
        return LocalChapterInput(title = chapterTitle, filePath = dest.path, contentHash = dest.sha256Hex)
    }

    /** Re-indexes a local book's chapters naturally (deletes + reinserts the list). */
    private suspend fun rewriteBookChapters(bookId: String, chapters: List<LocalChapterInput>) {
        val sorted = chapters.sortedWith(Comparator { a, b -> compareNatural(a.title, b.title) })
        dao.deleteChaptersForBook(bookId)
        dao.insertChapters(
            sorted.mapIndexed { index, ch ->
                ChapterEntity(
                    id = "${bookId}_ch${index + 1}",
                    bookId = bookId,
                    chapterIndex = index,
                    title = ch.title,
                    durationSeconds = 0L,
                    streamUrl = ch.filePath,
                    localFilePath = ch.filePath,
                    isDownloaded = true,
                    contentHash = ch.contentHash.ifBlank { null }
                )
            }
        )
        dao.updateBookStats(bookId, sorted.size, 0L)
    }

    /** Refreshes the local source's re-scan fingerprint from its stored chapters. */
    private suspend fun updateFingerprintFor(bookId: String) {
        val chapters = dao.getChaptersListForBook(bookId)
        val fingerprint = chapters
            .map { "${it.title.lowercase()}|${it.contentHash.orEmpty()}" }
            .sorted()
            .joinToString("\n")
            .ifBlank { null }
            ?.let { sha256Hex(it.toByteArray()) }
        dao.updateSourceFingerprint("$bookId-local", fingerprint)
    }

    private suspend fun insertLocalBook(
        title: String,
        author: String,
        description: String,
        chapters: List<LocalChapterInput>,
        sourceTreeUri: String? = null
    ): AudiobookEntity {
        val bookId = "local-${System.currentTimeMillis()}-${localImportSeq.incrementAndGet()}"
        val book = AudiobookEntity(
            id = bookId,
            title = title,
            author = author,
            narrator = "Локальний аудіофайл",
            description = description,
            coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
            coverImageUrl = null,
            genre = LOCAL_GENRE,
            sourceUrl = "",
            isDownloaded = true,
            downloadProgress = 1f,
            totalDurationSeconds = 0L,
            totalChapters = chapters.size,
            rating = 0f,
            sourceTreeUri = sourceTreeUri
        )
        dao.insertAudiobooks(listOf(book))
        dao.insertChapters(
            chapters.mapIndexed { index, chapter ->
                ChapterEntity(
                    id = "${bookId}_ch${index + 1}",
                    bookId = bookId,
                    chapterIndex = index,
                    title = chapter.title,
                    durationSeconds = 0L,
                    streamUrl = chapter.filePath,
                    localFilePath = chapter.filePath,
                    isDownloaded = true,
                    contentHash = chapter.contentHash
                )
            }
        )
        // Spec-10 T2: local imports are a LOCAL source of the Work.
        dao.insertSources(
            listOf(
                SourceEntity(
                    id = "$bookId-local",
                    bookId = bookId,
                    type = "local",
                    url = "",
                    streamOnly = false,
                    addedAt = System.currentTimeMillis()
                )
            )
        )
        return book
    }

    /** Natural (human) file-name comparison: track2 < track10. */
    private fun compareNatural(a: String, b: String): Int {
        val chunksA = SPLIT_CHUNKS.findAll(a.lowercase()).map { it.value }.toList()
        val chunksB = SPLIT_CHUNKS.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(chunksA.size, chunksB.size)) {
            val ca = chunksA[i]
            val cb = chunksB[i]
            val cmp = if (ca.first().isDigit() && cb.first().isDigit()) {
                (ca.toLongOrNull() ?: 0L).compareTo(cb.toLongOrNull() ?: 0L)
            } else {
                ca.compareTo(cb)
            }
            if (cmp != 0) return cmp
        }
        return chunksA.size - chunksB.size
    }

    private fun queryDisplayName(ctx: android.content.Context, uri: Uri): String? = try {
        ctx.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }


    fun observeBook(bookId: String): Flow<AudiobookEntity?> = dao.observeAudiobookById(bookId)
    suspend fun getBookSync(bookId: String): AudiobookEntity? = dao.getAudiobookById(bookId)

    fun observeChapters(bookId: String): Flow<List<ChapterEntity>> = dao.getChaptersForBook(bookId)
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
            // Spec-14 T5: the adapter owns the page parse; the repository only
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
                "AudiobookRepo",
                "No chapters for bookId=$bookId and 4read fetch returned none; " +
                    "refusing to fabricate placeholder audio."
            )
        }
        return chapters
    }


    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>> = dao.getBookmarksForBook(bookId)

    suspend fun addBookmark(bookmark: BookmarkEntity) = dao.insertBookmark(bookmark)
    suspend fun deleteBookmark(bookmarkId: Long) = dao.deleteBookmark(bookmarkId)

    fun observeProgress(bookId: String): Flow<PlaybackProgressEntity?> = dao.getPlaybackProgress(bookId)
    fun observeProgress(bookId: String, sourceKey: String): Flow<PlaybackProgressEntity?> =
        dao.getPlaybackProgress(bookId, sourceKey)
    suspend fun getProgressSync(bookId: String): PlaybackProgressEntity? = dao.getPlaybackProgressSync(bookId)
    suspend fun getProgressSync(bookId: String, sourceKey: String): PlaybackProgressEntity? =
        dao.getPlaybackProgressSync(bookId, sourceKey)

    /**
     * Persists the playback position keyed per source (spec-10 T2). Callers
     * that know the source pass its key; the default "" keeps the legacy
     * single-source behaviour.
     */
    suspend fun updateProgress(bookId: String, chapterIndex: Int, positionSeconds: Long, sourceKey: String = "") {
        val progress = PlaybackProgressEntity(
            bookId = bookId,
            sourceKey = sourceKey,
            currentChapterIndex = chapterIndex,
            currentPositionSeconds = positionSeconds,
            lastListenedAt = System.currentTimeMillis()
        )
        dao.savePlaybackProgress(progress)
    }

    // --- Playback event log (spec-16, wayfinder #53) -----------------------
    // The seam the player uses to record discrete listening transitions. The
    // state row above stays the authoritative "where am I now"; the log is
    // history for undo, future sync and listening intelligence. Every write
    // funnels through recordPlaybackEvent, which also compacts the bucket.

    /**
     * Appends one discrete transition to the log and runs the bucket's
     * compaction. [timestampMs] is injectable so tests stay free of the wall
     * clock. The player calls this from its transition points (T2); nothing
     * else here changes behaviour yet.
     */
    suspend fun recordPlaybackEvent(
        bookId: String,
        kind: String,
        chapterIndex: Int,
        positionSeconds: Long,
        sourceKey: String = "",
        fromPositionSeconds: Long? = null,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        dao.insertPlaybackEvent(
            PlaybackEventEntity(
                bookId = bookId,
                sourceKey = sourceKey,
                kind = kind,
                chapterIndex = chapterIndex,
                positionSeconds = positionSeconds,
                fromPositionSeconds = fromPositionSeconds,
                timestamp = timestampMs,
                deviceId = ""
            )
        )
        compactPlaybackEvents(bookId, sourceKey, nowMs = timestampMs)
    }

    /**
     * The undo candidate for (book, source): the latest SEEK / SOURCE_SWITCH
     * event whose jump met the threshold (pure policy). Null when there is
     * nothing undoable — the caller shows no «Повернутися» offer.
     */
    suspend fun lastUndoCandidate(bookId: String, sourceKey: String = ""): PlaybackEventEntity? {
        val latest = dao.getLatestUndoCandidate(bookId, sourceKey) ?: return null
        return if (PlaybackEventPolicy.isUndoCandidate(latest)) latest else null
    }

    /**
     * Prunes one (book, source) bucket to the policy: newest [cap] events
     * kept, stale undo candidates dropped. The state row is never touched.
     */
    suspend fun compactPlaybackEvents(bookId: String, sourceKey: String = "", nowMs: Long = System.currentTimeMillis()) {
        val events = dao.getPlaybackEventsForBookSource(bookId, sourceKey)
        val prune = PlaybackEventPolicy.pruneIds(events, nowMs = nowMs)
        if (prune.isNotEmpty()) dao.deletePlaybackEvents(prune)
    }

    /**
     * Outcome of an offline download attempt. `totalChapters == 0` means no
     * audio could be found at all (the caller shows a "no audio" message);
     * `downloadedChapters` counts how many chapters made it to disk.
     */
    data class OfflineDownloadResult(
        val downloadedChapters: Int,
        val totalChapters: Int
    )

    suspend fun downloadAudiobookOffline(bookId: String): OfflineDownloadResult {
        // Spec-10 T6: a stream-only source must never download — refuse before
        // any state change or network I/O. The UI hides the action too; this
        // guard is defence in depth.
        val streamOnlyBook = dao.getAudiobookById(bookId)
        if (streamOnlyBook != null && isStreamOnly(streamOnlyBook)) {
            Log.w("AudiobookRepo", "downloadAudiobookOffline refused: book $bookId is stream-only")
            return OfflineDownloadResult(0, 0)
        }
        // Spec-13 T2: the track CDNs (shared `redirectto.cc`) 403 without the
        // owning source's Referer — derive it from the book, not the URL host.
        val sourceId = streamOnlyBook?.let { sourceTypeOfUrl(it.sourceUrl) } ?: "unknown"

        // Use the fallback-fetching [getChaptersList], NOT a raw Room read: a
        // catalogue book's chapters live on its 4read page and are materialised
        // on demand. Previously the raw read returned 0 chapters for any book
        // whose page had never been opened/played, and the method silently
        // returned — the Download button did nothing (observed on-device:
        // 183 of 214 books had no chapters in Room).
        val chapters = getChaptersList(bookId)
        val total = chapters.size
        if (total == 0) {
            Log.w("AudiobookRepo", "downloadAudiobookOffline: no chapters found for bookId=$bookId")
            return OfflineDownloadResult(0, 0)
        }

        // Phase 2.5 hotfix (SF-004 / SEC-008): the previous /sdcard fallback
        // was unreachable on Android 11+ scoped storage and would have failed
        // at runtime. The app always constructs this repository with a real
        // Context, so fail loudly when it isn't there.
        val ctx = context ?: run {
            Log.e("AudiobookRepo", "downloadAudiobookOffline called without Context; aborting")
            dao.updateDownloadState(bookId, isDownloaded = false, progress = 0f)
            return OfflineDownloadResult(0, 0)
        }
        // Phase 2.5 hotfix (HI-002 / PERF-015): the cache size reader and
        // clearer look at filesDir/audio_downloads while this method wrote
        // to filesDir/audiobooks, so Clear Cache never cleared anything.
        // Align every component on the same constant directory name.
        val audioDir = File(ctx.filesDir, OFFLINE_AUDIO_DIR)
        if (!audioDir.exists()) audioDir.mkdirs()

        val completedCount = AtomicInteger(0)
        var successCount = 0

        dao.updateDownloadState(bookId, isDownloaded = false, progress = 0.05f)

        coroutineScope {
            chapters.map { chapter ->
                async(Dispatchers.IO) {
                    val localFile = File(audioDir, "${chapter.id}.mp3")
                    var chapterOk = false

                    try {
                        if (!localFile.exists() || localFile.length() < 100) {
                            val streamUrl = chapter.streamUrl
                            if (streamUrl.startsWith("http")) {
                                val url = URL(streamUrl)
                                val connection = (url.openConnection() as HttpURLConnection).apply {
                                    connectTimeout = 10000
                                    readTimeout = 20000
                                    requestMethod = "GET"
                                    setRequestProperty("User-Agent", OFFLINE_USER_AGENT)
                                    // Spec-10 T6 + spec-13 T2: the playerjs CDN
                                    // (redirectto.cc) 403s without the owning
                                    // source's Referer (audiobookmp3, sluhay,
                                    // sluhayknigi); other CDNs need none.
                                    headersFor(sourceId, streamUrl).forEach { (k, v) ->
                                        setRequestProperty(k, v)
                                    }
                                    instanceFollowRedirects = true
                                }

                                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                                    BufferedInputStream(connection.inputStream, 65536).use { input ->
                                        BufferedOutputStream(localFile.outputStream(), 65536).use { output ->
                                            val buffer = ByteArray(65536)
                                            var read: Int
                                            while (input.read(buffer).also { read = it } != -1) {
                                                output.write(buffer, 0, read)
                                            }
                                            output.flush()
                                        }
                                    }
                                    chapterOk = localFile.length() > 100
                                }
                                connection.disconnect()
                            }
                        } else if (localFile.length() > 100) {
                            // Already downloaded.
                            chapterOk = true
                        }
                    } catch (e: Exception) {
                        Log.w("AudiobookRepo", "Download failed for chapter ${chapter.id}: ${e.message}")
                        // Phase 2.5 hotfix (HI-001 / SF-001): the previous
                        // catch wrote a literal "OFFLINE_AUDIO_<id>" text
                        // marker into the .mp3 path and then set
                        // chapter.isDownloaded = true. The player tried to
                        // decode text and the user saw a "Downloaded" badge
                        // over unplayable content. Surface the failure
                        // instead.
                        if (localFile.exists()) localFile.delete()
                    }

                    val finished = completedCount.incrementAndGet()
                    val currentProgress = finished.toFloat() / total
                    dao.updateDownloadState(bookId, isDownloaded = false, progress = currentProgress)
                    dao.updateChapterDownloadState(
                        chapter.id,
                        isDownloaded = chapterOk,
                        filePath = if (chapterOk) localFile.absolutePath else null
                    )
                    if (chapterOk) successCount++
                }
            }.awaitAll()
        }

        val allOk = successCount == total
        dao.updateDownloadState(
            bookId,
            isDownloaded = allOk,
            progress = if (allOk) 1.0f else successCount.toFloat() / total
        )
        return OfflineDownloadResult(successCount, total)
    }

    suspend fun removeOfflineDownload(bookId: String) {
        val chapters = dao.getChaptersListForBook(bookId)
        chapters.forEach { ch ->
            ch.localFilePath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
            dao.updateChapterDownloadState(ch.id, isDownloaded = false, filePath = null)
        }
        // The copies are gone; the hashes must not pretend they still exist,
        // otherwise a later re-import of the same files would be skipped as
        // "duplicate" and the book would stay unplayable (wayfinder #48+#50).
        dao.clearChapterContentHashes(bookId)
        dao.updateDownloadState(bookId, isDownloaded = false, progress = 0f)
    }

    suspend fun refreshBookCoverAndDetails(bookId: String) = withContext(Dispatchers.IO) {
        val book = dao.getAudiobookById(bookId) ?: return@withContext
        val chapters = dao.getChaptersListForBook(bookId)

        // 1. We skip audio metadata (embedded picture) extraction because MediaMetadataRetriever
        // frequently causes "Media Quality Service not found" and "getEmbeddedPicture failed" errors
        // on emulators and some devices, and is extremely slow for network streams.
        var audioCoverUrl: String? = null

        if (audioCoverUrl != null) {
            dao.updateCoverImageUrl(bookId, audioCoverUrl)
            return@withContext
        }

        // 2. Fall back to book's webpage (spec-14 T5: the adapter owns the
        // page parse; the repository persists what the seam provides).
        if (book.sourceUrl.isNotBlank()) {
            val detail = fourReadAdapter.fetchBookPage(book.sourceUrl)
            if (!detail.coverImageUrl.isNullOrBlank()) {
                dao.updateCoverImageUrl(bookId, detail.coverImageUrl)
            }
            // Real metadata (author/narrator/genre/duration/rating/series) is
            // back-filled on EVERY book-page open — the catalogue seed only
            // ever had placeholders, and a book may already carry them from a
            // previous session, so gating on chapters.isEmpty() would leave
            // "4read.org" / "4:00:00" forever.
            val author = detail.author.ifBlank { null }
            val narrator = detail.narrator.ifBlank { null }
            val genres = detail.genres.joinToString(" · ").ifBlank { null }
            val rating = detail.rating?.toFloat()
            val seriesTitle = detail.series?.name
            val seriesIndex = detail.series?.position
            val seriesUrl = detail.series?.url
            if (detail.totalDurationSeconds != null || author != null ||
                narrator != null || genres != null ||
                rating != null || seriesTitle != null || seriesUrl != null
            ) {
                dao.updateBookStats(
                    bookId,
                    chapters.size.takeIf { it > 0 } ?: detail.chapters.size,
                    detail.totalDurationSeconds ?: book.totalDurationSeconds
                )
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
            // Same guard as getChaptersList: never overwrite existing (seeded)
            // chapters with live-page ones -- that duplicated rows on every
            // book-detail open.
            if (chapters.isEmpty() && detail.chapters.isNotEmpty()) {
                // Same id format as getChaptersList ("_ch_") so a concurrent
                // fetch-then-insert (e.g. an offline Download racing this
                // refresh) produces identical rows and @Insert(REPLACE)
                // dedupes them — a mixed `ch`/`ch_` format used to duplicate
                // the whole chapter list.
                val updatedChapters = detail.chapters.mapIndexed { index, chapter ->
                    ChapterEntity(
                        id = "${bookId}_ch_${index + 1}",
                        bookId = bookId,
                        chapterIndex = index,
                        title = "Глава ${index + 1} (${book.title})",
                        durationSeconds = 0L, // unknown until played
                        streamUrl = chapter.streamUrl
                    )
                }
                dao.insertChapters(updatedChapters)
            }
        }
    }

    /**
     * Spec-14 T4/T5 — the WebView door rides the same parser + transport as
     * every other door: the adapter owns the captured page parse (playlist
     * content resolved through its own HttpFetcher), and the shared import
     * path persists the Work with the same merge key / id shape. The
     * repository performs no 4read parsing or transport. A captured page that
     * yields nothing playable surfaces as absent (null) — never a forged card.
     */
    suspend fun importAudiobookFromHtml(urlOrSlug: String, html: String): AudiobookEntity? {
        val cleanInput = urlOrSlug.trim()
        val sourceUrl = if (cleanInput.startsWith("http")) cleanInput else "https://4read.org/$cleanInput"
        val adapter = fourReadAdapter as? FourReadAdapter ?: return null
        val detail = adapter.parseCapturedPage(html, sourceUrl)
        if (detail.chapters.isEmpty()) return null
        return importBookFromSource("4read", detail)
    }

    /**
     * Spec-14 T3/T5 — the link-import door rides the source seam: the adapter
     * owns fetching and extraction (fetchBookPage); the repository only
     * persists through the shared import path (same merge key, same source row
     * as every other door). A page that yields nothing playable surfaces as
     * absent (null) — the fabricated fallback card is gone.
     */
    suspend fun importAudiobookFrom4ReadUrl(urlOrSlug: String): AudiobookEntity? {
        val cleanInput = urlOrSlug.trim()
        val sourceUrl = if (cleanInput.startsWith("http")) cleanInput else "https://4read.org/$cleanInput"
        return importFromSourceUrl("4read", sourceUrl)
    }

    // Cache & Download Management
    fun getAudioCacheSizeBytes(): Long {
        val ctx = context ?: return 0L
        var total = 0L
        // Phase 2.5 hotfix (HI-002 / PERF-015): previously read
        // filesDir/audio_downloads while downloadAudiobookOffline wrote
        // filesDir/audiobooks. Cache size was always 0 MB.
        val audioDir = File(ctx.filesDir, OFFLINE_AUDIO_DIR)
        if (audioDir.exists()) {
            audioDir.walkTopDown().forEach { file ->
                if (file.isFile) total += file.length()
            }
        }
        return total
    }

    suspend fun clearAllAudioCache() {
        val ctx = context
        withContext(Dispatchers.IO) {
            if (ctx != null) {
                // Phase 2.5 hotfix (HI-002 / PERF-015): same constant as
                // getAudioCacheSizeBytes and downloadAudiobookOffline.
                val audioDir = File(ctx.filesDir, OFFLINE_AUDIO_DIR)
                if (audioDir.exists()) {
                    audioDir.deleteRecursively()
                }
            }
            dao.markAllNotDownloaded()
            dao.clearAllChaptersDownloadState()
        }
    }

    // Favorites Management
    suspend fun toggleFavorite(bookId: String, isFavorite: Boolean) {
        dao.setFavorite(bookId, isFavorite)
    }

    fun getFavoriteAudiobooks(): Flow<List<AudiobookEntity>> = dao.getFavoriteAudiobooks()

    // Listening Stats
    fun getAllListeningStats(): Flow<List<ListeningStatEntity>> = dao.getAllListeningStats()

    suspend fun recordListeningTime(seconds: Long) {
        if (seconds <= 0) return
        val dateIso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        withContext(Dispatchers.IO) {
            val current = dao.getListeningStatForDate(dateIso)
            val updatedSeconds = (current?.listenedSeconds ?: 0L) + seconds
            dao.saveListeningStat(ListeningStatEntity(dateIso, updatedSeconds))
        }
    }

    companion object {
        /** TTL of the in-memory per-source «new arrivals» feed cache (spec-10 T4). */
        private const val NEW_FEED_TTL_MS = 15 * 60 * 1000L

        /** Single source of truth for the offline-audio directory name. */
        const val OFFLINE_AUDIO_DIR = "audiobooks"

        /** Directory holding user-imported local audio files (spec #8 T7). */
        const val LOCAL_AUDIO_DIR = "local_imports"
        /** User-Agent used by the offline-download HttpURLConnection. */
        const val OFFLINE_USER_AGENT = "Mozilla/5.0 (Android; 4read-Audio-Engine/1.0)"

        /** Author/genre labels for locally-imported books. */
        private const val LOCAL_FILE_AUTHOR = "Локальний файл"
        private const val LOCAL_FOLDER_AUTHOR = "Локальна папка"
        private const val LOCAL_GENRE = "Локальні"

        /** Monotonic counter guaranteeing unique local ids/names within a burst of imports. */
        private val localImportSeq = java.util.concurrent.atomic.AtomicInteger(0)

        /** Splits a file name into numeric and non-numeric chunks for natural sorting. */
        private val SPLIT_CHUNKS = Regex("""\d+|\D+""")
    }
}

/** Outcome of a local folder/file import (spec #8 Block 4). */
data class LocalImportResult(
    val booksImported: Int,
    val filesImported: Int,
    val skippedFiles: Int,
    // wayfinder #48: files whose bytes already existed in the library; they
    // were never copied, so no storage was consumed.
    val duplicateFiles: Int = 0
)

/**
 * A local audio file materialised into the library (wayfinder #48): the
 * chapter title, the copied file path, and the SHA-256 that made the copy
 * dedupe-able against earlier imports.
 */
data class LocalChapterInput(
    val title: String,
    val filePath: String,
    val contentHash: String
)

/** A local file copied into private storage, with its content digest. */
data class CopiedLocalFile(
    val path: String,
    val sha256Hex: String
)

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
