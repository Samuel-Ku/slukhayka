package com.slukhayka.audiobooks.data.metadata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.CorrectionKind
import com.slukhayka.audiobooks.data.db.CorrectionOrigin
import com.slukhayka.audiobooks.data.db.WorkEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0053 / #855 (T2) — the write half of the cover Override against the REAL
 * SQL: the listener's fix lands on the card through the ordinary cover write
 * path ([AudiobookDao.updateCoverImageUrl]) and is remembered in the existing
 * correction memory (no new table), so the rule the resolvers consult is
 * actually there afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CoverOverrideRoomTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    private val store = { CoverOverrideStore(dao) }

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

    private suspend fun seedCard(bookId: String = BOOK_ID, mergeKey: String = MERGE_KEY, coverUrl: String? = null) {
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = bookId,
                    title = "Кобзар",
                    author = "Тарас Шевченко",
                    narrator = "",
                    description = "",
                    coverDrawableRes = 0,
                    coverImageUrl = coverUrl,
                    genre = "",
                    sourceUrl = "",
                    totalDurationSeconds = 0L,
                    totalChapters = 0
                )
            )
        )
        dao.upsertWork(WorkEntity(id = bookId, mergeKey = mergeKey, title = "Кобзар", author = "Тарас Шевченко"))
    }

    @Test
    fun `pinning a cover writes the row through the cover write path and remembers it`() = runBlocking {
        seedCard()

        store().pin(BOOK_ID, MERGE_KEY, "https://mine.example/c.jpg", now = 100L)

        assertEquals("https://mine.example/c.jpg", dao.getAudiobookById(BOOK_ID)!!.coverImageUrl)
        val memory = dao.getCorrectionsForMergeKey(MERGE_KEY)
        assertEquals(1, memory.size)
        assertEquals(CorrectionKind.FIELD, memory[0].kind)
        assertEquals("cover=https://mine.example/c.jpg", memory[0].value)
        assertEquals(CorrectionOrigin.USER_MADE, memory[0].origin)
        assertEquals(
            CoverOverride.Pinned("https://mine.example/c.jpg"),
            store().pinned(MERGE_KEY)
        )
    }

    @Test
    fun `pinning an absence clears the row and is still a decision`() = runBlocking {
        seedCard(coverUrl = "https://claim.example/wrong.jpg")

        store().pin(BOOK_ID, MERGE_KEY, null, now = 100L)

        assertEquals("", dao.getAudiobookById(BOOK_ID)!!.coverImageUrl)
        assertEquals(CoverOverride.Pinned(null), store().pinned(MERGE_KEY))
        assertTrue(CoverOverride.blocksWrite(store().pinned(MERGE_KEY)))
    }

    @Test
    fun `the newest pin wins - both on the row and in the memory`() = runBlocking {
        seedCard()

        store().pin(BOOK_ID, MERGE_KEY, "https://first.example/c.jpg", now = 100L)
        store().pin(BOOK_ID, MERGE_KEY, "https://second.example/c.jpg", now = 200L)
        assertEquals("https://second.example/c.jpg", dao.getAudiobookById(BOOK_ID)!!.coverImageUrl)
        assertEquals(CoverOverride.Pinned("https://second.example/c.jpg"), store().pinned(MERGE_KEY))

        // Clearing afterwards is the listener's latest word.
        store().pin(BOOK_ID, MERGE_KEY, null, now = 300L)
        assertEquals("", dao.getAudiobookById(BOOK_ID)!!.coverImageUrl)
        assertEquals(CoverOverride.Pinned(null), store().pinned(MERGE_KEY))
    }

    @Test
    fun `re-pinning the same value replaces one memory row, never duplicates it`() = runBlocking {
        seedCard()

        store().pin(BOOK_ID, MERGE_KEY, "https://mine.example/c.jpg", now = 100L)
        store().pin(BOOK_ID, MERGE_KEY, "https://mine.example/c.jpg", now = 200L)

        assertEquals(1, dao.getCorrectionsForMergeKey(MERGE_KEY).size)
    }

    @Test
    fun `a blank merge key still fixes the row but remembers nothing`() = runBlocking {
        seedCard(mergeKey = "")

        store().pin(BOOK_ID, "", "https://mine.example/c.jpg", now = 100L)

        assertEquals("https://mine.example/c.jpg", dao.getAudiobookById(BOOK_ID)!!.coverImageUrl)
        assertEquals(0, dao.getCorrectionsForMergeKey("").size)
        assertNull(store().pinned(""))
    }

    private companion object {
        const val BOOK_ID = "b1"
        const val MERGE_KEY = "кобзар|тарас шевченко"
    }
}
