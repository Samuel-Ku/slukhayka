package com.slukhayka.audiobooks.data.db

import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #874 — the narrator twin of `worksForAuthor`: the Work-level view of a person
 * who NARRATES. This is what the single person page will read for both roles,
 * so a narrator gets the same work projection an author does.
 */
class WorksForNarratorTest {

    private val dao = FakeAudiobookDao()

    private fun work(id: String, title: String) = WorkEntity(
        id = id,
        mergeKey = "$id|author",
        title = title,
        author = "Тарас Шевченко"
    )

    @Test
    fun `a narrator's works are the ones their editions name`() = runBlocking {
        dao.seedNarratedWork(work("w1", "Кобзар"), "Іван")
        dao.seedNarratedWork(work("w2", "Гайдамаки"), "Петро")

        assertEquals(listOf("w1"), dao.worksForNarrator("Іван").map { it.id })
        assertEquals(listOf("w2"), dao.worksForNarrator("Петро").map { it.id })
        assertTrue("an unknown narrator narrates no known work", dao.worksForNarrator("Ніхто").isEmpty())
    }

    @Test
    fun `the match is case-insensitive, because the edition stores the name as written`() = runBlocking {
        dao.seedNarratedWork(work("w1", "Кобзар"), "Іван Франко")

        assertEquals(
            "the same person, however the name was typed",
            listOf("w1"),
            dao.worksForNarrator("іван франко").map { it.id }
        )
    }

    @Test
    fun `ownership is marked for a narrator exactly as for an author`() = runBlocking {
        dao.seedNarratedWork(work("w1", "Кобзар"), "Іван")
        dao.seedNarratedWork(work("w2", "Гайдамаки"), "Іван")
        dao.seedLibraryEntry(
            LibraryEntryEntity(id = "entry-1", workId = "w1", createdAt = 1L)
        )

        assertEquals(
            "only the owned Work is marked, and only through this narrator",
            listOf("w1"),
            dao.ownedWorkIdsForNarrator("Іван").sorted()
        )
        assertEquals(
            "another narrator owns nothing of ours",
            emptyList<String>(),
            dao.ownedWorkIdsForNarrator("Петро")
        )
    }

    @Test
    fun `the projection is ordered by title, not by insertion`() = runBlocking {
        dao.seedNarratedWork(work("w2", "Явір"), "Іван")
        dao.seedNarratedWork(work("w1", "Барвінок"), "Іван")

        assertEquals(listOf("w1", "w2"), dao.worksForNarrator("Іван").map { it.id })
    }
}
