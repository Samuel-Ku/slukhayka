package com.slukhayka.audiobooks.data.entries

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.ReadthroughMapping
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
 * #1286 — the progress module against the REAL Room doors: a record and a
 * finish land in the `readthroughs` table and publish the active list and the
 * year that belong to that same save. The fake adapter covers the failure
 * ordering; this proves the SQL seam the production adapter uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReadingProgressRoomTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    // 2026-02-02 — inside the injected calendar year.
    private val at = 1_770_000_000_000L

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

    private suspend fun seedPaper(): Readthrough {
        val pass = ReadthroughPolicy.start(
            id = "paper-1",
            libraryEntryId = "entry-1",
            workId = "work-1",
            format = ReadingFormat.PAPER,
            startedAt = 1_000L,
            value = 10
        )!!
        with(ReadthroughMapping) { dao.upsertReadthrough(pass.toEntity()) }
        return pass
    }

    @Test
    fun `record writes the journal and publishes the active pass`() = runBlocking {
        val progress = ReadingProgressRecorder(dao, now = { at }, currentYear = { 2026 })
        seedPaper()

        progress.refresh()
        progress.record("paper-1", value = 40)

        val stored = with(ReadthroughMapping) {
            dao.readthroughById("paper-1")!!.toModelOrNull()!!
        }
        assertEquals(40, stored.units.value)
        assertEquals(ReadingUnit.PAGES, stored.units.unit)
        assertEquals(1, stored.journal.size)

        val state = progress.state.value as ReadingProgressRecorder.State.Ready
        assertEquals(40, state.projection.active.single().units.value)
        assertEquals(1, state.projection.active.single().journal.size)
    }

    @Test
    fun `finish moves the pass into the year and leaves the audio pass alone`() = runBlocking {
        val progress = ReadingProgressRecorder(dao, now = { at }, currentYear = { 2026 })
        seedPaper()
        val audio = ReadthroughPolicy.start(
            id = "audio-1",
            libraryEntryId = "entry-1",
            workId = "work-1",
            format = ReadingFormat.AUDIO,
            startedAt = 1_000L,
            editionId = "edition-1",
            value = 300
        )!!
        with(ReadthroughMapping) { dao.upsertReadthrough(audio.toEntity()) }

        val finished = progress.finish("paper-1") as ReadingProgressRecorder.Result.Finished
        assertEquals(ReadingState.FINISHED, finished.readthrough.state)

        val state = progress.state.value as ReadingProgressRecorder.State.Ready
        assertTrue("paper leaves the active list", state.projection.active.none { it.id == "paper-1" })
        assertTrue("audio stays active", state.projection.active.any { it.id == "audio-1" })
        assertEquals(1, state.projection.year.finishedByFormat[ReadingFormat.PAPER])
    }
}
