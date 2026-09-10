package com.slukhayka.audiobooks.data.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * ADR-0040 — the politeness seam reads the adapter's DECLARED profile and
 * the gate decides: the declared class reaches the gate (a listener action
 * waits out a short dry spell and then fetches, where a refresh would be
 * deferred), the declared TTL serves a fresh hit with zero fetches, and a
 * deferred budget degrades to the honest empty body. The fetcher never
 * classifies — it only forwards the endpoint.
 */
class SourceGateFetcherTest {

    private class FakeTransport(var body: String) : HttpFetcher() {
        var calls = 0
        override fun getText(url: String): String {
            calls++
            return body
        }
    }

    private class Harness(profileFor: (SourceEndpoint) -> SourceRequestProfile) {
        var now = 1_000_000L
        val store = InMemorySourceGateBudgetStore()
        val params = SourceGateParams(
            bucketCapacity = 6,
            refillIntervalMs = 1_000,
            listenerWaitCapMs = 5_000,
            jitterMinMs = 0,
            jitterMaxMs = 0
        )
        val gate = SourceRequestGate(
            params = params,
            budgetStore = store,
            clock = { now },
            sleeper = { millis -> now += millis; delay(millis) },
            random = Random(7)
        )
        val transport = FakeTransport("body")
        val fetcher = SourceGateFetcher(transport, gate, profileFor)
    }

    private val url = "https://a.example/search"

    @Test
    fun `the declared listener class waits out a dry spell and then fetches`() = runTest {
        val harness = Harness { SourceRequestProfile(SourceRequestClass.LISTENER_ACTION, 0L) }
        harness.store.save("a.example", SourceBucketState(tokens = 0, lastRefillAtMs = harness.now))
        val startedAt = harness.now

        val body = harness.fetcher.getText(url, SourceEndpoint.SEARCH)

        assertEquals("body", body)
        // A TTL_REFRESH would have been Deferred at zero tokens; the
        // listener action waited one refill and proceeded.
        assertEquals(1_000, harness.now - startedAt)
        assertEquals(1, harness.transport.calls)
    }

    @Test
    fun `the declared ttl serves a fresh hit with zero fetches`() = runTest {
        val harness = Harness {
            SourceRequestProfile(SourceRequestClass.LISTENER_ACTION, 60_000L)
        }

        assertEquals("body", harness.fetcher.getText(url, SourceEndpoint.SEARCH))
        assertEquals("body", harness.fetcher.getText(url, SourceEndpoint.SEARCH))

        assertEquals(1, harness.transport.calls)
    }

    @Test
    fun `a deferred budget degrades to the honest empty body`() = runTest {
        val harness = Harness { SourceRequestProfile(SourceRequestClass.BACKGROUND, 0L) }
        harness.store.save("a.example", SourceBucketState(tokens = 0, lastRefillAtMs = harness.now))

        val body = harness.fetcher.getText(url, SourceEndpoint.SEARCH)

        assertEquals("", body)
        assertEquals(0, harness.transport.calls)
    }

    @Test
    fun `a failed transport body is served as the honest empty`() = runTest {
        val harness = Harness { SourceRequestProfile(SourceRequestClass.LISTENER_ACTION, 0L) }
        harness.transport.body = ""  // needs var — see below
        assertEquals("", harness.fetcher.getText(url, SourceEndpoint.SEARCH))
    }
}