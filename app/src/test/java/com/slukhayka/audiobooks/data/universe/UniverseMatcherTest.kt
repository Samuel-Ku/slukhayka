package com.slukhayka.audiobooks.data.universe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-25 (#171) — pure JVM fixture tests for [UniverseMatcher]. The
 * Abercrombie case is the acceptance anchor: a book of the «Епоха
 * божевілля» cycle resolves to universe «Перший закон» at position 2,
 * preceded by «Перший закон». URL match wins over title match; normalized
 * titles (case, punctuation, trailing annotation) and aliases fall back.
 */
class UniverseMatcherTest {

    private val universes = listOf(
        UniverseList(
            id = "first-law",
            name = "Перший закон",
            series = listOf(
                UniverseSeries(
                    title = "Перший закон",
                    aliases = listOf("Трилогія Першого закону", "The First Law"),
                    urls = listOf("https://4read.org/xfsearch/cikl/pervyj-zakon/")
                ),
                UniverseSeries(
                    title = "Епоха божевілля",
                    aliases = listOf("The Age of Madness"),
                    urls = listOf("https://4read.org/xfsearch/cikl/epoha-bozhevillja/")
                )
            )
        ),
        UniverseList(
            id = "witcher",
            name = "Відьмак",
            series = listOf(
                UniverseSeries(
                    title = "Відьмак",
                    aliases = listOf("Сага про Відьмака", "The Witcher"),
                    urls = listOf("https://4read.org/xfsearch/cikl/vidmak/")
                )
            )
        )
    )

    // ---------------------------------------------------------------------
    // The acceptance anchor and title matching
    // ---------------------------------------------------------------------

    @Test
    fun `acceptance anchor - a book of the Age of Madness cycle resolves to the First Law universe at position 2`() {
        val match = UniverseMatcher.resolve(universes, seriesTitle = "Епоха божевілля", seriesUrl = null)!!

        assertEquals("first-law", match.universe.id)
        assertEquals("Перший закон", match.universe.name)
        assertEquals("Епоха божевілля", match.series.title)
        assertEquals(2, match.position)
    }

    @Test
    fun `normalized title matching folds case and trailing annotation`() {
        val match = UniverseMatcher.resolve(universes, "ЕПОХА БОЖЕВІЛЛЯ (цикл)", null)!!

        assertEquals("Епоха божевілля", match.series.title)
        assertEquals(2, match.position)
    }

    @Test
    fun `a title alias matches the series`() {
        val match = UniverseMatcher.resolve(universes, "The Age of Madness", null)!!

        assertEquals("Епоха божевілля", match.series.title)
        assertEquals(2, match.position)
    }

    @Test
    fun `the first series of a universe resolves at position 1`() {
        val match = UniverseMatcher.resolve(universes, "Перший закон", null)!!

        assertEquals(1, match.position)
    }

    // ---------------------------------------------------------------------
    // URL match wins over title match
    // ---------------------------------------------------------------------

    @Test
    fun `url match wins over title match`() {
        // A wrong (unseeded) title but the right series URL — the URL wins.
        val match = UniverseMatcher.resolve(
            universes,
            seriesTitle = "Невідомий цикл",
            seriesUrl = "https://4read.org/xfsearch/cikl/pervyj-zakon/"
        )!!

        assertEquals("Перший закон", match.series.title)
        assertEquals(1, match.position)
    }

    @Test
    fun `url matching folds case and the trailing slash`() {
        val match = UniverseMatcher.resolve(
            universes,
            seriesTitle = "Епоха божевілля",
            seriesUrl = "https://4read.org/xfsearch/cikl/EPOHA-BOZHEVILLJA"
        )!!

        assertEquals("Епоха божевілля", match.series.title)
        assertEquals(2, match.position)
    }

    // ---------------------------------------------------------------------
    // Unknown series → nothing
    // ---------------------------------------------------------------------

    @Test
    fun `an unseeded series resolves to nothing`() {
        assertNull(UniverseMatcher.resolve(universes, "Шерлок Холмс", null))
        // A wrong URL AND an unseeded title — nothing to fall back to.
        assertNull(UniverseMatcher.resolve(universes, "Невідомий цикл", "https://example.invalid/cycle"))
        // A wrong URL with a SEEDED title still resolves via the title
        // fallback — URL wins, the title is the fallback, not a gate.
        assertEquals(
            "Епоха божевілля",
            UniverseMatcher.resolve(universes, "Епоха божевілля", "https://example.invalid/cycle")!!.series.title
        )
    }

    @Test
    fun `a blank title resolves to nothing`() {
        assertNull(UniverseMatcher.resolve(universes, "", null))
        assertNull(UniverseMatcher.resolve(universes, "   ", null))
    }
}
