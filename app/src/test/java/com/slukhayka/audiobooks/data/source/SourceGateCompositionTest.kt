package com.slukhayka.audiobooks.data.source

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Spec #681 T3 (#684) — the composition behaviour at the transport seam: the
 * one gate guards the blocking [HttpFetcher.getText] door with the fetcher's
 * class, a dry budget yields the honest empty result instead of a request, a
 * listener request waits the bounded spell and then fetches, the gate cache
 * serves within its TTL, non-Source hosts stay raw, and the process provider
 * supplies the gate to fetchers constructed later.
 */
class SourceGateCompositionTest {

    private class FakeTransport(
        sourceGate: SourceRequestGate? = null,
        defaultRequestClass: SourceRequestClass = SourceRequestClass.BACKGROUND,
        defaultCacheTtlMillis: Long = 0L
    ) : HttpFetcher(
        sourceGate = sourceGate,
        defaultRequestClass = defaultRequestClass,
        defaultCacheTtlMillis = defaultCacheTtlMillis
    ) {
        var calls = 0

        public override fun executeRequest(url: String, extraHeaders: Map<String, String>): Response? {
            calls++
            return Response.Builder()
                .request(Request.Builder().url(url).build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("<html>page</html>".toResponseBody("text/html".toMediaType()))
                .build()
        }
    }

    private class FakeClock {
        var now = 1_000_000L
    }

    private fun dryGate(clock: FakeClock): SourceRequestGate {
        val store = InMemorySourceGateBudgetStore().apply {
            save("4read.org", SourceBucketState(tokens = 0, lastRefillAtMs = clock.now))
        }
        return SourceRequestGate(
            params = SourceGateParams(refillIntervalMs = 1_000, listenerWaitCapMs = 5_000),
            budgetStore = store,
            clock = { clock.now },
            sleeper = { clock.now += it },
            random = Random(7)
        )
    }

    @After
    fun tearDown() {
        SourceGateProvider.install(null)
    }

    @Test
    fun `the blocking door defers a background request without touching the transport`() {
        val transport = FakeTransport(sourceGate = dryGate(FakeClock()))

        assertEquals("", transport.getText("https://4read.org/book"))
        assertEquals(0, transport.calls)
    }

    @Test
    fun `a listener request waits out the dry spell then fetches`() = runTest {
        val clock = FakeClock()
        val transport = FakeTransport(sourceGate = dryGate(clock))

        val outcome = transport.fetchText(
            "https://4read.org/book",
            SourceRequestClass.LISTENER_ACTION
        )

        assertEquals(GateOutcome.Fetched("<html>page</html>"), outcome)
        assertEquals(1_000L, clock.now - 1_000_000L)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `the blocking door defers immediately instead of parking the caller`() {
        val clock = FakeClock()
        val transport = FakeTransport(sourceGate = dryGate(clock))
        val startedAt = clock.now

        val body = transport.getText(
            "https://4read.org/book",
            emptyMap(),
            SourceRequestClass.LISTENER_ACTION,
            0L
        )

        assertEquals("", body)
        assertEquals(0L, clock.now - startedAt)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `a fresh gate cache serves the second call without transport`() {
        val transport = FakeTransport(
            sourceGate = SourceRequestGate(random = Random(7)),
            defaultCacheTtlMillis = 60_000L
        )

        val first = transport.getText("https://4read.org/book")
        val second = transport.getText("https://4read.org/book")

        assertEquals("<html>page</html>", first)
        assertEquals(first, second)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `a non-source host stays raw`() {
        val transport = FakeTransport(sourceGate = dryGate(FakeClock()))

        assertEquals("<html>page</html>", transport.getText("https://query.wikidata.org/sparql?q=x"))
        assertEquals(1, transport.calls)
    }

    @Test
    fun `the provider supplies the gate to a fetcher constructed without one`() {
        SourceGateProvider.install(dryGate(FakeClock()))
        val transport = FakeTransport()

        assertEquals("", transport.getText("https://4read.org/book"))
        assertEquals(0, transport.calls)
    }

    @Test
    fun `a non-source host bypasses the installed provider`() {
        SourceGateProvider.install(dryGate(FakeClock()))
        val transport = FakeTransport()

        assertTrue(transport.getText("https://query.wikidata.org/sparql?q=x").isNotEmpty())
        assertEquals(1, transport.calls)
    }

    @Test
    fun `a cookie bearing request bypasses the gate`() {
        val transport = FakeTransport(sourceGate = dryGate(FakeClock()))

        val body = transport.getText(
            "https://4read.org/book",
            mapOf("Cookie" to "cf_clearance=live"),
            SourceRequestClass.LISTENER_ACTION,
            0L
        )

        assertEquals("<html>page</html>", body)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `a cookie bearing gated fetch stays outside the throat`() = runTest {
        val transport = FakeTransport(sourceGate = dryGate(FakeClock()))

        val live = transport.fetchText(
            "https://4read.org/book",
            SourceRequestClass.LISTENER_ACTION,
            extraHeaders = mapOf("Cookie" to "cf_clearance=live")
        )
        val clean = transport.fetchText("https://4read.org/book", SourceRequestClass.BACKGROUND)

        assertEquals(GateOutcome.Fetched("<html>page</html>"), live)
        assertTrue(clean is GateOutcome.Deferred)
        assertEquals(1, transport.calls)
    }
}
