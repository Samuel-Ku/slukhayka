package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #814 — the JVM half of the «кожне джерело має грати» campaign.
 *
 * The per-source fixture tests pin each parser against its own live markup.
 * This suite pins the ONE contract every source adapter shares, driven by the
 * registry itself rather than a hand-kept list, so a source cannot be added,
 * renamed or re-tiered without the Android side proving it:
 *
 *  1. **Coverage** — every registered source except the `local`
 *     pseudo-source (the listener's own files, not a transport) is wired to
 *     exactly one adapter, and no adapter targets an unregistered id. A new
 *     `sources.json` entry fails this suite until its adapter exists.
 *  2. **Declaration congruence** — each adapter declares the registry's
 *     access mode and content language. A drift here silently re-tiers a
 *     source in selection order and blanks its language facet.
 *  3. **Honest refusal** — on a dead transport every adapter answers an empty
 *     list/page, never a fabricated book, chapter or error. Empty is a
 *     verdict, not a failure: the caller decides gating.
 *  4. **Owned, stable identity** — `bookId` belongs to the adapter's own
 *     source and is deterministic for the same URL.
 *
 * Everything here is device-free: adapters run on the pure-JVM
 * [FakeFetcher], so a green suite proves the seams, not that a CDN answered.
 * The live half (real audio bytes, DRM, Cloudflare, expiry) is the phone
 * campaign in the ticket and stays unproven on CI by construction.
 */
class SourcePlaybackContractTest {

    /**
     * The production adapter set, exactly as the composition root (`App.kt`)
     * builds it — one adapter per playable source, the sibling site riding the
     * shared sluhay adapter. Only the transport differs: a [FakeFetcher] instead
     * of the real HTTP stack, so nothing here touches the network.
     */
    private fun adaptersOn(transport: HttpFetcher): List<SourceAdapter> = listOf(
        FourReadAdapter(fetcher = transport),
        SoundBooksAdapter(fetcher = transport),
        AudiobookMp3Adapter(fetcher = transport),
        LihtarAdapter(fetcher = transport),
        SluhayuaAdapter(fetcher = transport),
        SluhayAdapter(fetcher = transport),
        SluhayAdapter(
            fetcher = transport,
            site = SluhaySite(sourceId = "sluhayknigi", origin = "https://sluhayknigi.com")
        ),
        AudiobookCoUaAdapter(fetcher = transport),
        ChytayloAdapter(fetcher = transport),
        UkrainianaudiobooksAdapter(fetcher = transport),
        LibriVoxAdapter(fetcher = transport),
        KnigiOnlineAdapter(fetcher = transport),
        ChitakaAdapter(fetcher = transport),
        TgPreviewSourceAdapter()
    )

    /** Every fetch falls back to the empty string — a dead, silent transport. */
    private val deadTransport = FakeFetcher()
    private val adapters: List<SourceAdapter> by lazy { adaptersOn(deadTransport) }

    @Test
    fun `every registered playable source is wired to exactly one adapter`() {
        // `local` is the listener's own downloaded files, not a transport.
        val expected = (SourceRegistry.ids() - "local").sorted()
        val actual = adapters.map { it.sourceId }

        assertEquals("each adapter owns a distinct source id", actual.size, actual.toSet().size)
        assertEquals("every registered playable source has an adapter", expected, actual.sorted())
    }

    @Test
    fun `every adapter declares the registry access mode`() {
        for (adapter in adapters) {
            assertEquals(
                "${adapter.sourceId}: adapter and registry disagree on access mode",
                SourceRegistry.modeFor(adapter.sourceId),
                adapter.accessMode
            )
        }
    }

    @Test
    fun `every adapter declares the registry content language`() {
        for (adapter in adapters) {
            assertEquals(
                "${adapter.sourceId}: adapter and registry disagree on content language",
                SourceRegistry.contentLanguage(adapter.sourceId),
                adapter.contentLanguage
            )
        }
    }

    @Test
    fun `a dead transport is an honest empty - never a fabricated card`() = runBlocking {
        for (adapter in adapters) {
            assertTrue("${adapter.sourceId}: search invented a card", adapter.search("кобзар").isEmpty())
            assertTrue("${adapter.sourceId}: fetchNew invented a card", adapter.fetchNew(limit = 10).isEmpty())
            assertTrue("${adapter.sourceId}: fetchCatalog invented a card", adapter.fetchCatalog(limit = 10).isEmpty())
            assertTrue(
                "${adapter.sourceId}: fetchGenrePage invented a card",
                adapter.fetchGenrePage(genrePath = "genre", limit = 10).books.isEmpty()
            )
        }
    }

    @Test
    fun `a dead transport yields no playable chapter - never a fabricated stream`() = runBlocking {
        for (adapter in adapters) {
            val detail = adapter.fetchBookPage("https://example.invalid/book")
            assertTrue(
                "${adapter.sourceId}: a dead transport invented a chapter",
                detail.chapters.isEmpty()
            )
            assertTrue(
                "${adapter.sourceId}: a dead transport invented a stream host",
                detail.chapters.all { it.streamUrl.startsWith("http") }
            )
        }
    }

    @Test
    fun `bookId is deterministic and belongs to its own source`() {
        for (adapter in adapters) {
            val url = "https://${hostOf(adapter.sourceId)}/some-book"
            val first = adapter.bookId(url)
            val second = adapter.bookId(url)

            assertEquals("${adapter.sourceId}: bookId is not deterministic", first, second)
            assertTrue(
                "${adapter.sourceId}: bookId is not owned by the source ($first)",
                first.startsWith("${adapter.sourceId}-")
            )
        }
    }

    @Test
    fun `bookId stays deterministic when the url carries no slug`() {
        // A series/landing link ending in "/" has no last path segment. The id
        // must still be stable: a timestamp fallback mints a NEW Work every
        // import and defeats the merge by construction.
        for (adapter in adapters) {
            val url = "https://${hostOf(adapter.sourceId)}/"
            assertEquals(
                "${adapter.sourceId}: a slug-less url mints a new id every call",
                adapter.bookId(url),
                adapter.bookId(url)
            )
        }
    }

    /** The host each source's pages live on, for a plausible per-source URL. */
    private fun hostOf(sourceId: String): String =
        SourceRegistry.facts(sourceId)?.homeUrl
            ?.removePrefix("https://")
            ?.removePrefix("http://")
            ?.trimEnd('/')
            ?.ifBlank { null }
            ?: "$sourceId.example"
}
