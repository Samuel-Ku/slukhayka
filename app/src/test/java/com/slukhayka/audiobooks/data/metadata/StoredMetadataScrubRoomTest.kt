package com.slukhayka.audiobooks.data.metadata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.NativeRoomWorkerIdentity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room seam (spec-24 T1 + #264): the one-time startup scrub rewrites stored
 * titles (audiobooks + works) and stored descriptions (audiobooks) through
 * the pure [MetadataAssertions] rules and is idempotent — a second run
 * matches nothing. In-memory Room, same style as the DAO / migration Room
 * tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StoredMetadataScrubRoomTest {
    companion object {
        @JvmStatic
        @AfterClass
        fun recordNativeWorkerIdentity() = NativeRoomWorkerIdentity.record()
    }

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

    private fun book(id: String, title: String, description: String = "") = AudiobookEntity(
        id = id,
        title = title,
        author = "Автор",
        narrator = "",
        description = description,
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

        val changed = StoredMetadataScrub(dao).scrubOnce()

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

        val scrub = StoredMetadataScrub(dao)
        val first = scrub.scrubOnce()
        val second = scrub.scrubOnce()

        assertEquals(2, first)
        assertEquals(0, second)
        assertEquals("Тіні забутих предків", dao.getAllBookTitleRows().single().title)
        assertEquals("Пасажир", dao.getAllWorkTitleRows().single().title)
    }

    @Test
    fun `description scrub is idempotent at the Room level too`() = runBlocking {
        dao.insertAudiobooks(
            listOf(
                book("b1", "Кобзар", "Аудіокнігу онлайн Кобзар, читає Хтось. Справжній текст."),
                book("b2", "1984", "Слушать аудиокниги онлайн — 1984, бесплатно и без регистрации.")
            )
        )

        val scrub = StoredMetadataScrub(dao)
        assertEquals(2, scrub.scrubOnce())
        val descriptions = dao.getAllBookDescriptionRows().associate { it.id to it.description }
        assertEquals("Справжній текст.", descriptions["b1"])
        assertEquals("", descriptions["b2"])
        // The second pass matches nothing — the rules are stable on stored rows.
        assertEquals(0, scrub.scrubOnce())
    }

    // --- #264: stored descriptions -----------------------------------------

    @Test
    fun `startup scrub rewrites a stored SEO template description to empty`() = runBlocking {
        dao.insertAudiobooks(
            listOf(
                book("b1", "Кобзар", "Слушать аудиокниги онлайн — Кобзар, бесплатно и без регистрации."),
                book("b2", "1984", "Честный текст аннотации.") // honest — untouched
            )
        )

        val changed = StoredMetadataScrub(dao).scrubOnce()

        assertEquals(1, changed)
        // A template scrubs to EMPTY — an unknown annotation renders as
        // absent, never a fabricated one.
        val descriptions = dao.getAllBookDescriptionRows().associate { it.id to it.description }
        assertEquals("", descriptions["b1"])
        assertEquals("Честный текст аннотации.", descriptions["b2"])
    }

    @Test
    fun `startup scrub strips a stored sluhayua prefix keeping the blurb`() = runBlocking {
        dao.insertAudiobooks(
            listOf(
                book(
                    "b1", "Тореадори з Васюківки",
                    "Аудіокнігу онлайн Тореадори з Васюківки, читає Валерій Клименко. Справжній текст анотації."
                )
            )
        )

        val changed = StoredMetadataScrub(dao).scrubOnce()

        assertEquals(1, changed)
        assertEquals("Справжній текст анотації.", dao.getAllBookDescriptionRows().single().description)
    }
}
