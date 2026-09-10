package com.slukhayka.audiobooks.data.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Spec #681 T1 (#682) — the gate's external behaviour through its one seam,
 * on pure JVM with a fake clock and a fake sleeper: fresh cache spends
 * nothing, the bucket is persistent and class-aware, listener actions
 * overtake background, two consumers share one fetch, the throat holds one
 * request and jitter separates consecutive ones.
 */
class SourceRequestGateTest {

    private class Harness(val params: SourceGateParams = SourceGateParams()) {
        var now = 1_000_000L
        val store = InMemorySourceGateBudgetStore()

        fun newGate(): SourceRequestGate = SourceRequestGate(
            params = params,
            budgetStore = store,
            clock = { now },
            sleeper = { millis ->
                now += millis
                delay(millis)
            },
            random = Random(7)
        )

        val gate: SourceRequestGate = newGate()
    }

    private val url = "https://a.example/book"

    // --- fresh cache ---

    @Test
    fun `a fresh cached body is served without a fetch and without a token`() = runTest {
        val harness = Harness()
        var fetches = 0

        val first = harness.gate.run(url, SourceRequestClass.TTL_REFRESH, cacheTtlMillis = 1_000) {
            fetches++
            "body"
        }
        val tokensAfterFirst = requireNotNull(harness.store.load("a.example")).tokens

        val second = harness.gate.run(url, SourceRequestClass.TTL_REFRESH, cacheTtlMillis = 1_000) {
            fetches++
            "body"
        }

        assertEquals(GateOutcome.Fetched("body"), first)
        assertEquals(GateOutcome.Fresh("body"), second)
        assertEquals(1, fetches)
        assertEquals(tokensAfterFirst, requireNotNull(harness.store.load("a.example")).tokens)
    }

    @Test
    fun `a stale cache entry refetches after its ttl`() = runTest {
        val harness = Harness()
        var fetches = 0

        harness.gate.run(url, SourceRequestClass.TTL_REFRESH, cacheTtlMillis = 500) {
            fetches++
            "body"
        }
        harness.now += 501
        val second = harness.gate.run(url, SourceRequestClass.TTL_REFRESH, cacheTtlMillis = 500) {
            fetches++
            "body"
        }

        assertEquals(GateOutcome.Fetched("body"), second)
        assertEquals(2, fetches)
    }

    @Test
    fun `a failed fetch is remembered negatively while the call opted into caching`() = runTest {
        val harness = Harness(SourceGateParams(negativeTtlMs = 60_000))
        var fetches = 0

        val first = harness.gate.run<String>(url, SourceRequestClass.TTL_REFRESH, cacheTtlMillis = 1_000) {
            fetches++
            null
        }
        val second = harness.gate.run<String>(url, SourceRequestClass.TTL_REFRESH, cacheTtlMillis = 1_000) {
            fetches++
            null
        }

        assertEquals(GateOutcome.Unavailable, first)
        assertEquals(GateOutcome.Unavailable, second)
        assertEquals(1, fetches)
    }

    @Test
    fun `a listener action with no cache ttl still reaches the network after a failure`() = runTest {
        val harness = Harness(SourceGateParams(negativeTtlMs = 60_000))
        var fetches = 0

        harness.gate.run<String>(url, SourceRequestClass.TTL_REFRESH, cacheTtlMillis = 1_000) {
            fetches++
            null
        }
        harness.gate.run<String>(url, SourceRequestClass.LISTENER_ACTION, cacheTtlMillis = 0L) {
            fetches++
            null
        }

        assertEquals(2, fetches)
    }

    // --- the persistent, class-aware bucket ---

    @Test
    fun `an empty bucket defers a ttl refresh with the refill delay and no request`() = runTest {
        val harness = Harness(SourceGateParams(refillIntervalMs = 10_000))
        harness.store.save("a.example", SourceBucketState(tokens = 0, lastRefillAtMs = harness.now))
        var fetches = 0

        val outcome = harness.gate.run(url, SourceRequestClass.TTL_REFRESH) {
            fetches++
            "body"
        }

        assertEquals(GateOutcome.Deferred(10_000), outcome)
        assertEquals(0, fetches)
    }

    @Test
    fun `a background request is skipped while the bucket is at half or less`() = runTest {
        val harness = Harness(SourceGateParams(bucketCapacity = 6, backgroundMinTokens = 4))
        harness.store.save("a.example", SourceBucketState(tokens = 3, lastRefillAtMs = harness.now))
        var fetches = 0

        val outcome = harness.gate.run(url, SourceRequestClass.BACKGROUND) {
            fetches++
            "body"
        }

        assertTrue(outcome is GateOutcome.Deferred)
        assertEquals(0, fetches)
    }

    @Test
    fun `a background request proceeds when the bucket is more than half full`() = runTest {
        val harness = Harness(SourceGateParams(bucketCapacity = 6, backgroundMinTokens = 4))
        harness.store.save("a.example", SourceBucketState(tokens = 4, lastRefillAtMs = harness.now))

        val outcome = harness.gate.run(url, SourceRequestClass.BACKGROUND) { "body" }

        assertEquals(GateOutcome.Fetched("body"), outcome)
    }

