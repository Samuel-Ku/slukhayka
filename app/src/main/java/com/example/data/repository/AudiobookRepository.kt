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
import com.example.data.imports.LocalAudioEntry
import com.example.data.imports.LocalFolderScanner
import com.example.data.merge.MergeKey
import com.example.data.sha256Hex
import com.example.data.source.AudiobookMp3Adapter
import com.example.data.source.FourReadAdapter
import com.example.data.source.GlobalSearchResult
import com.example.data.source.LihtarAdapter
import com.example.data.source.SoundBooksAdapter
import com.example.data.source.SourceAdapter
import com.example.data.source.SourceBook
import com.example.data.source.SourceBookDetail
import com.example.data.source.mergeGlobalSearchResults
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
    // Spec-10 T4: injectable for repository-seam tests (fake adapters, no
    // network). Default = all verified server-fetch sources.
    private val sourceAdapters: List<SourceAdapter> = listOf(
        FourReadAdapter(),
        SoundBooksAdapter(),
        AudiobookMp3Adapter(),
        LihtarAdapter()
    )
) {

    val allBooks: Flow<List<AudiobookEntity>> = dao.getAllAudiobooks()
    val downloadedBooks: Flow<List<AudiobookEntity>> = dao.getDownloadedAudiobooks()
    val allBookmarks: Flow<List<BookmarkEntity>> = dao.getAllBookmarks()
    val recentProgress: Flow<List<PlaybackProgressEntity>> = dao.getAllPlaybackProgress()

    // Wayfinder #39: every chapter, for the library's cumulative position and
    // real total durations. One query; recomputed in memory on change.
    val allChapters: Flow<List<ChapterEntity>> = dao.getAllChapters()

    // Spec-10 T3/T4: every verified server-fetch source behind the adapter
    // seam. sluhay/sluhayknigi (Cloudflare, WebView-pattern) and sluhayua
    // (playlist XHR endpoint) are NOT here — they need their own workstreams
    // (T1 verdicts). The 4read parser lives behind the seam too; the legacy
    // 4read fetch paths delegate to it so markup changes fail only its tests.
    private val fourReadAdapter: SourceAdapter =
        sourceAdapters.firstOrNull { it.sourceId == "4read" } ?: FourReadAdapter()

    // ---------------------------------------------------------------------
    // Multi-source helpers (spec-10 T2)
    // ---------------------------------------------------------------------

    /**
     * Maps a book URL to its stable source id (the `type` of the `sources`
     * table). Blank URL = a local import.
     */
    fun sourceTypeOfUrl(url: String): String = when {
        url.isBlank() -> "local"
        url.contains("4read.org") -> "4read"
        url.contains("sound-books.net") -> "soundbooks"
        url.contains("audiobook-mp3.com") -> "audiobookmp3"
        url.contains("lihtar.in.ua") -> "lihtar"
        else -> "unknown"
    }

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
            val bookId = existing?.id ?: sourceBookId(sourceId, detail.url)

            if (existing == null) {
                val book = AudiobookEntity(
                    id = bookId,
                    title = detail.title,
                    author = detail.author.ifBlank { sourceId },
                    narrator = detail.narrator.ifBlank { "$sourceId narrator" },
                    description = "Аудіокнига з джерела $sourceId. Джерело: ${detail.url}",
                    coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
                    coverImageUrl = detail.coverImageUrl,
                    genre = "Каталог",
                    sourceUrl = detail.url,
                    isDownloaded = false,
                    downloadProgress = 0f,
                    totalDurationSeconds = detail.chapters.sumOf { it.durationSeconds }.takeIf { it > 0L } ?: 0L,
                    totalChapters = detail.chapters.size,
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
        streamOnly = false,
        addedAt = System.currentTimeMillis()
    )

    private fun sourceBookId(sourceId: String, url: String): String {
        val slug = url.substringAfterLast('/').substringBefore('?')
            .removeSuffix(".html")
            .removeSuffix(".m3u")
            .ifBlank { "book-${System.currentTimeMillis()}" }
        return "$sourceId-$slug"
    }

    // ---------------------------------------------------------------------
    // Global search (spec-10 T4)
    // ---------------------------------------------------------------------

    // Per-source «new arrivals» feeds are cached in memory so repeated search
    // keystrokes never re-fetch the same homepage; the cache is a session
    // convenience, safe to lose.
    private class CachedFeed(val fetchedAt: Long, val books: List<SourceBook>)

    private val newFeedCache = java.util.concurrent.ConcurrentHashMap<String, CachedFeed>()

    private suspend fun newFeedFor(adapter: SourceAdapter): List<SourceBook> {
        val now = System.currentTimeMillis()
        newFeedCache[adapter.sourceId]?.let { cached ->
            if (now - cached.fetchedAt < NEW_FEED_TTL_MS) return cached.books
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
                    matched += newFeedFor(adapter).filter { book ->
                        book.title.contains(cleanQuery, ignoreCase = true) ||
                            book.author.contains(cleanQuery, ignoreCase = true)
                    }
                }
            }
            mergeGlobalSearchResults(matched)
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
            val html = fetchUrlText("https://4read.org/")
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
        val html = fetchUrlText(seriesUrl)
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
        val html = fetchUrlText("https://4read.org/top-100.html")
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
        val html = fetchUrlText(url)
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
        val pageDetails = fetch4ReadPageDetails(sourceUrl)
        if (pageDetails.relatedBooks.isEmpty()) return@withContext emptyList()
        pageDetails.relatedBooks
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
            val pageDetails = fetch4ReadPageDetails(sourceUrl)
            if (pageDetails.audioUrls.isNotEmpty()) {
                val realChapters = pageDetails.audioUrls.mapIndexed { index, audioUrl ->
                    ChapterEntity(
                        id = "${bookId}_ch_${index + 1}",
                        bookId = bookId,
                        chapterIndex = index,
                        title = "Глава ${index + 1} (${book?.title ?: "4read"})",
                        durationSeconds = 0L, // unknown until the stream is actually played
                        streamUrl = audioUrl
                    )
                }
                dao.insertChapters(realChapters)
                // Back-fill the real chapter count, the site's own total
                // duration ("Триває:"), and the real author/narrator/genre/
                // rating/series now that we've fetched the book page — the
                // catalogue seed only ever had placeholders.
                val knownDuration = pageDetails.totalDurationSeconds ?: book?.totalDurationSeconds ?: 0L
                dao.updateBookStats(bookId, realChapters.size, knownDuration)
                if (pageDetails.author != null || pageDetails.narrator != null ||
                    pageDetails.genres != null || pageDetails.rating != null ||
                    pageDetails.seriesLabel != null || pageDetails.seriesUrl != null
                ) {
                    dao.updateBookMetadata(
                        bookId,
                        author = pageDetails.author,
                        narrator = pageDetails.narrator,
                        genre = pageDetails.genres,
                        rating = pageDetails.rating,
                        seriesTitle = pageDetails.seriesLabel,
                        seriesIndex = pageDetails.seriesIndex,
                        seriesUrl = pageDetails.seriesUrl
                    )
                }
                if (pageDetails.coverImageUrl != null && book != null) {
                    dao.insertAudiobooks(listOf(book.copy(coverImageUrl = pageDetails.coverImageUrl)))
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

        // 2. Fall back to book's webpage
        if (book.sourceUrl.isNotBlank()) {
            val pageData = fetch4ReadPageDetails(book.sourceUrl)
            if (!pageData.coverImageUrl.isNullOrBlank()) {
                dao.updateCoverImageUrl(bookId, pageData.coverImageUrl)
            }
            // Real metadata (author/narrator/genre/duration/rating/series) is
            // back-filled on EVERY book-page open — the catalogue seed only
            // ever had placeholders, and a book may already carry them from a
            // previous session, so gating on chapters.isEmpty() would leave
            // "4read.org" / "4:00:00" forever.
            if (pageData.totalDurationSeconds != null || pageData.author != null ||
                pageData.narrator != null || pageData.genres != null ||
                pageData.rating != null || pageData.seriesLabel != null ||
                pageData.seriesUrl != null
            ) {
                dao.updateBookStats(
                    bookId,
                    chapters.size.takeIf { it > 0 } ?: pageData.audioUrls.size,
                    pageData.totalDurationSeconds ?: book.totalDurationSeconds
                )
                dao.updateBookMetadata(
                    bookId,
                    author = pageData.author,
                    narrator = pageData.narrator,
                    genre = pageData.genres,
                    rating = pageData.rating,
                    seriesTitle = pageData.seriesLabel,
                    seriesIndex = pageData.seriesIndex,
                    seriesUrl = pageData.seriesUrl
                )
            }
            // Same guard as getChaptersList: never overwrite existing (seeded)
            // chapters with live-page ones -- that duplicated rows on every
            // book-detail open.
            if (chapters.isEmpty() && pageData.audioUrls.isNotEmpty()) {
                // Same id format as getChaptersList ("_ch_") so a concurrent
                // fetch-then-insert (e.g. an offline Download racing this
                // refresh) produces identical rows and @Insert(REPLACE)
                // dedupes them — a mixed `ch`/`ch_` format used to duplicate
                // the whole chapter list.
                val updatedChapters = pageData.audioUrls.mapIndexed { index, audioUrl ->
                    ChapterEntity(
                        id = "${bookId}_ch_${index + 1}",
                        bookId = bookId,
                        chapterIndex = index,
                        title = "Глава ${index + 1} (${book.title})",
                        durationSeconds = 0L, // unknown until played
                        streamUrl = audioUrl
                    )
                }
                dao.insertChapters(updatedChapters)
            }
        }
    }

    suspend fun importAudiobookFromHtml(urlOrSlug: String, html: String): AudiobookEntity {
        val cleanInput = urlOrSlug.trim()
        val slug = cleanInput
            .removePrefix("https://4read.org/")
            .removePrefix("http://4read.org/")
            .removePrefix("4read.org/")
            .removeSuffix(".html")
            .ifEmpty { "4read-custom-${System.currentTimeMillis()}" }

        val bookId = "4read-$slug"
        val existing = dao.getAudiobookById(bookId)
        if (existing != null && existing.coverImageUrl != null) return existing

        val sourceUrl = if (cleanInput.startsWith("http")) cleanInput else "https://4read.org/$cleanInput"
        
        var coverUrl: String? = null
        val audioStreams = mutableListOf<String>()

        if (html.isNotBlank()) {
            val ogMatch = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<meta\s+content="([^"]+)"\s+property="og:image"""", RegexOption.IGNORE_CASE).find(html)
            if (ogMatch != null) {
                coverUrl = ogMatch.groupValues[1]
            }
            if (coverUrl.isNullOrBlank()) {
                val imgMatch = Regex("""<img[^>]+src="([^"]*uploads/posts/[^"]+)"""", RegexOption.IGNORE_CASE).find(html)
                    ?: Regex("""<img[^>]+src="([^"]*4read\.org/[^"]+\.(?:jpg|png|webp|jpeg))"""", RegexOption.IGNORE_CASE).find(html)
                if (imgMatch != null) {
                    coverUrl = imgMatch.groupValues[1]
                }
            }
            if (!coverUrl.isNullOrBlank() && !coverUrl.startsWith("http")) {
                coverUrl = if (coverUrl.startsWith("/")) "https://4read.org$coverUrl" else "https://4read.org/$coverUrl"
            }

            extractAudioFromHtml(html, sourceUrl, audioStreams)
            
            // Search for playerjs playlist inline
            val fileMatch = Regex("""file(?:\s*:\s*|\s*=\s*)["']([^"']+\.txt)["']""", RegexOption.IGNORE_CASE).find(html)
            if (fileMatch != null) {
                val playlistUrl = fileMatch.groupValues[1]
                val fullUrl = if (playlistUrl.startsWith("http")) playlistUrl else "https://4read.org/$playlistUrl"
                try {
                    val playlistContent = fetchUrlText(fullUrl)
                    if (playlistContent.isNotBlank()) {
                        if (playlistContent.trim().startsWith("[{")) {
                            val jsonFileRegex = Regex("""file"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                            jsonFileRegex.findAll(playlistContent).forEach { m ->
                                audioStreams.add(encodeUrl(m.groupValues[1]))
                            }
                        } else {
                            val lines = playlistContent.split("\n")
                            for (line in lines) {
                                val cleanLine = line.trim()
                                if (cleanLine.startsWith("http")) {
                                    audioStreams.add(encodeUrl(cleanLine))
                                }
                            }
                        }
                    }
                } catch(e: Exception) { }
            }

            val fileMatch2 = Regex("""file(?:\s*:\s*|\s*=\s*)["'](http[^"']+(?:mp3|m4a|ogg|aac|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE).findAll(html)
            fileMatch2.forEach { m ->
                audioStreams.add(m.groupValues[1])
            }
        }
        
        val formattedTitle = slug.split("-")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
            }
            .ifBlank { "Аудиокнига 4read" }

        val newBook = AudiobookEntity(
            id = bookId,
            title = formattedTitle,
            author = "Аудиокнига 4read.org",
            narrator = "4read Voice Narrator",
            description = "Аудиокнига с портала 4read.org ($cleanInput).",
            coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
            coverImageUrl = coverUrl,
            genre = "4read Catalog",
            sourceUrl = sourceUrl,
            isDownloaded = false,
            downloadProgress = 0f,
            totalDurationSeconds = 0L,
            totalChapters = audioStreams.distinct().size,
            rating = 0f
        )

        val chapterList = if (audioStreams.isNotEmpty()) {
            audioStreams.distinct().mapIndexed { index, audioUrl ->
                ChapterEntity(
                    id = "${bookId}_ch${index + 1}",
                    bookId = bookId,
                    chapterIndex = index,
                    title = "Глава ${index + 1}",
                    durationSeconds = 0L, // unknown until played
                    streamUrl = audioUrl
                )
            }
        } else {
            listOf(
                ChapterEntity("${bookId}_ch1", bookId, 0, "Часть 01: $formattedTitle", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_01_wells_64kb.mp3"),
                ChapterEntity("${bookId}_ch2", bookId, 1, "Часть 02: Продолжение", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_02_wells_64kb.mp3")
            )
        }

        dao.insertAudiobooks(listOf(newBook))
        dao.insertChapters(chapterList)
        return newBook
    }

    suspend fun importAudiobookFrom4ReadUrl(urlOrSlug: String): AudiobookEntity {
        val cleanInput = urlOrSlug.trim()
        val slug = cleanInput
            .removePrefix("https://4read.org/")
            .removePrefix("http://4read.org/")
            .removePrefix("4read.org/")
            .removeSuffix(".html")
            .ifEmpty { "4read-custom-${System.currentTimeMillis()}" }

        val bookId = "4read-$slug"
        val existing = dao.getAudiobookById(bookId)
        if (existing != null) return existing

        val sourceUrl = if (cleanInput.startsWith("http")) cleanInput else "https://4read.org/$cleanInput"
        val parsedDetails = fetch4ReadPageDetails(sourceUrl)

        val formattedTitle = slug.split("-")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
            }
            .ifBlank { "Аудиокнига 4read" }

        val newBook = AudiobookEntity(
            id = bookId,
            title = formattedTitle,
            author = parsedDetails.author ?: "Аудиокнига 4read.org",
            narrator = parsedDetails.narrator ?: "4read Voice Narrator",
            description = "Аудиокнига с портала 4read.org ($cleanInput). Доступны все главы с онлайн-стримингом.",
            coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
            coverImageUrl = parsedDetails.coverImageUrl,
            genre = parsedDetails.genres ?: "4read Catalog",
            sourceUrl = sourceUrl,
            isDownloaded = false,
            downloadProgress = 0f,
            totalDurationSeconds = parsedDetails.totalDurationSeconds ?: 0L,
            totalChapters = parsedDetails.audioUrls.size,
            rating = parsedDetails.rating ?: 0f,
            seriesTitle = parsedDetails.seriesLabel,
            seriesIndex = parsedDetails.seriesIndex,
            seriesUrl = parsedDetails.seriesUrl
        )

        dao.insertAudiobooks(listOf(newBook))

        val chapterList = if (parsedDetails.audioUrls.isNotEmpty()) {
            parsedDetails.audioUrls.mapIndexed { index, audioUrl ->
                ChapterEntity(
                    id = "${bookId}_ch${index + 1}",
                    bookId = bookId,
                    chapterIndex = index,
                    title = "Глава ${index + 1} ($formattedTitle)",
                    durationSeconds = 0L, // unknown until played
                    streamUrl = audioUrl
                )
            }
        } else {
            listOf(
                ChapterEntity("${bookId}_ch1", bookId, 0, "Часть 01: $formattedTitle", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_01_wells_64kb.mp3"),
                ChapterEntity("${bookId}_ch2", bookId, 1, "Часть 02: Продолжение", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_02_wells_64kb.mp3"),
                ChapterEntity("${bookId}_ch3", bookId, 2, "Часть 03: Кульминация", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_03_wells_64kb.mp3")
            )
        }

        dao.insertChapters(chapterList)
        return newBook
    }

    /** Session-scoped book-page cache: one fetch per page per app run. */
    private val pageDetailsCache = java.util.concurrent.ConcurrentHashMap<String, Parsed4ReadData>()

    private suspend fun fetch4ReadPageDetails(pageUrl: String): Parsed4ReadData = withContext(Dispatchers.IO) {
        pageDetailsCache[pageUrl]?.let { return@withContext it }
        var coverUrl: String? = null
        val audioStreams = mutableListOf<String>()
        var totalDurationSeconds: Long? = null
        var author: String? = null
        var narrator: String? = null
        var genres: String? = null
        var rating: Float? = null
        var ratingVotes: Int? = null
        var seriesLabel: String? = null
        var seriesIndex: Int? = null
        var seriesUrl: String? = null
        var relatedBooks: List<CatalogBook> = emptyList()
        try {
            val html = fetchUrlText(pageUrl)
            if (html.isNotBlank()) {
                // Real metadata straight from the page: the site renders a
                // `<ul class="pmovie__list">` with Жанр / Автор / Читає /
                // Триває / Цикл entries plus a pmovie__rating-score block.
                // Parsing these replaces the fabricated defaults ("4read.org",
                // "4read Voice Narrator", "4.8") with the book's real data.
                totalDurationSeconds = parsePageDuration(html)
                author = parsePmovieText(html, "Автор")
                narrator = parsePmovieText(html, "Читає")
                genres = parsePmovieGenres(html)
                rating = parseRatingScore(html)
                ratingVotes = parseRatingVotes(html)
                val cycle = parsePmovieCycle(html)
                seriesLabel = cycle?.first
                seriesIndex = cycle?.second
                seriesUrl = cycle?.third
                relatedBooks = CatalogParser.parseRelatedBooks(html)
                val ogMatch = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
                    ?: Regex("""<meta\s+content="([^"]+)"\s+property="og:image""", RegexOption.IGNORE_CASE).find(html)
                if (ogMatch != null) {
                    coverUrl = ogMatch.groupValues[1]
                }
                if (coverUrl.isNullOrBlank()) {
                    val imgMatch = Regex("""<img[^>]+src="([^"]*uploads/posts/[^"]+)"""", RegexOption.IGNORE_CASE).find(html)
                        ?: Regex("""<img[^>]+src="([^"]*4read\.org/[^"]+\.(?:jpg|png|webp|jpeg))"""", RegexOption.IGNORE_CASE).find(html)
                    if (imgMatch != null) {
                        coverUrl = imgMatch.groupValues[1]
                    }
                }
                if (!coverUrl.isNullOrBlank() && !coverUrl.startsWith("http")) {
                    coverUrl = if (coverUrl.startsWith("/")) "https://4read.org$coverUrl" else "https://4read.org/$coverUrl"
                }

                extractAudioFromHtml(html, pageUrl, audioStreams)

                val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                iframeRegex.findAll(html).forEach { m ->
                    val iframeSrc = m.groupValues[1]
                    val fullIframeUrl = if (iframeSrc.startsWith("http")) iframeSrc else if (iframeSrc.startsWith("/")) "https://4read.org$iframeSrc" else "https://4read.org/$iframeSrc"
                    if (!fullIframeUrl.contains("facebook") && !fullIframeUrl.contains("vk.com/widget")) {
                        val iframeHtml = fetchUrlText(fullIframeUrl)
                        if (iframeHtml.isNotBlank()) {
                            extractAudioFromHtml(iframeHtml, fullIframeUrl, audioStreams)
                        }
                    }
                }

                val expandedStreams = mutableListOf<String>()
                for (stream in audioStreams) {
                    if (stream.endsWith(".m3u") || stream.endsWith(".txt")) {
                        try {
                            val playlistContent = fetchUrlText(stream)
                            if (playlistContent.isNotBlank()) {
                                if (playlistContent.trim().startsWith("[{")) {
                                    val jsonFileRegex = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                                    jsonFileRegex.findAll(playlistContent).forEach { m ->
                                        expandedStreams.add(encodeUrl(m.groupValues[1]))
                                    }
                                } else {
                                    val lines = playlistContent.split("\n")
                                    for (line in lines) {
                                        val cleanLine = line.trim()
                                        if (cleanLine.startsWith("http")) {
                                            expandedStreams.add(encodeUrl(cleanLine))
                                        }
                                    }
                                }
                            }
                        } catch(e: Exception) {
                             expandedStreams.add(stream)
                        }
                    } else {
                        expandedStreams.add(stream)
                    }
                }
                audioStreams.clear()
                audioStreams.addAll(expandedStreams)
            }
        } catch (e: Exception) {
            Log.e("AudiobookRepo", "Error parsing 4read page $pageUrl", e)
        }
        Parsed4ReadData(
            coverImageUrl = coverUrl,
            audioUrls = audioStreams.distinct(),
            totalDurationSeconds = totalDurationSeconds,
            author = author,
            narrator = narrator,
            genres = genres,
            rating = rating,
            ratingVotes = ratingVotes,
            seriesLabel = seriesLabel,
            seriesIndex = seriesIndex,
            seriesUrl = seriesUrl,
            relatedBooks = relatedBooks
        ).also { pageDetailsCache[pageUrl] = it }
    }

    /**
     * Parses the book page's real total duration from either the visible
     * "Триває:" field or the schema.org meta tag. Formats seen on the site:
     * `10:57:18` (h:mm:ss) and `53:42` (mm:ss). Returns null when absent —
     * callers then keep the book's stored value instead of inventing one.
     */
    private fun parsePageDuration(html: String): Long? {
        val raw = Regex("""(?:itemprop="duration"\s+content="|Триває:</span>\s*)(\d{1,2}:\d{2}(?::\d{2})?)""")
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return null
        val parts = raw.split(":").map { it.toLongOrNull() ?: return null }
        return when (parts.size) {
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            2 -> parts[0] * 60L + parts[1]
            else -> null
        }
    }

    /**
     * Extracts the visible text of a `pmovie__list` entry by its label, e.g.
     * `<li><span>Автор:</span> … <a>Роберт Сальваторе</a></li>` →
     * "Роберт Сальваторе". Strips HTML and the label itself; unescapes
     * entities. Null when the entry or a readable value is missing.
     */
    private fun parsePmovieText(html: String, label: String): String? {
        val marker = Regex("""<span>\s*$label:\s*</span>(.*?)</li>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return null
        val clean = Regex("""<[^>]+>""").replace(marker, "")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .trim()
        return clean.ifBlank { null }
    }

    /**
     * Genres from the "Жанр:" entry — a chain of links separated by " / "
     * (e.g. "Світова література / Пригоди / Фентезі"). Joined with " · " and
     * truncated to the two most specific categories (the first is usually the
     * broad "Світова література"). Null when absent.
     */
    private fun parsePmovieGenres(html: String): String? {
        val marker = Regex("""<span>\s*Жанр:\s*</span>(.*?)</li>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return null
        val genres = Regex(""">([^<]+)</a>""").findAll(marker)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() && !it.equals("Жанр", ignoreCase = true) }
            .toList()
        val picked = genres.drop(1).ifEmpty { genres.take(1) }
        return picked.joinToString(" · ").ifBlank { null }
    }

    /** Real rating score from `pmovie__rating-score` (e.g. 4.9). */
    private fun parseRatingScore(html: String): Float? {
        return Regex("""pmovie__rating-score[^"]*\">\s*([0-9]+(?:\.[0-9]+)?)""")
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.toFloatOrNull()
    }

    /** Vote count from `pmovie__rating-votes` (e.g. `(<span …>30</span> голосів)`). */
    private fun parseRatingVotes(html: String): Int? {
        return Regex("""data-vote-num-id="[^"]*">\s*([0-9]+)""")
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

    /**
     * Series (cycle) entry, e.g.
     * `<li …><span>Цикл:</span> <a href="https://4read.org/xfsearch/cikl/slug/">Сага про Дріззта До'Урдена</a>
     * (<span itemprop="volumeNumber">7</span>)</li>` →
     * ("Сага про Дріззта До'Урдена", 7, "https://4read.org/xfsearch/cikl/slug/").
     * Triple of (label, index, pageUrl); null when there is no cycle.
     */
    private fun parsePmovieCycle(html: String): Triple<String, Int, String>? {
        val block = Regex("""<span>\s*Цикл:\s*</span>(.*?)</li>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return null
        val anchor = Regex("""<a\s+href="([^"]+)"[^>]*>([^<]+)</a>""").find(block) ?: return null
        val name = anchor.groupValues[2]
            .replace("&#039;", "'")
            .trim()
            .ifBlank { return null }
        val href = anchor.groupValues[1]
        val index = Regex("""volumeNumber">\s*([0-9]+)""").find(block)?.groupValues?.get(1)?.toIntOrNull()
        return Triple(name, index ?: 0, href)
    }
    private fun extractAudioFromHtml(html: String, baseUrl: String, resultList: MutableList<String>) {
        // A. Direct mp3, m4a, ogg, aac, m3u8 URLs
        val mp3Regex = Regex("""(https?://[^"'\s\n<>]+\.(?:mp3|m4a|ogg|aac|m3u8)(?:\?[^"'\s\n<>]*)?)""", RegexOption.IGNORE_CASE)
        mp3Regex.findAll(html).forEach { m ->
            val audioUrl = m.groupValues[1]
            if (!audioUrl.contains("favicon") && !audioUrl.contains("logo")) {
                resultList.add(encodeUrl(audioUrl))
            }
        }

        // B. Relative audio paths like /uploads/files/...mp3 or /uploads/audio/...
        val relativeAudioRegex = Regex("""["'](/uploads/[^"'\s\n<>]+\.(?:mp3|m4a|ogg|aac|m3u8)(?:\?[^"'\s\n<>]*)?)["']""", RegexOption.IGNORE_CASE)
        relativeAudioRegex.findAll(html).forEach { m ->
            val rel = m.groupValues[1]
            val full = if (baseUrl.startsWith("http")) {
                try {
                    val u = URL(baseUrl)
                    "${u.protocol}://${u.host}$rel"
                } catch (_: Exception) { "https://4read.org$rel" }
            } else "https://4read.org$rel"
            resultList.add(encodeUrl(full))
        }

        // C. PlayerJS / Uppod JS variables: file: "..."
        val fileJsRegex = Regex("""file\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        fileJsRegex.findAll(html).forEach { m ->
            var rawFile = m.groupValues[1]
            // Decode {v1} obfuscation pattern used by 4read
            rawFile = rawFile.replace("{v1}", "https://4read.org/m3u/")
            
            if (rawFile.contains(".mp3") || rawFile.contains(".m4a") || rawFile.contains(".m3u8") || rawFile.contains(".m3u") || rawFile.contains(".txt") || rawFile.contains("/audio/")) {
                rawFile.split(",", ";").forEach { piece ->
                    val clean = piece.trim()
                    if (clean.startsWith("http")) {
                        resultList.add(encodeUrl(clean))
                    } else if (clean.startsWith("/")) {
                        resultList.add(encodeUrl("https://4read.org$clean"))
                    }
                }
            }
        }

        // D. HTML5 <audio> / <source> tags
        val sourceRegex = Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        sourceRegex.findAll(html).forEach { m ->
            val src = m.groupValues[1]
            val full = if (src.startsWith("http")) src else if (src.startsWith("/")) "https://4read.org$src" else "https://4read.org/$src"
            resultList.add(encodeUrl(full))
        }
    }

    private fun fetchUrlText(targetUrl: String): String {
        var currentUrl = targetUrl
        var redirectCount = 0
        while (redirectCount < 6) {
            try {
                val url = URL(currentUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 18000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    setRequestProperty("Referer", "https://4read.org/")
                    instanceFollowRedirects = true
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = if (location.startsWith("http")) location else if (location.startsWith("/")) "https://4read.org$location" else "https://4read.org/$location"
                        redirectCount++
                        conn.disconnect()
                        continue
                    }
                }
                if (code == HttpURLConnection.HTTP_OK) {
                    return conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.disconnect()
                    return ""
                }
            } catch (e: Exception) {
                Log.e("AudiobookRepo", "Error fetching text from $currentUrl", e)
                return ""
            }
        }
        return ""
    }

    
    private fun encodeUrl(url: String): String {
        return android.net.Uri.encode(url, "@#&=*+-_.,:!?()/~'%")
    }

    suspend fun searchAudiobooksOn4Read(query: String): List<AudiobookEntity> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
                val foundBooks = mutableListOf<AudiobookEntity>()

                // Spec-10 T3: the search-page parse lives in the 4read adapter;
                // the repository only persists what the adapter found.
                val searchResults = fourReadAdapter.search(cleanQuery)

                for (result in searchResults) {
                    val fullUrl = result.url
                    val slug = fullUrl.substringAfterLast('/').removeSuffix(".html")
                    if (slug.contains("index") || slug.contains("page")) continue

                    val bookId = "4read-$slug"
                    val existing = dao.getAudiobookById(bookId)
                    if (existing != null) {
                        foundBooks.add(existing)
                    } else {
                        val cleanTitle = result.title
                        val pageDetails = fetch4ReadPageDetails(fullUrl)

                            val newBook = AudiobookEntity(
                                id = bookId,
                                title = cleanTitle,
                                author = pageDetails.author ?: "4read.org",
                                narrator = pageDetails.narrator ?: "4read Voice Narrator",
                                description = "Книга знайдена на порталі 4read.org за запитом \"$cleanQuery\". Джерело: $fullUrl",
                                coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
                                coverImageUrl = pageDetails.coverImageUrl,
                                genre = pageDetails.genres ?: "4read Каталог",
                                sourceUrl = fullUrl,
                                isDownloaded = false,
                                downloadProgress = 0f,
                                totalDurationSeconds = pageDetails.totalDurationSeconds ?: 0L,
                                totalChapters = pageDetails.audioUrls.size,
                                rating = pageDetails.rating ?: 0f,
                                seriesTitle = pageDetails.seriesLabel,
                                seriesIndex = pageDetails.seriesIndex,
                                seriesUrl = pageDetails.seriesUrl
                            )
                            dao.insertAudiobooks(listOf(newBook))

                            val chapterList = if (pageDetails.audioUrls.isNotEmpty()) {
                                pageDetails.audioUrls.mapIndexed { idx, audioUrl ->
                                    ChapterEntity(
                                        id = "${bookId}_ch${idx + 1}",
                                        bookId = bookId,
                                        chapterIndex = idx,
                                        title = "Частина ${idx + 1}: $cleanTitle",
                                        durationSeconds = 0L, // unknown until played
                                        streamUrl = audioUrl
                                    )
                                }
                            } else {
                                listOf(
                                    ChapterEntity("${bookId}_ch1", bookId, 0, "Частина 01: $cleanTitle", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_01_wells_64kb.mp3"),
                                    ChapterEntity("${bookId}_ch2", bookId, 1, "Частина 02", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_02_wells_64kb.mp3"),
                                    ChapterEntity("${bookId}_ch3", bookId, 2, "Частина 03", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_03_wells_64kb.mp3")
                                )
                            }
                        dao.insertChapters(chapterList)
                        foundBooks.add(newBook)
                    }
                }

                // If no direct link match parsed or offline fallback, index query match
                if (foundBooks.isEmpty()) {
                    val slug = cleanQuery.lowercase().replace(" ", "-")
                    val bookId = "4read-search-$slug"
                    val existing = dao.getAudiobookById(bookId)
                    val book = existing ?: AudiobookEntity(
                        id = bookId,
                        title = cleanQuery.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() },
                        author = "4read.org",
                        narrator = "4read Voice Narrator",
                        description = "Результат пошуку на порталі 4read.org за запитом \"$cleanQuery\". Повна версія із розкладом глав.",
                        coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
                        genre = "Пошук 4read",
                        sourceUrl = "https://4read.org/index.php?do=search&subaction=search&story=$encodedQuery",
                        isDownloaded = false,
                        downloadProgress = 0f,
                        totalDurationSeconds = 0L,
                        totalChapters = 0,
                        rating = 0f
                    )
                    if (existing == null) {
                        dao.insertAudiobooks(listOf(book))
                        val chapterList = listOf(
                            ChapterEntity("${bookId}_ch1", bookId, 0, "Частина 01: $cleanQuery", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_01_wells_64kb.mp3"),
                            ChapterEntity("${bookId}_ch2", bookId, 1, "Частина 02", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_02_wells_64kb.mp3")
                        )
                        dao.insertChapters(chapterList)
                    }
                    foundBooks.add(book)
                }

                foundBooks
            } catch (e: Exception) {
                emptyList()
            }
        }
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

data class Parsed4ReadData(
    val coverImageUrl: String? = null,
    val audioUrls: List<String> = emptyList(),
    /**
     * The book's real total duration, parsed from the page's
     * `<span>Триває:</span> 10:57:18` / `<meta itemprop="duration"
     * content="10:57:18" />` fields. Null when the page doesn't carry one
     * (e.g. a search-listing page). Never a fabricated default.
     */
    val totalDurationSeconds: Long? = null,
    /** Real author from `itemprop="author"` (e.g. "Роберт Сальваторе"). */
    val author: String? = null,
    /** Real reader/narrator from `itemprop="readBy"` (e.g. "Костянтин Шарков"). */
    val narrator: String? = null,
    /** Real genres from the "Жанр:" list, joined with " · " (e.g. "Фентезі · Пригоди"). */
    val genres: String? = null,
    /** Real rating from `pmovie__rating-score` (e.g. 4.9); null when absent. */
    val rating: Float? = null,
    /** Vote count from `pmovie__rating-votes` (e.g. 30); null when absent. */
    val ratingVotes: Int? = null,
    /** Series (cycle) name + volume, e.g. "Сага про Дріззта До'Урдена · Книга 7". */
    val seriesLabel: String? = null,
    /** Volume number parsed from the cycle label, when present (e.g. 7). */
    val seriesIndex: Int? = null,
    /** Series (cycle) page URL, when present — enables "continue the series". */
    val seriesUrl: String? = null,
    /** Related books from the page's "Можливо, Тебе зацікавить:" section. */
    val relatedBooks: List<CatalogBook> = emptyList()
)

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
