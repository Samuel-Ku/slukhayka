package com.slukhayka.audiobooks.data.bibliography

import com.slukhayka.audiobooks.data.source.InMemorySourceGateBudgetStore
import com.slukhayka.audiobooks.data.source.SourceBucketState
import com.slukhayka.audiobooks.data.source.SourceGateParams
import com.slukhayka.audiobooks.data.source.SourceRequestClass
import com.slukhayka.audiobooks.data.source.SourceRequestGate
import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * ADR-0053 / #857 — the Open Library provider, tested through its OUTSIDE
 * behavior only. Every payload is a VERBATIM live response captured
 * 2026-09-18 (except where a case is impossible to capture and is said so):
 * - `openlibrary-search-kobzar-2026-09-18.json` — `/search.json?q=Кобзар`
 * - `openlibrary-work-OL717725W-2026-09-18.json` — a work with year + covers
 * - `openlibrary-work-OL45456299W-2026-09-18.json` — a work with neither
 * - `openlibrary-isbn-9786177023202-2026-09-18.json` — an edition with `ukr`
 * - `openlibrary-isbn-9786170901491-2026-09-18.json` — a cover, no language
 *
 * No network anywhere: the shared fetcher serves canned text (ADR-0006), and
 * the politeness gate is a REAL [SourceRequestGate] with a fake clock and a
 * fake budget, so caching, deferral and single-flight are exercised for real
 * instead of being stubbed away.
 */
