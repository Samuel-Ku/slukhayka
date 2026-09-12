package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.merge.MergeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-49 follow-up — the local Work index: an exact MergeKey entry (a
 * catalogue card) wins over the fuzzy slug rule, and the slug rule still
 * matches transliterated sitemap URLs.
 */
class CatalogWorkIndexTest {

    @Test
    fun `an exact mergeKey card entry wins over a slug entry`() {
        val key = MergeKey.keyFor("Лісова пісня", "Леся Українка")
        val index = CatalogWorkIndex(
            listOf(
                CatalogIndexEntry("audiobookcoua", "https://audiobook.co.ua/lisova-pisnia-lesia-ukrainka/", "lisova-pisnia-lesia-ukrainka"),
                CatalogIndexEntry("knigionline", "https://knigi-online.com.ua/audioknyha-lisova-pisnia/", "", mergeKey = key)
            )
        )

        assertEquals("knigionline", index.lookup("Лісова пісня", "Леся Українка")?.sourceId)
    }

    @Test
    fun `a slug entry matches a cyrillic work through transliteration`() {
        val index = CatalogWorkIndex(
            listOf(
                CatalogIndexEntry("audiobookcoua", "https://audiobook.co.ua/igra-dzheralda-stiven-king/", "igra-dzheralda-stiven-king")
            )
        )

        assertEquals("audiobookcoua", index.lookup("Ігри Джеральда", "Стівен Кінг")?.sourceId)
        assertNull(index.lookup("Кобзар", "Тарас Шевченко"))
    }

    // --- #526 — the bounded three-candidate action -------------------------

    @Test
    fun `candidates stay on one source and never exceed three`() {
        val key = MergeKey.keyFor("Ігри Джеральда", "Стівен Кінг")
        val index = CatalogWorkIndex(
            listOf(
                CatalogIndexEntry("knigionline", "https://knigi-online.com.ua/a/", "", mergeKey = key),
                CatalogIndexEntry("knigionline", "https://knigi-online.com.ua/b/", "", mergeKey = key),
                CatalogIndexEntry("knigionline", "https://knigi-online.com.ua/c/", "", mergeKey = key),
                CatalogIndexEntry("knigionline", "https://knigi-online.com.ua/d/", "", mergeKey = key),
                CatalogIndexEntry("audiobookcoua", "https://audiobook.co.ua/igra/", "igra", mergeKey = key)
            )
        )

        val candidates = index.candidates("Ігри Джеральда", "Стівен Кінг")

        assertEquals(3, candidates.size)
        assertTrue("one source per action", candidates.all { it.sourceId == "knigionline" })
        assertEquals(
            listOf(
                "https://knigi-online.com.ua/a/",
                "https://knigi-online.com.ua/b/",
                "https://knigi-online.com.ua/c/"
            ),
            candidates.map { it.url }
        )
    }

    @Test
    fun `exact mergeKey candidates come first and dedupe`() {
        val key = MergeKey.keyFor("Лісова пісня", "Леся Українка")
        val index = CatalogWorkIndex(
            listOf(
                CatalogIndexEntry("knigionline", "https://knigi-online.com.ua/a/", "", mergeKey = key),
                CatalogIndexEntry("knigionline", "https://knigi-online.com.ua/a/", "", mergeKey = key),
                CatalogIndexEntry("knigionline", "https://knigi-online.com.ua/b/", "", mergeKey = key),
                CatalogIndexEntry("audiobookcoua", "https://audiobook.co.ua/lisova-pisnia/", "lisova-pisnia")
            )
        )

        val candidates = index.candidates("Лісова пісня", "Леся Українка")

        assertEquals(listOf(
            "https://knigi-online.com.ua/a/",
            "https://knigi-online.com.ua/b/"
        ), candidates.map { it.url })
    }

    @Test
    fun `no match and a zero limit yield nothing`() {
        val index = CatalogWorkIndex(
            listOf(CatalogIndexEntry("audiobookcoua", "https://audiobook.co.ua/a/", "a"))
        )

        assertTrue(index.candidates("Кобзар", "Тарас Шевченко").isEmpty())
        assertTrue(index.candidates("Ігри Джеральда", "Стівен Кінг", limit = 0).isEmpty())
    }
}
