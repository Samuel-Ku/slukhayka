package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import kotlinx.coroutines.flow.first
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
 * #1487 (slice 2) — the persistence door of a probed public page.
 *
 * The page of an unknown host is materialised through the ORDINARY import core
 * under the internal `unknown` marker: no fabricated narrator/author
 * placeholders may leak into the UI (they are not registered source ids, so
 * BookDisplay cannot blank them), the same canonical URL is the same card, and
 * an unidentified page imports nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryImportProbedPageTest {

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

    private fun imports() = LibraryImport(dao, context, emptyList())

    private fun detail(
        title: String = "Неначе сон",
        author: String = "",
        url: String = "https://unknown-site.example/book"
    ) = SourceBookDetail(
        title = title,
        author = author,
        url = url,
        chapters = listOf(
            SourceChapter("Частина 1", "https://cdn.unknown-site.example/1.mp3"),
            SourceChapter("Частина 2", "https://cdn.unknown-site.example/2.mp3")
        )
    )

    @Test
    fun `a probed page becomes a local unknown card without fake people`() = runBlocking {
        val book = imports().importProbedPage(detail())!!

        val stored = dao.getAudiobookById(book.id)!!
        assertEquals("Неначе сон", stored.title)
        // The internal `unknown` marker must never be displayed as a person.
        assertEquals("", stored.author)
        assertEquals("", stored.narrator)
        assertEquals("", stored.description)
        val edition = dao.getEditionForWork(book.id)!!
        assertEquals("", edition.narrator)
        val sources = dao.getSourcesForBookSync(book.id).filter { it.type == "unknown" }
        assertEquals(1, sources.size)
        assertEquals("https://unknown-site.example/book", sources.single().url)
        assertEquals(1, dao.getAllAudiobooks().first().size)
    }

    @Test
    fun `the same canonical url is the same card on a second probe`() = runBlocking {
        val first = imports().importProbedPage(detail())!!
        // A DIFFERENT title claim would normally fork a second MergeKey card —
        // the URL identity is what pins the same page to the same card.
        val second = imports().importProbedPage(detail(title = "Інша книга"))!!

        assertEquals(first.id, second.id)
        assertEquals(1, dao.getAllAudiobooks().first().size)
        assertEquals(
            1,
            dao.getSourcesForBookSync(first.id).count { it.type == "unknown" }
        )
        // The URL identity wins over a changed title claim: a re-probe must not
        // silently rewrite the card the listener already has.
        assertEquals("Неначе сон", dao.getAudiobookById(first.id)!!.title)
    }

    @Test
    fun `an unidentified page imports nothing`() = runBlocking {
        assertNull(imports().importProbedPage(detail(title = "")))
        assertNull(imports().importProbedPage(detail(title = "   ")))
        assertTrue(dao.getAllAudiobooks().first().isEmpty())
    }

    @Test
    fun `an empty or non-http playlist imports nothing`() = runBlocking {
        assertNull(imports().importProbedPage(detail().copy(chapters = emptyList())))
        assertNull(
            imports().importProbedPage(
                detail().copy(chapters = listOf(SourceChapter("x", "ftp://example/x.mp3")))
            )
        )
        assertTrue(dao.getAllAudiobooks().first().isEmpty())
    }
}
