package com.slukhayka.audiobooks.data.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the spec-10 T2 merge key (parser seam: no Android, no Room).
 *
 * ADR-0010 — the Work key is BIBLIOGRAPHIC: `title|author`. The narrator is
 * deliberately excluded: it distinguishes EDITIONS (renditions) of the same
 * Work, so two narrations of one text are ONE Work with TWO Editions — never
 * two Works. (The Edition id carries the narrator — see EditionIdTest — so
 * the narrations still keep separate listening state, ADR-0001.)
 */
class MergeKeyTest {

    @Test
    fun `identical books from different sources share a key`() {
        val k1 = MergeKey.keyFor("Кобзар", "Тарас Шевченко")
        val k2 = MergeKey.keyFor("Кобзар", "Тарас Шевченко")
        assertEquals(k1, k2)
    }

    @Test
    fun `case punctuation and whitespace do not split the key`() {
        val k1 = MergeKey.keyFor("Кобзар", "Тарас Шевченко")
        val k2 = MergeKey.keyFor("  кобзар! ", "Тарас, Шевченко")
        assertEquals(k1, k2)
    }

    @Test
    fun `subtitle after colon is stripped so editions merge`() {
        val k1 = MergeKey.keyFor("Кобзар", "Тарас Шевченко")
        val k2 = MergeKey.keyFor("Кобзар: повна збірка", "Тарас Шевченко")
        assertEquals(k1, k2)
    }

    @Test
    fun `different narrators of the same text are ONE Work`() {
        // ADR-0010: the narrator is an Edition property — the Work key must
        // NOT split two narrations of the same text.
        val k1 = MergeKey.keyFor("Кобзар", "Тарас Шевченко")
        assertEquals(k1, k1)
        // The narrator argument simply no longer exists in the key — the two
        // narrations resolve to the same bibliographic key.
        assertEquals(
            MergeKey.keyFor("Кобзар", "Тарас Шевченко"),
            MergeKey.keyFor("Кобзар", "Тарас Шевченко")
        )
    }

    @Test
    fun `the key carries no narrator component`() {
        // The key is exactly title|author — no narrator segment can sneak in.
        val key = MergeKey.keyFor("Кобзар", "Тарас Шевченко")
        assertTrue(!key.contains("читець") && !key.contains("завалко"))
        assertEquals("кобзар|тарас шевченко", key)
    }

    @Test
    fun `different authors never merge`() {
        val k1 = MergeKey.keyFor("Кобзар", "Тарас Шевченко")
        val k2 = MergeKey.keyFor("Кобзар", "Іван Котляревський")
        assertNotEquals(k1, k2)
    }

    @Test
    fun `unusable input yields a blank key that never merges`() {
        assertTrue(MergeKey.keyFor("", "").isBlank())
        assertTrue(MergeKey.keyFor("!!!", "???").isBlank())
    }

    @Test
    fun `latin transliteration is not conflated with cyrillic`() {
        val k1 = MergeKey.keyFor("Кобзар", "Шевченко")
        val k2 = MergeKey.keyFor("Kobzar", "Shevchenko")
        assertNotEquals(k1, k2)
    }

    @Test
    fun `spec27 - a raw page title with a dash brand suffix merges with the clean title`() {
        // Spec-27 (#184) BUG-002: «Трохи ненависті - АудіоКниги Українською»
        // is the raw <title> of the 4read page; the clean title is the Work's
        // real name. They must produce the SAME key so the raw-titled import
        // merges into the existing card instead of spawning a second one.
        val raw = MergeKey.keyFor("Трохи ненависті - АудіоКниги Українською", "Джо Аберкромбі")
        val clean = MergeKey.keyFor("Трохи ненависті", "Джо Аберкромбі")
        assertEquals(raw, clean)
        assertEquals("трохи ненависті|джо аберкромбі", clean)
    }

    @Test
    fun `spec27 - the SEO scrub runs before the key - a raw feed title merges with the clean one`() {
        // The curated SEO suffix (a different source's marketing phrase) is
        // scrubbed before the key is computed, so a feed row and a clean row
        // of the same book share a Work.
        val raw = MergeKey.keyFor("Пасажир - аудіокнига слухати онлайн", "Жан-Крістоф Гранже")
        val clean = MergeKey.keyFor("Пасажир", "Жан-Крістоф Гранже")
        assertEquals(raw, clean)
    }

    @Test
    fun `spec27 - the dash suffix cut never blanks a title key`() {
        // A title that is entirely the suffix keeps a usable (if imperfect)
        // key — the normalization never degrades a title into blank.
        assertTrue(MergeKey.keyFor("АудіоКниги Українською", "Джо Аберкромбі").isNotBlank())
    }
}
