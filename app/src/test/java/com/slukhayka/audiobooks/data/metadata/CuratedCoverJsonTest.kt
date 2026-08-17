package com.slukhayka.audiobooks.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-30 T3 (#218) — pure JVM fixture tests for the curated-covers asset
 * decoder (no Robolectric — the decoder is the repo's own strict parser,
 * prior art: the collections decoder test).
 */
class CuratedCoverJsonTest {

    private val sample = """
        {
          "covers": [
            { "mergeKey": "кобзар|шевченко", "coverUrl": "https://4read.org/img/kobzar.jpg" },
            { "mergeKey": "лісова пісня|українка", "coverUrl": "https://4read.org/img/lisova.jpg" }
          ]
        }
    """.trimIndent()

    private val incomplete = """
        {
          "covers": [
            { "mergeKey": "кобзар|шевченко", "coverUrl": "https://4read.org/img/kobzar.jpg" },
            { "mergeKey": "лісова пісня|українка" }
          ]
        }
    """.trimIndent()

    @Test
    fun `decodes the curated asset shape`() {
        val covers = CuratedCoverJson.decode(sample)

        assertEquals(2, covers.size)
        assertEquals("кобзар|шевченко", covers[0].mergeKey)
        assertEquals("https://4read.org/img/kobzar.jpg", covers[0].coverUrl)
        assertEquals("лісова пісня|українка", covers[1].mergeKey)
    }

    @Test
    fun `an entry without a cover URL is dropped`() {
        val covers = CuratedCoverJson.decode(incomplete)

        assertEquals(1, covers.size)
        assertEquals("кобзар|шевченко", covers[0].mergeKey)
    }

    @Test
    fun `an empty covers list decodes to an empty list`() {
        assertTrue(CuratedCoverJson.decode("""{"covers": []}""").isEmpty())
    }

    @Test
    fun `a malformed document decodes to an empty list`() {
        assertTrue(CuratedCoverJson.decode("not json").isEmpty())
        assertTrue(CuratedCoverJson.decode("""{"covers": 42}""").isEmpty())
    }
}