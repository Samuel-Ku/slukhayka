package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.FakeSourceCookieProvider
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceRequestClass
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Spec-49 follow-up, layer B — the Work index refresher: sitemap URLs and
 * catalogue cards land in ONE index (cards carry the exact MergeKey), the
 * TTL bounds the network, and a persisted fresh index skips every request.
 * No network: the guarded transport is a canned fixture.
 */
class WorkIndexRefresherTest {

    private val coUaUrl = "https://audiobook.co.ua/post-sitemap.xml"
    private val chytayloUrl = "https://chytaylo.com.ua/sitemap.xml"
    private val sluhayUrl = "https://sluhay.com/news_pages.xml"

    private class FixtureFetcher(
        var docs: Map<String, String>,
        /** URLs that answer only with a Cookie header (a session-bound sitemap). */
        private val sessionRequired: Set<String> = emptySet()
    ) : HttpFetcher() {
        var calls = 0
        val headers = mutableMapOf<String, Map<String, String>>()

        override fun getText(
            url: String,
            extraHeaders: Map<String, String>,
            requestClass: SourceRequestClass,
            cacheTtlMillis: Long
        ): String {
            calls++
            headers[url] = extraHeaders
            if (url in sessionRequired && extraHeaders["Cookie"].isNullOrBlank()) return ""
            return docs[url].orEmpty()
        }
    }

    private fun sitemap(vararg locs: String): String =
        "<urlset>" + locs.joinToString("") { "<url><loc>$it</loc></url>" } + "</urlset>"

    private fun fetcher() = FixtureFetcher(
        mapOf(
            coUaUrl to sitemap("https://audiobook.co.ua/igra-dzheralda-stiven-king/"),
            chytayloUrl to sitemap("https://chytaylo.com.ua/books/tini-zabutykh-predkiv")
        )
    )

    private fun cardSources() = mapOf<String, WorkIndexRefresher.CardSource>(
        "knigionline" to WorkIndexRefresher.CardSource { _ ->
            listOf(
                SourceBook(
                    title = "Кобзар",
                    author = "Тарас Шевченко",
                    url = "https://knigi-online.com.ua/audioknyha-kobzar/",
                    sourceId = "knigionline"
                )
            )
        }
    )

    @Test
    fun `sitemaps and cards land in one index and lookup prefers the card mergeKey`() = runTest {
        val refresher = WorkIndexRefresher(
            fetcher = fetcher(),
            store = null,
            cardSources = cardSources(),
            clock = { 1_000_000L }
        )

        val built = refresher.refreshIfStale()

        assertTrue((built?.size ?: 0) >= 3)
        assertEquals("knigionline", refresher.lookup("Кобзар", "Тарас Шевченко")?.sourceId)
        assertEquals("chytaylo", refresher.lookup("Тіні забутих предків", "Михайло Коцюбинський")?.sourceId)
        assertEquals("audiobookcoua", refresher.lookup("Ігри Джеральда", "Стівен Кінг")?.sourceId)
    }

