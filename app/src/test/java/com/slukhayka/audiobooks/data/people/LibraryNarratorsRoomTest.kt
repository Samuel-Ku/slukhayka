package com.slukhayka.audiobooks.data.people

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #736 / ADR-0041 — the «Виконавці» index is the Медіатека, not a provider
 * page: only narrators of owned Editions are listed, and one narrator's page
 * resolves the listener's own books locally. A mirrored (not owned) narration
 * never enters either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryNarratorsRoomTest {

    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun own(workId: String, bookId: String, title: String, narrator: String) {
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = bookId,
                    title = title,
                    author = "Автор",
                    narrator = narrator,
                    description = "",
                    coverDrawableRes = 0,
                    genre = "",
                    sourceUrl = ""
                )
            )
        )
        dao.upsertWork(WorkEntity(id = workId, mergeKey = workId, title = title, author = "Автор", addedAt = 0L))
        dao.upsertLibraryEntry(
            id = bookId,
            workId = workId,
            isFavorite = false,
            createdAt = 0L,
            downloadProgress = 0f
        )
        dao.insertEdition(
            EditionEntity(
                id = "ed-$bookId",
                workId = workId,
                narrator = narrator,
                totalChapters = 1,
                totalDurationSeconds = 0L
            )
        )
    }

    /** A mirrored Work with an Edition but no Library Entry — never owned. */
    private suspend fun mirror(workId: String, title: String, narrator: String) {
        dao.upsertWork(WorkEntity(id = workId, mergeKey = workId, title = title, author = "Автор", addedAt = 0L))
        dao.insertEdition(
            EditionEntity(
                id = "ed-$workId",
                workId = workId,
                narrator = narrator,
                totalChapters = 1,
                totalDurationSeconds = 0L
            )
        )
    }

    @Test
    fun `narrator index is library-only and one narrator's books resolve locally`() = runBlocking {
        own("w1", "b1", "Кобзар", "Диктор А")
        own("w2", "b2", "Гайдамаки", "Диктор Б")
        own("w3", "b3", "Місто", "Диктор А")
        mirror("w9", "Замок", "Диктор В")

        val narrators = dao.observeLibraryNarrators().first()
        assertEquals(listOf("Диктор А", "Диктор Б"), narrators.map { it.displayName })
        assertEquals(2, narrators.first { it.displayName == "Диктор А" }.workCount)
        assertEquals(1, narrators.first { it.displayName == "Диктор Б" }.workCount)

        assertEquals(
            listOf("Кобзар", "Місто"),
            dao.libraryBooksForNarrator("Диктор А").map { it.title }
        )
        assertTrue(
            "a mirrored narration never enters the local person page",
            dao.libraryBooksForNarrator("Диктор В").isEmpty()
        )
    }

    @Test
    fun `an empty narrator name is never a person`() = runBlocking {
        own("w1", "b1", "Кобзар", "Диктор А")
        // A blank-narration Edition (unknown) must not create a "" person.
        dao.insertEdition(
            EditionEntity(
                id = "ed-blank",
                workId = "w1",
                narrator = "",
                totalChapters = 1,
                totalDurationSeconds = 0L
            )
        )

        assertEquals(listOf("Диктор А"), dao.observeLibraryNarrators().first().map { it.displayName })
    }
}
