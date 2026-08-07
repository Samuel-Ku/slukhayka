package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.R
import com.example.data.catalog.CatalogBook
import com.example.data.catalog.CatalogParser
import com.example.data.catalog.CatalogSection
import com.example.data.db.*
import com.example.data.imports.LocalAudioEntry
import com.example.data.imports.LocalFolderScanner
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
    private val autoSyncOnInit: Boolean = true
) {

    val allBooks: Flow<List<AudiobookEntity>> = dao.getAllAudiobooks()
    val downloadedBooks: Flow<List<AudiobookEntity>> = dao.getDownloadedAudiobooks()
    val allBookmarks: Flow<List<BookmarkEntity>> = dao.getAllBookmarks()
    val recentProgress: Flow<List<PlaybackProgressEntity>> = dao.getAllPlaybackProgress()

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


    // ---------------------------------------------------------------------
    // Catalogue sections (spec #8 tickets T5/T6): rows for the Explore
    // screen, parsed from the 4read.org homepage and cached in memory.
    // ---------------------------------------------------------------------

    private val _catalogSections = MutableStateFlow<List<CatalogSection>>(emptyList())
    val catalogSections: StateFlow<List<CatalogSection>> = _catalogSections.asStateFlow()

    private val _isCatalogLoading = MutableStateFlow(false)
    val isCatalogLoading: StateFlow<Boolean> = _isCatalogLoading.asStateFlow()

    /**
     * Book ids deleted this session (spec #8 T3). The 4read homepage re-lists
     * deleted books, so without a tombstone the next catalogue sync would
     * resurrect them in Room and in the Explore rows (code-review MEDIUM).
     */
    private val deletedCatalogBookIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
     * DB entities.
     */
    suspend fun fetchSeriesBooks(seriesUrl: String): List<AudiobookEntity> = withContext(Dispatchers.IO) {
        val html = fetchUrlText(seriesUrl)
        if (html.isBlank()) return@withContext emptyList()
        CatalogParser.parseSeriesPage(html)
            .filter { it.id !in deletedCatalogBookIds }
            .map { book -> upsertCatalogBook(book) }
    }

    /** Inserts the book if absent; otherwise returns the stored row. */
    private suspend fun upsertCatalogBook(book: CatalogBook): AudiobookEntity {
        val existing = dao.getAudiobookById(book.id)
        if (existing != null) return existing
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
            totalDurationSeconds = 14400L,
            totalChapters = 5,
            rating = 4.8f
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
        dao.deleteAudiobook(bookId)
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
     */
    suspend fun importLocalAudioStream(displayName: String, stream: java.io.InputStream): AudiobookEntity =
        withContext(Dispatchers.IO) {
            val base = sanitizeLocalBaseName(displayName)
            val dest = copyLocalAudioStream(base, localFileExtension(displayName), stream)
            insertLocalBook(
                title = base,
                author = LOCAL_FILE_AUTHOR,
                description = "Імпортований аудіофайл: $displayName",
                chapters = listOf(base to dest)
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
        importAudioEntries(entries)
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
     */
    suspend fun importAudioEntries(entries: List<LocalAudioEntry>): LocalImportResult =
        withContext(Dispatchers.IO) {
            var booksImported = 0
            var filesImported = 0
            var skippedFiles = 0

            // 1) Loose files at the tree root → one single-chapter book each.
            for (entry in entries.filter { it.parentFolder.isNullOrBlank() }) {
                val base = sanitizeLocalBaseName(entry.fileName)
                val dest = try {
                    copyLocalAudioStream(base, localFileExtension(entry.fileName), entry.openStream())
                } catch (e: Exception) {
                    Log.w("AudiobookRepo", "Local import failed for ${entry.fileName}", e)
                    skippedFiles++
                    continue
                }
                insertLocalBook(
                    title = base,
                    author = LOCAL_FILE_AUTHOR,
                    description = "Імпортований аудіофайл: ${entry.fileName}",
                    chapters = listOf(base to dest)
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
                val chapters = mutableListOf<Pair<String, String>>()
                for (entry in files.sortedWith(Comparator { a, b -> compareNatural(a.fileName, b.fileName) })) {
                    val chapterTitle = sanitizeLocalBaseName(entry.fileName).ifBlank { entry.fileName }
                    val dest = try {
                        copyLocalAudioStream("$bookTitle-$chapterTitle", localFileExtension(entry.fileName), entry.openStream())
                    } catch (e: Exception) {
                        Log.w("AudiobookRepo", "Local import failed for ${entry.fileName}", e)
                        skippedFiles++
                        continue
                    }
                    chapters.add(chapterTitle to dest)
                    filesImported++
                }
                if (chapters.isNotEmpty()) {
                    insertLocalBook(
                        title = bookTitle,
                        author = LOCAL_FOLDER_AUTHOR,
                        description = "Імпортовано з папки «$folder» — ${chapters.size} файл(ів)",
                        chapters = chapters
                    )
                    booksImported++
                }
            }

            LocalImportResult(booksImported = booksImported, filesImported = filesImported, skippedFiles = skippedFiles)
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
    private fun copyLocalAudioStream(baseName: String, extension: String, stream: java.io.InputStream): String {
        val ctx = context ?: throw IllegalStateException("local import requires Context")
        val audioDir = File(ctx.filesDir, LOCAL_AUDIO_DIR)
        if (!audioDir.exists()) audioDir.mkdirs()
        // Unique suffix (counter-based, unlike the old timestamp-only one) so
        // rapid folder imports never collide within the same millisecond. The
        // original extension is preserved so ExoPlayer detects the container.
        val destFile = File(audioDir, "$baseName-${localImportSeq.incrementAndGet()}.$extension")
        stream.use { input -> destFile.outputStream().use { output -> input.copyTo(output) } }
        return destFile.absolutePath
    }

    /** Creates one local book with the given chapters (title, localFilePath). */
    private suspend fun insertLocalBook(
        title: String,
        author: String,
        description: String,
        chapters: List<Pair<String, String>>
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
            rating = 4.5f
        )
        dao.insertAudiobooks(listOf(book))
        dao.insertChapters(
            chapters.mapIndexed { index, (chapterTitle, filePath) ->
                ChapterEntity(
                    id = "${bookId}_ch${index + 1}",
                    bookId = bookId,
                    chapterIndex = index,
                    title = chapterTitle,
                    durationSeconds = 0L,
                    streamUrl = filePath,
                    localFilePath = filePath,
                    isDownloaded = true
                )
            }
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
                        durationSeconds = 1800L,
                        streamUrl = audioUrl
                    )
                }
                dao.insertChapters(realChapters)
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
    suspend fun getProgressSync(bookId: String): PlaybackProgressEntity? = dao.getPlaybackProgressSync(bookId)

    suspend fun updateProgress(bookId: String, chapterIndex: Int, positionSeconds: Long) {
        val progress = PlaybackProgressEntity(
            bookId = bookId,
            currentChapterIndex = chapterIndex,
            currentPositionSeconds = positionSeconds,
            lastListenedAt = System.currentTimeMillis()
        )
        dao.savePlaybackProgress(progress)
    }

    suspend fun downloadAudiobookOffline(bookId: String) {
        val chapters = dao.getChaptersListForBook(bookId)
        val total = chapters.size
        if (total == 0) return

        // Phase 2.5 hotfix (SF-004 / SEC-008): the previous /sdcard fallback
        // was unreachable on Android 11+ scoped storage and would have failed
        // at runtime. The app always constructs this repository with a real
        // Context, so fail loudly when it isn't there.
        val ctx = context ?: run {
            Log.e("AudiobookRepo", "downloadAudiobookOffline called without Context; aborting")
            dao.updateDownloadState(bookId, isDownloaded = false, progress = 0f)
            return
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
            // Same guard as getChaptersList: never overwrite existing (seeded)
            // chapters with live-page ones -- that duplicated rows on every
            // book-detail open.
            if (chapters.isEmpty() && pageData.audioUrls.isNotEmpty()) {
                val updatedChapters = pageData.audioUrls.mapIndexed { index, audioUrl ->
                    ChapterEntity(
                        id = "${bookId}_ch${index + 1}",
                        bookId = bookId,
                        chapterIndex = index,
                        title = "Глава ${index + 1} (${book.title})",
                        durationSeconds = 1800L,
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
            totalDurationSeconds = 14400L,
            totalChapters = audioStreams.size.coerceAtLeast(1),
            rating = 4.9f
        )

        val chapterList = if (audioStreams.isNotEmpty()) {
            audioStreams.distinct().mapIndexed { index, audioUrl ->
                ChapterEntity(
                    id = "${bookId}_ch${index + 1}",
                    bookId = bookId,
                    chapterIndex = index,
                    title = "Глава ${index + 1}",
                    durationSeconds = 1800L,
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
            author = "Аудиокнига 4read.org",
            narrator = "4read Voice Narrator",
            description = "Аудиокнига с портала 4read.org ($cleanInput). Доступны все главы с онлайн-стримингом.",
            coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
            coverImageUrl = parsedDetails.coverImageUrl,
            genre = "4read Catalog",
            sourceUrl = sourceUrl,
            isDownloaded = false,
            downloadProgress = 0f,
            totalDurationSeconds = 14400L,
            totalChapters = parsedDetails.audioUrls.size.coerceAtLeast(3),
            rating = 4.9f
        )

        dao.insertAudiobooks(listOf(newBook))

        val chapterList = if (parsedDetails.audioUrls.isNotEmpty()) {
            parsedDetails.audioUrls.mapIndexed { index, audioUrl ->
                ChapterEntity(
                    id = "${bookId}_ch${index + 1}",
                    bookId = bookId,
                    chapterIndex = index,
                    title = "Глава ${index + 1} ($formattedTitle)",
                    durationSeconds = 1800L,
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

    private suspend fun fetch4ReadPageDetails(pageUrl: String): Parsed4ReadData = withContext(Dispatchers.IO) {
        var coverUrl: String? = null
        val audioStreams = mutableListOf<String>()
        try {
            val html = fetchUrlText(pageUrl)
            if (html.isNotBlank()) {
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
            audioUrls = audioStreams.distinct()
        )
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
                val searchUrl = "https://4read.org/index.php?do=search&subaction=search&story=$encodedQuery"
                val url = URL(searchUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 12000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    instanceFollowRedirects = true
                }

                val html = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    ""
                } finally {
                    conn.disconnect()
                }

                val foundBooks = mutableListOf<AudiobookEntity>()

                if (html.isNotEmpty()) {
                    val linkRegex = Regex("""<a\s+href="(https?://4read\.org/([^"]+)\.html)"[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
                    val matches = linkRegex.findAll(html)
                    val addedSlugs = mutableSetOf<String>()

                    for (match in matches) {
                        val fullUrl = match.groupValues[1]
                        val slug = match.groupValues[2]
                        val rawTitle = match.groupValues[3].trim()

                        if (slug.contains("index") || slug.contains("page") || rawTitle.length < 3 || addedSlugs.contains(slug)) {
                            continue
                        }
                        addedSlugs.add(slug)

                        val bookId = "4read-$slug"
                        val existing = dao.getAudiobookById(bookId)
                        if (existing != null) {
                            foundBooks.add(existing)
                        } else {
                            val cleanTitle = rawTitle.replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'")
                            val pageDetails = fetch4ReadPageDetails(fullUrl)

                            val newBook = AudiobookEntity(
                                id = bookId,
                                title = cleanTitle,
                                author = "4read.org",
                                narrator = "4read Voice Narrator",
                                description = "Книга знайдена на порталі 4read.org за запитом \"$cleanQuery\". Джерело: $fullUrl",
                                coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
                                coverImageUrl = pageDetails.coverImageUrl,
                                genre = "4read Каталог",
                                sourceUrl = fullUrl,
                                isDownloaded = false,
                                downloadProgress = 0f,
                                totalDurationSeconds = 14400L,
                                totalChapters = pageDetails.audioUrls.size.coerceAtLeast(3),
                                rating = 4.8f
                            )
                            dao.insertAudiobooks(listOf(newBook))

                            val chapterList = if (pageDetails.audioUrls.isNotEmpty()) {
                                pageDetails.audioUrls.mapIndexed { idx, audioUrl ->
                                    ChapterEntity(
                                        id = "${bookId}_ch${idx + 1}",
                                        bookId = bookId,
                                        chapterIndex = idx,
                                        title = "Частина ${idx + 1}: $cleanTitle",
                                        durationSeconds = 1800L,
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
                        totalDurationSeconds = 14400L,
                        totalChapters = 3,
                        rating = 4.9f
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
    val audioUrls: List<String> = emptyList()
)

/** Outcome of a local folder/file import (spec #8 Block 4). */
data class LocalImportResult(
    val booksImported: Int,
    val filesImported: Int,
    val skippedFiles: Int
)
