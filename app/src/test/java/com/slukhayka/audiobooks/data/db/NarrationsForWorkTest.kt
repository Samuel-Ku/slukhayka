package com.slukhayka.audiobooks.data.db

import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #874 — a Work's narrations, as the playable cards its row shows. */
class NarrationsForWorkTest {

    private fun book(id: String, title: String) = AudiobookEntity(
        id = id,
        title = title,
        author = "Тарас Шевченко",
        narrator = "Іван",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = "local"
    )

    private val dao = FakeAudiobookDao(
        books = listOf(
            book("b2", "Явір"),
            book("b1", "Барвінок"),
            book("other", "Чуже")
        )
    )

    @Test
    fun `only the narrations of THAT work come back, ordered by title`() = runBlocking {
        dao.seedLibraryEntry(LibraryEntryEntity(id = "b2", workId = "w1", createdAt = 1L))
        dao.seedLibraryEntry(LibraryEntryEntity(id = "b1", workId = "w1", createdAt = 1L))
        dao.seedLibraryEntry(LibraryEntryEntity(id = "other", workId = "w2", createdAt = 1L))

        assertEquals(listOf("b1", "b2"), dao.narrationsForWork("w1").map { it.id })
        assertEquals(listOf("other"), dao.narrationsForWork("w2").map { it.id })
        assertTrue("a Work with no narration of ours returns nothing", dao.narrationsForWork("w3").isEmpty())
    }
}
