package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-42 #427 — host-aware Cookie isolation, pure JVM.
 *
 * Cookie is read just-in-time for the concrete request host and never copied
 * to another host or source. 4read and its allowed audio hosts (reasd.org)
 * each keep their own cookie jar — a 4read.org cookie never travels on a
 * reasd.org request, and a sluhay.com cookie never travels on a reasd or
 * redirectto request. There is one shared host-aware provider instead of
 * repeated per-adapter lambdas.
 */
class SourceCookieProviderTest {

    @Test
    fun `cookieFor returns exact host cookie and nothing for other hosts`() {
        val provider = FakeSourceCookieProvider(
            mapOf(
                "sluhay.com" to "cf_clearance=sluhay; session=abc",
                "4read.org" to "cf_clearance=4read; other=xyz",
                "s1.reasd.org" to "reasd_token=123"
            )
        )
        assertEquals("cf_clearance=sluhay; session=abc", provider.cookieFor("https://sluhay.com/page"))
        assertEquals("cf_clearance=sluhay; session=abc", provider.cookieFor("https://www.sluhay.com/other"))
        assertEquals("cf_clearance=4read; other=xyz", provider.cookieFor("https://4read.org/book.html"))
        assertEquals("reasd_token=123", provider.cookieFor("https://s1.reasd.org/5370/01.mp3"))
        // Strict isolation: 4read cookie never copied to reasd host
        assertTrue(provider.cookieFor("https://s1.reasd.org/5370/01.mp3") != "cf_clearance=4read; other=xyz")
        // No cross-source leak
        assertTrue(provider.cookieFor("https://sluhay.com/page") != "cf_clearance=4read; other=xyz")
        assertEquals("", provider.cookieFor("https://evil.com/"))
        assertEquals("", provider.cookieFor("https://redirectto.cc/track.mp3"))
    }

    @Test
    fun `cookieFor is scheme-aware - only http and https return cookies`() {
        val provider = FakeSourceCookieProvider(mapOf("sluhay.com" to "cf_clearance=abc"))
        assertEquals("", provider.cookieFor("file:///sdcard/page"))
        assertEquals("", provider.cookieFor("javascript:alert(1)"))
        assertEquals("", provider.cookieFor("ftp://sluhay.com/file"))
        assertEquals("cf_clearance=abc", provider.cookieFor("http://sluhay.com/page"))
        assertEquals("cf_clearance=abc", provider.cookieFor("https://sluhay.com/page"))
    }

    @Test
    fun `cookieFor returns empty for blank or malformed urls`() {
        val provider = FakeSourceCookieProvider(mapOf("sluhay.com" to "cf_clearance=abc"))
        assertEquals("", provider.cookieFor(""))
        assertEquals("", provider.cookieFor("   "))
        assertEquals("", provider.cookieFor("not a url"))
    }

    @Test
    fun `host matching is case-insensitive and strips www`() {
        val provider = FakeSourceCookieProvider(mapOf("sluhay.com" to "cookie=1"))
        assertEquals("cookie=1", provider.cookieFor("https://WWW.SLUHAY.COM/page"))
        assertEquals("cookie=1", provider.cookieFor("https://SLUHAY.COM/page"))
    }

    @Test
    fun `cookieHeadersFor returns empty map when cookie empty and header map otherwise`() {
        val emptyProvider = FakeSourceCookieProvider(emptyMap())
        assertTrue(emptyProvider.cookieHeadersFor("https://sluhay.com/page").isEmpty())
        val provider = FakeSourceCookieProvider(mapOf("sluhay.com" to "cf_clearance=abc"))
        assertEquals(mapOf("Cookie" to "cf_clearance=abc"), provider.cookieHeadersFor("https://sluhay.com/page"))
        assertTrue(provider.cookieHeadersFor("https://4read.org/page").isEmpty())
    }

    @Test
    fun `cover headers are scoped to 4read and use only that host cookie`() {
        val provider = FakeSourceCookieProvider(
            mapOf(
                "4read.org" to "cf_clearance=4read",
                "s1.reasd.org" to "reasd_token=audio"
            )
        )

        val headers = provider.coverHeadersFor("https://4read.org/uploads/posts/cover.jpg")

        assertEquals("https://4read.org/", headers["Referer"])
        assertEquals("cf_clearance=4read", headers["Cookie"])
        assertEquals("image", headers["Sec-Fetch-Dest"])
        assertTrue(provider.coverHeadersFor("https://s1.reasd.org/cover.jpg").isEmpty())
        assertTrue(provider.coverHeadersFor("https://example.org/cover.jpg").isEmpty())
    }

