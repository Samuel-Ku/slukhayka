package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.KnownBookIdentity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.metadata.BookProfile
import com.slukhayka.audiobooks.data.metadata.ProfileChapter
import com.slukhayka.audiobooks.data.metadata.ProfileFreshness
import com.slukhayka.audiobooks.data.metadata.ProfileProvenance
import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.metadata.SharedProfileEntry
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-32 T2/T3 (#232/#233) — the shared-profile sync in the import path,
 * Robolectric over an in-memory database: a resolved page writes its full
 * profile back best-effort; a FRESH shared profile imports WITHOUT fetching
 * the page; a stale profile is served fail-open when the re-fetch fails; a
 * failing profile write never breaks the import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryProfileSyncTest {

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

    private fun imports(store: FakeProfileStore?, adapters: List<SourceAdapter> = emptyList()) =
        LibraryImport(dao, context, adapters, profileStore = store)

    private val identity = KnownBookIdentity("Кобзар", "Тарас Шевченко")

    private fun editionId() = EditionId.forBook(
        MergeKey.keyFor(identity.title, identity.author),
        "",
        // The import's narrator placeholder for a blank narration claim.
        "soundbooks narrator",
        ""
    )
    private val editionKey = "soundbooks|${editionId()}"

    private fun detailOf(title: String, chapters: Int = 2) = SourceBookDetail(
        title = title,
        author = "Тарас Шевченко",
        url = "https://sound-books.net/kobzar.html",
        coverImageUrl = "https://sound-books.net/uploads/kobzar.jpg",
        chapters = (1..chapters).map {
            SourceChapter("Розділ $it", "https://arch.sound-books.net/kobzar/$it.mp3", it * 100L)
        },
        totalDurationSeconds = chapters * 100L,
        rating = 4.5,
        genres = listOf("Поезія"),
        description = "Збірка поезій."
    )

    private fun profileOf(chapters: Int = 2) = BookProfile(
        title = identity.title,
        author = identity.author,
        description = "Збірка поезій.",
        coverImageUrl = "https://sound-books.net/uploads/kobzar.jpg",
        genres = listOf("Поезія"),
        chapters = (1..chapters).map {
            ProfileChapter("Розділ $it", "https://arch.sound-books.net/kobzar/$it.mp3", it * 100L)
        },
        totalDurationSeconds = chapters * 100L
    )

    private class FakeAdapter(
        private val detail: SourceBookDetail?,
        var throwOnFetch: Boolean = false
    ) : SourceAdapter {
        override val sourceId: String = "soundbooks"
        var fetchCalls = 0

        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail {
            fetchCalls++
            if (throwOnFetch) throw IllegalStateException("site down")
            return detail ?: throw IllegalStateException("no detail configured")
        }
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun parseCapturedPage(html: String, url: String): SourceBookDetail? = null
    }

    private class FakeProfileStore(
        var throwOnPut: Boolean = false
    ) : SharedBookMetaStore {
        val puts = mutableListOf<Pair<String, BookProfile>>()
        val entries = mutableMapOf<String, SharedProfileEntry>()

        override suspend fun getDuration(editionId: String): Long? = null
        override suspend fun getDurations(editionIds: List<String>): Map<String, Long> = emptyMap()
        override suspend fun putDuration(editionId: String, durationSeconds: Long, provenance: com.slukhayka.audiobooks.data.metadata.DurationProvenance) = Unit

        override suspend fun getProfile(sourceId: String, editionId: String): BookProfile? =
            entries["$sourceId|$editionId"]?.profile

        override suspend fun getProfileEntry(sourceId: String, editionId: String): SharedProfileEntry? =
            entries["$sourceId|$editionId"]

        override suspend fun putProfile(
            sourceId: String,
            editionId: String,
            profile: BookProfile,
            provenance: ProfileProvenance
        ) {
            if (throwOnPut) throw IllegalStateException("shared base down")
            puts += ("$sourceId|$editionId" to profile)
            entries["$sourceId|$editionId"] = SharedProfileEntry(profile, provenance.resolvedAt)
        }
    }

    // --- T2 (#232): write-back on resolution ------------------------------

    @Test
    fun `a resolved page writes its full profile back`() = runBlocking {
        val store = FakeProfileStore()
        val adapter = FakeAdapter(detailOf("Кобзар"))
        val book = imports(store, listOf(adapter))
            .importFromSourceUrl("soundbooks", "https://sound-books.net/kobzar.html", identity)

        assertNotNull(book)
        assertEquals(1, adapter.fetchCalls)
        assertEquals(1, store.puts.size)
        val (key, profile) = store.puts.single()
        assertEquals(editionKey, key)
        assertEquals("Кобзар", profile.title)
        assertEquals(2, profile.chapters.size)
    }

    @Test
    fun `a failing profile write never breaks the import`() = runBlocking {
        val store = FakeProfileStore(throwOnPut = true)
        val adapter = FakeAdapter(detailOf("Кобзар"))
        val book = imports(store, listOf(adapter))
            .importFromSourceUrl("soundbooks", "https://sound-books.net/kobzar.html", identity)

        assertNotNull(book)
        assertEquals("Кобзар", dao.getAudiobookById(book!!.id)!!.title)
    }

    // --- T3 (#233): read-skip + fail-open ---------------------------------

    @Test
    fun `a fresh shared profile imports without fetching the page`() = runBlocking {
        val store = FakeProfileStore()
        store.entries[editionKey] = SharedProfileEntry(profileOf(), System.currentTimeMillis())
        val adapter = FakeAdapter(detailOf("НЕ З ЦЬОГО ФЕТЧУ"))
        val book = imports(store, listOf(adapter))
            .importFromSourceUrl("soundbooks", "https://sound-books.net/kobzar.html", identity)

        assertNotNull(book)
        assertEquals(0, adapter.fetchCalls)
        assertEquals("Кобзар", dao.getAudiobookById(book!!.id)!!.title)
        assertEquals(2, dao.getAudiobookById(book.id)!!.totalChapters)
    }

    @Test
    fun `a fresh shared profile is not re-written back`() = runBlocking {
        val store = FakeProfileStore()
        store.entries[editionKey] = SharedProfileEntry(profileOf(), System.currentTimeMillis())
        val adapter = FakeAdapter(detailOf("НЕ З ЦЬОГО ФЕТЧУ"))
        val book = imports(store, listOf(adapter))
            .importFromSourceUrl("soundbooks", "https://sound-books.net/kobzar.html", identity)

        assertNotNull(book)
        assertEquals(0, adapter.fetchCalls)
        // A cache hit is no resolution — the profile must not be re-written
        // (that would roll the freshness forward and burn the write quota).
        assertTrue(store.puts.isEmpty())
    }

    @Test
    fun `a stale profile re-fetches and refreshes the cache`() = runBlocking {
        val store = FakeProfileStore()
        store.entries[editionKey] = SharedProfileEntry(
            profileOf(),
            System.currentTimeMillis() - ProfileFreshness.FRESHNESS_MILLIS - 1
        )
        val adapter = FakeAdapter(detailOf("Кобзар"))
        val book = imports(store, listOf(adapter))
            .importFromSourceUrl("soundbooks", "https://sound-books.net/kobzar.html", identity)

        assertNotNull(book)
        assertEquals(1, adapter.fetchCalls)
        // The refreshed page is written back, rolling the profile freshness.
        assertEquals(1, store.puts.size)
    }

    @Test
    fun `a failed re-fetch serves the stale profile (fail-open)`() = runBlocking {
        val store = FakeProfileStore()
        store.entries[editionKey] = SharedProfileEntry(
            profileOf(),
            System.currentTimeMillis() - ProfileFreshness.FRESHNESS_MILLIS - 1
        )
        val adapter = FakeAdapter(detailOf("x"), throwOnFetch = true)
        val book = imports(store, listOf(adapter))
            .importFromSourceUrl("soundbooks", "https://sound-books.net/kobzar.html", identity)

        assertNotNull(book)
        assertEquals(1, adapter.fetchCalls)
        assertEquals("Кобзар", dao.getAudiobookById(book!!.id)!!.title)
        assertEquals(2, dao.getAudiobookById(book.id)!!.totalChapters)
    }

    @Test
    fun `without a known identity the page is fetched despite a fresh cache`() = runBlocking {
        val store = FakeProfileStore()
        store.entries[editionKey] = SharedProfileEntry(profileOf(), System.currentTimeMillis())
        val adapter = FakeAdapter(detailOf("Кобзар"))
        val book = imports(store, listOf(adapter))
            .importFromSourceUrl("soundbooks", "https://sound-books.net/kobzar.html")

        assertNotNull(book)
        assertEquals(1, adapter.fetchCalls)
    }
}
