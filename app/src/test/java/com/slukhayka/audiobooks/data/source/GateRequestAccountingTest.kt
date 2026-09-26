package com.slukhayka.audiobooks.data.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * #533 AC8 — «точне трасування показує request count для кожної дії».
 *
 * Before this, `SourceRequestGate` kept no counters at all, so the lease,
 * one-page and three-candidate budgets could only be eyeballed in logcat —
 * which is how a budget regression stays invisible. The gate now counts
 * REQUESTS BY CLASS and terminal OUTCOMES, and this test reads them.
 *
 * The distinction the counters make visible is the one that hurt in practice
 * (#1037): `deferred` is a request the SOURCE NEVER SAW, because our own
 * budget refused it. Counting it separately from `fetched` is what turns
 * "the book lost its chapters" into "the bucket ran dry".
 */
class GateRequestAccountingTest {

    private class Harness(capacity: Int = 6) {
        var now = 1_000_000L
        val store = InMemorySourceGateBudgetStore()
        val gate = SourceRequestGate(
            params = SourceGateParams(
                bucketCapacity = capacity,
                refillIntervalMs = 10_000,
                listenerWaitCapMs = 1_500,
                jitterMinMs = 0,
                jitterMaxMs = 0
            ),
            budgetStore = store,
            clock = { now },
            sleeper = { millis -> now += millis },
            random = Random(7)
        )
    }

    @Test
    fun `a full bucket is spent one request at a time and the rest are deferred`() = runBlocking {
        val h = Harness(capacity = 6)
        h.store.save("sluhay.com.ua", SourceBucketState(tokens = 6, lastRefillAtMs = h.now))

        // The shape of one SluhayUA import: a page fetch plus one /play per
        // chapter — 9 listener requests against a 6-token bucket.
        repeat(9) { i ->
            h.gate.run("https://sluhay.com.ua/play?fileId=$i", SourceRequestClass.LISTENER_ACTION, 0L) { "BODY" }
        }

        assertEquals(
            "the listener class must be counted once per admitted request",
            6L,
            h.gate.requestsByClass()[SourceRequestClass.LISTENER_ACTION]
        )
        assertEquals("six requests reached the source", 6L, h.gate.outcomes()["fetched"])
        assertEquals("three were refused by our own budget", 3L, h.gate.outcomes()["deferred"])
    }

    @Test
    fun `a background request is counted under its own class`() = runBlocking {
        val h = Harness(capacity = 6)
        h.store.save("sluhay.com.ua", SourceBucketState(tokens = 6, lastRefillAtMs = h.now))

        h.gate.run("https://sluhay.com.ua/a", SourceRequestClass.BACKGROUND, 0L) { "B" }
        h.gate.run("https://sluhay.com.ua/b", SourceRequestClass.LISTENER_ACTION, 0L) { "L" }

        val byClass = h.gate.requestsByClass()
        assertEquals(1L, byClass[SourceRequestClass.BACKGROUND])
        assertEquals(1L, byClass[SourceRequestClass.LISTENER_ACTION])
    }

    @Test
    fun `a cached answer counts as fresh and never reaches the source`() = runBlocking {
        val h = Harness(capacity = 6)
        h.store.save("sluhay.com.ua", SourceBucketState(tokens = 6, lastRefillAtMs = h.now))
        val url = "https://sluhay.com.ua/cached"

        h.gate.run(url, SourceRequestClass.LISTENER_ACTION, cacheTtlMillis = 60_000L) { "BODY" }
        h.gate.run(url, SourceRequestClass.LISTENER_ACTION, cacheTtlMillis = 60_000L) { "BODY" }

        assertEquals("only the first request reached the source", 1L, h.gate.requestsByClass()[SourceRequestClass.LISTENER_ACTION])
        assertEquals(1L, h.gate.outcomes()["fresh"])
    }
}
