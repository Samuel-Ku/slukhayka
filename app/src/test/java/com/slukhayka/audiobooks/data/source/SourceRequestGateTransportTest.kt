package com.slukhayka.audiobooks.data.source

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec #681 T2 (#683) — the transport seam: one gated text request calls the
 * raw transport exactly once, a fresh cache hit calls it zero times, audio
 * streams never cross the gate, and a failure degrades to an honest outcome
 * instead of crashing. The raw transport is a canned in-memory response —
 * no network.
 */
class SourceRequestGateTransportTest {

    private class FakeTransport(sourceGate: SourceRequestGate?) : HttpFetcher(sourceGate = sourceGate) {
        var calls = 0
        var body: String? = "<html>page</html>"
        var status = 200

        public override fun executeRequest(url: String, extraHeaders: Map<String, String>): Response? {
            calls++
            val payload = body ?: return null
            return Response.Builder()
                .request(Request.Builder().url(url).build())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("OK")
                .body(payload.toResponseBody("text/html".toMediaType()))
                .build()
        }
    }

    private fun gate(store: InMemorySourceGateBudgetStore = InMemorySourceGateBudgetStore()) =
        SourceRequestGate(budgetStore = store)

    @Test
    fun `a gated request reaches the transport exactly once`() = runTest {
        val transport = FakeTransport(gate())

        val outcome = transport.fetchText("https://4read.org/book", SourceRequestClass.TTL_REFRESH)

        assertEquals(GateOutcome.Fetched("<html>page</html>"), outcome)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `a fresh cache hit reaches the transport zero times`() = runTest {
        val transport = FakeTransport(gate())

        transport.fetchText("https://4read.org/book", SourceRequestClass.TTL_REFRESH, cacheTtlMillis = 1_000)
        val second =
            transport.fetchText("https://4read.org/book", SourceRequestClass.TTL_REFRESH, cacheTtlMillis = 1_000)

        assertEquals(GateOutcome.Fresh("<html>page</html>"), second)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `a deferred request reaches the transport zero times`() = runTest {
        val store = InMemorySourceGateBudgetStore()
        store.save("4read.org", SourceBucketState(tokens = 0, lastRefillAtMs = System.currentTimeMillis()))
        val transport = FakeTransport(gate(store))

        val outcome = transport.fetchText("https://4read.org/book", SourceRequestClass.BACKGROUND)

        assertTrue(outcome is GateOutcome.Deferred)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `getStream never passes the gate`() = runTest {
        val store = InMemorySourceGateBudgetStore()
        store.save("4read.org", SourceBucketState(tokens = 0, lastRefillAtMs = System.currentTimeMillis()))
        val transport = FakeTransport(gate(store))

        val stream = transport.getStream("https://4read.org/audio.mp3")
        assertNotNull(stream)
        stream!!.close()
        val text = transport.fetchText("https://4read.org/book", SourceRequestClass.BACKGROUND)

        assertTrue(text is GateOutcome.Deferred)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `a failed transport degrades to an honest unavailable`() = runTest {
        val transport = FakeTransport(gate()).apply { body = null }

        val outcome = transport.fetchText("https://4read.org/book", SourceRequestClass.LISTENER_ACTION)

        assertEquals(GateOutcome.Unavailable, outcome)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `a non-200 page is unavailable and its failure is cached`() = runTest {
        val transport = FakeTransport(gate()).apply { status = 404 }

        val first =
            transport.fetchText("https://4read.org/book", SourceRequestClass.TTL_REFRESH, cacheTtlMillis = 1_000)
        val second =
            transport.fetchText("https://4read.org/book", SourceRequestClass.TTL_REFRESH, cacheTtlMillis = 1_000)

        assertEquals(GateOutcome.Unavailable, first)
        assertEquals(GateOutcome.Unavailable, second)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `without a gate the transport still fetches`() = runTest {
        val transport = FakeTransport(sourceGate = null)

        val outcome = transport.fetchText("https://4read.org/book", SourceRequestClass.TTL_REFRESH)

        assertEquals(GateOutcome.Fetched("<html>page</html>"), outcome)
        assertEquals(1, transport.calls)
    }
}
