package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #737 — the offline, library-first half of search: owned rows matched by
 * title or author, instantly and without a request.
 */
class LibrarySearchTest {

    private fun book(id: String, title: String, author: String) = AudiobookEntity(
        id = id,
        title = title,
        author = author,
        narrator = "",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = ""
    )

    private val library = listOf(
        book("1", "Темна матерія", "Блейк Крауч"),
        book("2", "Кобзар", "Тарас Шевченко"),
        book("3", "1984", "Джордж Орвелл")
    )

    @Test
    fun matchesTitleOrAuthorCaseInsensitively() {
        assertEquals(listOf("1"), library.matchingLibraryQuery("темна").map { it.id })
        assertEquals(listOf("1"), library.matchingLibraryQuery("КРАУЧ").map { it.id })
        assertEquals(listOf("2"), library.matchingLibraryQuery("шевченко").map { it.id })
    }

    @Test
    fun aQueryWithNoLocalHitIsHonestlyEmpty() {
        assertTrue(library.matchingLibraryQuery("Місто").isEmpty())
    }

    @Test
    fun aBlankQueryIsNotASearch_andReturnsTheWholeLibrary() {
        assertEquals(library, library.matchingLibraryQuery("   "))
    }

    @Test
    fun anImportedSourceHitBecomesALocalMatch() {
        val beforeImport = emptyList<AudiobookEntity>()
        assertTrue(beforeImport.matchingLibraryQuery("Боварі").isEmpty())

        // The same shape the import door materializes for a source hit.
        val imported = beforeImport + book("imported", "Пані Боварі", "Гюстав Флобер")

        assertEquals(listOf("imported"), imported.matchingLibraryQuery("Боварі").map { it.id })
        assertEquals(listOf("imported"), imported.matchingLibraryQuery("Флобер").map { it.id })
    }
}
