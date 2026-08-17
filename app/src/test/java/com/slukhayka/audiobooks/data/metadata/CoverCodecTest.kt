package com.slukhayka.audiobooks.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-30 T3 (#218) — the Firestore document codec for a shared canonical
 * cover URL: the shape stored in the shared base is pure JVM and round-trips,
 * and a corrupt or implausible document decodes to null (a miss, never a
 * crash). Prior art: [SharedDurationCodecTest].
 */
class CoverCodecTest {

    private val provenance = CoverProvenance(
        source = CoverProvenance.SOURCE_CURATED,
        resolvedAt = 1_234_567L
    )

    @Test
    fun `a cover URL round-trips through the document shape`() {
        val map = CoverCodec.toMap("https://4read.org/img/kniga.jpg", provenance)

        assertEquals("https://4read.org/img/kniga.jpg", map["coverImageUrl"])
        assertEquals(CoverProvenance.SOURCE_CURATED, map["source"])
        assertEquals(1_234_567L, map["resolvedAt"])

        assertEquals("https://4read.org/img/kniga.jpg", CoverCodec.fromMap(map))
    }

    @Test
    fun `a missing or mistyped cover URL decodes to null`() {
        val map = CoverCodec.toMap("https://4read.org/img/kniga.jpg", provenance).toMutableMap()

        map.remove("coverImageUrl")
        assertNull(CoverCodec.fromMap(map))
        map["coverImageUrl"] = 42
        assertNull(CoverCodec.fromMap(map))
    }

    @Test
    fun `a blank cover URL decodes to null`() {
        assertNull(CoverCodec.fromMap(mapOf("coverImageUrl" to "")))
        assertNull(CoverCodec.fromMap(mapOf("coverImageUrl" to "   ")))
    }

    @Test
    fun `a non-http cover URL decodes to null`() {
        assertNull(CoverCodec.fromMap(mapOf("coverImageUrl" to "file:///sdcard/cover.jpg")))
        assertNull(CoverCodec.fromMap(mapOf("coverImageUrl" to "ftp://4read.org/cover.jpg")))
        assertNull(CoverCodec.fromMap(mapOf("coverImageUrl" to "not-a-url")))
    }

    @Test
    fun `an overlong cover URL decodes to null`() {
        assertNull(
            CoverCodec.fromMap(
                mapOf("coverImageUrl" to "https://4read.org/" + "x".repeat(BookProfileLimits.MAX_URL_LEN))
            )
        )
        // The bound itself is still plausible.
        assertEquals(
            "https://4read.org/" + "x".repeat(BookProfileLimits.MAX_URL_LEN - "https://4read.org/".length),
            CoverCodec.fromMap(
                mapOf("coverImageUrl" to "https://4read.org/" + "x".repeat(BookProfileLimits.MAX_URL_LEN - "https://4read.org/".length))
            )
        )
    }

    @Test
    fun `reads ignore the provenance fields - older documents decode fine`() {
        // A document without source/resolvedAt (e.g. written by an earlier
        // client) is still a valid cover document.
        assertEquals(
            "https://4read.org/img/kniga.jpg",
            CoverCodec.fromMap(mapOf("coverImageUrl" to "https://4read.org/img/kniga.jpg"))
        )
    }
}