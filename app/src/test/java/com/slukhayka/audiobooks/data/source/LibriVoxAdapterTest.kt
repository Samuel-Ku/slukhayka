package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * - `librivox-archive-metadata-escaped.json` — crafted archive metadata whose
 *   chapter titles carry escaped quotes, braces, a backslash and a `\uXXXX`
 *   escape (R2 #509 regression), and whose description has no narrator claim
 * - `librivox-api-feed-escaped.json` — crafted API feed with an escaped
 *   quote/brace title plus a German record (R2 #509 regression)
 *
 * Spec-51 (#742) adds the multilingual admission cases: the Russian drop in
 * the mirror transport and at the detail door, the un-gated archive queries,
 * and the non-English narrator claim.
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
        assertEquals("https://archive.org/download/prideandprejudice_1107_librivox/__ia_thumb.jpg", pride.coverImageUrl)
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
        assertEquals("https://archive.org/download/socialism_2609_librivox/__ia_thumb.jpg", cards.first().coverImageUrl)
        assertTrue(cards.all { it.sourceId == "librivox" && it.language == "en" })
    }

    @Test
    fun fetchCatalogAdmitsMappedLanguages() = runBlocking {
        // offset 4390 mixes English and German — both mapped; the wider
        // catalogue (A) keeps the German records with their real language.
        val adapter = LibriVoxAdapter(
            FakeFetcher(
                mapOf(
                    "https://librivox.org/api/feed/audiobooks/?format=json&limit=8&offset=0" to
                        fixture("librivox-api-feed-mixed.json")
                )
            )
        )

        val cards = adapter.fetchCatalog(8)

        // All 8 records survive: 6 English + 2 German (mapped, not hidden).
        assertEquals(8, cards.size)
        assertTrue(cards.all { it.language.isNotBlank() })
        val littleMen = cards.first { it.title == "Little Men (version 2)" }
        assertEquals("Louisa May Alcott", littleMen.author)
        // Cards carry the archive.org mirror page (T3 #491 plays from it) —
        // the identifier the api embeds in `url_zip_file`.
        assertEquals("https://archive.org/details/little_men_1107_librivox", littleMen.url)
        assertEquals("https://archive.org/download/little_men_1107_librivox/__ia_thumb.jpg", littleMen.coverImageUrl)
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
    fun `fetchBookPage parses ordered chapters with real streams from the archive metadata`() = runBlocking {
        val adapter = LibriVoxAdapter(
            FakeFetcher(fallback = fixture("librivox-archive-metadata-socialism.json"))
        )

        val detail = adapter.fetchBookPage("https://archive.org/details/socialism_2609_librivox")

        assertEquals("Socialism", detail.title)
        assertEquals("Edwin Clyde Robbins", detail.author)
        assertEquals("en", detail.language)
        assertEquals(
            "https://archive.org/download/socialism_2609_librivox/__ia_thumb.jpg",
            detail.coverImageUrl
        )
        assertTrue(detail.description.contains("LibriVox"))
        // 22 VBR MP3 sections; the 64/128 Kbps duplicates and covers are never chapters.
        assertEquals(22, detail.chapters.size)
        val first = detail.chapters.first()
        assertEquals("01 - Robbins, E. C., Introductory Statement", first.title)
        assertEquals(
            "https://archive.org/download/socialism_2609_librivox/socialism_01_robbins.mp3",
            first.streamUrl
        )
        assertEquals(558L, first.durationSeconds) // id3 "length": "09:18"
        // Chapters follow the id3 track order.
        assertTrue(detail.chapters[1].streamUrl.endsWith("socialism_02_robbins.mp3"))
    }

    @Test
    fun `fetchBookPage of a non-archive or blank url reports nothing playable`() = runBlocking {
        val adapter = LibriVoxAdapter(FakeFetcher(fallback = ""))

        // A librivox.org page has no archive identifier the adapter can play.
        val page = adapter.fetchBookPage("https://librivox.org/socialism-by-edwin-clyde-robbins/")
        assertTrue(page.chapters.isEmpty())

        // A blank archive response degrades the same way.
        val blank = adapter.fetchBookPage("https://archive.org/details/socialism_2609_librivox")
        assertTrue(blank.chapters.isEmpty())
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
    fun `fetchBookPage decodes escaped quotes braces and unicode escapes in chapter titles`() = runBlocking {
        val adapter = LibriVoxAdapter(
            FakeFetcher(fallback = fixture("librivox-archive-metadata-escaped.json"))
        )

        val detail = adapter.fetchBookPage("https://archive.org/details/escaped_0000_librivox")

        // R2 (#509): an escaped quote before a brace in a title must never
        // end the object early — all three VBR chapters survive, the 128Kbps
        // duplicate and the cover never become chapters.
        assertEquals(3, detail.chapters.size)
        assertEquals("01 - Chapter with \"quoted\" {braces} inside", detail.chapters[0].title)
        assertEquals("02 - Backslash \\ path and \u2665 heart", detail.chapters[1].title)
        assertEquals("03 - No track number, ordered by name", detail.chapters[2].title)
        assertEquals(
            "https://archive.org/download/escaped_0000_librivox/escaped_02_test.mp3",
            detail.chapters[1].streamUrl
        )
        assertEquals(330L, detail.chapters[0].durationSeconds)
        assertEquals("escaped_0000_librivox", detail.coverImageUrl!!.substringAfter("download/").substringBefore('/'))
    }

    @Test
    fun `fetchCatalog parses escaped titles without truncating records`() = runBlocking {
        val adapter = LibriVoxAdapter(
            FakeFetcher(
                mapOf(
                    "https://librivox.org/api/feed/audiobooks/?format=json&limit=5&offset=0" to
                        fixture("librivox-api-feed-escaped.json")
                )
            )
        )

        val cards = adapter.fetchCatalog(5)

        // Both records survive whole: the escaped-quote-brace one and the
        // German one (mapped language, not hidden).
        assertEquals(2, cards.size)
        assertEquals("A \"Quoted\" {Title} with braces", cards.first().title)
        assertEquals("Quoted Author", cards.first().author)
        assertEquals(36000L, cards.first().totalDurationSeconds)
        assertEquals("en", cards.first().language)
    }

    @Test
    fun `fetchBookPage extracts the confirmed narrator from the LibriVox description`() = runBlocking {
        val adapter = LibriVoxAdapter(
            FakeFetcher(fallback = fixture("librivox-archive-metadata-socialism.json"))
        )

        val detail = adapter.fetchBookPage("https://archive.org/details/socialism_2609_librivox")

        // R3 (#510): the standard "Read in English by <name>" phrase is the
        // confirmed claim — the fixture names Ted Lienhart.
        assertEquals("Ted Lienhart", detail.narrator)
    }

    @Test
    fun `fetchBookPage leaves the narrator empty when no reliable claim exists`() = runBlocking {
        val adapter = LibriVoxAdapter(
            FakeFetcher(fallback = fixture("librivox-archive-metadata-escaped.json"))
        )

        val detail = adapter.fetchBookPage("https://archive.org/details/escaped_0000_librivox")

        // No narrator phrase in the description: no name, and the author's
        // text must never become the narrator.
        assertEquals("", detail.narrator)
        assertTrue(detail.chapters.isNotEmpty())
    }

    @Test
    fun `corrupted json degrades to empty without fabricated tracks`() = runBlocking {
        val corrupted = "{\"books\":[{\"title\":\"truncated\""
        val adapter = LibriVoxAdapter(FakeFetcher(fallback = corrupted))

        assertTrue(adapter.fetchCatalog(5).isEmpty())
        assertTrue(adapter.search("anything").isEmpty())
        val page = adapter.fetchBookPage("https://archive.org/details/socialism_2609_librivox")
        assertTrue(page.chapters.isEmpty())
    }

    @Test
    fun `librivox is a direct source with a display name and archive url mapping`() {
        assertEquals(SourceAccessMode.DIRECT, SourceAccessPolicy.modeFor("librivox"))
        assertEquals("LibriVox", sourceDisplayName("librivox"))
        assertEquals("librivox", sourceIdForUrl("https://archive.org/details/socialism_2609_librivox"))
    }

    @Test
    fun `search drops russian mirror docs and keeps every other mapped language`() = runBlocking {
        // Spec-51 (#742): the mirror now serves the whole collection; a
        // "rus" doc is dropped at parse (the one standing exclusion), while
        // a German doc keeps its real language — and the English gate that
        // used to hide it is gone.
        val json = """
            {"response":{"docs":[
              {"identifier":"voina_i_mir_2601_librivox","title":"Война и мир","creator":"Лев Толстой","language":"rus"},
              {"identifier":"die_schatzinsel_2212_librivox","title":"Die Schatzinsel","creator":"Robert Louis Stevenson","language":"ger"}
            ]}}
        """.trimIndent()
        val adapter = LibriVoxAdapter(FakeFetcher(fallback = json))

        val cards = adapter.search("Stevenson")

        assertEquals(1, cards.size)
        assertEquals("Die Schatzinsel", cards.single().title)
        assertEquals("de", cards.single().language)
        assertEquals("https://archive.org/details/die_schatzinsel_2212_librivox", cards.single().url)
    }

    @Test
    fun `the archive queries no longer gate on english`() = runBlocking {
        // Spec-51 (#742): search and new arrivals cover every admitted
        // language — the listener's Content Language Preference filters the
        // results downstream instead of the query hiding non-English rows.
        val fetcher = FakeFetcher(fallback = "{\"response\":{\"docs\":[]}}")
        val adapter = LibriVoxAdapter(fetcher)

        adapter.search("Kafka")
        adapter.fetchNew(5)

        assertEquals(2, fetcher.requestedUrls.size)
        assertTrue(fetcher.requestedUrls.all { it.contains("collection%3Alibrivoxaudio") })
        assertFalse(fetcher.requestedUrls.any { it.contains("language%3Aeng") || it.contains("language:eng") })
    }

    @Test
    fun `fetchBookPage refuses a russian archive item`() = runBlocking {
        val json = """
            {"metadata":{"title":"Война и мир","creator":"Лев Толстой","language":"rus",
             "description":"<p>Read in Russian by Кто-то</p>"},
             "files":[{"format":"VBR MP3","name":"voina_01.mp3","title":"01 - Глава","track":"1","length":"10:00"}]}
        """.trimIndent()
        val adapter = LibriVoxAdapter(FakeFetcher(fallback = json))

        val detail = adapter.fetchBookPage("https://archive.org/details/voina_i_mir_2601_librivox")

        // The admission exclusion holds at the detail door: no Edition, no
        // playable chapters — never a Russian source materialised silently.
        assertTrue(detail.chapters.isEmpty())
        assertEquals("", detail.title)
    }

    @Test
    fun `fetchBookPage reads a non-english narrator claim`() = runBlocking {
        val json = """
            {"metadata":{"title":"Die Schatzinsel","creator":"Robert Louis Stevenson","language":"ger",
             "description":"<p>Read in German by Otto Normal</p>"},
             "files":[{"format":"VBR MP3","name":"die_schatzinsel_01.mp3","title":"01 - Kapitel","track":"1","length":"10:00"}]}
        """.trimIndent()
        val adapter = LibriVoxAdapter(FakeFetcher(fallback = json))

        val detail = adapter.fetchBookPage("https://archive.org/details/die_schatzinsel_2601_librivox")

        // The claim names its own language — «Read in German by X» is read
        // for what it is, never filtered through an English-only pattern.
        assertEquals("de", detail.language)
        assertEquals("Otto Normal", detail.narrator)
        assertEquals(1, detail.chapters.size)
    }
}
