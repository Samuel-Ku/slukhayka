package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
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
 * Repository seam: per-Source assertions used by the canonical book-detail
 * presentation. Tests external behaviour: one claim per Source, graceful
 * degradation, and truthful description enrichment for existing entries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceProfileRepositoryTest {

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

    private open class FakeAdapter(
        override val sourceId: String,
        private val detail: SourceBookDetail
    ) : SourceAdapter {
        override suspend fun search(query: String): List<com.slukhayka.audiobooks.data.source.SourceBook> = emptyList()
        override suspend fun fetchNew(limit: Int): List<com.slukhayka.audiobooks.data.source.SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail = detail
    }

    // ADR-0002 (#140): the per-source profile aggregation lives in the Library
    // Entries module — construct it directly, no god module.
    private fun repo(vararg adapters: SourceAdapter) =
        LibraryEntries(dao, adapters.toList())

    private suspend fun seedBook(
        vararg sources: SourceEntity,
        description: String = ""
    ): String {
        val book = AudiobookEntity(
            id = "sluhay-pasazhir",
            title = "Пасажир",
            author = "Жан-Крістоф Гранже",
            narrator = "sluhay narrator",
            description = description,
            coverDrawableRes = 0,
            genre = "",
            sourceUrl = sources.firstOrNull()?.url ?: "",
            isDownloaded = false
        )
        val workId = MergeKey.keyFor(book.title, book.author)
        dao.insertAudiobooks(listOf(book))
        dao.upsertWork(
            WorkEntity(id = workId, mergeKey = workId, title = book.title, author = book.author, addedAt = 0L)
        )
        dao.upsertLibraryEntry(
            id = book.id, workId = workId, isFavorite = false, createdAt = 0L, downloadProgress = 0f
        )
        dao.insertSources(sources.toList())
        return book.id
    }

    private fun source(sourceId: String, url: String) = SourceEntity(
        id = "$sourceId-pasazhir",
        bookId = "sluhay-pasazhir",
        type = sourceId,
        url = url
    )

    @Test
    fun `one profile per source carrying the Work`() = runBlocking {
        val sluhayDetail = SourceBookDetail(
            title = "Пасажир",
            author = "Жан-Крістоф Гранже",
            url = "https://sluhay.com/pasazhir.html",
            chapters = listOf(SourceChapter("1", "https://cdn.example/1.mp3")),
            description = "Париж, нічний потяг і зниклий пасажир.",
            genres = listOf("Детектив", "Трилер")
        )
        val fourReadDetail = SourceBookDetail(
            title = "Пасажир",
            author = "Жан-Крістоф Гранже",
            narrator = "Валерій Завалко",
            url = "https://4read.org/pasazhir.html",
            chapters = listOf(SourceChapter("Глава 1", "https://4read.org/1.mp3")),
            description = "Перший роман циклу про комісара Греньє.",
            rating = 4.8,
            genres = listOf("Зарубіжна література", "Детектив")
        )
        val repository = repo(
            FakeAdapter("sluhay", sluhayDetail),
            FakeAdapter("4read", fourReadDetail)
        )
        val bookId = seedBook(
            source("sluhay", "https://sluhay.com/pasazhir.html"),
            source("4read", "https://4read.org/pasazhir.html")
        )

        val profiles = repository.fetchSourceProfiles(bookId)

        assertEquals(2, profiles.size)
        val sluhay = profiles.first { it.sourceId == "sluhay" }
        assertEquals("Sluhay", sluhay.sourceName)
        assertEquals("Париж, нічний потяг і зниклий пасажир.", sluhay.description)
        assertNull(sluhay.rating)
        assertEquals(listOf("Детектив", "Трилер"), sluhay.genres)
        val fourRead = profiles.first { it.sourceId == "4read" }
        assertEquals("4read", fourRead.sourceName)
        assertEquals("Валерій Завалко", fourRead.narrator)
        assertEquals(4.8, fourRead.rating)
    }

    @Test
    fun `a failing source degrades to the remaining blocks`() = runBlocking {
        val goodDetail = SourceBookDetail(
            title = "Пасажир",
            author = "Жан-Крістоф Гранже",
            url = "https://sluhay.com/pasazhir.html",
            chapters = listOf(SourceChapter("1", "https://cdn.example/1.mp3")),
            description = "Париж, нічний потяг і зниклий пасажир."
        )
        val broken = object : FakeAdapter(
            "4read",
            SourceBookDetail("", "", url = "https://4read.org/pasazhir.html", chapters = emptyList())
        ) {
            override suspend fun fetchBookPage(url: String): SourceBookDetail = throw RuntimeException("boom")
        }
        val repository = repo(FakeAdapter("sluhay", goodDetail), broken)
        val bookId = seedBook(
            source("sluhay", "https://sluhay.com/pasazhir.html"),
            source("4read", "https://4read.org/pasazhir.html")
        )

        val profiles = repository.fetchSourceProfiles(bookId)

        // The failing 4read fetch contributes no block — never a blank page.
        assertEquals(1, profiles.size)
        assertEquals("sluhay", profiles.single().sourceId)
    }

    @Test
    fun `a page that yields nothing is a failure not an empty block`() = runBlocking {
        val emptyDetail = SourceBookDetail("", "", url = "https://sluhay.com/pasazhir.html", chapters = emptyList())
        val repository = repo(FakeAdapter("sluhay", emptyDetail))
        val bookId = seedBook(source("sluhay", "https://sluhay.com/pasazhir.html"))

        val profiles = repository.fetchSourceProfiles(bookId)

        // Blank title AND no chapters = the page was unreachable/unparseable.
        assertTrue(profiles.isEmpty())
    }

    @Test
    fun `a book with no sources yields no blocks`() = runBlocking {
        val repository = repo(FakeAdapter("sluhay", SourceBookDetail(
            title = "x", author = "y", url = "u", chapters = emptyList()
        )))
        val bookId = seedBook()

        val profiles = repository.fetchSourceProfiles(bookId)

        assertTrue(profiles.isEmpty())
    }

    @Test
    fun `real source blurb replaces a stored catalog fallback`() = runBlocking {
        val repository = repo()
        val bookId = seedBook(description = "Аудіокнига з каталогу 4read.org")

        val changed = repository.fillMissingDescriptionFromProfiles(
            bookId,
            listOf("Над Адуа зависочіли промислові труби, тож світ закипає, бо зароджується нова ера.")
        )

        assertTrue(changed)
        assertEquals(
            "Над Адуа зависочіли промислові труби, тож світ закипає, бо зароджується нова ера.",
            dao.getAudiobookById(bookId)!!.description
        )
    }
}
