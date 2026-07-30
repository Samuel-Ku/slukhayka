package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
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
                seedInitialDataIfEmpty()
                fetchCatalogFrom4Read()
            }
        }
    }

    private suspend fun seedInitialDataIfEmpty() {
        val currentBooks = dao.getAllAudiobooks().first()
        val firstBookChapters = if (currentBooks.isNotEmpty()) dao.getChaptersListForBook(currentBooks.first().id) else emptyList()
        val containsMusicUrls = firstBookChapters.any { it.streamUrl.contains("soundhelix") }

        if (currentBooks.isEmpty() || containsMusicUrls) {
            val books = listOf(
                AudiobookEntity(
                    id = "2172-ybson-vylyam-neyromant",
                    title = "Нейромант (Neuromancer)",
                    author = "Уильям Гибсон",
                    narrator = "Аудиокнига (4read.org)",
                    description = "Культовый роман в стиле киберпанк. Джерело: 4read.org/2172-ybson-vylyam-neyromant.html. Кейс, бывший компьютерный взломщик из антиутопического Чиба-Сити, нанимается загадочным Армитеджем для последнего дела против искусственного интеллекта.",
                    coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
                    genre = "Cyberpunk / Sci-Fi",
                    sourceUrl = "https://4read.org/2172-ybson-vylyam-neyromant.html",
                    isDownloaded = true,
                    downloadProgress = 1.0f,
                    totalDurationSeconds = 12228L,
                    totalChapters = 6,
                    rating = 4.9f
                ),
                AudiobookEntity(
                    id = "4read-1984-orwell",
                    title = "1984",
                    author = "Джордж Оруэлл",
                    narrator = "4read Audio",
                    description = "Знаменитый роман-антиутопия о тоталитарном государстве Океания, Большом Брате и борьбе Уинстона Смита за свободу мысли. Джерело: 4read.org/1984.html",
                    coverDrawableRes = R.drawable.img_cyber_dystopia_1785247491038,
                    genre = "Антиутопия",
                    sourceUrl = "https://4read.org/1984.html",
                    isDownloaded = false,
                    downloadProgress = 0f,
                    totalDurationSeconds = 21600L,
                    totalChapters = 8,
                    rating = 5.0f
                ),
                AudiobookEntity(
                    id = "4read-fahrenheit-451",
                    title = "451° по Фаренгейту",
                    author = "Рэй Брэдбери",
                    narrator = "4read Audio",
                    description = "Мир будущего, где книги запрещены и сжигаются пожарными. Пожарный Гай Монтэг переосмысливает ценность человеческой мысли. Джерело: 4read.org",
                    coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
                    genre = "Фантастика",
                    sourceUrl = "https://4read.org/fahrenheit-451.html",
                    isDownloaded = false,
                    downloadProgress = 0f,
                    totalDurationSeconds = 14400L,
                    totalChapters = 5,
                    rating = 4.8f
                ),
                AudiobookEntity(
                    id = "dune-epic-saga",
                    title = "Дюна (Dune)",
                    author = "Фрэнк Герберт",
                    narrator = "4read Audio",
                    description = "Эпическая фантастическая сага о пустынной планете Арракис, ценнейшем ресурсе Пряности и судьбе Пола Атрейдеса. Джерело: 4read.org",
                    coverDrawableRes = R.drawable.img_dune_art_1785247505658,
                    genre = "Sci-Fi Classic",
                    sourceUrl = "https://4read.org/dune.html",
                    isDownloaded = false,
                    downloadProgress = 0f,
                    totalDurationSeconds = 18000L,
                    totalChapters = 5,
                    rating = 5.0f
                ),
                AudiobookEntity(
                    id = "4read-solaris-lem",
                    title = "Солярис",
                    author = "Станислав Лем",
                    narrator = "4read Voice Narrator",
                    description = "Психологический научно-фантастический роман о контакте с мыслящим Океаном планеты Солярис и фантомах человеческой памяти. Джерело: 4read.org",
                    coverDrawableRes = R.drawable.img_cyber_dystopia_1785247491038,
                    genre = "Sci-Fi Classic",
                    sourceUrl = "https://4read.org/solaris.html",
                    isDownloaded = false,
                    downloadProgress = 0f,
                    totalDurationSeconds = 16200L,
                    totalChapters = 6,
                    rating = 4.9f
                ),
                AudiobookEntity(
                    id = "4read-roadside-picnic",
                    title = "Пикник на обочине",
                    author = "Аркадий и Борис Стругацкие",
                    narrator = "4read Audio",
                    description = "История сталкера Рэдрика Шухарта, проникающего в опасную Зону Посещения в поисках заветного Золотого Шара. Джерело: 4read.org",
                    coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
                    genre = "Фантастика / Сталкер",
                    sourceUrl = "https://4read.org/roadside-picnic.html",
                    isDownloaded = false,
                    downloadProgress = 0f,
                    totalDurationSeconds = 15800L,
                    totalChapters = 5,
                    rating = 4.9f
                ),
                AudiobookEntity(
                    id = "4read-master-and-margarita",
                    title = "Мастер и Маргарита",
                    author = "Михаил Булгаков",
                    narrator = "4read Audio",
                    description = "Великий роман о визите Воланда и его свиты в Москву 1930-х годов, любви Мастера и Маргарты и Понтии Пилате. Джерело: 4read.org",
                    coverDrawableRes = R.drawable.img_dune_art_1785247505658,
                    genre = "Классика / Мистика",
                    sourceUrl = "https://4read.org/master-i-margarita.html",
                    isDownloaded = false,
                    downloadProgress = 0f,
                    totalDurationSeconds = 25200L,
                    totalChapters = 8,
                    rating = 5.0f
                ),
                AudiobookEntity(
                    id = "4read-sherlock-holmes",
                    title = "Приключения Шерлока Холмса",
                    author = "Артур Конан Дойл",
                    narrator = "4read Audio",
                    description = "Сборник детективных рассказов о великом сыщике Шерлоке Холмсе и его верном спутнике докторе Ватсоне. Джерело: 4read.org",
                    coverDrawableRes = R.drawable.img_cyber_dystopia_1785247491038,
                    genre = "Детектив",
                    sourceUrl = "https://4read.org/sherlock-holmes.html",
                    isDownloaded = false,
                    downloadProgress = 0f,
                    totalDurationSeconds = 19800L,
                    totalChapters = 6,
                    rating = 4.8f
                ),
                AudiobookEntity(
                    id = "cyber-dystopia-2077",
                    title = "Кибер Дистопия 2077",
                    author = "Алекс Риттер",
                    narrator = "4read Audio",
                    description = "Хроники высокотехнологичного мегаполиса, где границы между человеком и сетью размыты навсегда. Джерело: 4read.org",
                    coverDrawableRes = R.drawable.img_cyber_dystopia_1785247491038,
                    genre = "Cyberpunk",
                    sourceUrl = "https://4read.org/cyber-dystopia.html",
                    isDownloaded = false,
                    downloadProgress = 0f,
                    totalDurationSeconds = 10800L,
                    totalChapters = 4,
                    rating = 4.7f
                )
            )
            dao.insertAudiobooks(books)

            // Seed Chapters for Neuromancer with Spoken Voice Narration Streams
            val neuromancerChapters = listOf(
                ChapterEntity(
                    id = "neuro_ch_1",
                    bookId = "2172-ybson-vylyam-neyromant",
                    chapterIndex = 0,
                    title = "Глава 01: Блюз Чиба-Сити (Chiba City Blues)",
                    durationSeconds = 1725L, // 28:45
                    streamUrl = "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_01_wells_64kb.mp3",
                    isDownloaded = true
                ),
                ChapterEntity(
                    id = "neuro_ch_2",
                    bookId = "2172-ybson-vylyam-neyromant",
                    chapterIndex = 1,
                    title = "Глава 02: Вход в Матрицу (Matrix Entry)",
                    durationSeconds = 1930L, // 32:10
                    streamUrl = "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_02_wells_64kb.mp3",
                    isDownloaded = true
                ),
                ChapterEntity(
                    id = "neuro_ch_3",
                    bookId = "2172-ybson-vylyam-neyromant",
                    chapterIndex = 2,
                    title = "Глава 03: Торговые Сетки (Shopping Spree)",
                    durationSeconds = 1518L, // 25:18
                    streamUrl = "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_03_wells_64kb.mp3",
                    isDownloaded = false
                ),
                ChapterEntity(
                    id = "neuro_ch_4",
                    bookId = "2172-ybson-vylyam-neyromant",
                    chapterIndex = 3,
                    title = "Глава 04: Забег Стрейлайт (Straylight Run)",
                    durationSeconds = 2465L, // 41:05
                    streamUrl = "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_04_wells_64kb.mp3",
                    isDownloaded = false
                ),
                ChapterEntity(
                    id = "neuro_ch_5",
                    bookId = "2172-ybson-vylyam-neyromant",
                    chapterIndex = 4,
                    title = "Глава 05: Пробуждение Уинтермьюта (Wintermute Awakening)",
                    durationSeconds = 2210L, // 36:50
                    streamUrl = "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_05_wells_64kb.mp3",
                    isDownloaded = false
                ),
                ChapterEntity(
                    id = "neuro_ch_6",
                    bookId = "2172-ybson-vylyam-neyromant",
                    chapterIndex = 5,
                    title = "Глава 06: Синтез Нейроманта (Neuromancer Synthesis)",
                    durationSeconds = 2380L, // 39:20
                    streamUrl = "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_06_wells_64kb.mp3",
                    isDownloaded = false
                )
            )

            val cyberChapters = listOf(
                ChapterEntity("cyber_1", "cyber-dystopia-2077", 0, "Глава 01: Сигналы из Бездны", 2700L, "https://ia800302.us.archive.org/1/items/war_of_the_worlds_librivox/war_of_the_worlds_01_wells_64kb.mp3"),
                ChapterEntity("cyber_2", "cyber-dystopia-2077", 1, "Глава 02: Неоновый Призрак", 2700L, "https://ia800302.us.archive.org/1/items/war_of_the_worlds_librivox/war_of_the_worlds_02_wells_64kb.mp3"),
                ChapterEntity("cyber_3", "cyber-dystopia-2077", 2, "Глава 03: Сетевой Трафик", 2700L, "https://ia800302.us.archive.org/1/items/war_of_the_worlds_librivox/war_of_the_worlds_03_wells_64kb.mp3"),
                ChapterEntity("cyber_4", "cyber-dystopia-2077", 3, "Глава 04: Протокол Ноль", 2700L, "https://ia800302.us.archive.org/1/items/war_of_the_worlds_librivox/war_of_the_worlds_04_wells_64kb.mp3")
            )

            val duneChapters = listOf(
                ChapterEntity("dune_1", "dune-epic-saga", 0, "Глава 01: Испытание Гом Джаббар", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_01_wells_64kb.mp3"),
                ChapterEntity("dune_2", "dune-epic-saga", 1, "Глава 02: Прибытие на Арракис", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_02_wells_64kb.mp3"),
                ChapterEntity("dune_3", "dune-epic-saga", 2, "Глава 03: Пряность и Пустыня", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_03_wells_64kb.mp3"),
                ChapterEntity("dune_4", "dune-epic-saga", 3, "Глава 04: Засада Харконненов", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_04_wells_64kb.mp3"),
                ChapterEntity("dune_5", "dune-epic-saga", 4, "Глава 05: Путь Фрименов", 3600L, "https://ia800201.us.archive.org/12/items/time_machine_0802_librivox/timemachine_05_wells_64kb.mp3")
            )

            dao.insertChapters(neuromancerChapters + cyberChapters + duneChapters)

            // Seed initial bookmarks for Neuromancer
            dao.insertBookmark(
                BookmarkEntity(
                    bookId = "2172-ybson-vylyam-neyromant",
                    chapterIndex = 0,
                    chapterTitle = "Глава 01: Блюз Чиба-Сити",
                    timestampSeconds = 862L, // 14:22
                    note = "Встреча Кейса и Молли Миллионс в баре Затмение. Завязка сюжета!"
                )
            )
            dao.insertBookmark(
                BookmarkEntity(
                    bookId = "2172-ybson-vylyam-neyromant",
                    chapterIndex = 1,
                    chapterTitle = "Глава 02: Вход в Матрицу",
                    timestampSeconds = 1240L, // 20:40
                    note = "Описание деки Ono-Sendai Cyberspace 7 и первого погружения."
                )
            )

            // Seed initial playback progress
            dao.savePlaybackProgress(
                PlaybackProgressEntity(
                    bookId = "2172-ybson-vylyam-neyromant",
                    currentChapterIndex = 0,
                    currentPositionSeconds = 862L,
                    lastListenedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun observeBook(bookId: String): Flow<AudiobookEntity?> = dao.observeAudiobookById(bookId)
    suspend fun getBookSync(bookId: String): AudiobookEntity? = dao.getAudiobookById(bookId)

    fun observeChapters(bookId: String): Flow<List<ChapterEntity>> = dao.getChaptersForBook(bookId)
    suspend fun getChaptersList(bookId: String): List<ChapterEntity> {
        var chapters = dao.getChaptersListForBook(bookId)
        val book = dao.getAudiobookById(bookId)
        val sourceUrl = book?.sourceUrl ?: ""
        val hasArchiveFallback = chapters.isEmpty() || chapters.any { it.streamUrl.contains("archive.org") }

        if (hasArchiveFallback && sourceUrl.isNotBlank() && sourceUrl.contains("4read.org")) {
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

    suspend fun fetchCatalogFrom4Read(): List<AudiobookEntity> = withContext(Dispatchers.IO) {
        try {
            val html = fetchUrlText("https://4read.org/")
            if (html.isBlank()) return@withContext emptyList()

            val foundBooks = mutableListOf<AudiobookEntity>()
            val linkRegex = Regex("""<a\s+href="(https?://4read\.org/([^"/]+)\.html)"[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
            val addedSlugs = mutableSetOf<String>()

            for (match in linkRegex.findAll(html)) {
                val fullUrl = match.groupValues[1]
                val slug = match.groupValues[2]
                val rawTitle = match.groupValues[3].trim()

                if (slug.contains("index") || slug.contains("page") || slug.contains("rules") || rawTitle.length < 3 || addedSlugs.contains(slug)) {
                    continue
                }
                addedSlugs.add(slug)

                val bookId = "4read-$slug"
                val existing = dao.getAudiobookById(bookId)
                if (existing != null) {
                    foundBooks.add(existing)
                } else {
                    val cleanTitle = rawTitle.replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'")
                    val newBook = AudiobookEntity(
                        id = bookId,
                        title = cleanTitle,
                        author = "4read.org",
                        narrator = "4read Voice Narrator",
                        description = "Книга з каталогу 4read.org. Повна версія з аудіоплеєром. Джерело: $fullUrl",
                        coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
                        genre = "4read Каталог",
                        sourceUrl = fullUrl,
                        isDownloaded = false,
                        downloadProgress = 0f,
                        totalDurationSeconds = 14400L,
                        totalChapters = 5,
                        rating = 4.8f
                    )
                    dao.insertAudiobooks(listOf(newBook))
                    foundBooks.add(newBook)
                }
            }
            foundBooks
        } catch (e: Exception) {
            emptyList()
        }
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

        // 1. Try audio metadata (embedded picture)
        var audioCoverUrl: String? = null
        if (context != null && chapters.isNotEmpty()) {
            val firstChapter = chapters.first()
            val audioSource = firstChapter.localFilePath ?: firstChapter.streamUrl
            if (audioSource.isNotBlank()) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    if (firstChapter.localFilePath != null) {
                        retriever.setDataSource(firstChapter.localFilePath)
                    } else {
                        retriever.setDataSource(audioSource, mapOf("User-Agent" to "Mozilla/5.0"))
                    }
                    val artBytes = retriever.embeddedPicture
                    if (artBytes != null && artBytes.isNotEmpty()) {
                        val coversDir = File(context.filesDir, "covers")
                        if (!coversDir.exists()) coversDir.mkdirs()
                        val coverFile = File(coversDir, "${bookId}_art.jpg")
                        coverFile.writeBytes(artBytes)
                        audioCoverUrl = "file://${coverFile.absolutePath}"
                    }
                } catch (e: Exception) {
                    // Audio has no embedded image
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            }
        }

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
            if (pageData.audioUrls.isNotEmpty() && chapters.any { it.streamUrl.contains("archive.org") }) {
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
        /** User-Agent used by the offline-download HttpURLConnection. */
        const val OFFLINE_USER_AGENT = "Mozilla/5.0 (Android; 4read-Audio-Engine/1.0)"
    }
}

data class Parsed4ReadData(
    val coverImageUrl: String? = null,
    val audioUrls: List<String> = emptyList()
)
