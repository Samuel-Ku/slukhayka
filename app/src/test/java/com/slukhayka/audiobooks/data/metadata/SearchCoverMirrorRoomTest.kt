package com.slukhayka.audiobooks.data.metadata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
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
 * Spec-30 T3 (#218) — the mirror-to-local-database half of the cover resolver
 * over the REAL SQL (in-memory Room): a shared cover hit lands in the
 * matching library row through the existing cover write path
 * ([AudiobookDao.updateCoverImageUrl]), so the canonical URL works offline
 * afterwards; and a locally known cover is never overwritten by the shared
 * cache. Prior art: [SearchDurationMirrorRoomTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchCoverMirrorRoomTest {

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

    private class FakeStore(private val hits: Map<String, String>) : SharedBookMetaStore {
        override suspend fun getCover(mergeKey: String): String? = hits[mergeKey]
        override suspend fun getCovers(mergeKeys: List<String>): Map<String, String> =
            hits.filterKeys { it in mergeKeys }
        override suspend fun putCover(mergeKey: String, coverUrl: String, provenance: CoverProvenance) = Unit

        // Duration/profile methods — not exercised by the cover mirror.
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
        coverUrl: String? = null
    ) {
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = bookId,
                    title = "Книга",
                    author = "Автор",
                    narrator = "Читець",
                    description = "",
                    coverDrawableRes = 0,
                    genre = "",
                    sourceUrl = "https://4read.org/kniga.html",
                    coverImageUrl = coverUrl,
                    totalDurationSeconds = 0L,
                    totalChapters = 7
                )
            )
        )
        dao.upsertLibraryEntry(id = bookId, workId = workId, isFavorite = false, createdAt = 0L, downloadProgress = 0f)
        dao.upsertWork(
            WorkEntity(id = workId, mergeKey = mergeKey, title = "Книга", author = "Автор")
        )
    }

    private fun searchCard(mergeKey: String, url: String = "https://4read.org/kniga.html", coverUrl: String? = null) =
        GlobalSearchResult(
            title = "Книга",
            author = "Автор",
            narrator = "Читець",
            mergeKey = mergeKey,
            coverImageUrl = coverUrl,
            sources = listOf(GlobalSearchSource("4read", "4read", url))
        )

    @Test
    fun `a shared hit mirrors into the real local row via the cover write path`() = runBlocking {
        val mergeKey = "книга|автор"
        seedLibraryRow(bookId = "b1", workId = "w1", mergeKey = mergeKey, coverUrl = null)
        val resolver = SearchCoverResolver(
            dao,
            FakeStore(mapOf(mergeKey to "https://shared.example/cover.jpg"))
        )

        resolver.resolve(listOf(searchCard(mergeKey)))

        // The real SQL row now carries the canonical URL.
        val row = dao.getAudiobookById("b1")!!
        assertEquals("https://shared.example/cover.jpg", row.coverImageUrl)
    }

    @Test
    fun `a locally known cover is never overwritten by the shared cache`() = runBlocking {
        val mergeKey = "книга|автор"
        seedLibraryRow(bookId = "b1", workId = "w1", mergeKey = mergeKey, coverUrl = "https://local.example/cover.jpg")
        val resolver = SearchCoverResolver(
            dao,
            FakeStore(mapOf(mergeKey to "https://shared.example/other.jpg"))
        )

        val resolved = resolver.resolve(listOf(searchCard(mergeKey)))

        // The card shows the local value and the shared value never lands.
        assertEquals("https://local.example/cover.jpg", resolved[0].coverImageUrl)
        assertEquals("https://local.example/cover.jpg", dao.getAudiobookById("b1")!!.coverImageUrl)
    }

    @Test
    fun `a shared miss leaves the local row untouched`() = runBlocking {
        val mergeKey = "книга|автор"
        seedLibraryRow(bookId = "b1", workId = "w1", mergeKey = mergeKey, coverUrl = null)
        val resolver = SearchCoverResolver(dao, FakeStore(emptyMap()))

        val resolved = resolver.resolve(listOf(searchCard(mergeKey)))

        assertNull(resolved[0].coverImageUrl)
        assertNull(dao.getAudiobookById("b1")!!.coverImageUrl)
    }
}