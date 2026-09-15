package com.slukhayka.audiobooks.data.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
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
 * #823 — the search index write-through: every mergeable Work written
 * through the merge-on-write door lands a folded FTS4 row in the same
 * transaction, and the MATCH read finds it by title/author/series/narrator
 * prefix with zero network requests. Cards without a Work identity never
 * materialize; a repeated enumeration is idempotent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkSearchIndexRoomTest {

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

    private fun catalog() =
        SourceCatalog(dao, emptyList(), LibraryImport(dao, context, emptyList()))

    private fun matchIds(query: String, limit: Int = 50): List<String> =
        runBlocking {
            val match = SearchIndexNormalize.matchQuery(query) ?: return@runBlocking emptyList()
            dao.matchWorkSearch(match, limit)
        }

    @Test
    fun `catalog write indexes title author series and narrator`() = runBlocking {
        catalog().writeWorkEdition(
            sourceId = "s1",
            title = "Кобзар (вибране)",
            author = "Тарас Шевченко",
            narrator = "Іван Начитувач",
            sourceUrl = "https://s1.example/kobzar",
            seriesTitle = "Класика",
            language = "uk"
        )

        assertTrue(matchIds("кобз").isNotEmpty())
        assertTrue(matchIds("шевч").isNotEmpty())
        assertTrue(matchIds("класи").isNotEmpty())
        assertTrue(matchIds("начит").isNotEmpty())
        assertTrue(matchIds("неіснуюче").isEmpty())
    }

    @Test
    fun `work without identity never materializes in the index`() = runBlocking {
        catalog().writeWorkEdition(
            sourceId = "s1",
            title = "Без автора",
            author = "",
            narrator = "",
            sourceUrl = "https://s1.example/no-author"
        )

        assertTrue(matchIds("без").isEmpty())
        assertEquals(0, dao.workSearchRowCount())
    }

    @Test
    fun `repeated enumeration rewrites the same row`() = runBlocking {
        val catalog = catalog()
        repeat(2) {
            catalog.writeWorkEdition(
                sourceId = "s1",
                title = "Кобзар",
                author = "Тарас Шевченко",
                narrator = "",
                sourceUrl = "https://s1.example/kobzar"
            )
        }
        catalog.writeWorkEdition(
            sourceId = "s2",
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "",
            sourceUrl = "https://s2.example/kobzar"
        )

        assertEquals(1, dao.workSearchRowCount())
        assertEquals(1, matchIds("кобзар").size)
    }

    @Test
    fun `backfill door refills missing rows and replays cleanly`() = runBlocking {
        catalog().writeWorkEdition(
            sourceId = "s1",
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "",
            sourceUrl = "https://s1.example/kobzar"
        )
        val workId = matchIds("кобзар").single()
        dao.deleteWorkSearchRows(workId)
        assertEquals(0, dao.workSearchRowCount())

        assertEquals(1, dao.ensureWorkSearchBackfilled())
        assertEquals(workId, matchIds("кобзар").single())
        assertEquals(0, dao.ensureWorkSearchBackfilled())
    }
}
