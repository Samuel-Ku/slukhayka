package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.duration.DurationBuckets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-30 T2 (#217) — the Firestore document codec for a shared duration: the
 * shape stored in the shared base is pure JVM and round-trips, and a corrupt
 * or implausible document decodes to null (a miss, never a crash). Prior art:
 * [com.slukhayka.audiobooks.data.universe.SharedResolutionCodecTest].
 */
class SharedDurationCodecTest {

    private val provenance = DurationProvenance(
        source = DurationProvenance.SOURCE_DERIVED,
        derivedAt = 1_234_567L
    )

    @Test
    fun `a duration round-trips through the document shape`() {
        val map = SharedDurationCodec.toMap(7_200L, provenance)

        assertEquals(7_200L, map["durationSeconds"])
        assertEquals(DurationProvenance.SOURCE_DERIVED, map["source"])
        assertEquals(DurationProvenance.METHOD_SOURCE_METADATA, map["method"])
        assertEquals(1_234_567L, map["derivedAt"])

        assertEquals(7_200L, SharedDurationCodec.fromMap(map))
    }

    @Test
    fun `a missing or mistyped duration decodes to null`() {
        val map = SharedDurationCodec.toMap(7_200L, provenance).toMutableMap()

        map.remove("durationSeconds")
        assertNull(SharedDurationCodec.fromMap(map))
        map["durationSeconds"] = "три години"
        assertNull(SharedDurationCodec.fromMap(map))
    }

    @Test
    fun `an implausible duration decodes to null`() {
        // Zero / negative — never real.
        assertNull(SharedDurationCodec.fromMap(mapOf("durationSeconds" to 0L)))
        assertNull(SharedDurationCodec.fromMap(mapOf("durationSeconds" to -5L)))
        // The fabricated 4:00:00 legacy sentinel — treated as unknown everywhere.
        assertNull(
            SharedDurationCodec.fromMap(
                mapOf("durationSeconds" to DurationBuckets.FABRICATED_LEGACY_SECONDS)
            )
        )
        // Above the plausible ceiling — a wild/corrupt value is a miss.
        assertNull(
            SharedDurationCodec.fromMap(
                mapOf("durationSeconds" to DurationSanity.MAX_PLAUSIBLE_SECONDS + 1)
            )
        )
        // The ceiling itself is still plausible.
        assertEquals(
            DurationSanity.MAX_PLAUSIBLE_SECONDS,
            SharedDurationCodec.fromMap(mapOf("durationSeconds" to DurationSanity.MAX_PLAUSIBLE_SECONDS))
        )
    }
}
