package com.slukhayka.audiobooks.data.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishedCollectionCodecTest {

    private fun sample() = PublishedCollection(
        authorId = CuratorIdentity.authorId("test-uid-1"),
        collectionId = "c1",
        pseudonym = "Слухач",
        title = "Магія",
        description = "про зорі",
        bookIds = listOf("book-a", "book-b"),
        // Canonical form: reasons are ALWAYS aligned to the books. An unaligned
        // document is normalised on decode (see the alignment tests), so the
        // round trip is identity only against this aligned shape.
        reasons = listOf("бо раз", "бо два"),
        publishedAt = 1_700_000_000_000L
    )

    @Test
    fun `a published collection survives a round trip`() {
        val decoded = PublishedCollectionCodec.decode(PublishedCollectionCodec.encode(sample()))
        assertEquals(sample(), decoded)
    }

    @Test
    fun `an unaligned document is normalised, not round-tripped as-is`() {
        // Documents written before reasons existed carry none: decode pads them
        // to the canonical aligned shape. The round trip is therefore identity
        // ONLY against canonical input — worth pinning, because treating the
        // padded form as a difference would be a silent bug later.
        val decoded = PublishedCollectionCodec.decode(
            PublishedCollectionCodec.encode(sample().copy(reasons = emptyList()))
        )!!
        assertEquals(listOf("", ""), decoded.reasons)
        assertEquals(2, decoded.bookIds.size)
    }

    @Test
    fun `the document never carries the raw uid`() {
        val encoded = PublishedCollectionCodec.encode(sample())
        assertTrue(encoded.values.none { it is String && it.contains("test-uid-1") })
        assertTrue(encoded["authorId"] == CuratorIdentity.authorId("test-uid-1"))
    }

    @Test
    fun `a malformed document decodes to null instead of half an object`() {
        assertNull(PublishedCollectionCodec.decode(null))
        assertNull(PublishedCollectionCodec.decode(mapOf("title" to "Магія")))          // no author
        assertNull(PublishedCollectionCodec.decode(mapOf("authorId" to "a")))           // no id/title
        assertNull(
            PublishedCollectionCodec.decode(
                mapOf("authorId" to "a", "collectionId" to "c", "title" to "https://spam.example")
            )
        )
    }

    @Test
    fun `encoding keeps every field inside its limit`() {
        val encoded = PublishedCollectionCodec.encode(
            sample().copy(
                title = "я".repeat(500),
                description = "я".repeat(5_000),
                pseudonym = "п".repeat(200),
                bookIds = List(1_000) { "book-$it" }
            )
        )
        assertEquals(ListenerCollectionLimits.MAX_TITLE_LEN, (encoded["title"] as String).length)
        assertEquals(ListenerCollectionLimits.MAX_DESCRIPTION_LEN, (encoded["description"] as String).length)
        assertEquals(PublishedCollectionCodec.MAX_PSEUDONYM_LEN, (encoded["pseudonym"] as String).length)
        assertEquals(PublishedCollectionCodec.MAX_BOOKS, (encoded["bookIds"] as List<*>).size)
    }

    @Test
    fun `reasons travel with the books, positionally aligned`() {
        val encoded = PublishedCollectionCodec.encode(
            sample().copy(bookIds = listOf("a", "b", "c"), reasons = listOf("бо раз", "бо два"))
        )
        val decoded = PublishedCollectionCodec.decode(encoded)!!

        // Three books, two reasons: the third is an honest empty, never a
        // reason attached to the wrong book.
        assertEquals(listOf("бо раз", "бо два", ""), decoded.reasons)
        assertEquals(3, decoded.bookIds.size)
    }

    @Test
    fun `the rating aggregate round-trips`() {
        val rated = sample().copy(ratingSum = 9, ratingCount = 2)
        val decoded = PublishedCollectionCodec.decode(PublishedCollectionCodec.encode(rated))!!
        assertEquals(9, decoded.ratingSum)
        assertEquals(2, decoded.ratingCount)
    }

    @Test
    fun `the moderation state round-trips`() {
        val moderated = sample().copy(hidden = true, reportCount = 3)
        val decoded = PublishedCollectionCodec.decode(PublishedCollectionCodec.encode(moderated))!!
        assertTrue(decoded.hidden)
        assertEquals(3, decoded.reportCount)
    }

    @Test
    fun `a hostile aggregate decodes to the honest zero, never a fabricated number`() {
        val encoded = PublishedCollectionCodec.encode(sample()) +
            ("ratingSum" to "багато") + ("ratingCount" to -3)
        val decoded = PublishedCollectionCodec.decode(encoded)!!
        assertEquals(0, decoded.ratingSum)
        assertEquals(0, decoded.ratingCount)
    }

    @Test
    fun `a reason never borrows another book's slot`() {
        val encoded = PublishedCollectionCodec.encode(
            sample().copy(bookIds = listOf("a"), reasons = listOf("перше", "друге", "третє"))
        )
        val decoded = PublishedCollectionCodec.decode(encoded)!!
        assertEquals(listOf("перше"), decoded.reasons)
    }
}
