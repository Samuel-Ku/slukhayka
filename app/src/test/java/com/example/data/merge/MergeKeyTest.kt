package com.example.data.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the spec-10 T2 merge key (parser seam: no Android, no Room).
 */
class MergeKeyTest {

    @Test
    fun `identical books from different sources share a key`() {
        val k1 = MergeKey.keyFor("Кобзар", "Тарас Шевченко", "Валерій Завалко")
        val k2 = MergeKey.keyFor("Кобзар", "Тарас Шевченко", "Валерій Завалко")
        assertEquals(k1, k2)
    }

    @Test
    fun `case punctuation and whitespace do not split the key`() {
        val k1 = MergeKey.keyFor("Кобзар", "Тарас Шевченко", "")
        val k2 = MergeKey.keyFor("  кобзар! ", "Тарас, Шевченко", "")
        assertEquals(k1, k2)
    }

    @Test
    fun `subtitle after colon is stripped so editions merge`() {
        val k1 = MergeKey.keyFor("Кобзар", "Тарас Шевченко", "")
        val k2 = MergeKey.keyFor("Кобзар: повна збірка", "Тарас Шевченко", "")
        assertEquals(k1, k2)
    }

    @Test
    fun `different narrators keep books separate`() {
        val k1 = MergeKey.keyFor("Кобзар", "Тарас Шевченко", "Валерій Завалко")
        val k2 = MergeKey.keyFor("Кобзар", "Тарас Шевченко", "Богдан Бенюк")
        assertNotEquals(k1, k2)
    }

    @Test
    fun `a listed narrator differentiates from a blank one`() {
        // Narration-sensitive merge: a source that lists a narrator cannot
        // prove it is the same recording as one that lists none.
        val k1 = MergeKey.keyFor("Кобзар", "Тарас Шевченко", "")
        val k2 = MergeKey.keyFor("Кобзар", "Тарас Шевченко", "Валерій Завалко")
        assertNotEquals(k1, k2)
    }

    @Test
    fun `blank narrator on both sides yields the same key`() {
        val k1 = MergeKey.keyFor("Кобзар", "Тарас Шевченко", "")
        val k2 = MergeKey.keyFor("Кобзар", "Тарас Шевченко", " ")
        assertEquals(k1, k2)
    }

    @Test
    fun `different authors never merge`() {
        val k1 = MergeKey.keyFor("Кобзар", "Тарас Шевченко", "")
        val k2 = MergeKey.keyFor("Кобзар", "Іван Котляревський", "")
        assertNotEquals(k1, k2)
    }

    @Test
    fun `unusable input yields a blank key that never merges`() {
        assertTrue(MergeKey.keyFor("", "", "").isBlank())
        assertTrue(MergeKey.keyFor("!!!", "???", "").isBlank())
    }

    @Test
    fun `latin transliteration is not conflated with cyrillic`() {
        val k1 = MergeKey.keyFor("Кобзар", "Шевченко", "")
        val k2 = MergeKey.keyFor("Kobzar", "Shevchenko", "")
        assertNotEquals(k1, k2)
    }
}
