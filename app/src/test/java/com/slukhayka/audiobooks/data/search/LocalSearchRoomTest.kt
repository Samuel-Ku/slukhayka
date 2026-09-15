package com.slukhayka.audiobooks.data.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #824 — the local leg of aggregated search: a sufficient index answer
 * returns merged cards with zero network requests; a thin answer keeps its
 * rows while the live volley fills the gap; live hits mirror (guarded) so
 * the next identical query answers locally.
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

    /** Any request through this adapter fails the test — zero network proof. */
    private class SilentAdapter(override val sourceId: String) : SourceAdapter {
        override val contentLanguage: String = "uk"
        override suspend fun search(query: String): List<SourceBook> =
            throw AssertionError("network search on a sufficient local hit")
        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            throw AssertionError("network page on a sufficient local hit")
        override suspend fun fetchNew(limit: Int): List<SourceBook> =
            throw AssertionError("network feed on a sufficient local hit")
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = emptyList()
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
    fun `sufficient local answer fires zero requests`() = runBlocking {
        write("t1", "Кобзар", "Тарас Шевченко", "https://t1.example/kobzar")
        write("t2", "Кобза", "Автор А", "https://t2.example/kobza")
        write("t3", "Кобзареві думи", "Автор Б", "https://t3.example/dumy")
        write("t1", "Лісова пісня", "Леся Українка", "https://t1.example/lisova")

        val cards = catalog(SilentAdapter("t1"), SilentAdapter("t2"))
            .searchAllSources("кобз")

        assertEquals(3, cards.size)
        assertTrue(cards.map { it.title }.containsAll(listOf("Кобзар", "Кобза", "Кобзареві думи")))
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
    fun `mirrored live hits answer the next query locally`() = runBlocking {
        val live = listOf(
            book("Кобзар", "Тарас Шевченко", "https://t1.example/kobzar"),
            book("Кобза", "Автор А", "https://t1.example/kobza"),
            book("Кобзареві думи", "Автор Б", "https://t1.example/dumy")
        )
        val first = catalog(LiveAdapter("t1", live)).searchAllSources("кобз")
        assertEquals(3, first.size)

        val second = catalog(SilentAdapter("t1")).searchAllSources("кобз")
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

        val unfiltered = catalog(SilentAdapter("t1")).searchAllSources("oxford")
        assertEquals(3, unfiltered.size)
        assertTrue(unfiltered.all { it.language == "en" })

        val ukOnly = catalog(SilentAdapter("t1"), selection = setOf("uk")).searchAllSources("oxford")
        assertTrue(ukOnly.isEmpty())
    }

    @Test
    fun `tombstoned work never resurfaces through the index`() = runBlocking {
        write("t1", "Кобзар", "Тарас Шевченко", "https://t1.example/kobzar")
        write("t2", "Кобза", "Автор А", "https://t2.example/kobza")
        write("t3", "Кобзареві думи", "Автор Б", "https://t3.example/dumy")
        write("t4", "Кобзарик", "Автор В", "https://t4.example/kobzaryk")
        val tombstonedId = MergeKey.keyFor("Кобза", "Автор А")
        dao.insertTombstone(TombstoneEntity(bookId = tombstonedId))

        val cards = catalog(SilentAdapter("t1"), SilentAdapter("t2")).searchAllSources("кобз")

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

        val cards = catalog(SilentAdapter("t1")).searchAllSources("кобз")

        assertEquals(3, cards.size)
        assertTrue(cards.flatMap { it.sources }.none { it.sourceId == "4read" })
    }

    @Test
    fun `browser row needs a live session`() = runBlocking {
        write("sluhay", "Кобзар", "Тарас Шевченко", "https://sluhay.com/kobzar")
        write("t1", "Кобза", "Автор А", "https://t1.example/kobza")
        write("t2", "Кобзареві думи", "Автор Б", "https://t2.example/dumy")

        // No session: the session-backed row is skipped; the thin answer
        // falls through to the (empty) live leg instead of dead data.
        val noSession = catalog(LiveAdapter("t1", emptyList())).searchAllSources("кобз")
        assertEquals(2, noSession.size)
        assertTrue(noSession.flatMap { it.sources }.none { it.sourceId == "sluhay" })

        // A live first-party session makes the same row usable — zero requests.
        val withSession = catalog(SilentAdapter("t1"), sessionAlive = { true }).searchAllSources("кобз")
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
        val cards = catalog(SilentAdapter("t1"), refused = setOf("t1")).searchAllSources("кобз")

        assertEquals(3, cards.size)
    }
}
