package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #733 — the library-first «Новинки» projection: one card per Work, newest
 * first, with the source badge of the representative Library Entry.
 */
class LibraryNewArrivalsTest {

    private fun book(
        id: String,
        title: String,
        author: String = "Автор",
        sourceUrl: String = "",
        createdAt: Long = 0L,
        workId: String? = null,
        mergeKey: String = ""
    ): AudiobookEntity = AudiobookEntity(
        id = id,
        title = title,
        author = author,
        narrator = "",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = sourceUrl
    ).also {
        it.createdAt = createdAt
        it.workId = workId
        it.mergeKey = mergeKey
    }

    @Test
    fun oneCardPerWork_keepingTheMostRecentlyAddedEntry() {
        val arrivals = LibraryNewArrivals.project(
            listOf(
                book("old", "Темна матерія", createdAt = 1_000L, workId = "w1", sourceUrl = "https://4read.org/1"),
                book("new", "Темна матерія", createdAt = 5_000L, workId = "w1", sourceUrl = "https://sound-books.net/2")
            )
        )

        assertEquals(1, arrivals.size)
        assertEquals("w1", arrivals.single().workKey)
        assertEquals("new", arrivals.single().book.id)
        assertEquals("Sound-Books", arrivals.single().sourceName)
        assertEquals(5_000L, arrivals.single().addedAt)
    }

    @Test
    fun newestFirst_withTitleTieBreak() {
        val arrivals = LibraryNewArrivals.project(
            listOf(
                book("b", "Б", createdAt = 1_000L, workId = "wb"),
                book("a", "А", createdAt = 1_000L, workId = "wa"),
                book("c", "В", createdAt = 9_000L, workId = "wc")
            )
        )

        assertEquals(listOf("wc", "wa", "wb"), arrivals.map { it.workKey })
    }

    @Test
    fun workIdentityFallsBackToMergeKeyThenBookId() {
        val arrivals = LibraryNewArrivals.project(
            listOf(
                book("m", "Мержа", createdAt = 3_000L, mergeKey = "мержа|автор"),
                book("l", "Локальна", createdAt = 2_000L)
            )
        )

        assertEquals(listOf("мержа|автор", "l"), arrivals.map { it.workKey })
    }

    @Test
    fun localImportBadgesAsTheLocalSource() {
        val arrivals = LibraryNewArrivals.project(listOf(book("l", "Локальна", sourceUrl = "")))

        assertEquals("Локальна", arrivals.single().sourceName)
    }

    @Test
    fun newImportAppearsAtTheHead_andRemovalDropsTheCard() {
        val before = listOf(book("a", "А", createdAt = 1_000L, workId = "wa"))
        val afterImport = before + book("b", "Б", createdAt = 2_000L, workId = "wb")

        assertEquals(listOf("wa"), LibraryNewArrivals.project(before).map { it.workKey })
        assertEquals(listOf("wb", "wa"), LibraryNewArrivals.project(afterImport).map { it.workKey })
        assertEquals(emptyList<String>(), LibraryNewArrivals.project(emptyList()).map { it.workKey })
    }

    @Test
    fun limitCapsTheShelf_andEmptyInputIsHonest() {
        val many = (1..30).map { book("b$it", "Книга $it", createdAt = it.toLong(), workId = "w$it") }

        assertEquals(LibraryNewArrivals.DEFAULT_LIMIT, LibraryNewArrivals.project(many).size)
        assertEquals(3, LibraryNewArrivals.project(many, limit = 3).size)
        assertTrue(LibraryNewArrivals.project(emptyList()).isEmpty())
        assertTrue(LibraryNewArrivals.project(many, limit = 0).isEmpty())
    }
}
