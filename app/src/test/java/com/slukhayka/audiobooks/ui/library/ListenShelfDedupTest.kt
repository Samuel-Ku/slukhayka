package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.testing.TestDataFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [deduplicateListenShelves] (spec-28 #191): a book
 * renders in at most ONE shelf — the highest on screen — and the hero claims
 * its book before any shelf.
 */
class ListenShelfDedupTest {

    private val books = TestDataFactory.dataBooks()

    private fun block(id: ListenComposer.BlockId, title: String, bookIds: List<String>): ListenComposer.Block {
        val selected = books.filter { it.id in bookIds }
        return ListenComposer.Block(
            id = id,
            title = title,
            reason = null,
            books = buildLibraryBooks(selected, emptyList(), emptyMap())
        )
    }

    @Test
    fun `a book claimed by an earlier shelf is dropped from later shelves`() {
        val almostDone = block(
            ListenComposer.BlockId.ALMOST_DONE,
            "Майже дочитали",
            listOf("fixture-book-neuromancer", "fixture-book-1984")
        )
        val travel = block(
            ListenComposer.BlockId.TRAVEL,
            "Готово до поїздки",
            listOf("fixture-book-neuromancer", "fixture-book-fahrenheit")
        )

        val result = deduplicateListenShelves(listOf(almostDone, travel))

        // The first shelf keeps both; the second loses the already-claimed book.
        assertEquals(
            listOf("fixture-book-neuromancer", "fixture-book-1984"),
            result[0].books.map { it.book.id }
        )
        assertEquals(
            listOf("fixture-book-fahrenheit"),
            result[1].books.map { it.book.id }
        )
    }

    @Test
    fun `reordering shelves re-prioritises which shelf claims a book`() {
        val travel = block(
            ListenComposer.BlockId.TRAVEL,
            "Готово до поїздки",
            listOf("fixture-book-neuromancer", "fixture-book-fahrenheit")
        )
        val almostDone = block(
            ListenComposer.BlockId.ALMOST_DONE,
            "Майже дочитали",
            listOf("fixture-book-neuromancer", "fixture-book-1984")
        )

        val result = deduplicateListenShelves(listOf(travel, almostDone))

        // Same two blocks, swapped order — the higher shelf now claims the book.
        assertEquals(
            listOf("fixture-book-neuromancer", "fixture-book-fahrenheit"),
            result[0].books.map { it.book.id }
        )
        assertEquals(
            listOf("fixture-book-1984"),
            result[1].books.map { it.book.id }
        )
    }

    // spec-28 (#200): the hero-exception lives in ONE place — the function
    // claims the hero's book itself (no caller pre-seeds a set). The hero
    // keeps its book and no shelf below can show it.
    @Test
    fun `the hero keeps its book and claims it before any shelf`() {
        val hero = block(
            ListenComposer.BlockId.HERO,
            "Продовжити слухати",
            listOf("fixture-book-neuromancer")
        )
        val almostDone = block(
            ListenComposer.BlockId.ALMOST_DONE,
            "Майже дочитали",
            listOf("fixture-book-neuromancer", "fixture-book-1984")
        )

        val result = deduplicateListenShelves(listOf(hero, almostDone))

        assertEquals(hero, result[0]) // hero untouched
        assertEquals(listOf("fixture-book-1984"), result[1].books.map { it.book.id })
    }

    // The hero's claim happens before the walk, not positionally: even when
    // a shelf appears ABOVE the hero in the list, the hero's book is still
    // claimed first and never renders in any shelf.
    @Test
    fun `the hero claims its book regardless of its position in the list`() {
        val hero = block(
            ListenComposer.BlockId.HERO,
            "Продовжити слухати",
            listOf("fixture-book-neuromancer")
        )
        val travel = block(
            ListenComposer.BlockId.TRAVEL,
            "Готово до поїздки",
            listOf("fixture-book-neuromancer", "fixture-book-fahrenheit")
        )

        val result = deduplicateListenShelves(listOf(travel, hero))

        assertEquals(listOf("fixture-book-fahrenheit"), result[0].books.map { it.book.id })
        assertEquals(hero, result[1]) // hero untouched
    }

    @Test
    fun `a block emptied by dedup carries no books`() {
        val travel = block(
            ListenComposer.BlockId.TRAVEL,
            "Готово до поїздки",
            listOf("fixture-book-neuromancer")
        )
        val short = block(
            ListenComposer.BlockId.SHORT,
            "Щось коротке",
            listOf("fixture-book-neuromancer")
        )

        val result = deduplicateListenShelves(listOf(travel, short))

        assertTrue("the second shelf must render nothing", result[1].books.isEmpty())
    }

    // Display-order claiming is observable through the RESULTS: the higher
    // shelf keeps its books in order, the lower shelf loses what was claimed.
    @Test
    fun `each book is claimed exactly once in display order`() {
        val a = block(
            ListenComposer.BlockId.ALMOST_DONE,
            "Майже дочитали",
            listOf("fixture-book-neuromancer", "fixture-book-1984")
        )
        val b = block(
            ListenComposer.BlockId.TRAVEL,
            "Готово до поїздки",
            listOf("fixture-book-1984", "fixture-book-fahrenheit")
        )

        val result = deduplicateListenShelves(listOf(a, b))

        // The claimed set is internal (spec-28 #200) — the visible proof is
        // that no book appears twice across the shelves.
        val allIds = result.flatMap { it.books.map { book -> book.book.id } }
        assertEquals(allIds.size, allIds.toSet().size)
        assertEquals(
            listOf("fixture-book-neuromancer", "fixture-book-1984", "fixture-book-fahrenheit"),
            allIds
        )
    }
}
