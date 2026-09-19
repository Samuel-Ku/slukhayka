package com.slukhayka.audiobooks.data.bibliography

import com.slukhayka.audiobooks.data.source.InMemorySourceGateBudgetStore
import com.slukhayka.audiobooks.data.source.SourceBucketState
import com.slukhayka.audiobooks.data.source.SourceGateParams
import com.slukhayka.audiobooks.data.source.SourceRequestClass
import com.slukhayka.audiobooks.data.source.SourceRequestGate
import com.slukhayka.audiobooks.data.source.SourceRequestProfile
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
 * ADR-0053 / #858 (T5) — the Google Books fallback provider, tested through
 * its OUTSIDE behavior only, exactly like the Open Library provider.
 *
 * ## The fixtures, honestly
 *
 * Google Books without a key answers **429** — captured live 2026-09-19 and
 * kept as `web/src/worker/fixtures/googlebooks-quota-429-2026-09-19.json` —
 * and no key exists in this repository (that is the whole point of #858), so
 * a live `books#volumes` capture was IMPOSSIBLE here.
 * `googlebooks-volumes-isbn-9786177023202.json` is therefore assembled from
 * the documented `books#volumes` response shape, not captured; it is the
 * shape the worker returns verbatim. The edge cases that need a shape the
 * fixture does not carry are crafted inline and said so.
 *
 * No network anywhere: the shared fetcher serves canned text (ADR-0006) and
 * the politeness gate is a REAL [SourceRequestGate] with a fake clock and a
 * fake budget, so caching and deferral are exercised for real.
 */
class GoogleBooksBibliographyTest {

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResource("fixtures/$name")
    ).readText()

    private class Harness(
        routeBase: String = WORKER_BASE,
        fallback: String = "",
        params: SourceGateParams = SourceGateParams(
            refillIntervalMs = 1_000,
            listenerWaitCapMs = 500,
            jitterMinMs = 0,
            jitterMaxMs = 0
        )
    ) {
        var now = 1_000_000L
        val store = InMemorySourceGateBudgetStore()
        val fetcher = FakeFetcher(fallback = fallback)
        val provider = GoogleBooksBibliography(
            webTransportBase = routeBase,
            fetcher = fetcher,
            gate = SourceRequestGate(
                params = params,
                budgetStore = store,
                clock = { now },
                sleeper = { millis -> now += millis; delay(millis) },
                random = Random(7)
            )
        )

        /** The WORKER host's bucket is empty and refills only tomorrow. */
        fun drainBucket() {
            store.save(WORKER_HOST, SourceBucketState(tokens = 0, lastRefillAtMs = now))
        }

        companion object {
            const val WORKER_BASE = "https://transport.example.workers.dev"
            const val WORKER_HOST = "transport.example.workers.dev"
        }
    }

    private fun found(outcome: BibliographyOutcome<List<BibliographyCandidate>>): List<BibliographyCandidate> =
        (outcome as BibliographyOutcome.Found).value

    // --- the ISBN door -------------------------------------------------------

    @Test
    fun `isbn maps a volumes response into normalized cards`() = runTest {
        val harness = Harness(fallback = fixture("googlebooks-volumes-isbn-9786177023202.json"))

        val cards = found(harness.provider.isbn("978-617-702-3202"))

        assertEquals(2, cards.size)
        val first = cards[0]
        assertEquals("Чигиринський Кобзар і Гайдамаки", first.title)
        assertEquals(listOf("Тарас Григорович Шевченко"), first.authors)
        assertEquals(2014, first.firstPublishYear)
        // The ISBN-13 claim wins over the volume's ISBN-10.
        assertEquals("9786177023202", first.isbn)
        assertEquals(listOf("uk"), first.languages)
        // A Google Books volume id is not an Open Library Work key.
        assertNull(first.workKey)
        // The API hands the cover out over cleartext; the app forbids it, so
        // the same resource is requested over https.
        assertEquals(
            "https://books.google.com/books/content?id=mQFMDwAAQBAJ&printsec=frontcover&img=1&zoom=1&edge=curl&source=gbs_api",
            first.coverImageUrl
        )
    }

    @Test
    fun `a volume without authors year cover or language keeps those fields empty`() = runTest {
        val harness = Harness(fallback = fixture("googlebooks-volumes-isbn-9786177023202.json"))

        val sparse = found(harness.provider.isbn("9786177023202")).single {
            it.title == "Чигиринський Кобзар і Гайдамаки (передрук)"
        }

        assertTrue(sparse.authors.isEmpty())
        assertEquals(2018, sparse.firstPublishYear)
        assertNull(sparse.coverImageUrl)
        assertTrue("unknown language never hides the row", sparse.languages.isEmpty())
        // The document states no identifier; the value the caller ASKED with
        // is carried back — not a guess.
        assertEquals("9786177023202", sparse.isbn)
    }

    @Test
    fun `an empty volumes answer is a Found empty list, not a failure`() = runTest {
        val harness = Harness(fallback = fixture("googlebooks-volumes-empty.json"))

        assertEquals(emptyList<BibliographyCandidate>(), found(harness.provider.isbn("9791234567890")))
    }

    @Test
    fun `a malformed body yields no candidates and never throws`() = runTest {
        val harness = Harness(fallback = """{"items": [ this is not json""")

        assertEquals(emptyList<BibliographyCandidate>(), found(harness.provider.isbn("9786177023202")))
    }

    @Test
    fun `a failed fetch is Unavailable - never a fabricated card`() = runTest {
        val harness = Harness(fallback = "") // the fake's honest empty body

        assertEquals(BibliographyOutcome.Unavailable, harness.provider.isbn("9786177023202"))
    }

    @Test
    fun `a blank worker base honestly reports the door unusable`() = runTest {
        val harness = Harness(routeBase = "  ", fallback = fixture("googlebooks-volumes-isbn-9786177023202.json"))

        assertEquals(BibliographyOutcome.Unavailable, harness.provider.isbn("9786177023202"))
        assertTrue(harness.fetcher.requestedUrls.isEmpty())
    }

    @Test
    fun `an unusable isbn asks the door nothing`() = runTest {
        val harness = Harness(fallback = fixture("googlebooks-volumes-isbn-9786177023202.json"))

        assertEquals(emptyList<BibliographyCandidate>(), found(harness.provider.isbn("not-an-isbn")))
        assertTrue(harness.fetcher.requestedUrls.isEmpty())
    }

    // --- the client carries no key (AC #858) --------------------------------

    @Test
    fun `the client request carries no key and never talks to Google directly`() = runTest {
        val harness = Harness(fallback = fixture("googlebooks-volumes-isbn-9786177023202.json"))

        harness.provider.isbn("9786177023202")

        val url = harness.fetcher.requestedUrls.single()
        assertEquals(
            "https://transport.example.workers.dev/api/bibliography/isbn?isbn=9786177023202",
            url
        )
        assertFalse("no key may ever travel in a client URL", url.contains("key="))
        assertFalse("no Google API key material", url.contains("AIza"))
        assertFalse("the client must not open googleapis.com itself", url.contains("googleapis.com"))
    }

    // --- cache, budget and the declared rhythm ------------------------------

    @Test
    fun `a repeated isbn lookup is served from the gate cache with zero extra requests`() = runTest {
        val harness = Harness(fallback = fixture("googlebooks-volumes-isbn-9786177023202.json"))

        harness.provider.isbn("9786177023202")
        harness.provider.isbn("978-617-702-3202")

        assertEquals(1, harness.fetcher.requestedUrls.size)
    }

    @Test
    fun `a resting budget is the honest Deferred state`() = runTest {
        val harness = Harness(
            fallback = fixture("googlebooks-volumes-isbn-9786177023202.json"),
            params = SourceGateParams(
                refillIntervalMs = 60_000,
                listenerWaitCapMs = 1_000,
                jitterMinMs = 0,
                jitterMaxMs = 0
            )
        )
        harness.drainBucket()

        val outcome = harness.provider.isbn("9786177023202")

        assertTrue("a dry bucket must not fabricate a card", outcome is BibliographyOutcome.Deferred)
        assertTrue(harness.fetcher.requestedUrls.isEmpty())
    }

    @Test
    fun `the provider declares its own rhythm next to its code`() {
        val profile = GoogleBooksBibliography(Harness.WORKER_BASE).requestProfile()

        assertEquals(SourceRequestClass.LISTENER_ACTION, profile.requestClass)
        assertEquals(SourceRequestProfile.SEARCH_TTL_MS, profile.cacheTtlMillis)
    }

    // --- the pure parser -----------------------------------------------------

    @Test
    fun `literal JSON nulls are tolerated and remain absent fields`() {
        val cards = GoogleBooksJson.isbnCandidates(
            """{"totalItems":1,"items":[{"volumeInfo":{"title":"Твір","authors":null,
               "publishedDate":null,"imageLinks":null,"industryIdentifiers":null,"language":null}}]}""",
            requestedIsbn = "9786177023202"
        )

        val card = cards.single()
        assertEquals("Твір", card.title)
        assertTrue(card.authors.isEmpty())
        assertNull(card.firstPublishYear)
        assertNull(card.coverImageUrl)
        assertTrue(card.languages.isEmpty())
        assertEquals("9786177023202", card.isbn)
    }

    @Test
    fun `a volume without a title states nothing usable and is dropped`() {
        val cards = GoogleBooksJson.isbnCandidates(
            """{"totalItems":1,"items":[{"volumeInfo":{"authors":["Хтось"]}}]}""",
            requestedIsbn = "9786177023202"
        )

        assertTrue(cards.isEmpty())
    }

    @Test
    fun `an unmappable language claim never hides the row`() {
        val cards = GoogleBooksJson.isbnCandidates(
            """{"items":[{"volumeInfo":{"title":"Твір","language":"xyz"}}]}""",
            requestedIsbn = "9786177023202"
        )

        assertEquals(1, cards.size)
        assertTrue(cards.single().languages.isEmpty())
    }

    @Test
    fun `a non-ISBN identifier claim is ignored in favour of the requested isbn`() {
        val cards = GoogleBooksJson.isbnCandidates(
            """{"items":[{"volumeInfo":{"title":"Твір","industryIdentifiers":[
               {"type":"OTHER","identifier":"ocm12345"}]}}]}""",
            requestedIsbn = "9786177023202"
        )

        assertEquals("9786177023202", cards.single().isbn)
    }

    @Test
    fun `an isbn-10 claim is used when the volume states no isbn-13`() {
        val cards = GoogleBooksJson.isbnCandidates(
            """{"items":[{"volumeInfo":{"title":"Твір","industryIdentifiers":[
               {"type":"ISBN_10","identifier":"6177023206"}]}}]}""",
            requestedIsbn = "6177023206"
        )

        assertEquals("6177023206", cards.single().isbn)
    }
}
