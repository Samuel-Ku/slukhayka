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
        publishedAt = 1_700_000_000_000L
    )

    @Test
    fun `a published collection survives a round trip`() {
        val decoded = PublishedCollectionCodec.decode(PublishedCollectionCodec.encode(sample()))
        assertEquals(sample(), decoded)
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
}
