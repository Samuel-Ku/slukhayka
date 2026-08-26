package com.slukhayka.audiobooks.data.authors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorIdentityTest {
    @Test
    fun `canonical identity normalizes Ukrainian case whitespace and apostrophe variants`() {
        val straight = AuthorIdentity.fromWorkName("  О'ГЕНРІ  ")
        val curly = AuthorIdentity.fromWorkName("О’Генрі")
        val modifier = AuthorIdentity.fromWorkName("ОʼГенрі")

        assertEquals("о’генрі", straight.normalizedName)
        assertEquals(straight.id, curly.id)
        assertEquals(straight.id, modifier.id)
        assertEquals("О'ГЕНРІ", straight.displayName)
    }

    @Test
    fun `identity never merges Latin and Cyrillic lookalikes without explicit evidence`() {
        val cyrillic = AuthorIdentity.fromWorkName("Андрій Кокотюха")
        val latinLookalike = AuthorIdentity.fromWorkName("Aндрій Кокотюха")

        assertNotEquals(cyrillic.id, latinLookalike.id)
    }

    @Test
    fun `explicit aliases stay bounded and searchable without changing canonical identity`() {
        val canonical = AuthorIdentity.fromAssertion(
            canonicalId = "author-lesia",
            displayName = "Леся Українка",
            aliases = listOf("Лариса Косач", "Лариса Петрівна Косач"),
            sourceId = "wikidata"
        )

        assertEquals("author-lesia", canonical.author.id)
        assertEquals(listOf("лариса косач", "лариса петрівна косач", "леся українка"), canonical.aliases.map { it.normalizedAlias }.sorted())
        assertTrue(canonical.aliases.all { it.sourceId == "wikidata" })
    }

    @Test
    fun `alias bound always retains the canonical name`() {
        val canonical = AuthorIdentity.fromAssertion(
            canonicalId = "author-lesia",
            displayName = "Леся Українка",
            aliases = (1..40).map { "Псевдонім $it" },
            sourceId = "metadata"
        )

        assertEquals(AuthorIdentity.MAX_ALIASES, canonical.aliases.size)
        assertTrue(canonical.aliases.any { it.normalizedAlias == "леся українка" })
    }

    @Test
    fun `Ukrainian diacritics are case folded but never discarded`() {
        val upper = AuthorIdentity.fromWorkName("ҐАБРІЄЛЬ ГАРСІЯ МАРКЕС")
        val lower = AuthorIdentity.fromWorkName("Ґабрієль Гарсія Маркес")
        val withoutDiacritics = AuthorIdentity.fromWorkName("Габриель Гарсия Маркес")

        assertEquals(upper.id, lower.id)
        assertEquals("ґабрієль гарсія маркес", upper.normalizedName)
        assertNotEquals(upper.id, withoutDiacritics.id)
    }
}
