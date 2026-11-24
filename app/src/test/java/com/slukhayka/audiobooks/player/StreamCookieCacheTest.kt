package com.slukhayka.audiobooks.player

import com.slukhayka.audiobooks.data.source.SourceCookieProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * #1722 — the cache is the main-thread-safe half of the WebView cookie read:
 * [StreamCookieCache.cachedHeader] is a plain memory read, and only
 * [StreamCookieCache.refresh] may touch the provider (and therefore the
 * synchronous `CookieManager.getCookie` Binder call).
 */
class StreamCookieCacheTest {

    private class RecordingCookieProvider(
        private val cookie: String,
        private val onCall: (Thread) -> Unit = {}
    ) : SourceCookieProvider {
        @Volatile
        var calls: Int = 0

        override fun cookieFor(url: String): String {
            calls += 1
            onCall(Thread.currentThread())
            return cookie
        }
    }

    @Test
    fun `cachedHeader never calls the provider`() {
        val provider = RecordingCookieProvider("cf_clearance=abc")
        val cache = StreamCookieCache(provider, "https://4read.org/")

        repeat(10) { assertEquals("", cache.cachedHeader()) }

        assertEquals("the cached read must be pure memory", 0, provider.calls)
    }

    @Test
    fun `refresh reads the jar on the IO dispatcher and publishes the header`() = runBlocking {
        val caller = Thread.currentThread()
        var providerThread: Thread? = null
        val provider = RecordingCookieProvider("cf_clearance=abc") { providerThread = it }
        val cache = StreamCookieCache(provider, "https://4read.org/", Dispatchers.IO)

        val header = cache.refresh()

        assertEquals("cf_clearance=abc", header)
        assertEquals("cf_clearance=abc", cache.cachedHeader())
        assertEquals(1, provider.calls)
        assertNotSame("the Binder read must not run on the caller thread", caller, providerThread)
    }

    @Test
    fun `refresh keeps the previous header when the provider fails`() = runBlocking {
        var fail = false
        val provider = object : SourceCookieProvider {
            override fun cookieFor(url: String): String =
                if (fail) error("cookie store unavailable") else "cf_clearance=abc"
        }
        val cache = StreamCookieCache(provider, "https://4read.org/", Dispatchers.IO)
        cache.refresh()

        fail = true

        assertEquals("cf_clearance=abc", cache.refresh())
        assertEquals("cf_clearance=abc", cache.cachedHeader())
    }
}
