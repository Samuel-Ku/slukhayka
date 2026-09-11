package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.merge.MergeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
