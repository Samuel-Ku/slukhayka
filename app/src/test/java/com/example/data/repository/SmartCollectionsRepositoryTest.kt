package com.example.data.repository

import com.example.data.collection.CollectionSpec
import com.example.data.db.AudiobookEntity
import com.example.testing.FakeAudiobookDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-16 T2 (#108) — the repository seam: smart collections recompute from
 * stored catalog state on the union-sync trigger. Uses the fake DAO seam
 * (spec-14 pattern): the collection list is injected, no assets, no network.
 * External behaviour pinned: a collection appears once its books are in the
 * catalog, empty collections stay absent, and nothing is persisted.
 */
class SmartCollectionsRepositoryTest {

    private val fixture = listOf(
        CollectionSpec(
            id = "nobel",
            displayName = "Нобелівські лауреати",
            entries = listOf(
                com.example.data.collection.CollectionEntry("Ернест Хемінгуей", "Старий і море")
            )
        ),
        CollectionSpec(
            id = "shevchenko",
            displayName = "Шевченківська премія",
            entries = listOf(
                com.example.data.collection.CollectionEntry("Тарас Шевченко", "Кобзар"),
                com.example.data.collection.CollectionEntry("Тарас Шевченко", null)
            )
        )
    )

    private fun book(id: String, title: String, author: String) = AudiobookEntity(
        id = id,
        title = title,
        author = author,
        narrator = "",
        description = "",
        coverDrawableRes = 0,
        coverImageUrl = null,
        genre = "",
        sourceUrl = "https://4read.org/$id.html",
        isDownloaded = false,
        downloadProgress = 0f,
        totalDurationSeconds = 0L,
        totalChapters = 0,
        rating = 0f
    )

    private fun repo(dao: FakeAudiobookDao) = AudiobookRepository(
        dao = dao,
        context = null,
        autoSyncOnInit = false,
        sourceAdapters = emptyList(),
        collectionLoader = { fixture }
    )

    @Test
    fun `matching books surface in their collections after a catalog sync`() = runTest {
        val dao = FakeAudiobookDao(
            books = listOf(
                book("old-man", "Старий і море", "Ернест Хемінгуей"),
                book("kobzar", "Кобзар", "Тарас Шевченко")
            )
        )
        val repository = repo(dao)

        repository.refreshUnifiedCatalog(limit = 5)

        val collections = repository.smartCollections.value
        assertEquals(listOf("nobel", "shevchenko"), collections.map { it.id })
        assertEquals(listOf("old-man"), collections[0].books.map { it.id })
        assertEquals(listOf("kobzar"), collections[1].books.map { it.id })
    }

    @Test
    fun `a catalog change recomputes the rows`() = runTest {
        val dao = FakeAudiobookDao()
        val repository = repo(dao)

        repository.refreshUnifiedCatalog(limit = 5)
        assertTrue(repository.smartCollections.value.isEmpty())

        dao.insertAudiobooks(listOf(book("kobzar", "Кобзар", "Тарас Шевченко")))
        repository.refreshUnifiedCatalog(limit = 5)

        val collections = repository.smartCollections.value
        assertEquals(listOf("shevchenko"), collections.map { it.id })
    }

    @Test
    fun `an author-only entry stays absent while its author is ambiguous`() = runTest {
        val dao = FakeAudiobookDao(
            books = listOf(
                book("kobzar", "Кобзар", "Тарас Шевченко"),
                book("haidamaky", "Гайдамаки", "Тарас Шевченко")
            )
        )
        val repository = repo(dao)

        repository.refreshUnifiedCatalog(limit = 5)

        // «Кобзар» matches by title; the author-only entry contributes
        // nothing (two books by Тарас Шевченко) — the collection still
        // exists, showing only the title-matched book.
        val shevchenko = repository.smartCollections.value.single { it.id == "shevchenko" }
        assertEquals(listOf("kobzar"), shevchenko.books.map { it.id })
    }

    @Test
    fun `nothing is persisted for the collection rows`() = runTest {
        val dao = FakeAudiobookDao(
            books = listOf(book("kobzar", "Кобзар", "Тарас Шевченко"))
        )
        val repository = repo(dao)

        repository.refreshUnifiedCatalog(limit = 5)

        // Room holds exactly the input book — no collection rows, no match
        // bookkeeping, nothing beyond the stored catalog.
        assertEquals(listOf("kobzar"), dao.getAllAudiobooksOnce().map { it.id })
        assertEquals(1, repository.smartCollections.value.size)
    }
}