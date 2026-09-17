package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** spec-54 T14 (#869) — one library card per Work, narrations inside it. */
class WorkBookCardsTest {

    private fun entity(id: String, mergeKey: String, narrator: String) = AudiobookEntity(
        id = id,
        title = "Кобзар",
        author = "Тарас Шевченко",
        narrator = narrator,
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = "local"
    ).apply { this.mergeKey = mergeKey }

    private fun row(
        id: String,
        mergeKey: String,
        narrator: String,
        position: Long = 0L,
        total: Long = 100L,
        started: Boolean = position > 0L
    ) = LibraryBook(
        book = entity(id, mergeKey, narrator),
        progress = if (started) {
            PlaybackProgressEntity(editionId = id, bookId = id, currentPositionSeconds = position)
        } else {
            null
        },
        cumulativePositionSeconds = position,
        totalDurationSeconds = total
    )

    @Test
    fun `several narrations of one Work are ONE card`() {
        val cards = workBookCards(
            listOf(
                row("a", "k", "Іван"),
                row("b", "k", "Петро"),
                row("c", "other", "Іван")
            )
        )

        assertEquals(2, cards.size)
        val card = cards.first { it.workKey == "k" }
        assertEquals(setOf("a", "b"), card.narrations.map { it.book.id }.toSet())
        assertTrue(card.hasSeveralNarrations)
    }

    @Test
    fun `the card speaks for the narration the listener is furthest along in`() {
        val cards = workBookCards(
            listOf(
                row("a", "k", "Іван", position = 90),
                row("b", "k", "Петро", position = 10)
            )
        )

        assertEquals("the furthest-progressed rendition fronts the card", "a", cards.single().primary.book.id)
    }

    @Test
    fun `without any progress the choice is deterministic, not input-order luck`() {
        val forward = workBookCards(listOf(row("z", "k", "Яків"), row("a", "k", "Іван")))
        val backward = workBookCards(listOf(row("a", "k", "Іван"), row("z", "k", "Яків")))

        assertEquals(
            "the same rows give the same card voice",
            forward.single().primary.book.id,
            backward.single().primary.book.id
        )
    }

    @Test
    fun `every narration keeps its OWN progress and facts`() {
        val first = row("a", "k", "Іван", position = 90)
        val second = row("b", "k", "Петро", position = 10)

        val card = workBookCards(listOf(first, second)).single()

        assertEquals(
            "the very same rows ride the card",
            setOf(first, second),
            card.narrations.toSet()
        )
        assertEquals(90L, card.narrations.first { it.book.id == "a" }.cumulativePositionSeconds)
        assertEquals(10L, card.narrations.first { it.book.id == "b" }.cumulativePositionSeconds)
        assertEquals(
            "each narration keeps its own progress row",
            "a",
            card.narrations.first { it.book.id == "a" }.progress!!.editionId
        )
    }

    @Test
    fun `an unmergeable row is its own card`() {
        val cards = workBookCards(
            listOf(row("x", "", "Іван"), row("y", "", "Іван"))
        )

        assertEquals("blank keys never share a card", 2, cards.size)
        assertTrue(cards.none { it.hasSeveralNarrations })
    }
}
