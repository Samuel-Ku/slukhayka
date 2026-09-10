package com.slukhayka.audiobooks.data.ingest

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-53 T3 — the persistent submission carrier: a row saved by one store
 * survives into the next (process restart), awaiting rows are listed, and
 * the verdict settles exactly the addressed row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubmissionStateStoreTest {

    private lateinit var db: AudiobookDatabase
    private lateinit var store: RoomSubmissionStateStore

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomSubmissionStateStore(db.audiobookDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(sourceId: String, bookId: String) = SubmissionState(
        sourceId = sourceId,
        url = "https://www.youtube.com/watch?v=$sourceId",
        bookId = bookId,
        metadataJson = "{}",
        channelId = "",
        state = SubmissionState.State.AWAITING_PLAY,
        createdAt = 1L,
        updatedAt = 1L
    )

    @Test
    fun `a saved row is read back by a fresh store instance`() = runBlocking {
        store.save(row("source-1", "book-1"))

        val restarted = RoomSubmissionStateStore(db.audiobookDao())
        assertEquals("book-1", restarted.bySourceId("source-1")?.bookId)
        assertEquals(listOf("book-1"), restarted.awaiting().map { it.bookId })
    }

    @Test
    fun `the verdict settles only the addressed row`() = runBlocking {
        store.save(row("source-1", "book-1"))
        store.save(row("source-2", "book-2"))

        store.updateState("source-1", SubmissionState.State.PUBLISHED, null, 2L)

        assertEquals(SubmissionState.State.PUBLISHED, store.bySourceId("source-1")?.state)
        assertEquals(SubmissionState.State.AWAITING_PLAY, store.bySourceId("source-2")?.state)
        assertEquals(listOf("book-2"), store.awaiting().map { it.bookId })
    }

    @Test
    fun `an unknown source has no row`() = runBlocking {
        assertNull(store.bySourceId("missing"))
    }
}
