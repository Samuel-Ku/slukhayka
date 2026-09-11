package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 4read full-catalog hydration door is closed: the source is scam (its
 * clean-client audio is a 52-second artefact), so the crawl is dormant and
 * writes nothing to the Works/Editions layer. The engine's seam remains for
 * future browser sources; the 4read corpus is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FourReadHydrationRepositoryTest {

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
    fun `the 4read hydration door is dormant and writes nothing`() = runBlocking {
        val catalog = SourceCatalog(
            dao,
            emptyList(),
            LibraryImport(dao, context, emptyList()),
            fourReadFetcher = FakeFetcher(emptyMap())
        )

        val result = catalog.hydrateFourReadCatalog()

        assertEquals("4read", result.sourceId)
        assertEquals(0, result.found)
        assertEquals(0, result.imported)
        assertEquals(0, result.merged)
        assertEquals(0, result.failed)
        assertEquals(0, dao.countWorks())
        assertEquals(0, dao.countWorkSources())
    }
}
