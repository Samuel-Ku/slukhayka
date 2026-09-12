package com.slukhayka.audiobooks.data.collective

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #522 — the collective wire shape: one allowed field set, so a document that
 * smuggles a forbidden field is a miss, and anything the bounds reject can
 * never be encoded.
 */
class CollectiveCardCodecTest {

    private fun card(
        title: String = "Кобзар",
        observerAt: Long = 1_700_000_000_000L
    ) = CollectiveCardPublication(
        sourceId = "soundbooks",
        sourceUrl = "https://sound-books.net/kobzar",
        title = title,
        author = "Тарас Шевченко",
        narrator = "Диктор",
        language = "uk",
        coverUrl = "https://sound-books.net/c.jpg",
        seriesTitle = "Кобзар",
        seriesIndex = 1,
        durationSeconds = 7_200L,
        chapterCount = 12,
        observedAt = observerAt
    )

    @Test
    fun `a publishable card round-trips through the wire shape`() {
        val encoded = CollectiveCardCodec.toMap(card())
        assertNotNull(encoded)
        val decoded = CollectiveCardCodec.fromMap(encoded!!)

        assertNotNull(decoded)
        assertEquals("soundbooks", decoded!!.sourceId)
        assertEquals("Кобзар", decoded.title)
        assertEquals(1, decoded.seriesIndex)
        assertEquals(7_200L, decoded.durationSeconds)
        assertEquals(12, decoded.chapterCount)
        assertEquals(1_700_000_000_000L, decoded.observedAt)
    }

    @Test
    fun `an unpublishable card is never encoded`() {
        assertNull(CollectiveCardCodec.toMap(card(title = "")))
        assertNull(
            CollectiveCardCodec.toMap(
                CollectiveCardPublication(
                    sourceId = "4read",
                    sourceUrl = "https://4read.org/x",
                    title = "Х",
                    author = "А",
                    language = "uk",
                    observedAt = 1L
                )
            )
        )
    }

    @Test
    fun `a document carrying any forbidden field is a miss`() {
        val encoded = CollectiveCardCodec.toMap(card())!!.toMutableMap()
        encoded["query"] = "шевченко"
        assertNull(CollectiveCardCodec.fromMap(encoded))

        val withUid = CollectiveCardCodec.toMap(card())!!.toMutableMap()
        withUid["contributorId"] = "user-42"
        assertNull(CollectiveCardCodec.fromMap(withUid))

        val withTrack = CollectiveCardCodec.toMap(card())!!.toMutableMap()
        withTrack["trackUrl"] = "https://cdn/x.mp3"
        assertNull(CollectiveCardCodec.fromMap(withTrack))
    }

    @Test
    fun `a document missing a required field is a miss`() {
        val encoded = CollectiveCardCodec.toMap(card())!!.toMutableMap()
        encoded.remove(CollectiveCardCodec.FIELD_TITLE)
        assertNull(CollectiveCardCodec.fromMap(encoded))

        val noTimestamp = CollectiveCardCodec.toMap(card())!!.toMutableMap()
        noTimestamp.remove(CollectiveCardCodec.FIELD_OBSERVED_AT)
        assertNull(CollectiveCardCodec.fromMap(noTimestamp))
    }

    @Test
    fun `an overlong field decoded from the wire is still rejected`() {
        val encoded = CollectiveCardCodec.toMap(card())!!.toMutableMap()
        encoded[CollectiveCardCodec.FIELD_TITLE] = "т".repeat(CollectiveCardLimits.MAX_TITLE + 1)
        assertNull(CollectiveCardCodec.fromMap(encoded))
    }

    @Test
    fun `page size is bounded`() {
        assertTrue(CollectivePageLimits.bounded(10) == 10)
        assertTrue(CollectivePageLimits.bounded(0) == 0)
        assertTrue(CollectivePageLimits.bounded(-3) == 0)
        assertTrue(CollectivePageLimits.bounded(1_000) == CollectivePageLimits.MAX_PAGE_SIZE)
    }
}
