package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G (spec `2026-09-10-remove-4read-source`) — a catalogue pass must not walk
 * the whole sitemap when pages come back empty (budget-deferred or dead).
 *
 * The gate returns "" both for a genuine empty page and for a per-host
 * budget deferral, so an unbounded walk issues one gated request per `<loc>`
 * (the 401-fetches-for-400-locs stall measured on device). The walk stops
 * after a bounded run of misses instead.
 */
class CatalogWalkBoundsTest {

    private class CountingFetcher(responses: Map<String, String>) : FakeFetcher(responses) {
        var calls = 0

        override fun getText(url: String): String {
            calls++
            return super.getText(url)
        }

        override fun getText(
            url: String,
            extraHeaders: Map<String, String>,
            requestClass: SourceRequestClass,
            cacheTtlMillis: Long
        ): String {
            calls++
            return super.getText(url, extraHeaders, requestClass, cacheTtlMillis)
        }
    }

    private fun sitemap(host: String, count: Int): String =
        "<urlset>" + (1..count).joinToString("") {
            "<url><loc>https://$host/audioknyha-book-$it/</loc></url>"
        } + "</urlset>"

    @Test
    fun `knigi-online catalog stops after a bounded run of empty pages`() = runBlocking {
        val locs = sitemap("knigi-online.com.ua", 400)
        val fetcher = CountingFetcher(
            mapOf("https://knigi-online.com.ua/post-sitemap.xml" to locs)
        )

        val books = KnigiOnlineAdapter(fetcher).fetchCatalog(limit = 60)

        assertTrue(books.isEmpty())
        assertTrue("walked ${fetcher.calls} fetches", fetcher.calls < 20)
    }

    @Test
    fun `audiobook co ua catalog stops after a bounded run of empty pages`() = runBlocking {
        val locs = sitemap("audiobook.co.ua", 400)
        val fetcher = CountingFetcher(
            mapOf("https://audiobook.co.ua/post-sitemap.xml" to locs)
        )

        val books = AudiobookCoUaAdapter(fetcher).fetchCatalog(limit = 60)

        assertTrue(books.isEmpty())
        assertTrue("walked ${fetcher.calls} fetches", fetcher.calls < 20)
    }
}
