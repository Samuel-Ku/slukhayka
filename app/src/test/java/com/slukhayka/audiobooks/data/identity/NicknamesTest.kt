package com.slukhayka.audiobooks.data.identity

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-40 #275 (t1) — the generated default nickname is pure: a seeded
 * Random makes it deterministic, and the format is always «Слухач-%04d»
 * (ASCII digits, manual padding).
 */
class NicknamesTest {

    @Test
    fun `a seeded generator produces deterministic nicknames`() {
        assertEquals(Nicknames.generate(Random(42)), Nicknames.generate(Random(42)))
        assertEquals(Nicknames.generate(Random(-7)), Nicknames.generate(Random(-7)))
    }

    @Test
    fun `the nickname always matches Слухач with four ASCII digits`() {
        repeat(200) { seed ->
            val nickname = Nicknames.generate(Random(seed))

            assertTrue("seed $seed → $nickname", nickname.matches(Regex("Слухач-\\d{4}")))
        }
    }

    @Test
    fun `the number stays within the four-digit range`() {
        repeat(200) { seed ->
            val number = Nicknames.generate(Random(seed)).substringAfter('-').toInt()

            assertTrue(number in 0 until Nicknames.RANGE)
            // Zero-padding is manual and locale-independent.
            assertEquals(number.toString().padStart(4, '0'), Nicknames.generate(Random(seed)).substringAfter('-'))
        }
    }

    @Test
    fun `different seeds do not collapse into one nickname`() {
        val distinct = (0 until 50).map { Nicknames.generate(Random(it)) }.toSet()

        assertTrue("only ${distinct.size} distinct nicknames over 50 seeds", distinct.size >= 30)
    }
}