class OpenLibraryBibliographyTest {

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResource("fixtures/$name")
    ).readText()

    private class Harness(
        fallback: String = "",
        statusResponses: Map<String, Pair<Int, String>> = emptyMap(),
        params: SourceGateParams = SourceGateParams(
            refillIntervalMs = 1_000,
            listenerWaitCapMs = 500,
            jitterMinMs = 0,
            jitterMaxMs = 0
        )
    ) {
        var now = 1_000_000L
        val store = InMemorySourceGateBudgetStore()
        val fetcher = FakeFetcher(fallback = fallback, statusResponses = statusResponses)
        val provider = OpenLibraryBibliography(
            fetcher = fetcher,
            gate = SourceRequestGate(
                params = params,
                budgetStore = store,
                clock = { now },
                sleeper = { millis -> now += millis; delay(millis) },
                random = Random(7)
            )
        )

        /** The host's bucket is empty and refills only tomorrow. */
        fun drainBucket() {
            store.save("openlibrary.org", SourceBucketState(tokens = 0, lastRefillAtMs = now))
        }
    }

    private fun found(outcome: BibliographyOutcome<List<BibliographyCandidate>>): List<BibliographyCandidate> =
        (outcome as BibliographyOutcome.Found).value

    // --- the search door -----------------------------------------------------

    @Test
    fun `search maps the live response into normalized cards`() = runTest {
        val harness = Harness(fallback = fixture("openlibrary-search-kobzar-2026-09-18.json"))

        val cards = found(harness.provider.search("Кобзар"))

        assertEquals(5, cards.size)
        val first = cards[0]
        assertEquals("КОБЗАР", first.title)
        assertEquals(
            listOf("Елена Стойчева", "УкрСлово", "Тарас Григорович Шевченко"),
            first.authors
        )
        assertEquals(2022, first.firstPublishYear)
        assertEquals("9798847258173", first.isbn)
        assertEquals("/works/OL29170641W", first.workKey)
        assertEquals(listOf("uk"), first.languages)
        // This row states no cover — absence stays absence.
        assertNull(first.coverImageUrl)

        // The row with an edition list prefers the ISBN-13.
        val second = cards[1]
        assertEquals("Чигиринський Кобзар і Гайдамаки", second.title)
        assertEquals("9786177023202", second.isbn)
        assertEquals(2014, second.firstPublishYear)
    }

    @Test
    fun `a search row without a language claim stays visible with none`() = runTest {
        val harness = Harness(fallback = fixture("openlibrary-search-kobzar-2026-09-18.json"))

        val cards = found(harness.provider.search("Кобзар"))

        val coverlessClaim = cards.single { it.title == "Буду мову я вивчати" }
        assertTrue("unknown language never hides the row", coverlessClaim.languages.isEmpty())
        assertEquals(
            "https://covers.openlibrary.org/b/id/15229985-M.jpg",
            coverlessClaim.coverImageUrl
        )
    }

    @Test
    fun `the query carries no language filter and maps every live claim`() = runTest {
        val harness = Harness(fallback = fixture("openlibrary-search-kobzar-2026-09-18.json"))

        val cards = found(harness.provider.search("Кобзар"))

        val url = harness.fetcher.requestedUrls.single()
        assertTrue(url.startsWith("https://openlibrary.org/search.json?q="))
        assertFalse(
            "a language: clause would hide exactly the unknown-language rows",
            url.contains("language%3A")
        )
        // The multi-language work maps every known claim, in document order.
        val multilingual = cards.single { it.workKey == "/works/OL717725W" }
        assertEquals(listOf("pl", "ru", "hy", "en", "fr", "tr", "uk"), multilingual.languages)
    }

    @Test
    fun `an empty answer is a Found empty list, not a failure`() = runTest {
        val harness = Harness(fallback = """{"numFound":0,"docs":[]}""")

        assertEquals(emptyList<BibliographyCandidate>(), found(harness.provider.search("ніщо")))
    }

    @Test
    fun `a malformed body yields no candidates and never throws`() = runTest {
        val harness = Harness(fallback = """{"docs": [ this is not json""")

        assertEquals(emptyList<BibliographyCandidate>(), found(harness.provider.search("Кобзар")))
    }

    @Test
    fun `a failed fetch is Unavailable - never a fabricated card`() = runTest {
        val harness = Harness(fallback = "") // the fake's honest empty body

        assertEquals(BibliographyOutcome.Unavailable, harness.provider.search("Кобзар"))
    }

    @Test
    fun `a blank query asks the base nothing`() = runTest {
        val harness = Harness(fallback = fixture("openlibrary-search-kobzar-2026-09-18.json"))

        assertEquals(emptyList<BibliographyCandidate>(), found(harness.provider.search("   ")))
        assertTrue(harness.fetcher.requestedUrls.isEmpty())
    }

    // --- the work door -------------------------------------------------------

    @Test
    fun `work parses a live work document and invents no author`() = runTest {
        val harness = Harness(fallback = fixture("openlibrary-work-OL717725W-2026-09-18.json"))

        val card = found(harness.provider.work("/works/OL717725W")).single()

        assertEquals("Кобзар", card.title)
        assertEquals(1995, card.firstPublishYear)
        assertEquals("https://covers.openlibrary.org/b/id/6826629-M.jpg", card.coverImageUrl)
        assertEquals("/works/OL717725W", card.workKey)
        // OL work documents name author KEYS, not names — no name is invented.
        assertTrue(card.authors.isEmpty())
    }

    @Test
    fun `a live work without year cover or language keeps those fields empty`() = runTest {
        val harness = Harness(fallback = fixture("openlibrary-work-OL45456299W-2026-09-18.json"))

        val card = found(harness.provider.work("/works/OL45456299W")).single()

        assertEquals("Чигиринський Кобзар і Гайдамаки", card.title)
        assertEquals("/works/OL45456299W", card.workKey)
        assertNull(card.firstPublishYear)
        assertNull(card.coverImageUrl)
        assertNull(card.isbn)
        assertTrue(card.languages.isEmpty())
    }

    @Test
    fun `literal JSON nulls are tolerated and remain absent fields`() = runTest {
        // Crafted (no live work document carries every null at once): the
        // shared decoder rejects a literal null, so the provider must strip.
        val harness = Harness(
            fallback = """{"title":"Кобзар","covers":null,"languages":null,"first_publish_date":null}"""
        )

        val card = found(harness.provider.work("OL717725W")).single()

        assertEquals("Кобзар", card.title)
        assertNull(card.coverImageUrl)
        assertNull(card.firstPublishYear)
        assertTrue(card.languages.isEmpty())
    }

    @Test
    fun `an unusable work key asks the base nothing`() = runTest {
        val harness = Harness(fallback = fixture("openlibrary-work-OL717725W-2026-09-18.json"))

        assertEquals(emptyList<BibliographyCandidate>(), found(harness.provider.work("garbage")))
        assertEquals(emptyList<BibliographyCandidate>(), found(harness.provider.work("OL717725W-typo")))
        assertTrue(harness.fetcher.requestedUrls.isEmpty())
    }

    // --- the isbn door -------------------------------------------------------

    @Test
    fun `isbn resolves a live edition with its mapped language and work key`() = runTest {
        val harness = Harness(fallback = fixture("openlibrary-isbn-9786177023202-2026-09-18.json"))

        val card = found(harness.provider.isbn("978-617-702-3202")).single()

        assertEquals("Чигиринський Кобзар і Гайдамаки", card.title)
        assertEquals(2014, card.firstPublishYear)
        assertEquals("9786177023202", card.isbn)
        assertEquals("/works/OL45456299W", card.workKey)
        assertEquals(listOf("uk"), card.languages)
        assertNull(card.coverImageUrl)
        assertTrue(card.authors.isEmpty())
    }

    @Test
    fun `an edition with a cover and no language claim keeps the card`() = runTest {
        val harness = Harness(fallback = fixture("openlibrary-isbn-9786170901491-2026-09-18.json"))

        val card = found(harness.provider.isbn("9786170901491")).single()

        assertEquals("https://covers.openlibrary.org/b/id/15229985-M.jpg", card.coverImageUrl)
        assertEquals("/works/OL44356263W", card.workKey)
        assertTrue("unknown language never hides the row", card.languages.isEmpty())
    }

    @Test
    fun `an unusable isbn asks the base nothing`() = runTest {
        val harness = Harness(fallback = fixture("openlibrary-isbn-9786177023202-2026-09-18.json"))

        assertEquals(emptyList<BibliographyCandidate>(), found(harness.provider.isbn("not-an-isbn")))
        assertTrue(harness.fetcher.requestedUrls.isEmpty())
    }

    // --- #858 (T5): a 404 is an answer, a 5xx is a failure -------------------

    @Test
    fun `a 404 is the base positively not knowing the edition`() = runTest {
        // Live behavior (2026-09-19): an ISBN Open Library never had answers
        // 404 from /isbn/<isbn>.json — a KNOWLEDGE statement, which is the one
        // trigger the Google Books fallback waits for (#858).
        val harness = Harness(
            statusResponses = mapOf("https://openlibrary.org/isbn/9791234567890.json" to (404 to ""))
        )

        assertEquals(emptyList<BibliographyCandidate>(), found(harness.provider.isbn("9791234567890")))
    }

    @Test
    fun `a 5xx stays Unavailable so an outage never looks like absence`() = runTest {
        val harness = Harness(
            statusResponses = mapOf("https://openlibrary.org/isbn/9786177023202.json" to (500 to ""))
        )

        assertEquals(BibliographyOutcome.Unavailable, harness.provider.isbn("9786177023202"))
    }

    // --- cache, budget and the declared rhythm ------------------------------

    @Test
    fun `a repeated search is served from the gate cache with zero extra requests`() = runTest {
        val harness = Harness(fallback = fixture("openlibrary-search-kobzar-2026-09-18.json"))

        harness.provider.search("Кобзар")
        harness.provider.search("Кобзар")

        assertEquals(1, harness.fetcher.requestedUrls.size)
    }

    @Test
    fun `the work and isbn doors cache by their own key`() = runTest {
        val harness = Harness(fallback = fixture("openlibrary-work-OL717725W-2026-09-18.json"))

        harness.provider.work("/works/OL717725W")
        harness.provider.work("https://openlibrary.org/works/OL717725W.json")
        assertEquals(1, harness.fetcher.requestedUrls.size)

        harness.provider.work("/works/OL45456299W")
        assertEquals(2, harness.fetcher.requestedUrls.size)
        assertEquals(
            listOf(
                "https://openlibrary.org/works/OL717725W.json",
                "https://openlibrary.org/works/OL45456299W.json"
            ),
            harness.fetcher.requestedUrls.toList()
        )
    }

    @Test
    fun `a resting budget is the honest Deferred state`() = runTest {
        val harness = Harness(
            fallback = fixture("openlibrary-search-kobzar-2026-09-18.json"),
            params = SourceGateParams(
                refillIntervalMs = 60_000,
                listenerWaitCapMs = 1_000,
                jitterMinMs = 0,
                jitterMaxMs = 0
            )
        )
        harness.drainBucket()

        val outcome = harness.provider.search("Кобзар")

        assertTrue("a dry bucket must not fabricate a card", outcome is BibliographyOutcome.Deferred)
        assertTrue(harness.fetcher.requestedUrls.isEmpty())
    }

    @Test
    fun `the provider declares its own rhythm next to its code`() {
        val provider = OpenLibraryBibliography()

        for (endpoint in BibliographyEndpoint.values()) {
            val profile = provider.requestProfile(endpoint)
            assertEquals(endpoint.name, SourceRequestClass.LISTENER_ACTION, profile.requestClass)
            assertEquals(endpoint.name, 24 * 60 * 60 * 1000L, profile.cacheTtlMillis)
        }
    }

    // --- the pure parsers ----------------------------------------------------

    @Test
    fun `an unmappable language claim never hides the row`() {
        val cards = OpenLibraryJson.searchCandidates(
            """{"docs":[{"key":"/works/OL1W","title":"Твір","language":["xyz"]}]}"""
        )

        assertEquals(1, cards.size)
        assertTrue(cards.single().languages.isEmpty())
    }

    @Test
    fun `a search row without a title states nothing usable and is dropped`() {
        val cards = OpenLibraryJson.searchCandidates(
            """{"docs":[{"key":"/works/OL1W","title":"","author_name":["Хтось"]}]}"""
        )

        assertTrue(cards.isEmpty())
    }
}
