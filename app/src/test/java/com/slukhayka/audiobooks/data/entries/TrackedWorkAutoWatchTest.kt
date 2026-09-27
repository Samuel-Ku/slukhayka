package com.slukhayka.audiobooks.data.entries

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import com.slukhayka.audiobooks.data.merge.MergeKey
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
 * #856 (T3) — «трекований твір автоматично під наглядом».
 *
 * The listener creates a Work because it has no audio yet; asking them to ALSO
 * discover a separate «watch» gesture afterwards would be asking twice for the
 * same intent. This pins the arming on the creation path, through the real
 * SQL doors, with the watch capture standing in for the store.
 *
 * The refused path is pinned too: a tombstoned Work never reaches a watch, so
 * a deleted identity cannot quietly start notifying.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TrackedWorkAutoWatchTest {

    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    /** Every watch the creation path armed, in order. */
    private val armed = mutableListOf<Pair<String, String>>()

    private val mergeKey = MergeKey.keyFor(TITLE, AUTHOR)

    private fun module() = TrackedWorks(
        dao = dao,
        watchSource = { mergeKey, workId -> armed += mergeKey to workId }
    )

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

    @Test
    fun `a newly tracked work is under watch without a second gesture`() = runBlocking {
        val result = module().ensureTrackedWork(TITLE, AUTHOR)

        assertTrue("the Work must be created", result is TrackedWorks.Result.Added)
        assertEquals(
            "creating a tracked Work must arm the watch exactly once",
            listOf(mergeKey to mergeKey),
            armed
        )
    }

    @Test
    fun `an already-tracked work is still armed - the door is idempotent`() = runBlocking {
        val works = module()
        works.ensureTrackedWork(TITLE, AUTHOR)
        armed.clear()

        val again = works.ensureTrackedWork(TITLE, AUTHOR)

        assertTrue(again is TrackedWorks.Result.AlreadyTracked)
        assertEquals(
            "re-adding must still arm (the store keeps one entry and its seen state)",
            1,
            armed.size
        )
    }

    @Test
    fun `a tombstoned work is never armed`() = runBlocking {
        dao.insertTombstone(TombstoneEntity(bookId = mergeKey))

        val result = module().ensureTrackedWork(TITLE, AUTHOR)

        assertTrue(result is TrackedWorks.Result.Refused)
        assertTrue(
            "a deleted identity must not start notifying: $armed",
            armed.isEmpty()
        )
    }

    @Test
    fun `a refused identity is never armed`() = runBlocking {
        val result = module().ensureTrackedWork("", "")

        assertTrue(result is TrackedWorks.Result.Refused)
        assertTrue("no identity means no watch", armed.isEmpty())
    }

    private companion object {
        const val TITLE = "Кобзар"
        const val AUTHOR = "Тарас Шевченко"
    }
}
