package com.slukhayka.audiobooks.data.catalog

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import com.slukhayka.audiobooks.data.source.streamOnlyFor
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
 * #1283 — preparing a chapter-less Edition stores the origin of its OWN
 * Source. When a listener opens such an Edition, the allowed Source adapter
 * fetches its page and the prepared Chapter / SourceTrack rows keep that
 * page's provenance. The regression goes through the callers' own interface
 * ([SourceCatalog.getPlayableChapters]) and checks the actually stored rows:
 * a foreign page must never be filed under the literal `4read`, a foreign
 * refusal must not hide the own-source tracks, the own source's refusal keeps
 * the existing contract, and a refused write leaves no partial links.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EditionSourceOriginTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var refusal: MutableStateFlow<Set<String>>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
        refusal = MutableStateFlow(emptySet())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private class FakeSourceAdapter(
        override val sourceId: String,
        private val detail: SourceBookDetail
    ) : SourceAdapter {
        var fetchCalls = 0
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail {
            fetchCalls++
            return detail
        }
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
    }

    private val normalRunner: suspend (suspend () -> Unit) -> Unit =
        { block -> db.withTransaction { block() } }

    private fun imports(vararg adapters: SourceAdapter) =
        LibraryImport(dao, context, adapters.toList(), writeBatchRunner = normalRunner)

    private fun catalog(
        adapters: List<SourceAdapter>,
        imports: LibraryImport,
        runner: suspend (suspend () -> Unit) -> Unit = normalRunner
    ) = SourceCatalog(
        dao = dao,
        sourceAdapters = adapters,
        libraryImport = imports,
        writeBatchRunner = runner,
        sourceAudioRefusal = refusal
    )

    private fun detail(url: String, vararg streamUrls: String) = SourceBookDetail(
        title = "Кобзар",
        author = "Тарас Шевченко",
        url = url,
        chapters = streamUrls.mapIndexed { index, stream ->
            SourceChapter("Розділ ${index + 1}", stream, (index + 1) * 100L)
        },
        totalDurationSeconds = streamUrls.size * 100L,
        language = "uk"
    )

    private suspend fun seedChapterlessBook(bookId: String, sourceUrl: String) {
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = bookId,
                    title = "Кобзар",
                    author = "Тарас Шевченко",
                    narrator = "",
                    description = "",
                    coverDrawableRes = 0,
                    sourceUrl = sourceUrl,
                    genre = ""
                )
            )
        )
    }

    @Test
    fun `a prepared Edition's Source keeps the page's own type`() = runBlocking {
        val adapter = FakeSourceAdapter(
            "soundbooks",
            detail(
                "https://sound-books.net/kobzar.html",
                "https://arch.sound-books.net/kobzar/1.mp3",
                "https://arch.sound-books.net/kobzar/2.mp3"
            )
        )
        val cat = catalog(listOf(adapter), imports(adapter))
        seedChapterlessBook("book-soundbooks", "https://sound-books.net/kobzar.html")

        val playable = cat.getPlayableChapters("book-soundbooks")

        val source = dao.getSourcesForBookSync("book-soundbooks").single()
        assertEquals("the Source type is the page's adapter, never the 4read literal", "soundbooks", source.type)
        assertEquals("soundbooks-${source.editionId}", source.id)
        assertEquals("https://sound-books.net/kobzar.html", source.url)
        assertEquals(streamOnlyFor("soundbooks"), source.streamOnly)
        assertEquals(2, playable.size)
        playable.forEachIndexed { index, pair ->
            assertEquals("soundbooks", pair.sourceId)
            assertEquals(source.id, pair.track?.sourceId)
            assertEquals("https://arch.sound-books.net/kobzar/${index + 1}.mp3", pair.track?.url)
        }
        // The prepared Edition is opened through the same stored-sources read.
        val stored = cat.storedEditionSources("book-soundbooks").single()
        assertEquals(playable.map { it.chapter.id }, stored.map { it.chapter.id })
        assertEquals(playable.map { it.track?.url }, stored.map { it.track?.url })
        // The logical Chapter rows are the Edition's, materialized once.
        assertEquals(2, dao.getChaptersListForBook("book-soundbooks").size)
    }

    @Test
    fun `a second Source's page gets its own identity too`() = runBlocking {
        val adapter = FakeSourceAdapter(
            "lihtar",
            detail(
                "https://lihtar.in.ua/book/kobzar",
                "https://lihtar.in.ua/audio/1.mp3"
            )
        )
        val cat = catalog(listOf(adapter), imports(adapter))
        seedChapterlessBook("book-lihtar", "https://lihtar.in.ua/book/kobzar")

        val playable = cat.getPlayableChapters("book-lihtar")

        val source = dao.getSourcesForBookSync("book-lihtar").single()
        assertEquals("lihtar", source.type)
        assertEquals("lihtar-${source.editionId}", source.id)
        assertEquals(streamOnlyFor("lihtar"), source.streamOnly)
        assertEquals("lihtar", playable.single().sourceId)
        assertEquals(source.id, playable.single().track?.sourceId)
    }

    @Test
    fun `a foreign refusal never hides the own-source tracks`() = runBlocking {
        val adapter = FakeSourceAdapter(
            "soundbooks",
            detail("https://sound-books.net/kobzar.html", "https://arch.sound-books.net/kobzar/1.mp3")
        )
        val cat = catalog(listOf(adapter), imports(adapter))
        seedChapterlessBook("book-foreign-refusal", "https://sound-books.net/kobzar.html")
        refusal.value = setOf("4read")

        val playable = cat.getPlayableChapters("book-foreign-refusal")

        assertEquals(1, playable.size)
        assertEquals("soundbooks", playable.single().sourceId)
        assertTrue("the own-source track stays paired", playable.single().track != null)
        assertEquals(1, adapter.fetchCalls)
    }

    @Test
    fun `the own source's refusal keeps the existing contract - no page fetch`() = runBlocking {
        val adapter = FakeSourceAdapter(
            "soundbooks",
            detail("https://sound-books.net/kobzar.html", "https://arch.sound-books.net/kobzar/1.mp3")
        )
        val cat = catalog(listOf(adapter), imports(adapter))
        seedChapterlessBook("book-own-refusal", "https://sound-books.net/kobzar.html")
        refusal.value = setOf("soundbooks")

        val playable = cat.getPlayableChapters("book-own-refusal")

        assertTrue("a refused source is never fetched for materialization", playable.isEmpty())
        assertEquals(0, adapter.fetchCalls)
        assertTrue(dao.getSourcesForBookSync("book-own-refusal").isEmpty())
        assertTrue(dao.getChaptersListForBook("book-own-refusal").isEmpty())
    }

    @Test
    fun `a failed write leaves no partial links of the materialization`() = runBlocking {
        val adapter = FakeSourceAdapter(
            "soundbooks",
            detail(
                "https://sound-books.net/kobzar.html",
                "https://arch.sound-books.net/kobzar/1.mp3",
                "https://arch.sound-books.net/kobzar/2.mp3"
            )
        )
        val failingRunner: suspend (suspend () -> Unit) -> Unit = { block ->
            db.withTransaction {
                block()
                throw IllegalStateException("injected materialization failure")
            }
        }
        val cat = catalog(listOf(adapter), imports(adapter), runner = failingRunner)
        seedChapterlessBook("book-failed", "https://sound-books.net/kobzar.html")

        val outcome = runCatching { cat.getPlayableChapters("book-failed") }

        assertTrue("the write refusal reaches the caller", outcome.isFailure)
        assertNull("no Edition", dao.getEditionForWork("book-failed"))
        assertTrue("no Chapters", dao.getChaptersListForBook("book-failed").isEmpty())
        assertTrue("no Source", dao.getSourcesForBookSync("book-failed").isEmpty())
        assertTrue("no SourceTrack", dao.getTracksForBookSync("book-failed").isEmpty())
    }
}
