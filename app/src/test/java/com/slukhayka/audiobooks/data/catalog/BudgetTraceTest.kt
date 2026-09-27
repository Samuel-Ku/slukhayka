package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.source.InMemorySourceGateBudgetStore
import com.slukhayka.audiobooks.data.source.SourceBucketState
import com.slukhayka.audiobooks.data.source.SourceGateParams
import com.slukhayka.audiobooks.data.source.SourceRequestClass
import com.slukhayka.audiobooks.data.source.SourceRequestGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.slukhayka.audiobooks.data.merge.MergeKey
import kotlin.random.Random

/**
 * #533 AC8 — «точне трасування показує request count для кожної дії та доводить
 * lease, one-page і three-candidate budgets».
 *
 * The three budgets each already had their own behavioural test:
 *   - THREE-CANDIDATE: `CatalogWorkIndexTest.candidates stay on one source and
 *     never exceed three` and `WorkIndexRefresherTest`'s `at most three
 *     candidate pages`;
 *   - ONE-PAGE: `CatalogWorkIndex`'s contract that a candidate opens exactly one
 *     page (asserted in `WorkIndexRefresherTest`);
 *   - LEASE: `CollectiveFeedRefreshTest.concurrent clients give one owner and
 *     one source refresh`.
 *
 * What was missing is the CONNECTION between those budgets and an actual
 * request count — the gate kept no counters, so "the budget held" could only be
 * eyeballed in logcat. This test supplies that connection: it spends the
 * three-candidate budget against the gate and reads the recorded numbers back.
 */
class BudgetTraceTest {

    /** A gate with production timing and a full bucket. */
    private fun gate(): Pair<SourceRequestGate, InMemorySourceGateBudgetStore> {
        val store = InMemorySourceGateBudgetStore()
        store.save("example.com", SourceBucketState(tokens = 6, lastRefillAtMs = 1_000_000L))
        val gate = SourceRequestGate(
            params = SourceGateParams(
                bucketCapacity = 6,
                refillIntervalMs = 10_000,
                listenerWaitCapMs = 1_500,
                jitterMinMs = 0,
                jitterMaxMs = 0
            ),
            budgetStore = store,
            clock = { 1_000_000L },
            sleeper = { },
            random = Random(7)
        )
        return gate to store
    }

    /**
     * Ten candidates of ONE source for the same Work — deliberately more than
     * the budget, so the bound is what limits the action, not the data.
     */
    private fun index() = CatalogWorkIndex(
        (0 until 10).map { i ->
            CatalogIndexEntry(
                sourceId = "example",
                url = "https://example.com/kobzar-$i",
                slug = "kobzar-$i",
                mergeKey = MergeKey.keyFor("Кобзар", "Тарас Шевченко")
            )
        }
    )

    @Test
    fun `the three-candidate budget bounds how many pages one action may open`() = runBlocking {
        val (gate, _) = gate()
        // The budget itself: one action may open at most three candidate pages.
        val candidates = index().candidates("Кобзар", "Тарас Шевченко")
        assertEquals(
            "the candidate budget is the whole point of the bound",
            CatalogWorkIndex.MAX_CANDIDATES,
            candidates.size
        )

        // And that is exactly how many requests the gate then records — the
        // number the acceptance criterion asks to be visible.
        candidates.forEach { c ->
            gate.run(c.url, SourceRequestClass.LISTENER_ACTION, 0L) { "PAGE" }
        }

        assertEquals(
            "one action spent exactly the candidate budget in requests",
            CatalogWorkIndex.MAX_CANDIDATES.toLong(),
            gate.requestsByClass()[SourceRequestClass.LISTENER_ACTION]
        )
        assertEquals(3L, gate.outcomes()["fetched"])
        assertEquals(
            "a bounded action must not defer — it stays inside the bucket",
            0L,
            gate.outcomes()["deferred"] ?: 0L
        )
    }

    @Test
    fun `a bounded action leaves the bucket with room for the next one`() = runBlocking {
        val (gate, _) = gate()
        val idx = index()

        // Two consecutive explicit actions, each inside its own budget.
        repeat(2) {
            idx.candidates("Кобзар", "Тарас Шевченко").forEach { c ->
                gate.run(c.url, SourceRequestClass.LISTENER_ACTION, 0L) { "PAGE" }
            }
        }

        val byClass = gate.requestsByClass()[SourceRequestClass.LISTENER_ACTION]
        assertTrue("two bounded actions must fit the bucket, was $byClass", byClass!! <= 6L)
        assertEquals("no request was refused", 0L, gate.outcomes()["deferred"] ?: 0L)
    }
}
