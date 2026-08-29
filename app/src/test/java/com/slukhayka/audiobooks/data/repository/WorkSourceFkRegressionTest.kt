package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for #388: a WorkSource must never crash the process or
 * fabricate a blank Work when its FK parent is absent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkSourceFkRegressionTest {

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

    @Test
    fun `safe upsert skips a source whose Work is missing without fabricating metadata`() = runBlocking {
        val inserted = dao.safeUpsertWorkSource(
            WorkSourceEntity(
                id = "missing-work|4read|abc",
                workId = "missing-work",
                sourceId = "4read",
                sourceUrl = "https://4read.org/missing.html",
                streamOnly = false
            )
        )

        assertFalse(inserted)
        assertNull(dao.getWorkById("missing-work"))
        assertEquals(emptyList<WorkSourceEntity>(), dao.getWorkSourcesForWorkSync("missing-work"))
    }

    @Test
    fun `transactional upsert stores the real Work before its Source`() = runBlocking {
        val work = WorkEntity(
            id = "сни|лесь курбас",
            title = "Сни",
            author = "Лесь Курбас",
            mergeKey = "сни|лесь курбас"
        )
        val source = WorkSourceEntity(
            id = "сни|лесь курбас|4read|abc",
            workId = work.id,
            sourceId = "4read",
            sourceUrl = "https://4read.org/sny.html",
            streamOnly = false
        )

        dao.upsertWorkWithSource(work, source)

        assertEquals(work, dao.getWorkById(work.id))
        assertEquals(listOf(source), dao.getWorkSourcesForWorkSync(work.id))
    }

    @Test
    fun `catalog write for Sny persists one honest Work and Source without crashing`() = runBlocking {
        val libraryImport = LibraryImport(dao, context, emptyList())
        val catalog = SourceCatalog(dao, emptyList(), libraryImport)
        val sourceUrl = "https://4read.org/sny-les-kurbas.html"

        val result = catalog.writeWorkEdition(
            sourceId = "4read",
            title = "Сни",
            author = "Лесь Курбас",
            narrator = "",
            sourceUrl = sourceUrl,
            streamOnly = false
        )

        assertEquals("Сни", dao.getWorkById(result.work.id)?.title)
        assertEquals(sourceUrl, dao.getWorkSourcesForWorkSync(result.work.id).single().sourceUrl)
    }
}
