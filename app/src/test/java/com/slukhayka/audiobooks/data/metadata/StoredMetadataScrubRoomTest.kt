package com.slukhayka.audiobooks.data.metadata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.NativeRoomWorkerIdentity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `current Soundbooks SEO wrapper is removed from existing rows without changing identity`() = runBlocking {
        val seo = "Аудіокнига Темна матерія - Блейк Крауч слухати онлайн українською"
        dao.insertAudiobooks(listOf(book("kept-id", seo).copy(author = "Блейк Крауч")))
        dao.upsertWork(WorkEntity(id = "kept-id", mergeKey = "kept-merge-key", title = seo, author = "Блейк Крауч"))
        val scrub = StoredMetadataScrub(dao)
        assertEquals(2, scrub.scrubOnce())
        assertEquals("Темна матерія", dao.getAllBookTitleRows().single().title)
        assertEquals("Темна матерія", dao.getAllWorkTitleRows().single().title)
        assertEquals(0, scrub.scrubOnce())
    }

    @Test
    fun `startup scrub decodes a stored HTML entity on both tables and is idempotent`() = runBlocking {
        // #964 — the on-device Work row held «Ім&#x27;я тіні» (a React-rendered
        // hex entity persisted before the write rule decoded it). The one-time
        // pass repairs the stored title without a schema migration; identity
        // (mergeKey) is deliberately NOT touched here.
        dao.insertAudiobooks(listOf(book("b1", "Ім&#x27;я тіні")))
        dao.upsertWork(
            WorkEntity(id = "w1", mergeKey = "w1", title = "Ім&#x27;я тіні", author = "Айя Нея")
        )

        val scrub = StoredMetadataScrub(dao)
        assertEquals(2, scrub.scrubOnce())
        assertEquals("Ім'я тіні", dao.getAllBookTitleRows().single().title)
        assertEquals("Ім'я тіні", dao.getAllWorkTitleRows().single().title)
        // The repair rewrites the DISPLAY title only — a stored identity
        // (mergeKey) is never re-keyed behind the listener's back.
        assertNotNull(dao.findWorkByMergeKey("w1"))
        // Idempotent: a second run finds nothing left to decode.
        assertEquals(0, scrub.scrubOnce())
    }

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

    // --- #964 / #972 follow-up: stored author & narrator names -------------
    //
    // Before #972 the write path stored the source's rendered entity verbatim
    // in a person name («Наталія Дев&#x27;ятко»). The one-time pass repairs the
    // TEXT through the same shared decodeEntities the title repair uses; it
    // never re-keys identity (mergeKey / Edition id stay exactly as stored).

    @Test
    fun `startup scrub decodes a stored entity on every name column`() = runBlocking {
        dao.insertAudiobooks(
            listOf(
                book("b1", "Кобзар").copy(
                    author = "Наталія Дев&#x27;ятко",
                    narrator = "О&#039;Коннор"
                )
            )
        )
        dao.upsertWork(
            WorkEntity(id = "w1", mergeKey = "w1", title = "Кобзар", author = "Наталія Дев&#x27;ятко")
        )
        dao.replaceEdition(
            EditionEntity(id = "e1", workId = "b1", narrator = "Наталія Дев&#x27;ятко")
        )

        val scrub = StoredMetadataScrub(dao)
        // One changed row per table holding a name: audiobooks, works, editions.
        assertEquals(3, scrub.scrubOnce())

        assertEquals("Наталія Дев'ятко", dao.getAllBookTitleRows().single().author)
        assertEquals("О'Коннор", dao.getAudiobookById("b1")!!.narrator)
        assertEquals("Наталія Дев'ятко", dao.getAllWorkTitleRows().single().author)
        assertEquals("Наталія Дев'ятко", dao.getEditionById("e1")!!.narrator)
        // The pass is idempotent at the Room level too.
        assertEquals(0, scrub.scrubOnce())
    }

    @Test
    fun `a clean stored name is byte-identical - the repair only decodes entities`() = runBlocking {
        dao.insertAudiobooks(
            listOf(book("b1", "Кобзар").copy(author = "Тарас Шевченко", narrator = "О'Коннор"))
        )
        dao.upsertWork(
            WorkEntity(id = "w1", mergeKey = "w1", title = "Кобзар", author = "Тарас Шевченко")
        )
        dao.replaceEdition(EditionEntity(id = "e1", workId = "b1", narrator = "О'Коннор"))

        // Nothing to decode: a clean name is never rewritten (not even trimmed).
        assertEquals(0, StoredMetadataScrub(dao).scrubOnce())
        assertEquals("Тарас Шевченко", dao.getAllBookTitleRows().single().author)
        assertEquals("О'Коннор", dao.getAudiobookById("b1")!!.narrator)
        assertEquals("Тарас Шевченко", dao.getAllWorkTitleRows().single().author)
        assertEquals("О'Коннор", dao.getEditionById("e1")!!.narrator)
    }

    @Test
    fun `an unknown or malformed entity in a stored name stays literal`() = runBlocking {
        dao.insertAudiobooks(
            listOf(
                book("b1", "Кобзар").copy(
                    author = "Світ &foo; тіні",
                    narrator = "Дев&#xZZ;ятко"
                )
            )
        )
        dao.upsertWork(
            WorkEntity(id = "w1", mergeKey = "w1", title = "Кобзар", author = "Світ &foo; тіні")
        )
        dao.replaceEdition(EditionEntity(id = "e1", workId = "b1", narrator = "Дев&#xZZ;ятко"))

        // The decoder never fabricates a character: both stay byte-for-byte.
        assertEquals(0, StoredMetadataScrub(dao).scrubOnce())
        assertEquals("Світ &foo; тіні", dao.getAllBookTitleRows().single().author)
        assertEquals("Дев&#xZZ;ятко", dao.getAudiobookById("b1")!!.narrator)
        assertEquals("Світ &foo; тіні", dao.getAllWorkTitleRows().single().author)
        assertEquals("Дев&#xZZ;ятко", dao.getEditionById("e1")!!.narrator)
    }

    @Test
    fun `the name repair never re-keys identity - mergeKey and Edition id survive`() = runBlocking {
        dao.insertAudiobooks(
            listOf(book("b1", "Кобзар").copy(author = "Наталія Дев&#x27;ятко"))
        )
        dao.upsertWork(
            WorkEntity(
                id = "w1",
                mergeKey = "кобзар|наталія девx27ятко",
                title = "Кобзар",
                author = "Наталія Дев&#x27;ятко"
            )
        )
        dao.replaceEdition(EditionEntity(id = "edition-1", workId = "b1", narrator = "Наталія Дев&#x27;ятко"))

        assertEquals(3, StoredMetadataScrub(dao).scrubOnce())

        // #968 owns identity: the stored key and the rendition id are NOT
        // recomputed behind the listener's back, even though the display text
        // now decodes.
        assertEquals("кобзар|наталія девx27ятко", dao.getWorkById("w1")!!.mergeKey)
        assertEquals("edition-1", dao.getEditionById("edition-1")!!.id)
        assertNotNull(dao.findWorkByMergeKey("кобзар|наталія девx27ятко"))
    }

    @Test
    fun `name repair and title repair coexist in one pass`() = runBlocking {
        dao.insertAudiobooks(
            listOf(book("b1", "Ім&#x27;я тіні").copy(author = "Наталія Дев&#x27;ятко"))
        )
        dao.upsertWork(
            WorkEntity(
                id = "w1",
                mergeKey = "w1",
                title = "Ім&#x27;я тіні",
                author = "Наталія Дев&#x27;ятко"
            )
        )

        // The book and the Work each change under BOTH rules — the title rule
        // and the name rule, two rows apiece. (The counter reports rule hits,
        // the same accounting the title/description loops use.)
        assertEquals(4, StoredMetadataScrub(dao).scrubOnce())
        val book = dao.getAllBookTitleRows().single()
        assertEquals("Ім'я тіні", book.title)
        assertEquals("Наталія Дев'ятко", book.author)
        val work = dao.getAllWorkTitleRows().single()
        assertEquals("Ім'я тіні", work.title)
        assertEquals("Наталія Дев'ятко", work.author)
        assertEquals(0, StoredMetadataScrub(dao).scrubOnce())
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
