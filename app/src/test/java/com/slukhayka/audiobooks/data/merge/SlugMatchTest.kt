package com.slukhayka.audiobooks.data.merge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-49 follow-up — the slug matcher: a Work's Cyrillic title/author against
 * a transliterated slug. Pinned with the real shapes measured in the
 * 2026-09-10 sitemap spike (co.ua `igra-dzheralda-stiven-king`, chytaylo
 * `siddhartha`), plus the honest negatives.
 */
class SlugMatchTest {

    @Test
    fun `transliteration variants cover the ambiguous letters`() {
        val variants = SlugMatch.variants("джеральда")
        assertTrue("dzheralda must be a variant, got $variants", "dzheralda" in variants)
        assertTrue("gesse must be a variant of гессе", "gesse" in SlugMatch.variants("гессе"))
        assertTrue("king must be a variant of кінг", "king" in SlugMatch.variants("кінг"))
    }

    @Test
    fun `latin tokens pass through unchanged`() {
        assertTrue("king" in SlugMatch.variants("king"))
    }

    @Test
    fun `the chytaylo title-only slug matches its cyrillic work`() {
        assertTrue(SlugMatch.slugMatches("siddhartha", "Сіддгартха", "Герман Гессе"))
    }

    @Test
    fun `the co ua title-author slug matches through transliteration drift`() {
        assertTrue(
            SlugMatch.slugMatches(
                "igra-dzheralda-stiven-king",
                "Ігри Джеральда",
                "Стівен Кінг"
            )
        )
        assertTrue(
            SlugMatch.slugMatches(
                "zapiznila-rozplata-agata-kristi",
                "Запізніла розплата",
                "Агата Крісті"
            )
        )
    }

    @Test
    fun `a different work never matches`() {
        assertFalse(SlugMatch.slugMatches("siddhartha", "Кобзар", "Тарас Шевченко"))
        assertFalse(SlugMatch.slugMatches("igra-dzheralda-stiven-king", "Кобзар", "Тарас Шевченко"))
    }
}