    @Test
    fun `a persisted fresh index skips every request`() = runTest {
        val file = File.createTempFile("work-index", ".tsv").also { it.delete() }
        try {
            WorkIndexStore(file).save(
                PersistedWorkIndex(
                    entries = listOf(
                        CatalogIndexEntry(
                            sourceId = "knigionline",
                            url = "https://knigi-online.com.ua/audioknyha-kobzar/",
                            slug = "",
                            mergeKey = MergeKey.keyFor("Кобзар", "Тарас Шевченко")
                        )
                    ),
                    refreshedAtMs = 1_000_000L
                )
            )
            val fetcher = fetcher()
            val refresher = WorkIndexRefresher(
                fetcher = fetcher,
                store = WorkIndexStore(file),
                cardSources = cardSources(),
                clock = { 1_000_000L + 60_000L }
            )

            refresher.refreshIfStale()

            assertEquals(0, fetcher.calls)
            assertEquals("knigionline", refresher.lookup("Кобзар", "Тарас Шевченко")?.sourceId)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a stale persisted index rebuilds from the network`() = runTest {
        val file = File.createTempFile("work-index", ".tsv").also { it.delete() }
        try {
            WorkIndexStore(file).save(
                PersistedWorkIndex(
                    entries = listOf(CatalogIndexEntry("knigionline", "https://old.example/x", "")),
                    refreshedAtMs = 1_000_000L
                )
            )
            val fetcher = fetcher()
            val refresher = WorkIndexRefresher(
                fetcher = fetcher,
                store = WorkIndexStore(file),
                cardSources = cardSources(),
                clock = { 1_000_000L + SitemapPolicy.SITEMAP_TTL_MS + 1 }
            )

            val built = refresher.refreshIfStale()

            assertTrue(fetcher.calls >= 2)
            assertEquals("audiobookcoua", built?.lookup("Ігри Джеральда", "Стівен Кінг")?.sourceId)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a session-bound sitemap carries the host cookie and lands only book urls`() = runTest {
        // #725 — sluhay.com's inventory sits behind Cloudflare: the carrier
        // reads the live WebView cookie through the shared provider, and only
        // the book URLs (never category/tag/static pages) land.
        val fetcher = FixtureFetcher(
            docs = mapOf(
                sluhayUrl to sitemap(
                    "https://sluhay.com/ukrayinska-literatura/5855-melamed-gennadyy-myy-superdydus.html",
                    "https://sluhay.com/fantastika/5854-london-dzhek-kynec-kazki.html",
                    "https://sluhay.com/kazka/",
                    "https://sluhay.com/top.html"
                )
            ),
            sessionRequired = setOf(sluhayUrl)
        )
        val refresher = WorkIndexRefresher(
            fetcher = fetcher,
            store = null,
            cookieProvider = FakeSourceCookieProvider(mapOf("sluhay.com" to "cf_clearance=abc")),
            clock = { 1_000_000L }
        )

        val built = refresher.refreshIfStale()

        assertEquals("cf_clearance=abc", fetcher.headers[sluhayUrl]?.get("Cookie"))
        assertEquals("only the two book urls", 2, built?.size)
    }

    @Test
    fun `without a session the session-bound sitemap contributes nothing`() = runTest {
        val fetcher = FixtureFetcher(
            docs = mapOf(
                sluhayUrl to sitemap("https://sluhay.com/kazka/5853-melamed-gennadyy-myy-superdydus.html")
            ),
            sessionRequired = setOf(sluhayUrl)
        )
        val refresher = WorkIndexRefresher(
            fetcher = fetcher,
            store = null,
            cookieProvider = FakeSourceCookieProvider(),
            clock = { 1_000_000L }
        )

        // Best-effort: a cookie-free request stays blank, nothing is built.
        assertNull(refresher.refreshIfStale())
        assertEquals(0, fetcher.headers[sluhayUrl]?.size ?: 0)
    }

    // --- #526 — the sitemap index as a cheap weekly URL inventory ----------

    @Test
    fun `the persisted sitemap index lives a week across restarts`() = runTest {
        val file = File.createTempFile("work-index", ".tsv").apply { delete() }
        var now = 1_000_000L
        val first = fetcher()
        WorkIndexRefresher(
            fetcher = first,
            store = WorkIndexStore(file),
            cardSources = emptyMap(),
            clock = { now }
        ).refreshIfStale()
        val firstCalls = first.calls
        assertTrue(firstCalls > 0)

        // A restart inside the week reads the file, not the network.
        now += SitemapPolicy.SITEMAP_TTL_MS - 1
        val second = fetcher()
        WorkIndexRefresher(
            fetcher = second,
            store = WorkIndexStore(file),
            cardSources = emptyMap(),
            clock = { now }
        ).refreshIfStale()
        assertEquals("inside the week no sitemap is requested", 0, second.calls)

        // Past the week it reads them again.
        now += 2
        val third = fetcher()
        WorkIndexRefresher(
            fetcher = third,
            store = WorkIndexStore(file),
            cardSources = emptyMap(),
            clock = { now }
        ).refreshIfStale()
        assertTrue("past the week the sitemaps are read again", third.calls > 0)
        file.delete()
    }

    @Test
    fun `a malformed sitemap never erases the last good index`() = runTest {
        val file = File.createTempFile("work-index", ".tsv").apply { delete() }
        var now = 1_000_000L
        val fetcher = fetcher()
        val refresher = WorkIndexRefresher(
            fetcher = fetcher,
            store = WorkIndexStore(file),
            cardSources = emptyMap(),
            clock = { now }
        )
        val built = refresher.refreshIfStale()
        assertTrue(built!!.size > 0)

        // Past the TTL the sources answer garbage — the previous index stays.
        now += SitemapPolicy.SITEMAP_TTL_MS + 1
        fetcher.docs = mapOf(
            coUaUrl to "<html>challenge</html>",
            chytayloUrl to "<html>challenge</html>"
        )
        val again = refresher.refreshIfStale()

        assertEquals("the last good index is still served", built.size, again!!.size)
        file.delete()
    }
}
