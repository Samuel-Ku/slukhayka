package com.example.testing

import com.example.data.source.HttpFetcher

/**
 * Serves canned content per URL for source-adapter fixture tests — no network,
 * no Robolectric (parser seam). Unknown URLs fall back to [fallback] (empty by
 * default) so URL-encoded request URLs can be matched without exact keys.
 *
 * [recordedHeaders] captures the extra headers of every headerful request, so
 * tests can assert that a header-gated endpoint (sluhayua's X-Requested-With)
 * is actually sent the gate it needs.
 */
class FakeFetcher(
    private val responses: Map<String, String>,
    private val fallback: String = ""
) : HttpFetcher() {

    /** Extra headers of each headerful request, in call order. */
    val recordedHeaders = mutableListOf<Map<String, String>>()

    override fun getText(url: String): String = responses[url] ?: fallback

    override fun getText(url: String, extraHeaders: Map<String, String>): String {
        recordedHeaders += extraHeaders
        return responses[url] ?: fallback
    }
}
