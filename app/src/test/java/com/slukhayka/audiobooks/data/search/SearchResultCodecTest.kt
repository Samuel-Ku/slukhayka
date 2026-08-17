package com.slukhayka.audiobooks.data.search

import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-33 T1 (#229) — the Firestore document codec for a merged search
 * result: the shape stored in the shared base is pure JVM and round-trips,
 * and a corrupt or wild document decodes to null (a miss, never a crash).
 * Prior art: [com.slukhayka.audiobooks.data.metadata.SharedDurationCodecTest]
 * and [com.slukhayka.audiobooks.data.universe.SharedResolutionCodecTest].
 */
class SearchResultCodecTest {

    private val fetchedAt = 1_000_000L

    /** One rich card — covers, narrator and duration included (spec-33 US-4). */
    private val richCard = GlobalSearchResult(
        title = "Кобзар",
        author = "Тарас Шевченко",
        narrator = "Богдан Ступка",
        mergeKey = "кобзар|тарас шевченко",
        coverImageUrl = "https://4read.org/covers/kobzar.jpg",
        durationSeconds = 7_200,
        sources = listOf(
            GlobalSearchSource("4read", "4read", "https://4read.org/kobzar"),
            GlobalSearchSource("sluhayua", "Sluhay", "https://sluhay.com.ua/kobzar")
        )
    )

    @Test
    fun `a rich result entry round-trips through the document shape`() {
        val document = SearchResultCodec.toMap(fetchedAt, listOf(richCard))

        assertEquals(fetchedAt, document["fetchedAt"])
        val decoded = SearchResultCodec.fromMap(document)
        assertEquals(SearchCacheEntry(fetchedAt, listOf(richCard)), decoded)
    }

    @Test
    fun `a result without optional fields round-trips`() {
        val sparseCard = GlobalSearchResult(
            title = "Хіба ревуть воли, як ясла повні",
            author = "Панас Мирний",
            mergeKey = "",
            sources = listOf(GlobalSearchSource("4read", "4read", "https://4read.org/volы"))
        )

        val decoded = SearchResultCodec.fromMap(
            SearchResultCodec.toMap(fetchedAt, listOf(sparseCard))
        )

        assertEquals(SearchCacheEntry(fetchedAt, listOf(sparseCard)), decoded)
    }

    @Test
    fun `a missing or mistyped fetchedAt decodes to null`() {
        val document = SearchResultCodec.toMap(fetchedAt, listOf(richCard)).toMutableMap()

        document.remove("fetchedAt")
        assertNull(SearchResultCodec.fromMap(document))
        document["fetchedAt"] = "вчора"
        assertNull(SearchResultCodec.fromMap(document))
    }

    @Test
    fun `a missing, mistyped or empty results list decodes to null`() {
        val document = SearchResultCodec.toMap(fetchedAt, listOf(richCard)).toMutableMap()

        document.remove("results")
        assertNull(SearchResultCodec.fromMap(document))
        document["results"] = "не список"
        assertNull(SearchResultCodec.fromMap(document))
        document["results"] = emptyList<Any>()
        assertNull(SearchResultCodec.fromMap(document))
    }

    @Test
    fun `a card without a title decodes to null`() {
        val noTitle = SearchResultCodec.toMap(fetchedAt, listOf(richCard)).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val cards = noTitle["results"] as MutableList<Map<String, Any>>
        val card = cards[0].toMutableMap()
        card.remove("title")
        cards[0] = card
        noTitle["results"] = cards

        assertNull(SearchResultCodec.fromMap(noTitle))
    }

    @Test
    fun `a card without any source URL decodes to null`() {
        val noSources = SearchResultCodec.toMap(fetchedAt, listOf(richCard)).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val cards = noSources["results"] as MutableList<Map<String, Any>>
        val card = cards[0].toMutableMap()
        card["sources"] = emptyList<Any>()
        cards[0] = card
        noSources["results"] = cards

        assertNull(SearchResultCodec.fromMap(noSources))
    }

    @Test
    fun `a source without a url decodes to null`() {
        val noUrl = SearchResultCodec.toMap(fetchedAt, listOf(richCard)).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val cards = noUrl["results"] as MutableList<Map<String, Any>>
        val card = cards[0].toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val sources = (card["sources"] as List<Map<String, Any>>).toMutableList()
        val source = sources[0].toMutableMap()
        source.remove("url")
        sources[0] = source
        card["sources"] = sources
        cards[0] = card
        noUrl["results"] = cards

        assertNull(SearchResultCodec.fromMap(noUrl))
    }

    @Test
    fun `a document with more than the card bound decodes to null`() {
        // A wild/oversized document (whatever wrote it) is a miss, never a
        // crash — the decode bound mirrors the encode bound.
        val document = SearchResultCodec.toMap(fetchedAt, listOf(richCard)).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val cards = document["results"] as List<Map<String, Any>>
        document["results"] = List(SearchResultCodec.MAX_RESULTS + 1) { cards[0] }

        assertNull(SearchResultCodec.fromMap(document))
    }

    @Test
    fun `the write shape is bounded to the card cap`() {
        val manyCards = List(SearchResultCodec.MAX_RESULTS + 5) { index ->
            richCard.copy(title = "Книга $index")
        }

        val decoded = SearchResultCodec.fromMap(SearchResultCodec.toMap(fetchedAt, manyCards))
        assertEquals(SearchResultCodec.MAX_RESULTS, decoded?.results?.size)
    }

    @Test
    fun `the write cap is about 50 cards`() {
        // spec-33 T3 (#228): «Ліміт ~50 карток на результат» — the cap sits
        // in the 50s, not in the hundreds, so the shared base stays bounded.
        assertTrue(
            "MAX_RESULTS=${SearchResultCodec.MAX_RESULTS} should be ~50",
            SearchResultCodec.MAX_RESULTS in 40..60
        )
    }

    @Test
    fun `a card without a title is dropped from the write shape`() {
        val noTitle = richCard.copy(title = "   ")

        val decoded = SearchResultCodec.fromMap(
            SearchResultCodec.toMap(fetchedAt, listOf(noTitle, richCard))
        )

        // The incomplete card never reaches the document — sanitation happens
        // on the WRITE path (spec-33 T3), not only on a defensive read.
        assertEquals(listOf(richCard), decoded?.results)
    }

    @Test
    fun `a card without any source URL is dropped from the write shape`() {
        val noUrl = richCard.copy(
            sources = listOf(
                GlobalSearchSource("4read", "4read", "   "),
                GlobalSearchSource("sluhayua", "Sluhay", "")
            )
        )

        val decoded = SearchResultCodec.fromMap(
            SearchResultCodec.toMap(fetchedAt, listOf(noUrl, richCard))
        )

        assertEquals(listOf(richCard), decoded?.results)
    }

    @Test
    fun `a card with at least one usable source URL survives the write shape`() {
        val mixed = richCard.copy(
            sources = listOf(
                GlobalSearchSource("4read", "4read", "   "),
                GlobalSearchSource("sluhayua", "Sluhay", "https://sluhay.com.ua/kobzar")
            )
        )

        val decoded = SearchResultCodec.fromMap(
            SearchResultCodec.toMap(fetchedAt, listOf(mixed))
        )

        // The card survives; the unusable source is sanitized out of the
        // written document (per-source sanitation mirrors the card rule), so
        // the round-trip carries only the playable source.
        assertEquals(listOf(mixed.copy(sources = listOf(mixed.sources[1]))), decoded?.results)
    }

    @Test
    fun `an all-junk result writes an empty document`() {
        // Sanitation can empty the list entirely — the write then carries no
        // cards at all (the caller's no-negative-cache rule keeps it out of
        // the store; the codec itself stays honest about the shape).
        val junk = richCard.copy(title = "", sources = listOf(GlobalSearchSource("4read", "4read", "")))

        val document = SearchResultCodec.toMap(fetchedAt, listOf(junk))

        @Suppress("UNCHECKED_CAST")
        val cards = document["results"] as List<*>
        assertTrue(cards.isEmpty())
        assertEquals(fetchedAt, document["fetchedAt"])
    }
}