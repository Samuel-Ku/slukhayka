package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #874 — one row shape for a person's page, from either source. */
class PersonWorkRowsTest {

    private fun work(id: String, title: String) = WorkEntity(
        id = id,
        mergeKey = "$id|author",
        title = title,
        author = "Тарас Шевченко"
    )

    private fun card(id: String, mergeKey: String, narrator: String, title: String = "Кобзар") =
        AudiobookEntity(
            id = id,
            title = title,
            author = "Тарас Шевченко",
            narrator = narrator,
            description = "",
            coverDrawableRes = 0,
            genre = "",
            sourceUrl = "source"
        ).apply { this.mergeKey = mergeKey }

    @Test
    fun `known works become rows with their own narrations, in title order`() {
        val rows = personWorkRows(
            works = listOf(work("w2", "Явір"), work("w1", "Барвінок")),
            ownedWorkIds = setOf("w1"),
            narrationsByWork = mapOf("w1" to listOf(card("e1", "w1|author", "Іван")))
        )

        assertEquals(listOf("w1", "w2"), rows.map { it.workId })
        assertTrue("owned is marked for BOTH roles", rows.first { it.workId == "w1" }.ownedInLibrary)
        assertFalse(rows.first { it.workId == "w2" }.ownedInLibrary)
        assertEquals("Іван", rows.first { it.workId == "w1" }.narrator)
    }

    @Test
    fun `source cards group into works by their merge key`() {
        val rows = personWorkRowsFromCards(
            cards = listOf(
                card("a", "k|author", "Іван"),
                card("b", "k|author", "Петро"),
                card("c", "other|author", "Іван", title = "Гайдамаки")
            ),
            ownedWorkIds = setOf("k|author")
        )

        assertEquals(2, rows.size)
        val kobzar = rows.first { it.workId == "k|author" }
        assertEquals(setOf("a", "b"), kobzar.narrations.map { it.id }.toSet())
        assertTrue(kobzar.hasSeveralNarrations)
        assertTrue("ownership uses the Work key", kobzar.ownedInLibrary)
    }

    @Test
    fun `a card without a Work key is its OWN row, never folded in`() {
        val rows = personWorkRowsFromCards(
            cards = listOf(card("a", "", "Іван"), card("b", "", "Іван")),
            ownedWorkIds = emptySet()
        )

        assertEquals("blank keys never share a row", 2, rows.size)
        assertFalse("and an unkeyed row can never be called owned", rows.any { it.ownedInLibrary })
    }

    @Test
    fun `every narration stays its own row with its own facts`() {
        val first = card("a", "k|author", "Іван")
        val second = card("b", "k|author", "Петро")

        val row = personWorkRowsFromCards(listOf(first, second), emptySet()).single()

        assertEquals(setOf(first, second), row.narrations.toSet())
    }
}
