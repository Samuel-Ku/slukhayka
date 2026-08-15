package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookDatabase
import com.example.data.db.PlaybackEventKind
import com.example.data.db.PlaybackEventPolicy
import com.example.data.db.PlaybackProgressEntity
import com.example.data.entries.LibraryEntries
import com.example.data.listening.ListeningStateStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository seam (spec-16 T1): the playback-event log behind the
 * position-persistence path. Driven by a real in-memory Room database — the
 * same seam style as the other repository tests. Pins external behaviour:
 * events append with stable kinds, the latest qualifying jump is the undo
 * candidate, compaction keeps every (book, source) bucket bounded without
 * touching the state row, and book deletion clears the trail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackEventsRepositoryTest {

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

    // ADR-0002 (#140): playback events live in the Listening State module —
    // construct it directly, no god module.
    private fun repository() = ListeningStateStore(dao)

    private fun seekJump(seconds: Long): Long = PlaybackEventPolicy.SEEK_JUMP_THRESHOLD_MS / 1000L + seconds

    @Test
    fun `recordPlaybackEvent appends a stable-kind event with empty deviceId`() = runBlocking {
        val repo = repository()
        val now = 1_000L

        repo.recordPlaybackEvent(
            bookId = "b1", kind = PlaybackEventKind.RESUME, chapterIndex = 0, positionSeconds = 0L, timestampMs = now
        )
        repo.recordPlaybackEvent(
            bookId = "b1", kind = PlaybackEventKind.SEEK, chapterIndex = 2, positionSeconds = 900L,
            fromPositionSeconds = 100L, sourceKey = "soundbooks", timestampMs = now + 1
        )

        val events = dao.getPlaybackEventsForBookSource("b1", "").plus(dao.getPlaybackEventsForBookSource("b1", "soundbooks"))
        assertEquals(2, events.size)
        val resume = events.first { it.kind == PlaybackEventKind.RESUME }
        val seek = events.first { it.kind == PlaybackEventKind.SEEK }
        assertEquals("", resume.deviceId)
        assertEquals("", resume.sourceKey)
        assertEquals(0, resume.chapterIndex)
        assertEquals(900L, seek.positionSeconds)
        assertEquals(100L, seek.fromPositionSeconds)
        assertEquals("soundbooks", seek.sourceKey)
    }

    @Test
    fun `lastUndoCandidate is the latest qualifying jump`() = runBlocking {
        val repo = repository()
        // A qualifying jump first, then a smaller one — the latest event is
        // still the big jump's candidate only if it meets the threshold.
        repo.recordPlaybackEvent(
            bookId = "b1", kind = PlaybackEventKind.SEEK, chapterIndex = 0,
            positionSeconds = seekJump(10), fromPositionSeconds = 0L, timestampMs = 100L
        )
        repo.recordPlaybackEvent(
            bookId = "b1", kind = PlaybackEventKind.SEEK, chapterIndex = 0,
            positionSeconds = 30L, fromPositionSeconds = 0L, timestampMs = 200L
        )

        // The latest SEEK (30s) is sub-threshold — nothing undoable.
        assertNull(repo.lastUndoCandidate("b1"))
    }

    @Test
    fun `lastUndoCandidate returns the latest big jump`() = runBlocking {
        val repo = repository()
        repo.recordPlaybackEvent(
            bookId = "b1", kind = PlaybackEventKind.SEEK, chapterIndex = 0,
            positionSeconds = seekJump(5), fromPositionSeconds = 0L, timestampMs = 100L
        )
        repo.recordPlaybackEvent(
            bookId = "b1", kind = PlaybackEventKind.SEEK, chapterIndex = 0,
            positionSeconds = seekJump(20), fromPositionSeconds = 0L, timestampMs = 200L
        )

        val candidate = repo.lastUndoCandidate("b1")
        assertNotNull(candidate)
        assertEquals(seekJump(20), candidate!!.positionSeconds)
    }

    @Test
    fun `lastUndoCandidate is null when only non-seek kinds exist`() = runBlocking {
        val repo = repository()
        repo.recordPlaybackEvent(
            bookId = "b1", kind = PlaybackEventKind.PAUSE, chapterIndex = 1, positionSeconds = 400L, timestampMs = 100L
        )

        assertNull(repo.lastUndoCandidate("b1"))
    }

    @Test
    fun `compaction keeps the newest fifty events per book and source`() = runBlocking {
        val repo = repository()
        repeat(55) { i ->
            repo.recordPlaybackEvent(
                bookId = "b1", kind = PlaybackEventKind.RESUME, chapterIndex = 0,
                positionSeconds = i.toLong(), timestampMs = 1_000L + i
            )
        }

        val kept = dao.getPlaybackEventsForBookSource("b1", "")
        assertEquals(50, kept.size)
        // The five oldest (timestamps 1000..1004) were pruned.
        assertTrue(kept.none { it.positionSeconds < 5L })
        // Another (book, source) bucket is untouched.
        assertTrue(dao.getPlaybackEventsForBookSource("b2", "").isEmpty())
    }

    @Test
    fun `compaction prunes stale seek candidates but keeps fresh history`() = runBlocking {
        val repo = repository()
        val now = 100_000L
        val dayMs = 24 * 60 * 60 * 1000L
        // A big jump recorded 25 h ago — stale by the time the fresh event lands.
        repo.recordPlaybackEvent(
            bookId = "b1", kind = PlaybackEventKind.SEEK, chapterIndex = 0,
            positionSeconds = seekJump(10), fromPositionSeconds = 0L, timestampMs = now - 25 * 60 * 60 * 1000L
        )
        repo.recordPlaybackEvent(
            bookId = "b1", kind = PlaybackEventKind.PAUSE, chapterIndex = 1,
            positionSeconds = 500L, timestampMs = now
        )

        val kept = dao.getPlaybackEventsForBookSource("b1", "")
        assertEquals("stale SEEK pruned, fresh PAUSE kept", listOf(PlaybackEventKind.PAUSE), kept.map { it.kind })
        assertTrue("nothing newer than the 24 h window may be dropped", kept.all { now - it.timestamp <= dayMs })
    }

    @Test
    fun `compaction never touches the state row`() = runBlocking {
        val repo = repository()
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                bookId = "b1", sourceKey = "", currentChapterIndex = 2,
                currentPositionSeconds = 600L, lastListenedAt = 500L
            )
        )
        repeat(60) { i ->
            repo.recordPlaybackEvent(
                bookId = "b1", kind = PlaybackEventKind.RESUME, chapterIndex = 0,
                positionSeconds = i.toLong(), timestampMs = 1_000L + i
            )
        }

        val progress = dao.getPlaybackProgressSync("b1")
        assertNotNull(progress)
        assertEquals(2, progress!!.currentChapterIndex)
        assertEquals(600L, progress.currentPositionSeconds)
    }

    @Test
    fun `book deletion clears the event trail with the rest of the cascade`() = runBlocking {
        val repo = repository()
        repo.recordPlaybackEvent(
            bookId = "b1", kind = PlaybackEventKind.RESUME, chapterIndex = 0, positionSeconds = 0L, timestampMs = 100L
        )
        repo.recordPlaybackEvent(
            bookId = "b1", kind = PlaybackEventKind.SEEK, chapterIndex = 0,
            positionSeconds = seekJump(10), fromPositionSeconds = 0L, timestampMs = 200L
        )

        // The cascade itself is the Library Entries module's job; the store
        // only owns the event rows the cascade removes.
        LibraryEntries(dao, emptyList()).deleteBook("b1")

        assertTrue(dao.getPlaybackEventsForBookSource("b1", "").isEmpty())
        assertTrue(dao.getPlaybackEventsForBookSource("b1", "soundbooks").isEmpty())
    }
}