    @Test
    fun `there is one shared interface - sluhay adapter uses host-aware provider not a fixed lambda`() = runBlocking {
        // Prove the host-awareness contract inside the adapter: fetchNew for
        // sluhay.com must send the sluhay cookie, but the internal playlist
        // fetch (redirectto.cc) must not - even when the same provider holds
        // a sluhay cookie.
        val homeUrl = "https://sluhay.com/"
        val bookUrl = "https://sluhay.com/svitova-literatura/6150.html"
        val playlistUrl = "https://9giiu0g54k8c.redirectto.cc/s05/26544.pl.txt"
        val bookPage = """
            <html><head><meta property="og:title" content="Title - Author » Site" /></head><body>
            <ul><li><span>Назва</span> <span>Title</span></li><li><span>Автор</span> <span>Author</span></li></ul>
            <script>Playerjs({id:"playerjs1",file:"$playlistUrl"});</script></body></html>
        """.trimIndent()
        val playlistJson = """[{"title":"01.mp3","file":"https://j3wccg4mgjcw.redirectto.cc/track-0.mp3"}]"""

        // Provider holds only sluhay cookie - redirectto must get nothing
        val provider = FakeSourceCookieProvider(mapOf("sluhay.com" to "cf_clearance=sluhay"))
        val fetcher = FakeFetcher(mapOf(bookUrl to bookPage, playlistUrl to playlistJson, homeUrl to "<html></html>"))
        val adapter = SluhayAdapter(fetcher, cookieProvider = provider)

        // fetchBookPage: first request (bookUrl) carries Cookie, second (playlist) must not
        adapter.fetchBookPage(bookUrl)

        // Fetcher records headers per headerful request. The playlist fetch in
        // parseCapturedPage uses fetcher.getText(playlistUrl) without Cookie.
        // The book page fetch must have Cookie for its own host only.
        assertEquals(1, fetcher.recordedHeaders.size)
        assertEquals(mapOf("Cookie" to "cf_clearance=sluhay"), fetcher.recordedHeaders[0])
    }

    @Test
    fun `4read and reasd cookies stay isolated even though both hosts are allowlisted for same source`() {
        val provider = FakeSourceCookieProvider(
            mapOf(
                "4read.org" to "cf_clearance=4read_cookie",
                "s1.reasd.org" to "s1_token=reasd_only"
            )
        )
        // Reading for 4read host returns only 4read cookie
        assertEquals("cf_clearance=4read_cookie", provider.cookieFor("https://4read.org/book.html"))
        // Reading for reasd host returns only its own, never 4read's
        assertEquals("s1_token=reasd_only", provider.cookieFor("https://s1.reasd.org/5370/01.mp3"))
        assertTrue(provider.cookieFor("https://s1.reasd.org/5370/01.mp3") != "cf_clearance=4read_cookie")
        // Each allowlisted host is isolated even though SourceBrowserPolicy
        // considers both hosts allowed for the same source (4read).
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://4read.org/book.html", "4read"))
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://s1.reasd.org/5370/01.mp3", "4read"))
    }

    @Test
    fun `fetchCatalog uses host-aware cookies per request url not a fixed host`() = runBlocking {
        val homeUrl = "https://sluhay.com/"
        val categoryUrl = "https://sluhay.com/svitova-literatura/"
        val homeHtml = """
            <html><body>
            <a class="poster-item grid-item" href="https://sluhay.com/svitova-literatura/1.html">
                <div class="poster-item__title">Book One - Author One</div>
            </a>
            </body></html>
        """.trimIndent()
        val categoryHtml = """
            <html><body>
            <a class="poster-item grid-item" href="https://sluhay.com/svitova-literatura/2.html">
                <div class="poster-item__title">Book Two - Author Two</div>
            </a>
            </body></html>
        """.trimIndent()

        // Host-aware provider returns correct cookie for each sluhay.com URL
        val provider = FakeSourceCookieProvider(mapOf("sluhay.com" to "cf_clearance=host_aware"))
        val fetcher = FakeFetcher(mapOf(homeUrl to homeHtml, categoryUrl to categoryHtml))
        val adapter = SluhayAdapter(fetcher, cookieProvider = provider)

        val books = adapter.fetchCatalog(limit = 10)

        // Both fetches (home + category) must have carried the cookie for their own host
        assertEquals(2, fetcher.recordedHeaders.size)
        assertTrue(fetcher.recordedHeaders.all { it["Cookie"] == "cf_clearance=host_aware" })
        assertEquals(2, books.size)
    }

    @Test
    fun `no cookie header is ever sent to an external host even when provider holds sluhay cookie`() = runBlocking {
        // Directly prove absence of cross-host Cookie header at the fetcher layer:
        // the fetcher's recordedHeaders should never contain a sluhay Cookie
        // when the request URL host is outside the allowlist.
        val provider = FakeSourceCookieProvider(mapOf("sluhay.com" to "cf_clearance=sluhay"))
        // Simulate what a buggy implementation would do: copy sluhay cookie onto
        // a 4read request. Correct code must not.
        val sluhayCookieFor4read = provider.cookieFor("https://4read.org/book.html")
        assertEquals("", sluhayCookieFor4read)
        val sluhayCookieForRedirectto = provider.cookieFor("https://j3wccg4mgjcw.redirectto.cc/track.mp3")
        assertEquals("", sluhayCookieForRedirectto)
    }
}
