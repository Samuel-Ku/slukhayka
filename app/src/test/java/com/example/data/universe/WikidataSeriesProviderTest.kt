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

    private fun cirrusJson(vararg ids: String): String =
        """{"query":{"search":[${ids.joinToString(",") { """{"title":"$it"}""" }}]}}"""

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
        translator: TitleTranslator? = null,
        statusByUrl: (String) -> Int = { 200 },
        maxAttempts: Int = 3,
        retryDelayMs: (Int) -> Long = { 0 }
    ): WikidataSeriesProvider =
        WikidataSeriesProvider(
            // Mirrors HttpFetcher.getTextResult: the body is "" on any
            // non-200 status, so a 429/5xx fixture never leaks a body.
            fetch = { url ->
                val status = statusByUrl(url)
                WikidataResponse(status, if (status == 200) responses[url] ?: "" else "")
            },
            translator = translator,
            maxAttempts = maxAttempts,
            retryDelayMs = retryDelayMs
        )

    private fun searchUrl(language: String, title: String): String =
        "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json" +
            "&language=$language&uselang=$language&type=item&search=${URLEncoder.encode(title, "UTF-8")}"

    private fun authorSearchUrl(language: String, author: String): String =
        "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json" +
            "&language=$language&uselang=$language&type=item&search=${URLEncoder.encode(author, "UTF-8")}"

    /** The CirrusSearch URL the provider builds (spec-26 T2): the author's
     *  P50 constraint + the normalized title tokens. */
    private fun cirrusUrl(authorQid: String, title: String): String {
        // Mirrors MergeKey.normalizeTitle (subtitle cuts + punctuation strip).
        val tokens = title
            .substringBefore(':')
            .substringBefore('—')
            .substringBefore('–')
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N} ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return "https://www.wikidata.org/w/api.php?action=query&list=search&format=json" +
            "&srnamespace=0&srlimit=3" +
            "&srsearch=${URLEncoder.encode("haswbstatement:P50=$authorQid $tokens", "UTF-8")}"
    }

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
    // Failure diagnostics (spec-26 T4): the residual harness classifies
    // catalog misses by cause through the optional callback
    // ---------------------------------------------------------------------

    private fun diagnosticsOf(
        responses: Map<String, String>,
        configure: (WikidataSeriesProvider) -> WikidataSeriesProvider = { it }
    ): List<ResolutionDiagnostic> {
        val seen = mutableListOf<ResolutionDiagnostic>()
        val provider = configure(
            WikidataSeriesProvider(
                fetch = { url ->
                    val body = responses[url]
                    if (body != null) WikidataResponse(200, body) else WikidataResponse(0, "")
                },
                retryDelayMs = { 0 },
                diagnostic = { seen += it }
            )
        )
        runBlocking { provider.resolve("A Little Hatred", "Блейк Крауч") }
        return seen
    }

    @Test
    fun `a search miss is diagnosed as not-on-wikidata`() = runBlocking {
        // Empty direct searches in every language (no translated fallback
        // configured) — the work is simply not on Wikidata.
        val responses = fixtureResponses().toMutableMap()
        for (language in listOf("uk", "ru", "en")) {
            responses[searchUrl(language, "A Little Hatred")] = searchJson()
        }

        assertEquals(listOf(ResolutionDiagnostic.SEARCH_MISS), diagnosticsOf(responses))
    }

    @Test
    fun `a candidate whose author disagrees is diagnosed as author-mismatch`() = runBlocking {
        val responses = fixtureResponses().toMutableMap()
        responses[entityUrl("Q10")] = entityJson(
            mapOf("Q10" to """{"labels":{${labelsJson("uk" to "Інший автор")}}}""")
        )

        assertEquals(listOf(ResolutionDiagnostic.AUTHOR_MISMATCH), diagnosticsOf(responses))
    }

    @Test
    fun `a work without a series claim is diagnosed as no-series`() = runBlocking {
        val responses = fixtureResponses().toMutableMap()
        // Q1 loses its P179 — the work is not part of any series.
        responses[entityUrl("Q1")] = entityJson(
            mapOf("Q1" to """{"labels":{},"claims":{${claimJson("P50" to listOf("Q10"))}}}""")
        )

        assertEquals(listOf(ResolutionDiagnostic.NO_SERIES_CLAIM), diagnosticsOf(responses))
    }

    @Test
    fun `an exhausted 429 budget is diagnosed as throttled`() = runBlocking {
        val seen = mutableListOf<ResolutionDiagnostic>()
        val provider = WikidataSeriesProvider(
            fetch = { WikidataResponse(429, "") },
            maxAttempts = 3,
            retryDelayMs = { 0 },
            diagnostic = { seen += it }
        )

        provider.resolve("A Little Hatred", "Блейк Крауч")

        // One exhausted-429 per language of the author ladder (searchByAuthor
        // → resolveAuthorQid) and one per language of the title ladder
        // (search), then the terminal SEARCH_MISS — the harness classifies
        // throttled-first.
        assertEquals(
            List(6) { ResolutionDiagnostic.THROTTLED } + ResolutionDiagnostic.SEARCH_MISS,
            seen
        )
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

    // ---------------------------------------------------------------------
    // Spec-26 T2 (#176): the author-aware search (P50 constraint + title)
    // ---------------------------------------------------------------------

    /**
     * The author-aware fixture world: the author resolves to Q10 (verified by
     * its label), the author-narrowed CirrusSearch returns the work Q1, and
     * Q1 joins the same Q100/Q101 chain as the anchor fixture. The plain
     * title search URLs are deliberately ABSENT — a resolution can only
     * succeed through the author pass, proving the query carried both the
     * author (P50) and the title tokens.
     */
    private fun authorFixture(title: String, author: String): MutableMap<String, String> {
        val responses = mutableMapOf<String, String>()
        responses[authorSearchUrl("uk", author)] = searchJson("Q10")
        responses[entityUrl("Q10")] = entityJson(
            mapOf("Q10" to """{"labels":{${labelsJson("uk" to author)}}}""")
        )
        responses[cirrusUrl("Q10", title)] = cirrusJson("Q1")
        responses[entityUrl("Q1")] = entityJson(
            mapOf(
                "Q1" to """{"labels":{${labelsJson("uk" to title)}},"claims":{${claimJson("P50" to listOf("Q10"), "P179" to listOf("Q100"))}}}"""
            )
        )
        responses[entityUrl("Q100")] = entityJson(
            mapOf("Q100" to """{"labels":{${labelsJson("uk" to "Епоха божевілля")}},"claims":{${claimJson("P155" to listOf("Q101"))}}}""")
        )
        responses[entityUrl("Q101")] = entityJson(
            mapOf("Q101" to """{"labels":{${labelsJson("uk" to "Перший закон")}},"claims":{${claimJson("P156" to listOf("Q100"))}}}""")
        )
        return responses
    }

    @Test
    fun `the author-narrowed search resolves an ambiguous title to the right work`() = runBlocking {
        val title = "A Little Hatred"
        val author = "Блейк Крауч"
        // The title search URLs are absent — only the author-aware pass can
        // resolve, proving the query carried the author (P50) + title tokens.
        val responses = authorFixture(title, author)

        val resolution = provider(responses).resolve(title, author)!!

        assertEquals("Перший закон", resolution.universe.name)
        assertEquals(listOf("Перший закон", "Епоха божевілля"), resolution.universe.series.map { it.title })
    }

    @Test
    fun `an author with apostrophes normalizes identically on both sides`() = runBlocking {
        val title = "Книга"
        val author = "Пат О'Браєн"
        val responses = authorFixture(title, author)

        // Both the author-search label («Пат О'Браєн») and the book author
        // normalize to the same key — the apostrophe is stripped on both
        // sides by the shared rule, so the author QID is accepted.
        val resolution = provider(responses).resolve(title, author)!!

        assertEquals("Перший закон", resolution.universe.name)
    }

    @Test
    fun `the author search falls back uk to ru when uk has no hits`() = runBlocking {
        val title = "A Little Hatred"
        val author = "Блейк Крауч"
        val responses = authorFixture(title, author)
        // uk finds no author; ru does.
        responses[authorSearchUrl("uk", author)] = searchJson()
        responses[authorSearchUrl("ru", author)] = searchJson("Q10")

        val resolution = provider(responses).resolve(title, author)!!

        assertEquals("Перший закон", resolution.universe.name)
    }

    @Test
    fun `a wrong author resolution falls through to the title pass`() = runBlocking {
        val title = "A Little Hatred"
        val author = "Блейк Крауч"
        val responses = authorFixture(title, author)
        // The author search surfaces Q20 whose label does not agree with the
        // book's author — the QID is rejected and the plain title pass
        // resolves instead (its URL is present in the fixture).
        responses[authorSearchUrl("uk", author)] = searchJson("Q20")
        responses[entityUrl("Q20")] = entityJson(
            mapOf("Q20" to """{"labels":{${labelsJson("uk" to "Інший автор")}}}""")
        )
        responses[searchUrl("uk", title)] = searchJson("Q1")

        val resolution = provider(responses).resolve(title, author)!!

        assertEquals("Перший закон", resolution.universe.name)
    }

    @Test
    fun `an unresolvable author with an empty title pass resolves to nothing`() = runBlocking {
        val title = "A Little Hatred"
        val author = "Блейк Крауч"
        val responses = mutableMapOf<String, String>()
        // No author search hit in any language, no title-search hit either.
        responses[authorSearchUrl("uk", author)] = searchJson()
        responses[authorSearchUrl("ru", author)] = searchJson()
        responses[authorSearchUrl("en", author)] = searchJson()

        assertNull(provider(responses).resolve(title, author))
    }

    // ---------------------------------------------------------------------
    // Spec-26 T3 (#177): the 429 retry (exponential backoff + jitter)
    // ---------------------------------------------------------------------

    @Test
    fun `a 429 series retries and resolves after the Nth attempt`() = runBlocking {
        val title = "A Little Hatred"
        val author = "Блейк Крауч"
        val responses = authorFixture(title, author)
        val cirrus = cirrusUrl("Q10", title)
        var cirrusAttempts = 0
        // The author-narrowed search 429s twice, then succeeds on the third.
        val statusByUrl: (String) -> Int = { url ->
            if (url == cirrus) {
                cirrusAttempts += 1
                if (cirrusAttempts <= 2) WikidataRetryPolicy.HTTP_TOO_MANY_REQUESTS else 200
            } else 200
        }

        val resolution = provider(responses, statusByUrl = statusByUrl, retryDelayMs = { 0 })
            .resolve(title, author)!!

        assertEquals("Перший закон", resolution.universe.name)
        assertEquals(3, cirrusAttempts)
    }

    @Test
    fun `a 429 series past the limit resolves to nothing silently`() = runBlocking {
        val title = "A Little Hatred"
        val author = "Блейк Крауч"
        val responses = authorFixture(title, author)
        val cirrus = cirrusUrl("Q10", title)
        var cirrusAttempts = 0
        // Always 429 — with maxAttempts 2 the second response is returned
        // as-is (empty body) and the resolution degrades silently.
        val statusByUrl: (String) -> Int = { url ->
            if (url == cirrus) {
                cirrusAttempts += 1
                WikidataRetryPolicy.HTTP_TOO_MANY_REQUESTS
            } else 200
        }

        assertNull(
            provider(responses, statusByUrl = statusByUrl, maxAttempts = 2, retryDelayMs = { 0 })
                .resolve(title, author)
        )
        assertEquals(2, cirrusAttempts)
    }

    @Test
    fun `a non-429 failure does not retry`() = runBlocking {
        val title = "A Little Hatred"
        val author = "Блейк Крауч"
        val responses = authorFixture(title, author)
        val cirrus = cirrusUrl("Q10", title)
        var cirrusAttempts = 0
        // A 500 is not rate-limited — exactly one attempt, then the title
        // pass (absent here) and a silent null.
        val statusByUrl: (String) -> Int = { url ->
            if (url == cirrus) {
                cirrusAttempts += 1
                500
            } else 200
        }

        assertNull(provider(responses, statusByUrl = statusByUrl, retryDelayMs = { 0 })
            .resolve(title, author))
        assertEquals(1, cirrusAttempts)
    }
}
