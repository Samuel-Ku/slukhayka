package com.slukhayka.audiobooks.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1049 — the permutation behind the preview's ↑ / ↓ buttons.
 *
 * The buttons are the only UI the chapter order has, so this is where the
 * index arithmetic is decided. It is a plain `List<Int>` on purpose: the
 * planner's [com.slukhayka.audiobooks.data.imports.ImportPlanner.reorderChapters]
 * takes a permutation of the CURRENT chapter indices and refuses anything
 * else, so a bug here would show up as a silently UNCHANGED plan — the
 * listener taps «Вгору» and nothing moves.
 *
 * A move must also be a real move of the CHAPTER, not of its label: ADR-0007
 * pairs chapter→track by index, so the permutation is the whole safeguard
 * against playing the wrong audio.
 */
class ImportPreviewMovedOrderTest {

    @Test
    fun `moving a chapter up swaps it with its predecessor only`() {
        assertEquals(listOf(1, 0, 2, 3), movedOrder(size = 4, from = 1, to = 0))
        assertEquals(listOf(0, 2, 1, 3), movedOrder(size = 4, from = 2, to = 1))
    }

    @Test
    fun `moving a chapter down swaps it with its successor only`() {
        assertEquals(listOf(1, 0, 2, 3), movedOrder(size = 4, from = 0, to = 1))
        assertEquals(listOf(0, 1, 3, 2), movedOrder(size = 4, from = 2, to = 3))
    }

    @Test
    fun `the first chapter cannot move up and the last cannot move down`() {
        // The UI disables those buttons; the function is the second line of
        // defence and must not produce a broken permutation if one is called.
        assertEquals(listOf(0, 1, 2), movedOrder(size = 3, from = 0, to = -1))
        assertEquals(listOf(0, 1, 2), movedOrder(size = 3, from = 2, to = 3))
    }

    @Test
    fun `the result is always a permutation of the same indices`() {
        // The planner REFUSES a list that is not a permutation of
        // `chapters.indices`, and refusal is silent (it returns the plan
        // unchanged). So every call the UI can make must satisfy it.
        for (size in 1..6) {
            for (from in 0 until size) {
                for (to in 0 until size) {
                    val order = movedOrder(size, from, to)
                    assertEquals(
                        "не перестановка для size=$size from=$from to=$to: $order",
                        (0 until size).toList(),
                        order.sorted()
                    )
                }
            }
        }
    }

    @Test
    fun `a legal move really changes the order`() {
        // A "fix" that returned the identity for legal moves would pass the
        // permutation check above while making the buttons do nothing.
        val order = movedOrder(size = 3, from = 0, to = 1)
        assertTrue("легальний рух мусить щось міняти", order != listOf(0, 1, 2))
    }
}
