package com.slukhayka.audiobooks.data.metadata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.EditionId
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
 * Spec-30 T2 (#217) — the mirror-to-local-database half of the resolver over
 * the REAL SQL (in-memory Room): a shared duration hit lands in the matching
 * library row through the existing duration write path, so the value works
 * offline afterwards; and a locally known duration is never overwritten by
 * the shared cache.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchDurationMirrorRoomTest {

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

    private class FakeStore(private val hits: Map<String, Long>) : SharedBookMetaStore {
        override suspend fun getDuration(editionId: String): Long? = hits[editionId]
        override suspend fun getDurations(editionIds: List<String>): Map<String, Long> =
            hits.filterKeys { it in editionIds }
        override suspend fun putDuration(
            editionId: String,
            durationSeconds: Long,
            provenance: DurationProvenance
        ) = Unit

        // Spec-32 profile methods — not exercised by the duration mirror.
        override suspend fun getProfile(sourceId: String, editionId: String): BookProfile? = null
        override suspend fun putProfile(
            sourceId: String, editionId: String, profile: BookProfile, provenance: ProfileProvenance
        ) = Unit
    }

    private suspend fun seedLibraryRow(
        bookId: String,
        workId: String,
        mergeKey: String,
        totalDurationSeconds: Long = 0L
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
                    totalDurationSeconds = totalDurationSeconds,
                    totalChapters = 7
                )
            )
        )
        dao.upsertLibraryEntry(id = bookId, workId = workId, isFavorite = false, createdAt = 0L, downloadProgress = 0f)
        dao.upsertWork(
            WorkEntity(id = workId, mergeKey = mergeKey, title = "Книга", author = "Автор")
        )
    }

    private fun searchCard(mergeKey: String, url: String = "https://4read.org/kniga.html") =
        GlobalSearchResult(
            title = "Книга",
            author = "Автор",
            narrator = "Читець",
            mergeKey = mergeKey,
            sources = listOf(GlobalSearchSource("4read", "4read", url))
        )

    @Test
    fun `a shared hit mirrors into the real local row via the duration write path`() = runBlocking {
        val mergeKey = "книга|автор"
        seedLibraryRow(bookId = "b1", workId = "w1", mergeKey = mergeKey, totalDurationSeconds = 0L)
        val editionId = EditionId.forBook(mergeKey, "https://4read.org/kniga.html", "Читець")
        val resolver = SearchDurationResolver(dao, FakeStore(mapOf(editionId to 6_600L)))

        resolver.resolve(listOf(searchCard(mergeKey)))

        // The real SQL row now carries the shared duration (chapters kept).
        val row = dao.getAudiobookById("b1")!!
        assertEquals(6_600L, row.totalDurationSeconds)
        assertEquals(7, row.totalChapters)
    }

    @Test
    fun `a locally known duration is never overwritten by the shared cache`() = runBlocking {
        val mergeKey = "книга|автор"
        seedLibraryRow(bookId = "b1", workId = "w1", mergeKey = mergeKey, totalDurationSeconds = 7_200L)
        val editionId = EditionId.forBook(mergeKey, "https://4read.org/kniga.html", "Читець")
        val resolver = SearchDurationResolver(dao, FakeStore(mapOf(editionId to 99L)))

        val resolved = resolver.resolve(listOf(searchCard(mergeKey)))

        // The card shows the local value and the shared value never lands.
        assertEquals(7_200L, resolved[0].durationSeconds)
        assertEquals(7_200L, dao.getAudiobookById("b1")!!.totalDurationSeconds)
    }

    @Test
    fun `a shared miss leaves the local row untouched`() = runBlocking {
        val mergeKey = "книга|автор"
        seedLibraryRow(bookId = "b1", workId = "w1", mergeKey = mergeKey, totalDurationSeconds = 0L)
        val resolver = SearchDurationResolver(dao, FakeStore(emptyMap()))

        val resolved = resolver.resolve(listOf(searchCard(mergeKey)))

        assertNull(resolved[0].durationSeconds)
        assertEquals(0L, dao.getAudiobookById("b1")!!.totalDurationSeconds)
    }
}
