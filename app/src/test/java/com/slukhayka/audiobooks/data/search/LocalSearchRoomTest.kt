package com.slukhayka.audiobooks.data.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.metadata.CoverOverrideStore
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * #1119 — the local index is an immediate preview, never a reason to skip
 * sources. Every query gathers live hits and fills the guarded mirror gap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalSearchRoomTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private class LiveAdapter(
        override val sourceId: String,
        private val books: List<SourceBook>
    ) : SourceAdapter {
        override val contentLanguage: String = "uk"
        override suspend fun search(query: String): List<SourceBook> =
            books.filter { it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true) }
        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail("", "", url = url, chapters = emptyList())
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = emptyList()
    }

    private fun catalog(
        vararg adapters: SourceAdapter,
        selection: Set<String> = emptySet(),
        sessionAlive: (String) -> Boolean = { false },
        refused: Set<String> = emptySet()
    ) = SourceCatalog(
        dao,
        adapters.toList(),
        LibraryImport(dao, context, adapters.toList()),
        contentLanguageSelection = MutableStateFlow(selection),
        sourceAudioRefusal = MutableStateFlow(refused),
        sessionAlive = sessionAlive
    )

    private fun write(
        sourceId: String,
        title: String,
        author: String,
        url: String,
        language: String = "uk",
        narrator: String = ""
    ) = runBlocking {
        catalog().writeWorkEdition(
            sourceId = sourceId,
            title = title,
            author = author,
            narrator = narrator,
            sourceUrl = url,
            language = language
        )
    }

    private fun book(title: String, author: String, url: String, sourceId: String = "t1") =
        SourceBook(title = title, author = author, url = url, sourceId = sourceId, language = "uk")

    @Test
    fun `three local matches cannot hide books from any live source`() = runBlocking {
        write("t1", "Кобзар", "Тарас Шевченко", "https://t1.example/kobzar")
        write("t2", "Кобза", "Автор А", "https://t2.example/kobza")
        write("t3", "Кобзареві думи", "Автор Б", "https://t3.example/dumy")
        write("t1", "Лісова пісня", "Леся Українка", "https://t1.example/lisova")

        val cards = catalog(
            LiveAdapter("t1", listOf(book("Кобзарик", "Автор В", "https://t1.example/new"))),
            LiveAdapter("t2", listOf(book("Кобзареві шляхи", "Автор Г", "https://t2.example/new", "t2")))
        ).searchAllSources("кобз")

        assertEquals(
            setOf("Кобзар", "Кобза", "Кобзареві думи", "Кобзарик", "Кобзареві шляхи"),
            cards.map { it.title }.toSet()
        )
    }

    @Test
    fun `exact title ranks first`() = runBlocking {
        write("t1", "Великий кобзар", "Автор А", "https://t1.example/v")
        write("t1", "Кобзар", "Тарас Шевченко", "https://t1.example/k")
        write("t1", "Кобзареві шляхи", "Автор Б", "https://t1.example/s")

        val cards = catalog(LiveAdapter("t1", emptyList())).searchAllSources("кобзар")

        assertEquals("Кобзар", cards.first().title)
    }

    @Test
    fun `thin local answer keeps rows while live fills the gap`() = runBlocking {
        write("t1", "Кобзар", "Тарас Шевченко", "https://t1.example/kobzar")
        val live = listOf(
            book("Кобза", "Автор А", "https://t1.example/kobza"),
            book("Кобзареві думи", "Автор Б", "https://t1.example/dumy")
        )

        val cards = catalog(LiveAdapter("t1", live)).searchAllSources("кобз")

        assertEquals(3, cards.size)
        // The gap-fill mirrored the live hits: the index grew behind the call.
        assertEquals(3, dao.workSearchRowCount())
    }

    @Test
    fun `mirrored live hits remain available offline on the next query`() = runBlocking {
        val live = listOf(
            book("Кобзар", "Тарас Шевченко", "https://t1.example/kobzar"),
            book("Кобза", "Автор А", "https://t1.example/kobza"),
            book("Кобзареві думи", "Автор Б", "https://t1.example/dumy")
        )
        val first = catalog(LiveAdapter("t1", live)).searchAllSources("кобз")
        assertEquals(3, first.size)

        val second = catalog(LiveAdapter("t1", emptyList())).searchAllSources("кобз")
        assertEquals(3, second.size)
    }

    @Test
    fun `tombstoned live hit shows but never persists`() = runBlocking {
        val key = MergeKey.keyFor("Томбстоун", "Автор Т")
        dao.insertTombstone(TombstoneEntity(bookId = key))
        val live = listOf(book("Томбстоун", "Автор Т", "https://t1.example/tomb"))

        val cards = catalog(LiveAdapter("t1", live)).searchAllSources("томб")

        // Ephemeral search still shows the card — only the mirror write is barred.
        assertEquals(1, cards.size)
        assertNull(dao.findWorkByMergeKey(key))
        assertTrue(dao.matchWorkSearch(SearchIndexNormalize.matchQuery("томб")!!, 50).isEmpty())
    }

    @Test
    fun `local card carries the agreed rendition language through the filter`() = runBlocking {
        write("t1", "Oxford Tales", "Author E", "https://t1.example/ox", language = "en")
        write("t1", "Oxford Echoes", "Author F", "https://t1.example/ox2", language = "en")
        write("t1", "Oxford Nights", "Author G", "https://t1.example/ox3", language = "en")

        val unfiltered = catalog(LiveAdapter("t1", emptyList())).searchAllSources("oxford")
        assertEquals(3, unfiltered.size)
        assertTrue(unfiltered.all { it.language == "en" })

        // The uk selection hides all en cards in both the preview and the
        // settled answer. The empty live leg adds no visible book.
        val ukOnly = catalog(LiveAdapter("t1", emptyList()), selection = setOf("uk"))
            .searchAllSources("oxford")
        assertTrue(ukOnly.isEmpty())
    }

    @Test
    fun `hidden-language local rows stay hidden while live fills the gap`() = runBlocking {
        // The uk-only listener sees none of these three indexed en Works;
        // the live answer supplies the only visible match.
        write("t1", "Oxford Tales", "Author E", "https://t1.example/ox", language = "en")
        write("t1", "Oxford Echoes", "Author F", "https://t1.example/ox2", language = "en")
        write("t1", "Oxford Nights", "Author G", "https://t1.example/ox3", language = "en")
        val live = listOf(book("Oxford Ukrainian", "Автор У", "https://t1.example/ox-uk"))

        val cards = catalog(LiveAdapter("t1", live), selection = setOf("uk"))
            .searchAllSources("oxford")

        assertEquals(1, cards.size)
        assertEquals("Oxford Ukrainian", cards.single().title)
        assertTrue(cards.single().sources.all { it.language == "uk" })
        // The live hit mirrored into the index, so the next uk query answers
        // in the first local preview of the next query.
        val mirrored = dao.matchWorkSearch(SearchIndexNormalize.matchQuery("oxford")!!, 50)
        assertTrue(mirrored.contains(MergeKey.keyFor("Oxford Ukrainian", "Автор У")))
    }

    @Test
    fun `language-visible local matches survive an empty live answer`() = runBlocking {
        write("t1", "Кобзар", "Тарас Шевченко", "https://t1.example/kobzar", language = "uk")
        write("t2", "Кобза", "Автор А", "https://t2.example/kobza", language = "uk")
        write("t3", "Кобзареві думи", "Автор Б", "https://t3.example/dumy", language = "uk")

        val cards = catalog(LiveAdapter("t1", emptyList()), LiveAdapter("t2", emptyList()), selection = setOf("uk"))
            .searchAllSources("кобз")

        assertEquals(3, cards.size)
    }

    @Test
    fun `tombstoned work never resurfaces through the index`() = runBlocking {
        write("t1", "Кобзар", "Тарас Шевченко", "https://t1.example/kobzar")
        write("t2", "Кобза", "Автор А", "https://t2.example/kobza")
        write("t3", "Кобзареві думи", "Автор Б", "https://t3.example/dumy")
        write("t4", "Кобзарик", "Автор В", "https://t4.example/kobzaryk")
        val tombstonedId = MergeKey.keyFor("Кобза", "Автор А")
        dao.insertTombstone(TombstoneEntity(bookId = tombstonedId))

        val cards = catalog(LiveAdapter("t1", emptyList()), LiveAdapter("t2", emptyList())).searchAllSources("кобз")

        assertEquals(3, cards.size)
        assertTrue(cards.none { it.mergeKey == tombstonedId })

        // The write door purges the row on the next refresh — no accumulation.
        dao.refreshWorkSearchIndex(tombstonedId)
        assertEquals(3, dao.workSearchRowCount())
    }

    @Test
    fun `scam rows never surface`() = runBlocking {
        write("t1", "Кобза", "Автор А", "https://t1.example/kobza")
        write("t2", "Кобзареві думи", "Автор Б", "https://t2.example/dumy")
        write("t3", "Кобзарик", "Автор В", "https://t3.example/kobzaryk")
        write("4read", "Кобза", "Скам Автор", "https://4read.org/scam")

        val cards = catalog(LiveAdapter("t1", emptyList())).searchAllSources("кобз")

        assertEquals(3, cards.size)
        assertTrue(cards.flatMap { it.sources }.none { it.sourceId == "4read" })
    }

    @Test
    fun `browser row needs a live session`() = runBlocking {
        write("sluhay", "Кобзар", "Тарас Шевченко", "https://sluhay.com/kobzar")
        write("t1", "Кобза", "Автор А", "https://t1.example/kobza")
        write("t2", "Кобзареві думи", "Автор Б", "https://t2.example/dumy")

        // No session: the session-backed row is skipped in the local preview.
        // The live leg adds no usable data in this fixture.
        val noSession = catalog(LiveAdapter("t1", emptyList())).searchAllSources("кобз")
        assertEquals(2, noSession.size)
        assertTrue(noSession.flatMap { it.sources }.none { it.sourceId == "sluhay" })

        // A live first-party session makes the same local row usable.
        val withSession = catalog(LiveAdapter("t1", emptyList()), sessionAlive = { true }).searchAllSources("кобз")
        assertEquals(3, withSession.size)
        assertTrue(withSession.flatMap { it.sources }.any { it.sourceId == "sluhay" })
    }

    @Test
    fun `refused source still surfaces metadata like the live leg`() = runBlocking {
        write("t1", "Кобзар", "Тарас Шевченко", "https://t1.example/kobzar")
        write("t1", "Кобза", "Автор А", "https://t1.example/kobza")
        write("t1", "Кобзареві думи", "Автор Б", "https://t1.example/dumy")

        // ADR-0037: refusal gates playable pairing, never metadata — the
        // local leg shows the same cards the live leg shows; Play filters.
        val cards = catalog(LiveAdapter("t1", emptyList()), refused = setOf("t1")).searchAllSources("кобз")

        assertEquals(3, cards.size)
    }

    @Test
    fun `local preview arrives before a slow source and is then supplemented`() = runBlocking {
        write("t1", "Кобзар", "Тарас Шевченко", "https://t1.example/kobzar")
        val preview = CompletableDeferred<GlobalSearchUpdate>()
        val release = CompletableDeferred<Unit>()
        val delayed = object : SourceAdapter by LiveAdapter("t1", emptyList()) {
            override suspend fun search(query: String): List<SourceBook> {
                release.await()
                return listOf(book("Кобза", "Автор А", "https://t1.example/new"))
            }
        }
        val updates = mutableListOf<GlobalSearchUpdate>()
        val job = async {
            catalog(delayed).searchAllSources("кобз") {
                updates += it
                if (it.isSearchingSources) preview.complete(it)
            }
        }
        try {
            val first = withTimeout(10_000) { preview.await() }
            assertEquals(listOf("Кобзар"), first.results.map { it.title })
            assertTrue(first.isSearchingSources)
            assertTrue(!job.isCompleted)
        } finally {
            release.complete(Unit)
        }
        val final = job.await()
        assertEquals(setOf("Кобзар", "Кобза"), final.map { it.title }.toSet())
        assertEquals(final, updates.last().results)
        assertTrue(!updates.last().isSearchingSources)
    }

    @Test
    fun `search fills author catalogue even after three local matches`() = runBlocking {
        write("t1", "Воно", "Стівен Кінг", "https://t1.example/it")
        write("t1", "Сяйво", "Стівен Кінг", "https://t1.example/shining")
        write("t1", "Керрі", "Стівен Кінг", "https://t1.example/carrie")
        val repository = catalog(LiveAdapter("t1", listOf(
            book("Мізері", "Стівен Кінг", "https://t1.example/misery"),
            book("Зелена миля", "Стівен Кінг", "https://t1.example/mile")
        )))

        repository.searchAllSources("кінг")

        val author = repository.searchAuthors("кінг").single()
        assertEquals(5, author.workCount)
        assertEquals(setOf("Воно", "Сяйво", "Керрі", "Мізері", "Зелена миля"),
            repository.authorWorks(author.id).map { it.title }.toSet())
    }

    @Test
    fun `source failure keeps local and successful source results with a partial verdict`() = runBlocking {
        write("t1", "Кобзар", "Тарас Шевченко", "https://t1.example/kobzar")
        val failed = object : SourceAdapter by LiveAdapter("t2", emptyList()) {
            override suspend fun search(query: String): List<SourceBook> = error("offline")
        }
        val updates = mutableListOf<GlobalSearchUpdate>()
        val results = catalog(failed, LiveAdapter("t1", listOf(
            book("Кобза", "Автор А", "https://t1.example/new")
        ))).searchAllSources("кобз") { updates += it }

        assertEquals(setOf("Кобзар", "Кобза"), results.map { it.title }.toSet())
        assertTrue(updates.last().hasSourceFailures)
        assertTrue(!updates.last().isSearchingSources)
    }

    @Test
    fun `failed feed fallback is not reported as a successful empty search`() = runBlocking {
        val failed = object : SourceAdapter by LiveAdapter("t1", emptyList()) {
            override val supportsSearch: Boolean = false
            override suspend fun fetchNew(limit: Int): List<SourceBook> = error("offline feed")
        }
        val updates = mutableListOf<GlobalSearchUpdate>()
        catalog(failed).searchAllSources("кінг") { updates += it }

        assertTrue(updates.last().results.isEmpty())
        assertTrue(updates.last().hasSourceFailures)
    }

    @Test
    fun `failed feed enrichment keeps the card but reports partial source results`() = runBlocking {
        val feedBook = book("Кобзар", "", "https://t1.example/kobzar")
        val failed = object : SourceAdapter by LiveAdapter("t1", emptyList()) {
            override val supportsSearch: Boolean = false
            override suspend fun fetchNew(limit: Int): List<SourceBook> = listOf(feedBook)
            override suspend fun fetchBookPage(url: String): SourceBookDetail = error("offline detail")
        }
        val updates = mutableListOf<GlobalSearchUpdate>()
        val results = catalog(failed).searchAllSources("кобзар") { updates += it }

        assertEquals(listOf("Кобзар"), results.map { it.title })
        assertTrue(updates.last().hasSourceFailures)
        assertTrue(!updates.last().isSearchingSources)
    }

    @Test
    fun `cancelled search cannot publish or mirror a late source answer`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = object : SourceAdapter by LiveAdapter("t1", emptyList()) {
            override suspend fun search(query: String): List<SourceBook> = withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
                listOf(book("Кобзар", "Тарас Шевченко", "https://t1.example/late"))
            }
        }
        val updates = mutableListOf<GlobalSearchUpdate>()
        val repository = catalog(delayed)
        val job = async { repository.searchAllSources("кобз") { updates += it } }
        try {
            withTimeout(10_000) { started.await() }
            job.cancel()
        } finally {
            release.complete(Unit)
        }
        job.join()
        assertEquals(1, updates.size)
        assertTrue(updates.single().isSearchingSources)
        assertTrue(repository.searchAuthors("шевченко").isEmpty())
    }


    @Test
    fun `local preview respects a pinned cover before sources settle`() = runBlocking {
        assertPreviewCover("https://mine.example/cover.jpg")
    }

    @Test
    fun `local preview respects pinned absence rather than restoring a source cover`() = runBlocking {
        assertPreviewCover(null)
    }

    private suspend fun assertPreviewCover(pinnedCover: String?) {
        val repository = catalog(LiveAdapter("t1", emptyList()))
        val written = repository.writeWorkEdition(
            sourceId = "t1", title = "Кобзар", author = "Тарас Шевченко", narrator = "",
            sourceUrl = "https://t1.example/kobzar",
            coverImageUrl = "https://source.example/cover.jpg", language = "uk"
        )
        CoverOverrideStore(dao).pin(written.work.id, written.work.mergeKey, pinnedCover)
        val updates = mutableListOf<GlobalSearchUpdate>()
        repository.searchAllSources("кобзар") { updates += it }

        assertTrue(updates.first().isSearchingSources)
        assertEquals(pinnedCover, updates.first().results.single().coverImageUrl)
    }

    @Test
    fun `diagnostics distinguish local preview from live gather without query text`() = runBlocking {
        ShadowLog.clear()
        catalog(LiveAdapter("t1", listOf(
            book("Кобзар", "Тарас Шевченко", "https://t1.example/kobzar")
        ))).searchAllSources("кобз")

        val messages = ShadowLog.getLogsForTag("SourceCatalog").map { it.msg }
        assertTrue(messages.any { it.contains("path=local_pending local=0 visible=0") })
        assertTrue(messages.any { it.contains("path=live local=0 live=1 sources=1 failed=0") })
        assertTrue(messages.any { it.contains("path=settled local=0 live=1 merged=1 visible=1 failed=0") })
        assertTrue(messages.none { it.contains("кобз", ignoreCase = true) || it.contains("https://") })
    }

}
