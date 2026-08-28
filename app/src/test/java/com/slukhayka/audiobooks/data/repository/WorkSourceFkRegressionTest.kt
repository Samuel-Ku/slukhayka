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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #388 regression: upsertWorkSource with a workId not in the works table
 * used to throw SQLiteConstraintException (FOREIGN KEY constraint failed),
 * crashing the app when tapping a book like «Сни» by Лесь Курбас.
 *
 * After the fix, the upsert should either:
 * (a) succeed if the work row is inserted first, or
 * (b) fail gracefully without crashing the process.
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

    /**
     * The exact crash scenario from #388: insert a WorkSourceEntity whose
     * workId does NOT exist in the works table. Before the fix this throws
     * SQLiteConstraintException and crashes the process.
     */
    /**
     * #388: insert a WorkSourceEntity whose workId does NOT exist in works.
     * Before the fix this throws SQLiteConstraintException.
     * After the fix, upsertWorkSource should succeed (by ensuring the work
     * row exists first) or fail gracefully without throwing.
     */
    @Test
    fun `upsertWorkSource with missing work row does not throw`() = runBlocking {
        val workSource = WorkSourceEntity(
            id = "test-source-1",
            workId = "nonexistent-work-id",
            sourceId = "4read",
            sourceUrl = "https://4read.org/book/test",
            streamOnly = false
        )

        // Use the safe version that ensures the work row exists first
        dao.upsertWorkSourceSafe(workSource)

        // Verify the source was inserted (work row was auto-created)
        val sources = dao.getWorkSourcesForWorkSync("nonexistent-work-id")
        assertTrue("Source should be inserted after safe upsert", sources.size == 1)
    }

    /**
     * Happy path: insert work first, then source — should always succeed.
     */
    @Test
    fun `upsertWorkSource after upsertWork succeeds`() = runBlocking {
        val work = WorkEntity(
            id = "test-work-1",
            title = "Тестова книга",
            author = "Тестовий автор",
            mergeKey = "тестова книга|тестовий автор"
        )
        dao.upsertWork(work)

        val workSource = WorkSourceEntity(
            id = "test-source-1",
            workId = "test-work-1",
            sourceId = "4read",
            sourceUrl = "https://4read.org/book/test",
            streamOnly = false
        )
        dao.upsertWorkSource(workSource)

        val sources = dao.getWorkSourcesForWorkSync("test-work-1")
        assertTrue("Should have 1 source", sources.size == 1)
    }

    /**
     * Multiple sources for same work — all should succeed after work exists.
     */
    @Test
    fun `multiple upsertWorkSource for same work succeeds`() = runBlocking {
        val work = WorkEntity(
            id = "test-work-2",
            title = "Книга з джерелами",
            author = "Автор",
            mergeKey = "книга з джерелами|автор"
        )
        dao.upsertWork(work)

        for (i in 1..3) {
            dao.upsertWorkSource(
                WorkSourceEntity(
                    id = "source-$i",
                    workId = "test-work-2",
                    sourceId = "source-$i",
                    sourceUrl = "https://example.com/$i",
                    streamOnly = false
                )
            )
        }

        val sources = dao.getWorkSourcesForWorkSync("test-work-2")
        assertTrue("Should have 3 sources", sources.size == 3)
    }
}
