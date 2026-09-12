package com.slukhayka.audiobooks.data.collections

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #735 — the collection match corpus is the Медіатека: one card per owned
 * Work, carrying the source it was imported from.
 */
class LibraryMatchCorpusTest {

    private fun book(
        id: String,
        title: String,
        author: String,
        sourceUrl: String = "https://sound-books.net/$id",
        workId: String? = null,
        mergeKey: String = "",
        narrator: String = ""
    ) = AudiobookEntity(
        id = id,
        title = title,
        author = author,
        narrator = narrator,
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = sourceUrl
    ).also {
        it.workId = workId
        it.mergeKey = mergeKey
    }

    @Test
    fun oneCardPerWorkAcrossEditions() {
        val corpus = libraryMatchCorpus(
            listOf(
                book("a", "Темна матерія", "Блейк Крауч", workId = "w1"),
                book("b", "Темна матерія", "Блейк Крауч", workId = "w1", narrator = "Інший")
            )
        )

        assertEquals(1, corpus.size)
        assertEquals("Темна матерія", corpus.single().title)
    }

    @Test
    fun theBadgeNamesTheSourceTheListenerImportedFrom() {
        val corpus = libraryMatchCorpus(
            listOf(
                book("a", "Кобзар", "Тарас Шевченко", sourceUrl = "https://sound-books.net/kobzar"),
                book("b", "Emma", "Jane Austen", sourceUrl = "https://librivox.org/emma")
            )
        )

        assertEquals(
            listOf("Sound-Books", "LibriVox"),
            corpus.map { it.sources.single().sourceName }
        )
    }

    @Test
    fun localImportsAreBadgedAsTheLocalSourceAndKeepIdentity() {
        val corpus = libraryMatchCorpus(
            listOf(book("local-1", "Мій запис", "Автор", sourceUrl = ""))
        )

        val card = corpus.single()
        assertEquals("Локальна", card.sources.single().sourceName)
        assertEquals("Мій запис", card.title)
        // No Work row: the card still carries a usable merge identity.
        assertEquals(true, card.mergeKey.isNotBlank())
    }

    @Test
    fun anEmptyLibraryYieldsAnEmptyCorpus() {
        assertEquals(emptyList<Any>(), libraryMatchCorpus(emptyList()))
    }
}
