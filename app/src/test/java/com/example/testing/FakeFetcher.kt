package com.example.testing

import com.example.data.source.HttpFetcher

/**
 * Serves canned content per URL for source-adapter fixture tests — no network,
 * no Robolectric (parser seam). Unknown URLs fall back to [fallback] (empty by
 * default) so URL-encoded request URLs can be matched without exact keys.
 */
class FakeFetcher(
    private val responses: Map<String, String>,
    private val fallback: String = ""
) : HttpFetcher() {

    override fun getText(url: String): String = responses[url] ?: fallback
}
