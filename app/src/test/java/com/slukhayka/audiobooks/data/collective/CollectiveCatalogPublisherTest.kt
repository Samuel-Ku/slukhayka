package com.slukhayka.audiobooks.data.collective

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #522 — the publish half: a verified book is offered only through a DIRECT,
 * non-scam, Ukrainian source, the first publishable source wins, and a failing
 * write is silent.
 */
class CollectiveCatalogPublisherTest {

    private class FakeStore(var failing: Boolean = false) : CollectiveCardStore {
        val published = mutableListOf<CollectiveCardPublication>()

        override suspend fun putCard(card: CollectiveCardPublication) {
            if (failing) throw IllegalStateException("network down")
            published += card
        }
    }

    private fun book(
        id: String = "b1",
        title: String = "Кобзар",
        author: String = "Тарас Шевченко"
    ) = AudiobookEntity(
        id = id,
        title = title,
        author = author,
        narrator = "Диктор",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = "https://4read.org/legacy",
        totalDurationSeconds = 7_200L,
        totalChapters = 12
    ).also {
        it.language = "uk"
        it.seriesTitle = "Кобзар"
        it.seriesIndex = 1
    }

    private fun source(type: String, url: String) = SourceEntity(
        id = "$type-$url",
        bookId = "b1",
        type = type,
        url = url
    )

    @Test
    fun `a verified direct Ukrainian book is published once`() {
        val store = FakeStore()
        val publisher = CollectiveCatalogPublisher(
            store = store,
            book = { book() },
            sources = { listOf(source("soundbooks", "https://sound-books.net/kobzar")) },
            clock = { 1_700_000_000_000L }
        )

        val published = kotlinx.coroutines.runBlocking { publisher.publishVerified("b1") }

        assertTrue(published)
        val card = store.published.single()
        assertEquals("soundbooks", card.sourceId)
        assertEquals("https://sound-books.net/kobzar", card.sourceUrl)
        assertEquals("uk", card.language)
        assertEquals(7_200L, card.durationSeconds)
        assertEquals(12, card.chapterCount)
    }

    @Test
    fun `a legacy 4read row never blocks the real direct source`() {
        val store = FakeStore()
        val publisher = CollectiveCatalogPublisher(
            store = store,
            book = { book() },
            sources = {
                listOf(
                    source("4read", "https://4read.org/legacy"),
                    source("soundbooks", "https://sound-books.net/kobzar")
                )
            }
        )

        assertTrue(kotlinx.coroutines.runBlocking { publisher.publishVerified("b1") })
        assertEquals("soundbooks", store.published.single().sourceId)
    }

    @Test
    fun `a book with no publishable source publishes nothing`() {
        val store = FakeStore()
        val publisher = CollectiveCatalogPublisher(
            store = store,
            book = { book() },
            sources = { listOf(source("4read", "https://4read.org/legacy")) }
        )

        assertFalse(kotlinx.coroutines.runBlocking { publisher.publishVerified("b1") })
        assertTrue(store.published.isEmpty())
    }

    @Test
    fun `a failing write is silent`() {
        val publisher = CollectiveCatalogPublisher(
            store = FakeStore(failing = true),
            book = { book() },
            sources = { listOf(source("soundbooks", "https://sound-books.net/kobzar")) }
        )

        assertFalse(kotlinx.coroutines.runBlocking { publisher.publishVerified("b1") })
    }

    @Test
    fun `a null store publishes nothing`() {
        val publisher = CollectiveCatalogPublisher(
            store = null,
            book = { book() },
            sources = { listOf(source("soundbooks", "https://sound-books.net/kobzar")) }
        )

        assertFalse(kotlinx.coroutines.runBlocking { publisher.publishVerified("b1") })
    }
}
