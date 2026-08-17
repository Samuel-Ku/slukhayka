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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-30 T3 (#218) — the library half of the cover resolver over the REAL
 * SQL (in-memory Room): rows that have NO local cover are filled from the
 * shared canonical base through the existing cover write path
 * ([AudiobookDao.updateCoverImageUrl]), so the canonical URL shows in the
 * library (Медіатека) without any search; rows with a known cover are never
 * touched. Best-effort and silent — a miss, a failing store or a corrupt
 * document leaves the rows exactly as they are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryCoverResolverRoomTest {

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

    private class FakeStore(private val hits: Map<String, String>, private val throwOnBatch: Boolean = false) :
        SharedBookMetaStore {
        override suspend fun getCover(mergeKey: String): String? = hits[mergeKey]
        override suspend fun getCovers(mergeKeys: List<String>): Map<String, String> {
            if (throwOnBatch) throw IllegalStateException("shared base down")
            return hits.filterKeys { it in mergeKeys }
        }
        override suspend fun putCover(mergeKey: String, coverUrl: String, provenance: CoverProvenance) = Unit

        // Duration/profile methods — not exercised by the library resolver.
        override suspend fun getDuration(editionId: String): Long? = null
        override suspend fun getDurations(editionIds: List<String>): Map<String, Long> = emptyMap()
        override suspend fun putDuration(
            editionId: String, durationSeconds: Long, provenance: DurationProvenance
        ) = Unit
        override suspend fun getProfile(sourceId: String, editionId: String): BookProfile? = null
        override suspend fun getProfileEntry(sourceId: String, editionId: String): SharedProfileEntry? = null
        override suspend fun putProfile(
            sourceId: String, editionId: String, profile: BookProfile, provenance: ProfileProvenance
        ) = Unit
    }

    private suspend fun seedLibraryRow(
        bookId: String,
        workId: String,
        mergeKey: String,
        coverUrl: String? = null,
        title: String = "Книга"
    ) {
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = bookId,
                    title = title,
                    author = "Автор",
                    narrator = "Читець",
                    description = "",
                    coverDrawableRes = 0,
                    genre = "",
                    sourceUrl = "https://4read.org/$bookId.html",
                    coverImageUrl = coverUrl,
                    totalDurationSeconds = 0L,
                    totalChapters = 7
                )
            )
        )
        dao.upsertLibraryEntry(id = bookId, workId = workId, isFavorite = false, createdAt = 0L, downloadProgress = 0f)
        dao.upsertWork(
            WorkEntity(id = workId, mergeKey = mergeKey, title = title, author = "Автор")
        )
    }

    @Test
    fun `blank-cover library rows are filled from the shared base via the cover write path`() = runBlocking {
        seedLibraryRow(bookId = "b1", workId = "w1", mergeKey = "книга|автор")
        seedLibraryRow(bookId = "b2", workId = "w2", mergeKey = "кобзар|шевченко")
        val resolver = LibraryCoverResolver(
            dao,
            FakeStore(mapOf("книга|автор" to "https://shared.example/kniga.jpg"))
        )

        val filled = resolver.resolve()

        assertEquals(1, filled)
        assertEquals("https://shared.example/kniga.jpg", dao.getAudiobookById("b1")!!.coverImageUrl)
        assertNull(dao.getAudiobookById("b2")!!.coverImageUrl)
    }

    @Test
    fun `rows with a known cover are never touched`() = runBlocking {
        seedLibraryRow(bookId = "b1", workId = "w1", mergeKey = "книга|автор", coverUrl = "https://local.example/cover.jpg")
        val resolver = LibraryCoverResolver(
            dao,
            FakeStore(mapOf("книга|автор" to "https://shared.example/other.jpg"))
        )

        val filled = resolver.resolve()

        assertEquals(0, filled)
        assertEquals("https://local.example/cover.jpg", dao.getAudiobookById("b1")!!.coverImageUrl)
    }

    @Test
    fun `rows without a Work identity are never consulted`() = runBlocking {
        // A row whose works row is missing (blank merge key) cannot be keyed
        // in the shared base — it must stay untouched.
        seedLibraryRow(bookId = "b1", workId = "w1", mergeKey = "книга|автор")
        seedLibraryRow(bookId = "b2", workId = "w2", mergeKey = "")
        val resolver = LibraryCoverResolver(
            dao,
            FakeStore(mapOf("книга|автор" to "https://shared.example/kniga.jpg"))
        )

        val filled = resolver.resolve()

        assertEquals(1, filled)
        assertEquals("https://shared.example/kniga.jpg", dao.getAudiobookById("b1")!!.coverImageUrl)
        assertNull(dao.getAudiobookById("b2")!!.coverImageUrl)
    }

    @Test
    fun `a shared miss leaves the rows untouched`() = runBlocking {
        seedLibraryRow(bookId = "b1", workId = "w1", mergeKey = "книга|автор")
        val resolver = LibraryCoverResolver(dao, FakeStore(emptyMap()))

        val filled = resolver.resolve()

        assertEquals(0, filled)
        assertNull(dao.getAudiobookById("b1")!!.coverImageUrl)
    }

    @Test
    fun `a throwing shared store degrades silently`() = runBlocking {
        seedLibraryRow(bookId = "b1", workId = "w1", mergeKey = "книга|автор")
        val resolver = LibraryCoverResolver(dao, FakeStore(emptyMap(), throwOnBatch = true))

        val filled = resolver.resolve()

        assertEquals(0, filled)
        assertNull(dao.getAudiobookById("b1")!!.coverImageUrl)
    }

    @Test
    fun `no shared store means the resolver is a no-op`() = runBlocking {
        seedLibraryRow(bookId = "b1", workId = "w1", mergeKey = "книга|автор")
        val resolver = LibraryCoverResolver(dao, null)

        val filled = resolver.resolve()

        assertEquals(0, filled)
        assertNull(dao.getAudiobookById("b1")!!.coverImageUrl)
    }
}