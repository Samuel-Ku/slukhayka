package com.slukhayka.audiobooks.data.reviews

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-40 #277 — the [ListenerReview] Firestore document codec — pure JVM.
 * Table-driven, mirroring [com.slukhayka.audiobooks.data.metadata.BookProfileCodecTest]'s
 * defensiveness: the shape round-trips, the write path truncates to the
 * limits, and a corrupt/hostile document (mistyped fields, fractional or
 * out-of-range rating, over-limit strings) is a miss, never a crash.
 */
class ListenerReviewCodecTest {

    private val review = ListenerReview(
        workId = "work-123",
        uid = "uid-abc",
        authorName = "Читач_Олег",
        rating = 4,
        body = "Чудова начитка, слухав не відриваючись.",
        editionTag = "Валерій Завалко",
        createdAt = 1_755_000_000_000L,
        editedAt = 1_756_000_000_000L
    )

    @Test
    fun `a rich review round-trips through the codec`() {
        assertEquals(review, ListenerReviewCodec.fromMap(ListenerReviewCodec.toMap(review)))
    }

    @Test
    fun `a minimal review round-trips - only the required fields`() {
        val minimal = review.copy(body = null, editionTag = null, editedAt = null)

        val decoded = ListenerReviewCodec.fromMap(ListenerReviewCodec.toMap(minimal))

        assertEquals(minimal, decoded)
        assertNull(decoded?.body)
        assertNull(decoded?.editionTag)
        assertNull(decoded?.editedAt)
    }

    @Test
    fun `the document key is workId then uid`() {
        assertEquals("work-123_uid-abc", ListenerReviewCodec.documentId("work-123", "uid-abc"))
    }

    @Test
    fun `rating bounds - 1 and 5 decode, 0 and 6 are a miss`() {
        val doc = ListenerReviewCodec.toMap(review).toMutableMap()

        listOf(ListenerReviewLimits.MIN_RATING, ListenerReviewLimits.MAX_RATING).forEach { rating ->
            assertEquals(rating, ListenerReviewCodec.fromMap(doc + ("rating" to rating))?.rating)
        }
        listOf(0, 6, -3, 99).forEach { rating ->
            assertNull("rating $rating must be rejected", ListenerReviewCodec.fromMap(doc + ("rating" to rating)))
        }
    }

    @Test
    fun `a fractional rating is corrupt - never silently truncated`() {
        val doc = ListenerReviewCodec.toMap(review).toMutableMap()

        doc["rating"] = 3.5
        assertNull(ListenerReviewCodec.fromMap(doc))
        // An exact whole Double is fine — Firestore numbers arrive as Longs,
        // but a JSON-sourced integral double is still a real rating.
        doc["rating"] = 4.0
        assertEquals(4, ListenerReviewCodec.fromMap(doc)?.rating)
    }

    @Test
    fun `a mistyped field is a miss - never a crash`() {
        val base = ListenerReviewCodec.toMap(review)

        assertNull(ListenerReviewCodec.fromMap(base - "rating"))
        assertNull(ListenerReviewCodec.fromMap(base - "createdAt"))
        assertNull(ListenerReviewCodec.fromMap(base - "uid"))
        assertNull(ListenerReviewCodec.fromMap(base + ("rating" to "п'ять")))
        assertNull(ListenerReviewCodec.fromMap(base + ("body" to 42)))
        assertNull(ListenerReviewCodec.fromMap(base + ("editionTag" to true)))
        assertNull(ListenerReviewCodec.fromMap(base + ("editedAt" to "вчора")))
        assertNull(ListenerReviewCodec.fromMap(emptyMap()))
    }

    @Test
    fun `blank identity fields are a miss`() {
        val base = ListenerReviewCodec.toMap(review)

        assertNull(ListenerReviewCodec.fromMap(base + ("workId" to "   ")))
        assertNull(ListenerReviewCodec.fromMap(base + ("authorName" to "")))
    }

    @Test
    fun `the write path truncates over-limit strings to the limits`() {
        val huge = review.copy(
            body = "х".repeat(ListenerReviewLimits.MAX_BODY_LEN + 500),
            authorName = "о".repeat(ListenerReviewLimits.MAX_AUTHOR_LEN + 50),
            editionTag = "н".repeat(ListenerReviewLimits.MAX_EDITION_TAG_LEN + 30),
            workId = "w".repeat(ListenerReviewLimits.MAX_WORK_ID_LEN + 10)
        )

        val written = ListenerReviewCodec.toMap(huge)

        assertEquals(ListenerReviewLimits.MAX_BODY_LEN, (written["body"] as String).length)
        assertEquals(ListenerReviewLimits.MAX_AUTHOR_LEN, (written["authorName"] as String).length)
        assertEquals(ListenerReviewLimits.MAX_EDITION_TAG_LEN, (written["editionTag"] as String).length)
        assertEquals(ListenerReviewLimits.MAX_WORK_ID_LEN, (written["workId"] as String).length)
    }

    @Test
    fun `an over-limit string on READ is a hostile document - a miss, not a clip`() {
        val doc = ListenerReviewCodec.toMap(review).toMutableMap()

        doc["body"] = "b".repeat(ListenerReviewLimits.MAX_BODY_LEN + 1)
        assertNull(ListenerReviewCodec.fromMap(doc))

        doc["body"] = "ok"
        doc["editionTag"] = "t".repeat(ListenerReviewLimits.MAX_EDITION_TAG_LEN + 1)
        assertNull(ListenerReviewCodec.fromMap(doc))

        doc["editionTag"] = "ok"
        doc["authorName"] = "a".repeat(ListenerReviewLimits.MAX_AUTHOR_LEN + 1)
        assertNull(ListenerReviewCodec.fromMap(doc))
    }

    @Test
    fun `optional blanks are dropped on write and decode as absent`() {
        val blanked = review.copy(body = "   ", editionTag = "", editedAt = null)

        val doc = ListenerReviewCodec.toMap(blanked)

        assertEquals(setOf("workId", "uid", "authorName", "rating", "createdAt"), doc.keys)
        assertNull(ListenerReviewCodec.fromMap(doc)?.body)
    }
}
