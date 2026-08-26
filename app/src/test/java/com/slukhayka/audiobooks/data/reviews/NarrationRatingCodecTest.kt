package com.slukhayka.audiobooks.data.reviews

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ADR-0023 (#348) — the narration-rating document shape, pinned the same way
 * as [ListenerReviewCodec]: roundtrip fidelity, bounded writes, fail-closed
 * decode (a hostile or corrupt document is a miss, never a crash), integral
 * ratings only.
 */
class NarrationRatingCodecTest {

    private val rating = NarrationRating(
        workId = "кобзарь_тарас_шевченко",
        uid = "uid-123",
        editionId = "abc-hash-64",
        rating = 4,
        createdAt = 1_756_000_000_000L
    )

    @Test
    fun `document key is workId uid editionId`() {
        assertEquals(
            "кобзарь_тарас_шевченко_uid-123_abc-hash-64",
            NarrationRatingCodec.documentId("кобзарь_тарас_шевченко", "uid-123", "abc-hash-64")
        )
    }

    @Test
    fun `roundtrip keeps every field`() {
        val decoded = NarrationRatingCodec.fromMap(NarrationRatingCodec.toMap(rating))

        assertEquals(rating, decoded)
    }

    @Test
    fun `editedAt survives the roundtrip when present`() {
        val edited = rating.copy(editedAt = 1_756_100_000_000L)

        assertEquals(edited, NarrationRatingCodec.fromMap(NarrationRatingCodec.toMap(edited)))
    }

    @Test
    fun `over-limit workId is truncated on encode`() {
        val longWork = "w".repeat(500)
        val map = NarrationRatingCodec.toMap(rating.copy(workId = longWork))

        org.junit.Assert.assertEquals(
            NarrationRatingLimits.MAX_WORK_ID_LEN,
            (map["workId"] as String).length
        )
    }

    @Test
    fun `decode drops an out-of-range rating`() {
        assertNull(NarrationRatingCodec.fromMap(validMap().plus("rating" to 6)))
        assertNull(NarrationRatingCodec.fromMap(validMap().plus("rating" to 0)))
    }

    @Test
    fun `decode drops a fractional rating`() {
        assertNull(NarrationRatingCodec.fromMap(validMap().plus("rating" to 3.5)))
    }

    @Test
    fun `decode drops blank or missing identity fields`() {
        assertNull(NarrationRatingCodec.fromMap(validMap().plus("workId" to "   ")))
        assertNull(NarrationRatingCodec.fromMap(validMap() - "editionId"))
        assertNull(NarrationRatingCodec.fromMap(validMap().plus("uid" to 42)))
    }

    @Test
    fun `decode drops a mistyped timestamp`() {
        assertNull(NarrationRatingCodec.fromMap(validMap().plus("createdAt" to "yesterday")))
    }

    @Test
    fun `a fully valid document survives`() {
        assertNotNull(NarrationRatingCodec.fromMap(validMap()))
    }

    private fun validMap(): Map<String, Any> = mapOf(
        "workId" to "some-work",
        "uid" to "some-uid",
        "editionId" to "some-edition",
        "rating" to 5L,
        "createdAt" to 1_756_000_000_000L
    )
}
