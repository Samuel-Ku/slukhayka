package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.R
import com.example.data.catalog.CatalogBook
import com.example.data.catalog.CatalogGenre
import com.example.data.catalog.CatalogPerson
import com.example.data.catalog.CatalogSection
import com.example.data.catalog.SourceCatalog
import com.example.data.db.*
import com.example.data.imports.ImportPlan
import com.example.data.imports.LibraryImport
import com.example.data.imports.LocalAudioEntry
import com.example.data.imports.LocalImportResult
import com.example.data.listening.ListeningStateStore
import com.example.data.source.AudiobookMp3Adapter
import com.example.data.source.FourReadAdapter
import com.example.data.source.GlobalSearchResult
import com.example.data.source.LihtarAdapter
import com.example.data.source.SluhayAdapter
import com.example.data.source.SluhayuaAdapter
import com.example.data.source.SoundBooksAdapter
import com.example.data.source.SourceAdapter
import com.example.data.source.SourceBookDetail
import com.example.data.source.headersFor
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
     * ADR-0002 (#138): retained for call-site compatibility. Construction now
     * performs NO network I/O — the catalogue sync is an explicit
     * composition-root call (App.onCreate → syncCatalogOnStart), so this flag
     * is a no-op. Tests set it to `false` so a fixture-driven test never
     * performs network I/O and never races the seeder for the same rows.
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
    ),
    // ADR-0002 expand phase: Listening State lives in its own deep module; the
    // god module delegates its Listening State members to it (deletion is a
    // later ticket). Defaults to a fresh store over the same DAO.
    private val listeningState: ListeningStateStore = ListeningStateStore(dao),
    // ADR-0002 expand phase: Library Import lives in its own deep module; the
    // god module delegates all five import doors + rescan to it. Defaults to a
    // fresh module over the same DAO, Context, and injected source adapters.
    private val libraryImport: LibraryImport = LibraryImport(dao, context, sourceAdapters),
    // ADR-0002 expand phase: Source Catalog lives in its own deep module; the
    // god module delegates its catalog members to it (DAG edge: Source Catalog
    // → Library Import). Defaults to a fresh module over the same DAO,
    // adapters and shared import module.
    private val sourceCatalog: SourceCatalog = SourceCatalog(dao, sourceAdapters, libraryImport)
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

    // ---------------------------------------------------------------------
    // Multi-source helpers (spec-10 T2)
    // ---------------------------------------------------------------------

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
        libraryImport.importBookFromSource(sourceId, detail)

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
    // Source Catalog (ADR-0002): browse/sync/search lives in its own deep
    // module; the god module delegates its catalog members to it.
    // ---------------------------------------------------------------------

    val unifiedCatalog: StateFlow<List<GlobalSearchResult>> = sourceCatalog.unifiedCatalog
    val isUnifiedCatalogLoading: StateFlow<Boolean> = sourceCatalog.isUnifiedCatalogLoading
    val sourceFeeds: StateFlow<List<SourceCatalog.SourceNewFeed>> = sourceCatalog.sourceFeeds
    val isFeedsLoading: StateFlow<Boolean> = sourceCatalog.isFeedsLoading
    val catalogSections: StateFlow<List<CatalogSection>> = sourceCatalog.catalogSections
    val catalogGenres: StateFlow<List<CatalogGenre>> = sourceCatalog.catalogGenres
    val isCatalogLoading: StateFlow<Boolean> = sourceCatalog.isCatalogLoading

    suspend fun refreshUnifiedCatalog(limit: Int = 60): List<GlobalSearchResult> =
        sourceCatalog.refreshUnifiedCatalog(limit)

    suspend fun refreshSourceFeeds(): List<SourceCatalog.SourceNewFeed> =
        sourceCatalog.refreshSourceFeeds()

    suspend fun searchAllSources(query: String): List<GlobalSearchResult> =
        sourceCatalog.searchAllSources(query)

    /**
     * Spec-10 T4 — import-and-play entry point for a search result: fetch the
     * book page from the chosen source, import the Work (merging into an
     * existing card when the merge key matches), return the stored book. Null
     * when the source is unknown or the page yields nothing playable.
     */
    suspend fun importFromSourceUrl(sourceId: String, url: String): AudiobookEntity? =
        libraryImport.importFromSourceUrl(sourceId, url)

    /**
     * Spec-13 T3 — import a WebView-source book from its CAPTURED page HTML.
     * The page HTML comes from the live browser session (past the Cloudflare
     * challenge — server-fetch would 403); the adapter's captured-page path
     * parses metadata + the inline Playerjs playlist and fetches the playlist
     * with the source Referer. Null when the source is unknown, the page is
     * unparseable or yields nothing playable.
     */
    suspend fun importWebSourcePage(sourceId: String, url: String, html: String): AudiobookEntity? =
        libraryImport.importWebSourcePage(sourceId, url, html)

    suspend fun hydrateWebSourceCatalog(sourceId: String, limit: Int = 40): SourceCatalog.HydrationResult =
        sourceCatalog.hydrateWebSourceCatalog(sourceId, limit)

    /**
     * ADR-0002 (#138) — the explicit catalogue sync call. Construction performs
     * no network I/O; the composition root (App) invokes this when it wants the
     * Explore catalogue filled from the live 4read.org homepage.
     */
    suspend fun syncCatalogOnStart(): List<CatalogSection> =
        sourceCatalog.fetchCatalogSections()

    suspend fun fetchCatalogSections(): List<CatalogSection> =
        sourceCatalog.fetchCatalogSections()

    suspend fun fetchSeriesBooks(seriesUrl: String): List<AudiobookEntity> =
        sourceCatalog.fetchSeriesBooks(seriesUrl)

    suspend fun findNextInSeries(book: AudiobookEntity): AudiobookEntity? =
        sourceCatalog.findNextInSeries(book)

    suspend fun fetchGenreBooks(genreUrl: String): List<AudiobookEntity> =
        sourceCatalog.fetchGenreBooks(genreUrl)

    suspend fun fetchTop100(): List<AudiobookEntity> =
        sourceCatalog.fetchTop100()

    suspend fun fetchPeople(url: String): List<CatalogPerson> =
        sourceCatalog.fetchPeople(url)

    suspend fun fetchPersonBooks(path: String): List<AudiobookEntity> =
        sourceCatalog.fetchPersonBooks(path)

    suspend fun fetchRelatedBooks(bookId: String): List<AudiobookEntity> =
        sourceCatalog.fetchRelatedBooks(bookId)

    /**
     * Inserts the book if absent; otherwise returns the stored row. Series
     * metadata (spec-9 T1) is written on insert and back-filled on an existing
     * row when the parsed poster carries it, so a later homepage sync enriches
     * previously-known books without touching user state (favourite/download).
     * `internal` so JVM tests can drive the parser→entity mapping without a
     * network round-trip.
     */
    internal suspend fun upsertCatalogBook(book: CatalogBook): AudiobookEntity =
        sourceCatalog.upsertCatalogBook(book)

    /**
     * Cascading book deletion (spec #8 tickets T2/T3): removes local audio
     * files, chapters, bookmarks, playback progress and finally the book
     * itself. The entities have no FK constraints, so the cascade is
     * coordinated here.
     */
    suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        // Durable tombstone (v11, wayfinder #55 Q8): the 4read catalogue re-
        // lists deleted books on every sync, so without a durable marker the
        // next sync would resurrect the deleted book after a restart.
        dao.insertTombstone(TombstoneEntity(bookId = bookId))
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
        dao.insertTombstone(TombstoneEntity(bookId = bookId))
        dao.deleteChaptersForBook(bookId)
        dao.deleteBookmarksForBook(bookId)
        dao.deletePlaybackProgressForBook(bookId)
        dao.deleteSourcesForBook(bookId)
        dao.deletePlaybackEventsForBook(bookId)
        dao.deleteAudiobook(bookId)
    }

    /** Per-book preferred playback speed (wayfinder #26); null clears the preference. */
    suspend fun setPreferredSpeed(bookId: String, speed: Float?) = listeningState.setPreferredSpeed(bookId, speed)

    /** Real chapter duration discovered during playback (replaces unknown 0). */
    suspend fun updateChapterDuration(chapterId: String, durationSeconds: Long) =
        listeningState.updateChapterDuration(chapterId, durationSeconds)

    /** Real chapter count / total duration once the book's chapters are known. */
    suspend fun updateBookStats(bookId: String, totalChapters: Int, totalDurationSeconds: Long) =
        listeningState.updateBookStats(bookId, totalChapters, totalDurationSeconds)

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
        listeningState.updatePausedAt(bookId, pausedAt, sourceKey)

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
    ) = listeningState.recordPlaybackFailure(bookId, chapterIndex, errorCodeName, streamUrl, audioEngineMode)

    // ---------------------------------------------------------------------
    // Local audio import (spec #8 ticket T7): one picked file = one book.
    // ---------------------------------------------------------------------

    /**
     * Copies a user-picked audio file (SAF content Uri) into private app
     * storage and creates a single-chapter book whose chapter points at the
     * local file.
     */
    suspend fun importLocalAudioFile(uri: Uri): AudiobookEntity = libraryImport.importLocalAudioFile(uri)

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
        libraryImport.importLocalAudioStream(displayName, stream)

    /**
     * Folder import (spec #8 Block 4): walks the SAF tree picked via
     * `OpenDocumentTree` (recursively collecting mp3/m4a/ogg audio files) and
     * delegates the grouping/insertion to the testable [importAudioEntries]
     * core.
     */
    suspend fun importLocalAudioFolder(treeUri: Uri): LocalImportResult = libraryImport.importLocalAudioFolder(treeUri)

    /**
     * Step 2 of the smart import (wayfinder #29): scans a picked SAF tree and
     * builds the pure [ImportPlan] — grouping, natural sort and T0 merge
     * suggestions against the existing library — WITHOUT touching disk or
     * Room. The user reviews and edits this plan; only [applyImportPlan]
     * writes.
     */
    suspend fun planLocalAudioFolder(treeUri: Uri): ImportPlan = libraryImport.planLocalAudioFolder(treeUri)

    /**
     * Step 4 of the smart import (wayfinder #29): applies a confirmed
     * [ImportPlan] — the ONLY writer of the smart import. Reuses the same
     * copy-then-hash + [insertLocalBook] core as the direct import, so a
     * plan confirmed without edits behaves exactly like [importAudioEntries].
     *
     * A book with `mergedIntoBookId` set attaches its chapters to the
     * existing Work instead of creating a new card (the #54 merge path, the
     * user's explicit choice). The plan's corrections are persisted to the
     * `corrections` store at apply time — one write path, no orphan decisions.
     */
    suspend fun applyImportPlan(plan: ImportPlan, sourceTreeUri: String? = null): LocalImportResult =
        libraryImport.applyImportPlan(plan, sourceTreeUri)

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
        libraryImport.importAudioEntries(entries, sourceTreeUri)

    /** Strips the extension and unsafe characters from a file/folder display name. */

    /** Original extension of an audio file (lowercased), defaulting to mp3. */

    /** Copies a stream into the private local-imports dir under a unique name. */

    /** Creates one local book with the given chapters (title, localFilePath). */
    // ---------------------------------------------------------------------
    // Wayfinder #42: re-scanning a previously imported local folder
    // ---------------------------------------------------------------------

    /** Outcome of one [rescanLocalFolder] run — what changed in the tree. */

    /**
     * Re-scans one previously imported SAF tree (wayfinder #42): walks it,
     * hashes every stream (no copies — files the library already knows never
     * touch disk), diffs against the stored chapters by content hash, applies
     * new files as chapters/books, and reports missing/moved/duplicate files.
     * The library entry and its private copies survive every outcome
     * (wayfinder #59): nothing here ever deletes a row or a file.
     */
    suspend fun rescanLocalFolder(treeUri: String): LibraryImport.RescanReport =
        libraryImport.rescanLocalFolder(treeUri)

    /**
     * Testable core of the re-scan (wayfinder #42): hashes every stream (no
     * copies — files the library already knows never touch disk), diffs
     * against the stored chapters by content hash, applies new files as
     * chapters/books, and reports missing/moved/duplicate files. The library
     * entry and its private copies survive every outcome (wayfinder #59):
     * nothing here ever deletes a row or a file.
     */
    suspend fun rescanAudioEntries(entries: List<LocalAudioEntry>, treeUri: String): LibraryImport.RescanReport =
        libraryImport.rescanAudioEntries(entries, treeUri)

    /**
     * Re-scans every previously imported local tree, best-effort per tree:
     * one dead SAF grant (moved folder) fails that tree alone, never the rest.
     */
    suspend fun rescanAllLocalFolders(): List<LibraryImport.RescanReport> =
        libraryImport.rescanAllLocalFolders()

    /** Copies a NEW local file to private storage, deduped against the library. */

    /** Re-indexes a local book's chapters naturally (deletes + reinserts the list). */

    /** Refreshes the local source's re-scan fingerprint from its stored chapters. */


    /** Natural (human) file-name comparison: track2 < track10. */



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


    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>> = listeningState.observeBookmarks(bookId)

    suspend fun addBookmark(bookmark: BookmarkEntity) = listeningState.addBookmark(bookmark)
    suspend fun deleteBookmark(bookmarkId: Long) = listeningState.deleteBookmark(bookmarkId)

    fun observeProgress(bookId: String): Flow<PlaybackProgressEntity?> = listeningState.observeProgress(bookId)
    fun observeProgress(bookId: String, sourceKey: String): Flow<PlaybackProgressEntity?> =
        listeningState.observeProgress(bookId, sourceKey)
    suspend fun getProgressSync(bookId: String): PlaybackProgressEntity? = listeningState.getProgressSync(bookId)
    suspend fun getProgressSync(bookId: String, sourceKey: String): PlaybackProgressEntity? =
        listeningState.getProgressSync(bookId, sourceKey)

    /**
     * Persists the playback position keyed per source (spec-10 T2). Callers
     * that know the source pass its key; the default "" keeps the legacy
     * single-source behaviour.
     */
    suspend fun updateProgress(bookId: String, chapterIndex: Int, positionSeconds: Long, sourceKey: String = "") =
        listeningState.updateProgress(bookId, chapterIndex, positionSeconds, sourceKey)

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
    ) = listeningState.recordPlaybackEvent(bookId, kind, chapterIndex, positionSeconds, sourceKey, fromPositionSeconds, timestampMs)

    /**
     * The undo candidate for (book, source): the latest SEEK / SOURCE_SWITCH
     * event whose jump met the threshold (pure policy). Null when there is
     * nothing undoable — the caller shows no «Повернутися» offer.
     */
    suspend fun lastUndoCandidate(bookId: String, sourceKey: String = ""): PlaybackEventEntity? =
        listeningState.lastUndoCandidate(bookId, sourceKey)

    /**
     * Prunes one (book, source) bucket to the policy: newest [cap] events
     * kept, stale undo candidates dropped. The state row is never touched.
     */
    suspend fun compactPlaybackEvents(bookId: String, sourceKey: String = "", nowMs: Long = System.currentTimeMillis()) =
        listeningState.compactPlaybackEvents(bookId, sourceKey, nowMs)

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
        if (streamOnlyBook != null && streamOnlyFor(sourceIdForUrl(streamOnlyBook.sourceUrl))) {
            Log.w("AudiobookRepo", "downloadAudiobookOffline refused: book $bookId is stream-only")
            return OfflineDownloadResult(0, 0)
        }
        // Spec-13 T2: the track CDNs (shared `redirectto.cc`) 403 without the
        // owning source's Referer — derive it from the book, not the URL host.
        val sourceId = streamOnlyBook?.let { sourceIdForUrl(it.sourceUrl) } ?: "unknown"

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
    suspend fun importAudiobookFromHtml(urlOrSlug: String, html: String): AudiobookEntity? =
        libraryImport.importAudiobookFromHtml(urlOrSlug, html)

    /**
     * Spec-14 T3/T5 — the link-import door rides the source seam: the adapter
     * owns fetching and extraction (fetchBookPage); the repository only
     * persists through the shared import path (same merge key, same source row
     * as every other door). A page that yields nothing playable surfaces as
     * absent (null) — the fabricated fallback card is gone.
     */
    suspend fun importAudiobookFrom4ReadUrl(urlOrSlug: String): AudiobookEntity? =
        libraryImport.importAudiobookFrom4ReadUrl(urlOrSlug)

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
    fun getAllListeningStats(): Flow<List<ListeningStatEntity>> = listeningState.getAllListeningStats()

    suspend fun recordListeningTime(seconds: Long) = listeningState.recordListeningTime(seconds)

    companion object {
        /** TTL of the in-memory per-source «new arrivals» feed cache (spec-10 T4). */
        private const val NEW_FEED_TTL_MS = 15 * 60 * 1000L

        /** Single source of truth for the offline-audio directory name. */
        const val OFFLINE_AUDIO_DIR = "audiobooks"
        /** User-Agent used by the offline-download HttpURLConnection. */
        const val OFFLINE_USER_AGENT = "Mozilla/5.0 (Android; 4read-Audio-Engine/1.0)"
    }
}

/** Outcome of a local folder/file import (spec #8 Block 4). */


/** A local file copied into private storage, with its content digest. */

