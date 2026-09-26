package com.slukhayka.audiobooks.data.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * #1037 — can a DRAINED token bucket ever recover?
 *
 * The phone showed the consequence of asking: importing a second book from one
 * source silently returned null, because one import costs a page fetch plus one
 * `/play` call per chapter (8–9 requests) against a **6-token** bucket.
 *
 * This test asks the gate alone — no adapter, no fetcher, no device — whether
 * the bucket refills once it is dry. It exists to settle a suspicion about the
 * `Deferred` branch in `fetchUnderBudget`: `admit` computes a refilled state
 * with an advanced `lastRefillAtMs`, but the `Deferred` branch returns WITHOUT
 * saving it, so that computed anchor is thrown away.
 *
 * Deliberately uses PRODUCTION timing (refill 10 s, listener cap 1.5 s): with a
 * fast refill the gate waits internally and the `Deferred` branch never runs.
 */
class GateBucketRecoveryTest {

    private class Harness {
        var now = 1_000_000L
        val store = InMemorySourceGateBudgetStore()
        val gate = SourceRequestGate(
            params = SourceGateParams(
                bucketCapacity = 6,
                refillIntervalMs = 10_000,
                listenerWaitCapMs = 1_500,
                jitterMinMs = 0,
                jitterMaxMs = 0
            ),
            budgetStore = store,
            clock = { now },
            sleeper = { /* never sleeps in this harness */ },
            random = Random(7)
        )
    }

    private val url = "https://sluhay.com.ua/play?bookId=1403735&fileId=0"

    @Test
    fun `one import costs more than the bucket holds`() = runBlocking {
        val h = Harness()
        // Fresh bucket: 6 tokens, as a process starts.
        h.store.save("sluhay.com.ua", SourceBucketState(tokens = 6, lastRefillAtMs = h.now))

        // One SluhayUA import = the page fetch + one /play call per chapter.
        // 8 chapters => 9 gated requests. Nothing waits here: this is what the
        // adapter does today, and it is the arithmetic behind #1037.
        var served = 0
        for (i in 0 until 9) {
            val outcome = h.gate.run(
                "https://sluhay.com.ua/play?fileId=$i",
                SourceRequestClass.LISTENER_ACTION,
                0L
            ) { "BODY" }
            if (outcome is GateOutcome.Fetched || outcome is GateOutcome.Fresh) served++
        }

        // The bucket is capacity 6, so the tail of EVERY book runs dry. What
        // the caller does about it is the open question in #1037 — this test
        // only pins the cost, which is the fact the ticket rests on.
        assertEquals("one import cannot be served from a single bucket", 6, served)
    }

    @Test
    fun `a drained bucket recovers after the refill interval elapses`() = runBlocking {
        val h = Harness()
        // The bucket has just been drained by a previous import.
        h.store.save("sluhay.com.ua", SourceBucketState(tokens = 0, lastRefillAtMs = h.now))

        // First call, immediately: no tokens -> Deferred (10 s > 1.5 s cap).
        val first = h.gate.run(url, SourceRequestClass.LISTENER_ACTION, 0L) { "BODY" }
        assertEquals(GateOutcome.Deferred::class.java, first.javaClass)

        // The caller waits out the refill interval, then asks again.
        h.now += 10_000

        val second = h.gate.run(url, SourceRequestClass.LISTENER_ACTION, 0L) { "BODY" }
        assertEquals(
            "after a full refill interval the bucket MUST serve the request, was $second",
            "BODY",
            (second as? GateOutcome.Fetched)?.value
        )
    }
}
