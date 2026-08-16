package com.slukhayka.audiobooks.testing

import com.slukhayka.audiobooks.data.source.HttpFetcher
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Serves canned content per URL for source-adapter fixture tests — no network,
 * no Robolectric (parser seam). Unknown URLs fall back to [fallback] (empty by
 * default) so URL-encoded request URLs can be matched without exact keys.
 *
 * [recordedHeaders] captures the extra headers of every headerful request, so
 * tests can assert that a header-gated endpoint (sluhayua's X-Requested-With)
 * is actually sent the gate it needs.
 *
 * ADR-0006: [streamResponses] serves in-memory bytes for the binary
 * [HttpFetcher.getStream] transport — the offline download path consumes it,
 * so download-path tests exercise the real loop with no network.
 */
class FakeFetcher(
    private val responses: Map<String, String> = emptyMap(),
    private val fallback: String = "",
    private val streamResponses: Map<String, ByteArray> = emptyMap()
) : HttpFetcher() {

    /** Extra headers of each headerful request, in call order. Thread-safe:
     *  the offline download loop records from several async workers at once,
     *  and a plain mutable list would lose appends under that race. */
    val recordedHeaders = java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()

    override fun getText(url: String): String = responses[url] ?: fallback

    override fun getText(url: String, extraHeaders: Map<String, String>): String {
        recordedHeaders += extraHeaders
        return responses[url] ?: fallback
    }

    override fun getStream(url: String, extraHeaders: Map<String, String>): InputStream? {
        recordedHeaders += extraHeaders
        return streamResponses[url]?.let { ByteArrayInputStream(it) }
    }
}
