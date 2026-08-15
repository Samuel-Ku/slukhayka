package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.catalog.CatalogBook
import com.example.data.catalog.SourceCatalog
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookDatabase
import com.example.data.imports.LibraryImport
import com.example.data.merge.MergeKey
import com.example.data.source.SourceAdapter
import com.example.data.source.SourceBook
import com.example.data.source.SourceBookDetail
import com.example.data.source.SourceChapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-24 T1 (#162) — the SEO title scrub applies on EVERY write path that
 * persists a claimed title: source-page imports, captured-page imports,
 * catalog listing upserts, WebView-session hydration (the Work row). One rule
 * in the metadata-assertions module ([MetadataAssertions.normalizeTitle]), so
 * no door stores a dirty title. In-memory Room, same style as the other
 * repository tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TitleScrubWritePathTest {

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

    private fun imports(adapters: List<SourceAdapter> = emptyList()) = LibraryImport(dao, context, adapters)

    private fun catalog(adapters: List<SourceAdapter> = emptyList()) =
        SourceCatalog(dao, adapters, imports(adapters))

    private fun detail(title: String, url: String = "https://sound-books.net/x.html") = SourceBookDetail(
        title = title,
        author = "Тарас Шевченко",
        url = url,
        chapters = listOf(SourceChapter("Розділ 1", "https://arch.sound-books.net/x/01.mp3"))
    )

    // --- Door 1: source-page import ----------------------------------------

    @Test
    fun `source-page import stores the scrubbed title on the book and the Work row`() = runBlocking {
        val book = imports().importBookFromSource(
            "soundbooks",
            detail("Кобзар - аудіокнига слухати онлайн")
        )

        // The returned (JOINed) row and a fresh DAO read agree.
        assertEquals("Кобзар", book.title)
        assertEquals("Кобзар", dao.getAudiobookById(book.id)!!.title)
        // The Work row (ADR-0009: the entry links the book to it) is clean too.
        assertEquals("Кобзар", dao.observeWorks().first().single().title)
    }

    // --- Door 2: captured-page import --------------------------------------

    private class FakeAdapter(private val captured: SourceBookDetail) : SourceAdapter {
        override val sourceId: String = "4read"
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail = captured
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun parseCapturedPage(html: String, url: String): SourceBookDetail? = captured
    }

    @Test
    fun `captured-page import stores the scrubbed title`() = runBlocking {
        val dirty = detail("Нейромант (аудіокнига онлайн)", url = "https://4read.org/1.html")
        val book = imports(listOf(FakeAdapter(dirty)))
            .importAudiobookFromHtml("https://4read.org/1.html", "<html/>")!!

        assertEquals("Нейромант", book.title)
        assertEquals("Нейромант", dao.getAudiobookById(book.id)!!.title)
    }

    // --- Door 3: catalog listing upsert ------------------------------------

    @Test
    fun `catalog listing upsert stores the scrubbed title`() = runBlocking {
        catalog().upsertCatalogBook(
            CatalogBook(
                id = "4read-1",
                title = "1984, слухати онлайн безкоштовно",
                author = "Джордж Орвелл",
                url = "https://4read.org/1.html",
                coverImageUrl = null
            )
        )

        assertEquals("1984", dao.getAudiobookById("4read-1")!!.title)
        // The upsert also lands the Work row via ensureWorkAndEntry — clean.
        assertEquals("1984", dao.observeWorks().first().single().title)
    }

    // --- Work row (WebView-session hydration) ------------------------------

    @Test
    fun `Work row write stores the scrubbed title and keeps the raw-claim identity`() = runBlocking {
        val dirtyTitle = "Пасажир | аудіокнига українською"
        catalog().writeWorkEdition(
            sourceId = "sluhay",
            title = dirtyTitle,
            author = "Жан-Крістоф Гранже",
            narrator = "",
            sourceUrl = "https://sluhay.com/pasazhir.html"
        )

        val work = dao.observeWorks().first().single()
        // The displayed title is scrubbed…
        assertEquals("Пасажир", work.title)
        // …while the merge key keeps the RAW claim, so stored identities
        // (works ids, library_entries.workId, work_sources) never churn under
        // the scrub — a deliberate boundary, not an oversight.
        assertEquals(MergeKey.keyFor(dirtyTitle, "Жан-Крістоф Гранже"), work.id)
        assertEquals(work.id, work.mergeKey)
    }
}
