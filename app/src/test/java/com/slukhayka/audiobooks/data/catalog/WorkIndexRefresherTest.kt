package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceRequestClass
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private class FixtureFetcher(var docs: Map<String, String>) : HttpFetcher() {
        var calls = 0

        override fun getText(
            url: String,
            extraHeaders: Map<String, String>,
            requestClass: SourceRequestClass,
            cacheTtlMillis: Long
        ): String {
            calls++
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
                clock = { 1_000_000L + FeedSnapshotPolicy.CATALOG_TTL_MS + 1 }
            )

            val built = refresher.refreshIfStale()

            assertTrue(fetcher.calls >= 2)
            assertEquals("audiobookcoua", built?.lookup("Ігри Джеральда", "Стівен Кінг")?.sourceId)
        } finally {
            file.delete()
        }
    }
}