    @Test
    fun `a listener action waits out a short dry spell and then proceeds`() = runTest {
        val harness = Harness(SourceGateParams(refillIntervalMs = 1_000, listenerWaitCapMs = 5_000))
        harness.store.save("a.example", SourceBucketState(tokens = 0, lastRefillAtMs = harness.now))
        val startedAt = harness.now

        val outcome = harness.gate.run(url, SourceRequestClass.LISTENER_ACTION) { "body" }

        assertEquals(GateOutcome.Fetched("body"), outcome)
        assertEquals(1_000, harness.now - startedAt)
    }

    @Test
    fun `a listener action beyond the wait cap gets the honest deferred state`() = runTest {
        val harness = Harness(SourceGateParams(refillIntervalMs = 10_000, listenerWaitCapMs = 5_000))
        harness.store.save("a.example", SourceBucketState(tokens = 0, lastRefillAtMs = harness.now))
        var fetches = 0

        val outcome = harness.gate.run(url, SourceRequestClass.LISTENER_ACTION) {
            fetches++
            "body"
        }

        assertEquals(GateOutcome.Deferred(10_000), outcome)
        assertEquals(0, fetches)
    }

    @Test
    fun `the budget survives a new gate instance`() = runTest {
        val harness = Harness(SourceGateParams(bucketCapacity = 2))

        harness.gate.run(url, SourceRequestClass.TTL_REFRESH) { "a" }
        harness.gate.run(url, SourceRequestClass.TTL_REFRESH) { "b" }
        assertEquals(0, requireNotNull(harness.store.load("a.example")).tokens)

        val restarted = harness.newGate()
        var fetches = 0
        val outcome = restarted.run(url, SourceRequestClass.TTL_REFRESH) {
            fetches++
            "c"
        }

        assertTrue(outcome is GateOutcome.Deferred)
        assertEquals(0, fetches)
    }

    // --- the queue and the throat ---

    @Test
    fun `a listener action overtakes a queued background request`() = runTest {
        val harness = Harness()
        val starts = mutableListOf<String>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = launch {
            harness.gate.run("https://a.example/1", SourceRequestClass.TTL_REFRESH) {
                starts += "first"
                releaseFirst.await()
                "1"
            }
        }
        runCurrent()
        val background = launch {
            harness.gate.run("https://b.example/2", SourceRequestClass.BACKGROUND) {
                starts += "background"
                "2"
            }
        }
        val listener = launch {
            harness.gate.run("https://c.example/3", SourceRequestClass.LISTENER_ACTION) {
                starts += "listener"
                "3"
            }
        }
        runCurrent()

        releaseFirst.complete(Unit)
        joinAll(first, background, listener)

        assertEquals(listOf("first", "listener", "background"), starts)
    }

    @Test
    fun `two consumers of one url share a single fetch`() = runTest {
        val harness = Harness()
        var fetches = 0
        val release = CompletableDeferred<Unit>()

        val first = async {
            harness.gate.run(url, SourceRequestClass.TTL_REFRESH) {
                fetches++
                release.await()
                "body"
            }
        }
        runCurrent()
        val second = async {
            harness.gate.run(url, SourceRequestClass.TTL_REFRESH) {
                fetches++
                "body"
            }
        }
        runCurrent()

        release.complete(Unit)

        assertEquals(GateOutcome.Fetched("body"), first.await())
        assertEquals(GateOutcome.Fetched("body"), second.await())
        assertEquals(1, fetches)
    }

    @Test
    fun `the throat keeps at most one request in flight`() = runTest {
        val harness = Harness()
        var concurrent = 0
        var maxConcurrent = 0

        val jobs = (1..4).map { index ->
            async {
                harness.gate.run("https://host$index.example/x", SourceRequestClass.TTL_REFRESH) {
                    concurrent++
                    maxConcurrent = maxOf(maxConcurrent, concurrent)
                    delay(25)
                    concurrent--
                    "value-$index"
                }
            }
        }
        jobs.awaitAll()

        assertEquals(1, maxConcurrent)
    }

    @Test
    fun `consecutive requests are separated by the jitter gap`() = runTest {
        val harness = Harness(SourceGateParams(jitterMinMs = 200, jitterMaxMs = 800))
        val starts = mutableListOf<Long>()

        harness.gate.run("https://a.example/1", SourceRequestClass.TTL_REFRESH) {
            starts += harness.now
            "1"
        }
        harness.gate.run("https://b.example/2", SourceRequestClass.TTL_REFRESH) {
            starts += harness.now
            "2"
        }

        val gap = starts[1] - starts[0]
        assertTrue("gap $gap below the minimum", gap >= 200)
        assertTrue("gap $gap above the maximum", gap <= 800)
    }
}
