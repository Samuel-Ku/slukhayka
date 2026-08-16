package com.slukhayka.audiobooks.data.repository

import com.slukhayka.audiobooks.data.catalog.nextInSeries
import com.slukhayka.audiobooks.testing.TestDataFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-function tests for the "continue the series" resolution (spec-9 T4).
 * The function prefers a volume-number match, falls back to series page order,
 * and hides (null) when there is no next volume.
 */
class NextInSeriesTest {

    private val base = TestDataFactory.dataBooks()

    private fun seriesBook(id: String, seriesIndex: Int?): com.slukhayka.audiobooks.data.db.AudiobookEntity =
        // ADR-0009: series fields are @Ignore projections — set in place.
        base.first().copy(id = id).also {
            it.seriesTitle = "Сага"
            it.seriesUrl = "https://4read.org/xfsearch/cikl/saga/"
            it.seriesIndex = seriesIndex
        }

    private val volumes = listOf(
        seriesBook("book-1", seriesIndex = 1),
        seriesBook("book-2", seriesIndex = 2),
        seriesBook("book-3", seriesIndex = 3)
    )

    @Test
    fun `volume number match returns the next volume`() {
        val next = nextInSeries(currentIndex = 1, currentId = "book-1", seriesBooks = volumes)
        assertEquals("book-2", next?.id)
    }

    @Test
    fun `last volume yields null`() {
        val next = nextInSeries(currentIndex = 3, currentId = "book-3", seriesBooks = volumes)
        assertNull(next)
    }

    @Test
    fun `missing volume numbers fall back to series page order`() {
        val unordered = listOf(
            seriesBook("book-2", seriesIndex = null),
            seriesBook("book-1", seriesIndex = null),
            seriesBook("book-3", seriesIndex = null)
        )
        val next = nextInSeries(currentIndex = null, currentId = "book-1", seriesBooks = unordered)
        assertEquals("book-3", next?.id)
    }

    @Test
    fun `current book not in the list yields null unless an index match exists`() {
        // No volume number to match and the book isn't in the list → nothing to suggest.
        assertNull(nextInSeries(currentIndex = null, currentId = "unknown", seriesBooks = volumes))
        // A volume number that has no following volume, and the book isn't in the list.
        assertNull(nextInSeries(currentIndex = 5, currentId = "unknown", seriesBooks = volumes))
        // The volume number is the source of truth: even when the id is not in
        // the list, volume 1 implies volume 2 is the next.
        assertEquals("book-2", nextInSeries(currentIndex = 1, currentId = "unknown", seriesBooks = volumes)?.id)
    }

    @Test
    fun `empty series list yields null`() {
        assertNull(nextInSeries(currentIndex = 1, currentId = "book-1", seriesBooks = emptyList()))
    }

    @Test
    fun `index fallback when the next numbered volume is absent`() {
        val partial = listOf(
            seriesBook("book-1", seriesIndex = 1),
            seriesBook("book-5", seriesIndex = 5)
        )
        // No volume 2; page order puts book-5 after book-1.
        val next = nextInSeries(currentIndex = 1, currentId = "book-1", seriesBooks = partial)
        assertEquals("book-5", next?.id)
    }
}
