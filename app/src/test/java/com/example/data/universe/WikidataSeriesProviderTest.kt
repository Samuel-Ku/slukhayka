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

    private fun provider(
        responses: Map<String, String>,
        translator: TitleTranslator? = null
    ): WikidataSeriesProvider =
        WikidataSeriesProvider(fetchJson = { url -> responses[url] ?: "" }, translator = translator)

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

    // ---------------------------------------------------------------------
    // Spec-26 T1 (#175): the translated-title fallback (ML Kit via seam)
    // ---------------------------------------------------------------------

    /**
     * The translated-path fixture mirrors the real Wikidata anchor: work
     * Q131414129 «Немного ненависти» (A Little Hatred — NO uk label, exactly
     * the fallback scenario), author Q515421 (Джо Аберкромбі, uk label),
     * P179 → Q133457075 «The Age of Madness» (en label only) which follows
     * Q7734153 «The First Law» — the chain [The First Law, The Age of
     * Madness].
     */
    private fun translatedFixture(bookTitle: String): MutableMap<String, String> {
        val responses = mutableMapOf<String, String>()
        // Direct search empty in every language (no uk label on Wikidata).
        responses[searchUrl("uk", bookTitle)] = searchJson()
        responses[searchUrl("ru", bookTitle)] = searchJson()
        responses[searchUrl("en", bookTitle)] = searchJson()
        // Translated ru search «Немного ненависти» finds the work.
        responses[searchUrl("ru", "Немного ненависти")] = searchJson("Q131414129")
        responses[entityUrl("Q131414129")] = entityJson(
            mapOf(
                "Q131414129" to """{"labels":{${labelsJson("ru" to "Немного ненависти")}},"claims":{${claimJson("P50" to listOf("Q515421"), "P179" to listOf("Q133457075"))}}}"""
            )
        )
        responses[entityUrl("Q515421")] = entityJson(
            mapOf("Q515421" to """{"labels":{${labelsJson("uk" to "Джо Аберкромбі")}}}""")
        )
        responses[entityUrl("Q133457075")] = entityJson(
            mapOf(
                "Q133457075" to """{"labels":{${labelsJson("en" to "The Age of Madness")}},"claims":{${claimJson("P155" to listOf("Q7734153"))}}}"""
            )
        )
        responses[entityUrl("Q7734153")] = entityJson(
            mapOf(
                "Q7734153" to """{"labels":{${labelsJson("en" to "The First Law")}},"claims":{${claimJson("P156" to listOf("Q133457075"))}}}"""
            )
        )
        return responses
    }

    /** Records every requested target language — pins when the fallback fires. */
    private class RecordingTranslator(
        private val results: Map<String, String?>
    ) : TitleTranslator {
        val calls = mutableListOf<String>()
        override suspend fun translate(text: String, targetLanguage: String): String? {
            calls += targetLanguage
            return results[targetLanguage]
        }
    }

    @Test
    fun `translated title resolves when direct search is empty - anchor ru nemnogo nenavisti to Q131414129`() = runBlocking {
        val bookTitle = "Трохи ненависті"
        val responses = translatedFixture(bookTitle)
        val translator = RecordingTranslator(mapOf("ru" to "Немного ненависти"))

        val resolution = provider(responses, translator).resolve(bookTitle, "Джо Аберкромбі")!!

        // The chain head names the universe and anchors its id.
        assertEquals("wd:Q7734153", resolution.universe.id)
        assertEquals("The First Law", resolution.universe.name)
        assertEquals(
            listOf("The First Law", "The Age of Madness"),
            resolution.universe.series.map { it.title }
        )
        assertEquals("The Age of Madness", resolution.matchedSeries.title)
        assertEquals(2, resolution.position)
        // The translator was asked for ru first (the anchor), en never tried
        // because ru already hit.
        assertEquals(listOf("ru"), translator.calls)
    }

    @Test
    fun `a direct search hit never triggers the translator`() = runBlocking {
        val responses = fixtureResponses() // uk search already finds Q1
        val translator = RecordingTranslator(mapOf("ru" to "Немного ненависти"))

        provider(responses, translator).resolve("A Little Hatred", "Блейк Крауч")!!

        assertEquals(emptyList<String>(), translator.calls)
    }

    @Test
    fun `a failing translator resolves to nothing silently`() = runBlocking {
        val bookTitle = "Трохи ненависті"
        val responses = translatedFixture(bookTitle)
        val translator = RecordingTranslator(mapOf("ru" to null, "en" to null))

        assertNull(provider(responses, translator).resolve(bookTitle, "Джо Аберкромбі"))
        // The fallback did fire (both targets consulted) but nothing resolved.
        assertEquals(listOf("ru", "en"), translator.calls)
    }

    @Test
    fun `author verification applies on the translated path too`() = runBlocking {
        val bookTitle = "Трохи ненависті"
        val responses = translatedFixture(bookTitle)
        // The work's author does not agree with the book's author.
        responses[entityUrl("Q515421")] = entityJson(
            mapOf("Q515421" to """{"labels":{${labelsJson("uk" to "Інший автор")}}}""")
        )

        assertNull(provider(responses, RecordingTranslator(mapOf("ru" to "Немного ненависти")))
            .resolve(bookTitle, "Джо Аберкромбі"))
    }

    @Test
    fun `an identity translation contributes nothing`() = runBlocking {
        val bookTitle = "Трохи ненависті"
        val responses = translatedFixture(bookTitle)
        // The translator returns the input unchanged — the translated search
        // would duplicate the direct ru/en passes, so it is skipped.
        val translator = RecordingTranslator(mapOf("ru" to bookTitle, "en" to bookTitle))

        assertNull(provider(responses, translator).resolve(bookTitle, "Джо Аберкромбі"))
        assertEquals(listOf("ru", "en"), translator.calls)
    }
}
