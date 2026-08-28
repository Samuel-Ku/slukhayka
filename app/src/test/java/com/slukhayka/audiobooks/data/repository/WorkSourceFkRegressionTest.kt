package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression for #388: a missing Work must not turn a Source write into a process crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkSourceFkRegressionTest {
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `valid Work and Source are written together and survive a Work update`() = runBlocking {
        val work = WorkEntity(
            id = "missing-work",
            mergeKey = "сни|лесь курбас",
            title = "Сни",
            author = "Лесь Курбас"
        )
        val source = WorkSourceEntity(
            id = "test-source",
            workId = "missing-work",
            sourceId = "4read",
            sourceUrl = "https://4read.org/book/test",
            streamOnly = false
        )

        assertTrue(dao.upsertWorkWithSource(work, source))
        dao.upsertWork(work.copy(title = "Сни (оновлено)"))

        assertEquals("Сни (оновлено)", dao.getWorkById("missing-work")?.title)
        assertEquals(listOf(source), dao.getWorkSourcesForWorkSync("missing-work"))
    }

    @Test
    fun `invalid Work Source pair returns a controlled failure without partial rows`() = runBlocking {
        val invalidWork = WorkEntity(id = "missing-work", mergeKey = "", title = "", author = "")
        val source = WorkSourceEntity(
            id = "test-source",
            workId = "different-work",
            sourceId = "4read",
            sourceUrl = "https://4read.org/book/test",
            streamOnly = false
        )

        assertFalse(dao.upsertWorkWithSource(invalidWork, source))
        assertNull(dao.getWorkById("missing-work"))
        assertEquals(emptyList<WorkSourceEntity>(), dao.getWorkSourcesForWorkSync("different-work"))
    }
}
