package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** spec-54 T14 (#869) — one personal card per Work, narrations inside it. */
class WorkCardsTest {

    private fun book(
        id: String,
        mergeKey: String,
        narrator: String
    ) = AudiobookEntity(
        id = id,
        title = "Кобзар",
        author = "Тарас Шевченко",
        narrator = narrator,
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = "local"
    ).apply { this.mergeKey = mergeKey }

    @Test
    fun `several narrations of one Work give ONE card`() {
        val cards = workCards(
            listOf(
                book("a", "кобзар|шевченко", "Іван"),
                book("b", "кобзар|шевченко", "Петро"),
                book("c", "інша|автор", "Іван")
            )
        )

        assertEquals(2, cards.size)
        val kobzar = cards.first { it.workKey == "кобзар|шевченко" }
        assertEquals(listOf("a", "b"), kobzar.narrations.map { it.id }.sorted())
        assertTrue(kobzar.hasSeveralNarrations)
        assertTrue(!cards.first { it.workKey == "інша|автор" }.hasSeveralNarrations)
    }

    @Test
    fun `a row without a Work is its own card and never merged`() {
        val cards = workCards(
            listOf(
                book("local-1", "", "Іван"),
                book("local-2", "", "Іван")
            )
        )

        assertEquals("blank keys never share a card", 2, cards.size)
        assertEquals(listOf("", ""), cards.map { it.workKey })
    }

    @Test
    fun `every narration is kept as its own row with its own facts`() {
        val first = book("a", "k", "Іван")
        val second = book("b", "k", "Петро")

        val card = workCards(listOf(first, second)).single()

        assertEquals(
            "the very same entities ride the card: progress, bookmarks," +
                " downloads and speed stay per narration",
            listOf(first, second).toSet(),
            card.narrations.toSet()
        )
    }

    @Test
    fun `the card keeps the listener's order and lists narrations stably`() {
        val cards = workCards(
            listOf(
                book("a", "друга|автор", "Іван"),
                book("b", "перша|автор", "Петро"),
                book("c", "перша|автор", "Іван")
            )
        )

        assertEquals(listOf("друга|автор", "перша|автор"), cards.map { it.workKey })
        assertEquals(
            "both narrations ride the card, whatever the input order",
            setOf("b", "c"),
            cards[1].narrations.map { it.id }.toSet()
        )
        // The ORDER is deterministic: the same set in, the same order out, even
        // when the rows arrive reversed.
        val reversed = workCards(
            listOf(
                book("c", "перша|автор", "Іван"),
                book("b", "перша|автор", "Петро"),
                book("a", "друга|автор", "Іван")
            )
        )
        assertEquals(
            "the same rows give the same order, not an input-order artefact",
            cards.first { it.workKey == "перша|автор" }.narrations.map { it.id },
            reversed.first { it.workKey == "перша|автор" }.narrations.map { it.id }
        )
    }
}
