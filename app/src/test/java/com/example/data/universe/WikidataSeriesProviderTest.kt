package com.example.data.universe

import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-25 T2 (#173) — fixture tests for [WikidataSeriesProvider] against
 * canned API responses (prior art: source-adapter fixture tests). The
 * fixture world mirrors the Abercrombie anchor: work Q1 («Трохи ненависті»,
 * author Q10 «Блейк Крауч») → P179 series Q100 «Епоха божевілля», which
 * follows Q101 «Перший закон» — the chain [Перший закон, Епоха божевілля].
 */
class WikidataSeriesProviderTest {

    private fun entityJson(entities: Map<String, String>): String {
        val body = entities.entries.joinToString(",") { (qid, json) -> "\"$qid\":$json" }
        return """{"entities":{$body}}"""
    }

    private fun searchJson(vararg ids: String): String =
        """{"search":[${ids.joinToString(",") { """{"id":"$it"}""" }}]}"""

    private fun claimJson(vararg claims: Pair<String, List<String>>): String =
        claims.joinToString(",") { (property, ids) ->
            "\"$property\":[${ids.joinToString(",") { """{"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":"$it"}}}}""" }}]"
        }

    private fun labelsJson(vararg labels: Pair<String, String>): String =
        labels.joinToString(",") { (lang, value) -> "\"$lang\":{\"language\":\"$lang\",\"value\":\"$value\"}" }

    /** The work, its author and the two-series chain (Abercrombie anchor). */
    private fun fixtureResponses(): Map<String, String> {
        val searchUrl = searchUrl("uk", "A Little Hatred")
        return mapOf(
            searchUrl to searchJson("Q1"),
            entityUrl("Q1") to entityJson(
                mapOf(
                    "Q1" to """{"labels":{${labelsJson("uk" to "Трохи ненависті")}},"claims":{${claimJson("P50" to listOf("Q10"), "P179" to listOf("Q100"))}}}"""
                )
            ),
            entityUrl("Q10") to entityJson(
                mapOf("Q10" to """{"labels":{${labelsJson("uk" to "Блейк Крауч")}}}""")
            ),
            entityUrl("Q100") to entityJson(
                mapOf("Q100" to """{"labels":{${labelsJson("uk" to "Епоха божевілля")}},"claims":{${claimJson("P155" to listOf("Q101"))}}}""")
            ),
            entityUrl("Q101") to entityJson(
                mapOf("Q101" to """{"labels":{${labelsJson("uk" to "Перший закон")}},"claims":{${claimJson("P156" to listOf("Q100"))}}}""")
            )
        )
    }

    private fun provider(responses: Map<String, String>): WikidataSeriesProvider =
        WikidataSeriesProvider(fetchJson = { url -> responses[url] ?: "" })

    private fun searchUrl(language: String, title: String): String =
        "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json" +
            "&language=$language&uselang=$language&type=item&search=${URLEncoder.encode(title, "UTF-8")}"

    private fun entityUrl(ids: String): String =
        "https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&ids=$ids&props=claims|labels"

    // ---------------------------------------------------------------------
    // The acceptance path: book → series → precedes/follows chain
    // ---------------------------------------------------------------------

    @Test
    fun `resolves a book to its series universe with the precedes-follows chain`() = runBlocking {
        val resolution = provider(fixtureResponses()).resolve("A Little Hatred", "Блейк Крауч")!!

        // The chain head names the universe and anchors its id.
        assertEquals("wd:Q101", resolution.universe.id)
        assertEquals("Перший закон", resolution.universe.name)
        // The ordered chain, the book's series at position 2.
        assertEquals(listOf("Перший закон", "Епоха божевілля"), resolution.universe.series.map { it.title })
        assertEquals("Епоха божевілля", resolution.matchedSeries.title)
        assertEquals(2, resolution.position)
    }

    @Test
    fun `a single-series chain resolves a universe of one series`() = runBlocking {
        val responses = fixtureResponses().toMutableMap()
        // Q100 stands alone: no P155/P156.
        responses[entityUrl("Q100")] = entityJson(
            mapOf("Q100" to """{"labels":{${labelsJson("uk" to "Гіперіон")}},"claims":{}}""")
        )
        responses[entityUrl("Q101")] = ""

        val resolution = provider(responses).resolve("A Little Hatred", "Блейк Крауч")!!

        assertEquals("wd:Q100", resolution.universe.id)
        assertEquals("Гіперіон", resolution.universe.name)
        assertEquals(listOf("Гіперіон"), resolution.universe.series.map { it.title })
        assertEquals(1, resolution.position)
    }

    // ---------------------------------------------------------------------
    // Candidate verification (P50 must agree with the book's author)
    // ---------------------------------------------------------------------

    @Test
    fun `an author mismatch blocks the resolution`() = runBlocking {
        val responses = fixtureResponses().toMutableMap()
        responses[entityUrl("Q10")] = entityJson(
            mapOf("Q10" to """{"labels":{${labelsJson("uk" to "Інший автор")}}}""")
        )

        assertNull(provider(responses).resolve("A Little Hatred", "Блейк Крауч"))
    }

    @Test
    fun `ambiguity - no candidate author agrees resolves to nothing`() = runBlocking {
        val responses = fixtureResponses().toMutableMap()
        // Two candidates; neither author matches the book's author.
        responses[searchUrl("uk", "A Little Hatred")] = searchJson("Q1", "Q2")
        responses[entityUrl("Q1")] = entityJson(
            mapOf("Q1" to """{"labels":{},"claims":{${claimJson("P50" to listOf("Q20"))}}}""")
        )
        responses[entityUrl("Q2")] = entityJson(
            mapOf("Q2" to """{"labels":{},"claims":{${claimJson("P50" to listOf("Q20"))}}}""")
        )
        responses[entityUrl("Q20")] = entityJson(
            mapOf("Q20" to """{"labels":{${labelsJson("uk" to "Ще хтось")}}}""")
        )

        assertNull(provider(responses).resolve("A Little Hatred", "Блейк Крауч"))
    }

    // ---------------------------------------------------------------------
    // Silent no-ops: no network, no candidates, no series
    // ---------------------------------------------------------------------

    @Test
    fun `a network failure contributes nothing`() = runBlocking {
        assertNull(provider(emptyMap()).resolve("A Little Hatred", "Блейк Крауч"))
    }

    @Test
    fun `no candidates resolve to nothing`() = runBlocking {
        val responses = mapOf(searchUrl("uk", "A Little Hatred") to searchJson())
        assertNull(provider(responses).resolve("A Little Hatred", "Блейк Крауч"))
    }

    @Test
    fun `a work without a series resolves to nothing`() = runBlocking {
        val responses = fixtureResponses().toMutableMap()
        // Q1 loses its P179 — the work is not part of any series.
        responses[entityUrl("Q1")] = entityJson(
            mapOf("Q1" to """{"labels":{},"claims":{${claimJson("P50" to listOf("Q10"))}}}""")
        )

        assertNull(provider(responses).resolve("A Little Hatred", "Блейк Крауч"))
    }

    @Test
    fun `blank inputs resolve to nothing without a fetch`() = runBlocking {
        val responses = fixtureResponses()
        assertNull(provider(responses).resolve("", "Блейк Крауч"))
        assertNull(provider(responses).resolve("A Little Hatred", "  "))
    }

    // ---------------------------------------------------------------------
    // Language fallback: uk → ru → en
    // ---------------------------------------------------------------------

    @Test
    fun `search falls back from uk to ru when uk has no hits`() = runBlocking {
        val responses = fixtureResponses().toMutableMap()
        // uk has no hits; ru finds the work.
        responses[searchUrl("uk", "A Little Hatred")] = searchJson()
        responses[searchUrl("ru", "A Little Hatred")] = searchJson("Q1")

        val resolution = provider(responses).resolve("A Little Hatred", "Блейк Крауч")!!

        assertEquals("Перший закон", resolution.universe.name)
    }
}
