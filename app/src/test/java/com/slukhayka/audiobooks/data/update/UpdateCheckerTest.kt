package com.slukhayka.audiobooks.data.update

import com.slukhayka.audiobooks.data.source.HttpFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-36 T1 (#244) — the update-check door pinned through its ONE seam:
 * a counting fake transport, an in-memory store and an injected clock.
 * Only external behaviour is asserted — what the state flow emits, whether
 * the throttle window anchored, how many requests left. Internals (tag
 * parsing, JSON extraction) are never tested directly.
 */
class UpdateCheckerTest {

    /** Canned-body transport that counts requests (throttle observability). */
    private class CountingFetcher(private val body: String) : HttpFetcher() {
        var calls = 0
        override fun getText(url: String): String {
            calls++
            return body
        }
    }

    private class MemoryStore(override var lastCheckAtMillis: Long = 0L) : UpdateCheckStore

    private fun releaseBody(tag: String?) = """{"tag_name": ${tag?.let { "\"$it\"" } ?: "null"}, "name": "x"}"""

    private fun checker(
        fetcher: HttpFetcher,
        installed: String = "1.1",
        store: UpdateCheckStore = MemoryStore(),
        now: () -> Long = { 1_000_000L }
    ) = UpdateChecker(fetcher, store, clock = now, installedVersionName = installed)

    @Test
    fun `newer release emits version and direct apk url`() = runBlocking {
        val fetcher = CountingFetcher(releaseBody("v1.2"))
        val store = MemoryStore()
        val checker = checker(fetcher, store = store)

        checker.checkNow()

        assertEquals(
            AvailableAppRelease(
                versionName = "1.2",
                apkUrl = "https://github.com/Samuel-Ku/slukhayka/releases/latest/download/slukhayka-v1.2.apk"
            ),
            checker.available.value
        )
        // Success anchors the daily window.
        assertEquals(1_000_000L, store.lastCheckAtMillis)
        assertEquals(1, fetcher.calls)
    }

    @Test
    fun `equal version stays silent but still anchors the window`() = runBlocking {
        val store = MemoryStore()
        val checker = checker(CountingFetcher(releaseBody("v1.1")), store = store)

        checker.checkNow()

        assertNull(checker.available.value)
        assertEquals(1_000_000L, store.lastCheckAtMillis)
    }

    @Test
    fun `older release stays silent`() = runBlocking {
        val checker = checker(CountingFetcher(releaseBody("v1.0")))

        checker.checkNow()

        assertNull(checker.available.value)
    }

    @Test
    fun `garbage responses stay silent and do not anchor the window`() = runBlocking {
        for (body in listOf("", "not json", "{}", releaseBody(null), releaseBody("release-9"), """{"tag_name": 42}""")) {
            val store = MemoryStore(lastCheckAtMillis = 555L)
            val checker = checker(CountingFetcher(body), store = store)

            checker.checkNow()

            assertNull("body must stay silent", checker.available.value)
            assertEquals("body must not anchor", 555L, store.lastCheckAtMillis)
        }
    }

    @Test
    fun `daily throttle suppresses repeat checks inside the window`() = runBlocking {
        val store = MemoryStore()
        val fetcher = CountingFetcher(releaseBody("v9.9"))
        val now = mutableListOf(1_000_000L)
        val checker = checker(fetcher, store = store, now = { now.first() })

        checker.checkNow()
        now[0] += 23L * 60 * 60 * 1000
        checker.checkNow()

        assertEquals(1, fetcher.calls)
        assertEquals("9.9", checker.available.value?.versionName)
    }

    @Test
    fun `after the window the check runs again`() = runBlocking {
        val store = MemoryStore()
        val fetcher = CountingFetcher(releaseBody("v1.2"))
        val now = mutableListOf(1_000_000L)
        val checker = checker(fetcher, store = store, now = { now.first() })

        checker.checkNow()
        now[0] += UpdateChecker.CHECK_INTERVAL_MILLIS
        checker.checkNow()

        assertEquals(2, fetcher.calls)
    }

    @Test
    fun `segment comparison favours the higher minor`() {
        val checker = checker(CountingFetcher(releaseBody("v1.10")), installed = "1.9")
        runBlocking { checker.checkNow() }
        assertEquals("1.10", checker.available.value?.versionName)
    }
}
