package com.slukhayka.audiobooks.data.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #734 / ADR-0041 — the «Серії» corpus is the Медіатека: only cycles with an
 * owned book are aggregated, one cycle's page resolves the listener's own
 * books, and its Дзеркало neighbours are the known-but-not-owned Works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibrarySeriesIndexRoomTest {

    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun own(
        workId: String,
        bookId: String,
        title: String,
        seriesTitle: String,
        seriesUrl: String,
        position: Int
    ) {
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = bookId,
                    title = title,
                    author = "Анджей Сапковський",
                    narrator = "",
                    description = "",
                    coverDrawableRes = 0,
                    genre = "",
                    sourceUrl = ""
                )
            )
        )
        dao.upsertWork(
            WorkEntity(
                id = workId,
                mergeKey = workId,
                title = title,
                author = "Анджей Сапковський",
                seriesTitle = seriesTitle,
                seriesUrl = seriesUrl,
                seriesIndex = position,
                addedAt = 0L
            )
        )
        dao.upsertLibraryEntry(
            id = bookId,
            workId = workId,
            isFavorite = false,
            createdAt = 0L,
            downloadProgress = 0f
        )
    }

    /** A mirrored Work in a cycle but with NO Library Entry — never owned. */
    private suspend fun mirror(workId: String, title: String, seriesTitle: String, seriesUrl: String, position: Int) {
        dao.upsertWork(
            WorkEntity(
                id = workId,
                mergeKey = workId,
                title = title,
                author = "Анджей Сапковський",
                seriesTitle = seriesTitle,
                seriesUrl = seriesUrl,
                seriesIndex = position,
                addedAt = 0L
            )
        )
    }

    @Test
    fun `index lists only cycles with an owned book and the page resolves locally`() = runBlocking {
        own("w1", "b1", "Останнє бажання", "Відьмак", "https://4read.org/cikl/vidmak/", 1)
        own("w2", "b2", "Меч призначення", "Відьмак", "https://4read.org/cikl/vidmak/", 2)
        own("w3", "b3", "Гіперіон", "Гіперіон", "https://4read.org/cikl/giperion/", 1)
        // A mirrored cycle and a mirrored member of an owned cycle.
        mirror("w9", "Замок", "Замок", "https://4read.org/cikl/zamok/", 1)
        mirror("w4", "Кров ельфів", "Відьмак", "https://4read.org/cikl/vidmak/", 3)

        val rows = dao.ownedSeriesIndexRows()
        assertEquals(listOf("Відьмак", "Гіперіон"), rows.map { it.title })
        assertTrue("a mirrored-only cycle never appears", rows.none { it.title == "Замок" })

        assertEquals(
            listOf("Останнє бажання", "Меч призначення"),
            dao.libraryBooksForSeries("Відьмак").map { it.title }
        )
        assertEquals(
            listOf("Кров ельфів"),
            dao.mirrorNeighboursForSeries("Відьмак").map { it.title }
        )
    }

    @Test
    fun `a resolved series membership also contributes to the index`() = runBlocking {
        // The Work's own series fields are blank; the membership comes from
        // the resolved series table (curated universe).
        own("w1", "b1", "Дюна", seriesTitle = "", seriesUrl = "", position = 0)
        dao.upsertSeries(
            com.slukhayka.audiobooks.data.db.SeriesEntity(
                id = "s-dune",
                title = "Хроніки Дюни",
                url = "https://4read.org/cikl/dune/"
            )
        )
        dao.upsertSeriesMember(
            com.slukhayka.audiobooks.data.db.SeriesMemberEntity(
                workId = "w1",
                seriesId = "s-dune",
                position = 1
            )
        )

        assertEquals(listOf("Хроніки Дюни"), dao.ownedSeriesIndexRows().map { it.title })
        assertEquals(listOf("Дюна"), dao.libraryBooksForSeries("Хроніки Дюни").map { it.title })
    }
}
