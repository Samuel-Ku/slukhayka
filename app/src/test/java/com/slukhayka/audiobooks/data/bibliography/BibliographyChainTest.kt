package com.slukhayka.audiobooks.data.bibliography

import com.slukhayka.audiobooks.data.source.InMemorySourceGateBudgetStore
import com.slukhayka.audiobooks.data.source.SourceBucketState
import com.slukhayka.audiobooks.data.source.SourceGateParams
import com.slukhayka.audiobooks.data.source.SourceRequestGate
import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * ADR-0053 §2 / #858 (T5) — the OL→GB chain, tested through its OUTSIDE
 * behavior: two REAL providers, each on a real [SourceRequestGate] with a
 * fake clock, fake budget and canned fixtures. No network, no live base.
 *
 * The rule under test is the whole point of the class: the fallback fires on
 * Open Library's POSITIVE absence (a 404 / a document stating nothing) and
 * NEVER on `Deferred` or `Unavailable`, so one base's outage can never hide
 * behind the other's answer.
 */
class BibliographyChainTest {

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResource("fixtures/$name")
    ).readText()

    private class Harness(
        openLibraryBody: String = "",
        googleBooksBody: String = "",
        openLibraryStatus: Map<String, Pair<Int, String>> = emptyMap()
    ) {
        var now = 1_000_000L
        private val params = SourceGateParams(
            refillIntervalMs = 60_000,
            listenerWaitCapMs = 1_000,
            jitterMinMs = 0,
            jitterMaxMs = 0
        )

        val openLibraryStore = InMemorySourceGateBudgetStore()
        val openLibraryFetcher = FakeFetcher(
            fallback = openLibraryBody,
            statusResponses = openLibraryStatus
        )
        private val openLibrary = OpenLibraryBibliography(
            fetcher = openLibraryFetcher,
            gate = SourceRequestGate(
                params = params,
                budgetStore = openLibraryStore,
                clock = { now },
                sleeper = { millis -> now += millis; delay(millis) },
                random = Random(7)
            )
        )

        val googleBooksStore = InMemorySourceGateBudgetStore()
        val googleBooksFetcher = FakeFetcher(fallback = googleBooksBody)
        private val googleBooks = GoogleBooksBibliography(
            webTransportBase = WORKER_BASE,
            fetcher = googleBooksFetcher,
            gate = SourceRequestGate(
                params = params,
                budgetStore = googleBooksStore,
                clock = { now },
                sleeper = { millis -> now += millis; delay(millis) },
                random = Random(7)
            )
        )

        val chain = BibliographyChain(primary = openLibrary, fallback = googleBooks)

        fun drainOpenLibrary() {
            openLibraryStore.save("openlibrary.org", SourceBucketState(tokens = 0, lastRefillAtMs = now))
        }

        fun drainWorker() {
            googleBooksStore.save(WORKER_HOST, SourceBucketState(tokens = 0, lastRefillAtMs = now))
        }

        companion object {
            const val WORKER_BASE = "https://transport.example.workers.dev"
            const val WORKER_HOST = "transport.example.workers.dev"
            const val ISBN_URL = "https://openlibrary.org/isbn/9786177023202.json"
        }
    }

    private val primaryCard = fixture("openlibrary-isbn-9786177023202-2026-09-18.json")
    private val fallbackCard = fixture("googlebooks-volumes-isbn-9786177023202.json")

    private fun found(outcome: BibliographyOutcome<List<BibliographyCandidate>>): List<BibliographyCandidate> =
        (outcome as BibliographyOutcome.Found).value

    @Test
    fun `the primary's real answer never consults the fallback`() = runTest {
        val harness = Harness(openLibraryBody = primaryCard, googleBooksBody = fallbackCard)

        val card = found(harness.chain.isbn("9786177023202")).single()

        assertEquals("Чигиринський Кобзар і Гайдамаки", card.title)
        assertEquals(listOf("uk"), card.languages)
        assertTrue("a found primary must not spend a fallback request", harness.googleBooksFetcher.requestedUrls.isEmpty())
    }

    @Test
    fun `an ISBN the primary does not know falls through to the fallback`() = runTest {
        val harness = Harness(
            googleBooksBody = fallbackCard,
            openLibraryStatus = mapOf(Harness.ISBN_URL to (404 to ""))
        )

        val cards = found(harness.chain.isbn("9786177023202"))

        // Both volumes of the verbatim response come back from the fallback.
        assertEquals(2, cards.size)
        assertEquals("Чигиринський Кобзар і Гайдамаки", cards.first().title)
        assertEquals(listOf("uk"), cards.first().languages)
        assertEquals(1, harness.googleBooksFetcher.requestedUrls.size)
    }

    @Test
    fun `both bases stating nothing is a Found empty list`() = runTest {
        val harness = Harness(
            googleBooksBody = fixture("googlebooks-volumes-empty.json"),
            openLibraryStatus = mapOf(Harness.ISBN_URL to (404 to ""))
        )

        assertEquals(emptyList<BibliographyCandidate>(), found(harness.chain.isbn("9786177023202")))
    }

    @Test
    fun `a resting primary stays Deferred and never asks the fallback`() = runTest {
        val harness = Harness(openLibraryBody = primaryCard, googleBooksBody = fallbackCard)
        harness.drainOpenLibrary()

        val outcome = harness.chain.isbn("9786177023202")

        assertTrue("a resting budget is not a knowledge gap", outcome is BibliographyOutcome.Deferred)
        assertTrue(harness.googleBooksFetcher.requestedUrls.isEmpty())
    }

    @Test
    fun `a failed primary stays Unavailable and never asks the fallback`() = runTest {
        val harness = Harness(openLibraryBody = "", googleBooksBody = fallbackCard)

        val outcome = harness.chain.isbn("9786177023202")

        assertEquals(BibliographyOutcome.Unavailable, outcome)
        assertTrue("an outage must not hide behind another base", harness.googleBooksFetcher.requestedUrls.isEmpty())
    }

    @Test
    fun `an unavailable fallback after a primary absence stays Unavailable`() = runTest {
        val harness = Harness(
            googleBooksBody = "",
            openLibraryStatus = mapOf(Harness.ISBN_URL to (404 to ""))
        )

        assertEquals(BibliographyOutcome.Unavailable, harness.chain.isbn("9786177023202"))
    }

    @Test
    fun `a resting fallback after a primary absence stays Deferred`() = runTest {
        val harness = Harness(
            googleBooksBody = fallbackCard,
            openLibraryStatus = mapOf(Harness.ISBN_URL to (404 to ""))
        )
        harness.drainWorker()

        val outcome = harness.chain.isbn("9786177023202")

        assertTrue(outcome is BibliographyOutcome.Deferred)
        assertTrue(harness.googleBooksFetcher.requestedUrls.isEmpty())
    }
}
