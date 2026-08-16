package com.slukhayka.audiobooks.data.universe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-25 (#171) — strict-decoder tests for [UniverseJson]: a well-formed
 * asset decodes fully (id, name, ordered series with aliases + urls); any
 * malformed shape (missing series, a bad entry, an empty series list)
 * decodes to null — a curated asset either parses fully or is absent.
 */
class UniverseJsonTest {

    @Test
    fun `a well-formed universe decodes with its ordered series`() {
        val universe = UniverseJson.decode(
            """
            {
              "id": "first-law",
              "name": "Перший закон",
              "series": [
                { "title": "Перший закон", "aliases": ["The First Law"], "urls": ["https://4read.org/xfsearch/cikl/pervyj-zakon/"] },
                { "title": "Епоха божевілля", "aliases": [], "urls": [] }
              ]
            }
            """.trimIndent()
        )!!

        assertEquals("first-law", universe.id)
        assertEquals("Перший закон", universe.name)
        assertEquals(2, universe.series.size)
        assertEquals("Перший закон", universe.series[0].title)
        assertEquals(listOf("The First Law"), universe.series[0].aliases)
        assertEquals(listOf("https://4read.org/xfsearch/cikl/pervyj-zakon/"), universe.series[0].urls)
        assertEquals("Епоха божевілля", universe.series[1].title)
    }

    @Test
    fun `missing aliases and urls default to empty lists`() {
        val universe = UniverseJson.decode("""{"id":"w","name":"Відьмак","series":[{"title":"Відьмак"}]}""")!!

        assertTrue(universe.series[0].aliases.isEmpty())
        assertTrue(universe.series[0].urls.isEmpty())
    }

    @Test
    fun `a malformed asset decodes to null - never crashes`() {
        assertNull(UniverseJson.decode("""{"id":"x","name":"Без серій"}""")) // no series key
        assertNull(UniverseJson.decode("""{"id":"x","name":"","series":[{"title":"A"}]}""")) // blank name
        assertNull(UniverseJson.decode("""{"id":"x","name":"X","series":[{"title":""}]}""")) // blank series title
        assertNull(UniverseJson.decode("""{"id":"x","name":"X","series":[]}""")) // empty series
        assertNull(UniverseJson.decode("not json at all"))
    }
}
