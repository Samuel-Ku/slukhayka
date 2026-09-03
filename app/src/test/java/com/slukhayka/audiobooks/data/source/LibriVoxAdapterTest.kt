package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-45 T2 (#490) — LibriVoxAdapter fixture tests. Payloads are VERBATIM
 * live responses captured 2026-09-03:
 * - `librivox-api-feed-english.json` — librivox.org API, offset 0 (6 records)
 * - `librivox-api-feed-mixed.json`  — librivox.org API, offset 4390 (6 English
 *   + 2 German records — the English filter's fixture)
 * - `librivox-archive-search-pride.json` — archive.org advanced-search
 *   `"pride and prejudice"` over collection:librivoxaudio (8 docs)
 * - `librivox-archive-new.json` — archive.org advanced-search sorted by
 *   addeddate desc (8 docs)
 */
class LibriVoxAdapterTest {

    private fun fixture(name: String): String {
        val resource = javaClass.classLoader?.getResource("fixtures/$name")
            ?: throw IllegalStateException("Missing fixture $name")
        return resource.readText()
    }

    @Test
    fun `search returns English mirror cards with archive urls and source librivox`() = runBlocking {
        // The archive URL is served via the fallback (the exact query URL is
        // pinned by the live curl captures; the parse is what this tests).
        val adapter = LibriVoxAdapter(
            FakeFetcher(fallback = fixture("librivox-archive-search-pride.json"))
        )

        val cards = adapter.search("Pride and Prejudice")

        assertEquals(8, cards.size)
        val pride = cards.first { it.title == "Pride and Prejudice (version 6 dramatic reading)" }
        assertEquals("Jane Austen", pride.author)
        assertEquals("https://archive.org/details/prideandprejudice_1107_librivox", pride.url)
        assertEquals("librivox", pride.sourceId)
        assertEquals("en", pride.language)
        assertTrue(cards.all { it.url.startsWith("https://archive.org/details/") })
        assertTrue(cards.all { it.language == "en" })
    }

    @Test
    fun `fetchNew preserves the archive addeddate order`() = runBlocking {
        val adapter = LibriVoxAdapter(
            FakeFetcher(fallback = fixture("librivox-archive-new.json"))
        )

        val cards = adapter.fetchNew(4)

        assertEquals(4, cards.size)
        assertEquals("Socialism", cards.first().title)
        assertEquals("https://archive.org/details/socialism_2609_librivox", cards.first().url)
        assertTrue(cards.all { it.sourceId == "librivox" && it.language == "en" })
    }

    @Test
    fun `fetchCatalog parses the api feed and keeps English records only`() = runBlocking {
        // offset 4390 mixes English and German — the English filter's fixture.
        val adapter = LibriVoxAdapter(
            FakeFetcher(
                mapOf(
                    "https://librivox.org/api/feed/audiobooks/?format=json&limit=8&offset=0" to
                        fixture("librivox-api-feed-mixed.json")
                )
            )
        )

        val cards = adapter.fetchCatalog(8)

        // 6 English records survive; the 2 German ones are dropped.
        assertEquals(6, cards.size)
        assertTrue(cards.none { it.title.contains("Gemüthsruhe") || it.title.contains("Vogelöd") })
        val littleMen = cards.first { it.title == "Little Men (version 2)" }
        assertEquals("Louisa May Alcott", littleMen.author)
        assertEquals("https://librivox.org/little-men-version2-by-louisa-may-alcott/", littleMen.url)
        assertEquals("en", littleMen.language)
        assertEquals("librivox", littleMen.sourceId)
        // The API record carries the real duration.
        assertTrue(cards.all { it.totalDurationSeconds > 0L })
    }

    @Test
    fun `fetchCatalog keeps every record of an all-English api page`() = runBlocking {
        val adapter = LibriVoxAdapter(
            FakeFetcher(
                mapOf(
                    "https://librivox.org/api/feed/audiobooks/?format=json&limit=6&offset=0" to
                        fixture("librivox-api-feed-english.json")
                )
            )
        )

        val cards = adapter.fetchCatalog(6)

        assertEquals(6, cards.size)
        assertEquals("Count of Monte Cristo", cards.first().title)
        assertTrue(cards.all { it.language == "en" && it.sourceId == "librivox" })
    }

    @Test
    fun `blank and failing responses degrade to empty lists`() = runBlocking {
        val adapter = LibriVoxAdapter(FakeFetcher(fallback = ""))

        assertTrue(adapter.search("anything").isEmpty())
        assertTrue(adapter.fetchNew(5).isEmpty())
        assertTrue(adapter.fetchCatalog(5).isEmpty())
    }

    @Test
    fun `blank search query returns empty without fetching`() = runBlocking {
        val adapter = LibriVoxAdapter(FakeFetcher(fallback = "boom"))

        assertTrue(adapter.search("   ").isEmpty())
        assertTrue(adapter.search("\"\"\"").isEmpty())
    }

    @Test
    fun `the archive mirror of the same librivox book merges into one card`() {
        val merged = mergeGlobalSearchResults(
            listOf(
                SourceBook(
                    title = "Pride and Prejudice",
                    author = "Jane Austen",
                    url = "https://librivox.org/pride-and-prejudice-by-jane-austen/",
                    sourceId = "librivox"
                ),
                SourceBook(
                    title = "Pride and Prejudice",
                    author = "Jane Austen",
                    url = "https://archive.org/details/prideandprejudice_1107_librivox",
                    sourceId = "librivox"
                )
            )
        )

        // One card per Work, one LibriVox badge — never a duplicate catalogue row.
        assertEquals(1, merged.size)
        val sources = merged.single().sources
        assertEquals(1, sources.size)
        assertEquals("librivox", sources.single().sourceId)
        assertEquals("LibriVox", sources.single().sourceName)
    }

    @Test
    fun `librivox is a direct source with a display name and archive url mapping`() {
        assertEquals(SourceAccessMode.DIRECT, SourceAccessPolicy.modeFor("librivox"))
        assertEquals("LibriVox", sourceDisplayName("librivox"))
        assertEquals("librivox", sourceIdForUrl("https://archive.org/details/socialism_2609_librivox"))
    }
}
