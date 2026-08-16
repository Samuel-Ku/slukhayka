package com.slukhayka.audiobooks.data.metadata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room seam (spec-24 T1): the one-time startup scrub rewrites stored titles
 * (audiobooks + works) through the pure [MetadataAssertions.normalizeTitle]
 * rule and is idempotent — a second run matches nothing. In-memory Room,
 * same style as the DAO / migration Room tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StoredTitleScrubRoomTest {

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

    private fun book(id: String, title: String) = AudiobookEntity(
        id = id,
        title = title,
        author = "Автор",
        narrator = "",
        description = "",
        coverDrawableRes = 0,
        coverImageUrl = null,
        genre = "",
        sourceUrl = "",
        isDownloaded = false,
        totalDurationSeconds = 0L,
        totalChapters = 0,
        rating = 0f
    )

    @Test
    fun `startup scrub rewrites stored SEO titles on both tables and leaves clean rows`() = runBlocking {
        dao.insertAudiobooks(
            listOf(
                book("b1", "Тіні забутих предків - аудіокнига слухати онлайн"),
                book("b2", "Кобзар") // clean — untouched
            )
        )
        dao.upsertWork(WorkEntity(id = "w1", mergeKey = "w1", title = "Нейромант, слухати онлайн", author = "Автор"))
        dao.upsertWork(WorkEntity(id = "w2", mergeKey = "w2", title = "1984", author = "Автор"))

        val changed = StoredTitleScrub(dao).scrubOnce()

        assertEquals(2, changed)
        assertEquals("Тіні забутих предків", dao.getAllBookTitleRows().first { it.id == "b1" }.title)
        assertEquals("Кобзар", dao.getAllBookTitleRows().first { it.id == "b2" }.title)
        assertEquals("Нейромант", dao.getAllWorkTitleRows().first { it.id == "w1" }.title)
        assertEquals("1984", dao.getAllWorkTitleRows().first { it.id == "w2" }.title)
    }

    @Test
    fun `second run is a no-op - the pass is idempotent`() = runBlocking {
        dao.insertAudiobooks(listOf(book("b1", "Тіні забутих предків — аудіокнига слухати онлайн")))
        dao.upsertWork(WorkEntity(id = "w1", mergeKey = "w1", title = "Пасажир (аудіокнига онлайн)", author = "Автор"))

        val scrub = StoredTitleScrub(dao)
        val first = scrub.scrubOnce()
        val second = scrub.scrubOnce()

        assertEquals(2, first)
        assertEquals(0, second)
        assertEquals("Тіні забутих предків", dao.getAllBookTitleRows().single().title)
        assertEquals("Пасажир", dao.getAllWorkTitleRows().single().title)
    }
}
