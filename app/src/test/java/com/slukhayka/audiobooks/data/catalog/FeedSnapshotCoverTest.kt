package com.slukhayka.audiobooks.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedSnapshotCoverTest {
    @Test
    fun `old LibriVox snapshot gains the detail thumbnail without opening the book`() {
        val cards = FeedSnapshotCodec.decodeBooks("""[{"title":"Socialism","url":"https://archive.org/details/socialism_2609_librivox","sourceId":"librivox"}]""")
        assertEquals("https://archive.org/download/socialism_2609_librivox/__ia_thumb.jpg", cards.single().coverImageUrl)
        assertEquals(cards, FeedSnapshotCodec.decodeBooks(FeedSnapshotCodec.encodeBooks(cards)))
    }

    @Test
    fun `known cover survives snapshot reload and unrelated sources receive no invented cover`() {
        val cards = FeedSnapshotCodec.decodeBooks("""[
            {"title":"Socialism","url":"https://archive.org/details/socialism_2609_librivox","sourceId":"librivox","coverImageUrl":"https://covers.example/curated.jpg"},
            {"title":"Книга","url":"https://4read.org/book.html","sourceId":"4read"},
            {"title":"Invalid mirror","url":"https://archive.org.evil.test/details/item","sourceId":"librivox"}
        ]""")
        assertEquals("https://covers.example/curated.jpg", cards[0].coverImageUrl)
        assertNull(cards[1].coverImageUrl)
        assertNull(cards[2].coverImageUrl)
    }
}
