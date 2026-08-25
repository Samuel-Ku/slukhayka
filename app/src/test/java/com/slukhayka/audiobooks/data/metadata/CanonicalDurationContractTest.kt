package com.slukhayka.audiobooks.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalDurationContractTest {

    @Test
    fun `a plausible observation creates a missing canonical duration`() {
        val decision = DurationObservationPolicy.decide(
            canonicalDocumentExists = false,
            canonicalDurationSeconds = null,
            candidateSeconds = 7_200L
        )

        assertEquals(DurationWriteDecision.CreateCanonical, decision)
    }

    @Test
    fun `an observation matching the canonical duration is a no-op`() {
        val decision = DurationObservationPolicy.decide(
            canonicalDocumentExists = true,
            canonicalDurationSeconds = 7_200L,
            candidateSeconds = 7_200L
        )

        assertEquals(DurationWriteDecision.NoOp, decision)
    }

    @Test
    fun `a small measurement difference is equivalent and does not create a conflict`() {
        val decision = DurationObservationPolicy.decide(
            canonicalDocumentExists = true,
            canonicalDurationSeconds = 7_200L,
            candidateSeconds = 7_260L
        )

        assertEquals(DurationWriteDecision.NoOp, decision)
    }

    @Test
    fun `a materially different plausible observation creates a conflict`() {
        val decision = DurationObservationPolicy.decide(
            canonicalDocumentExists = true,
            canonicalDurationSeconds = 7_200L,
            candidateSeconds = 8_000L
        )

        assertEquals(DurationWriteDecision.CreateConflict, decision)
    }

    @Test
    fun `an implausible observation is always a no-op`() {
        val implausible = listOf(
            0L,
            -1L,
            com.slukhayka.audiobooks.data.duration.DurationBuckets.FABRICATED_LEGACY_SECONDS,
            DurationSanity.MAX_PLAUSIBLE_SECONDS + 1L
        )

        implausible.forEach { candidate ->
            assertEquals(
                DurationWriteDecision.NoOp,
                DurationObservationPolicy.decide(
                    canonicalDocumentExists = false,
                    canonicalDurationSeconds = null,
                    candidateSeconds = candidate
                )
            )
        }
    }

    @Test
    fun `a conflict document carries only Edition candidate and bounded provenance`() {
        val conflict = DurationConflict(
            editionId = "edition-1",
            candidateSeconds = 8_000L,
            provenance = DurationProvenance(
                source = "4read",
                derivedAt = 1_700_000_000_000L,
                method = DurationProvenance.METHOD_TECHNICAL_PROBE
            )
        )

        val encoded = DurationConflictCodec.toMap(conflict)

        assertEquals(
            setOf("editionId", "candidateSeconds", "source", "method", "observedAt"),
            encoded.keys
        )
        assertEquals(conflict, DurationConflictCodec.fromMap(encoded))
    }

    @Test
    fun `malformed or personally tagged conflict documents decode to a miss`() {
        val valid = mapOf<String, Any>(
            "editionId" to "edition-1",
            "candidateSeconds" to 8_000L,
            "source" to "4read",
            "method" to DurationProvenance.METHOD_SOURCE_METADATA,
            "observedAt" to 1_700_000_000_000L
        )
        val malformed = listOf(
            valid + ("editionId" to ""),
            valid + ("editionId" to "x".repeat(301)),
            valid + ("editionId" to "edition|ambiguous"),
            valid + ("candidateSeconds" to 0L),
            valid + ("candidateSeconds" to 8_000.5),
            valid + ("source" to ""),
            valid + ("source" to "x".repeat(101)),
            valid + ("method" to ""),
            valid + ("method" to "x".repeat(101)),
            valid + ("method" to "probe|ambiguous"),
            valid + ("observedAt" to -1L),
            valid + ("observedAt" to 1.5),
            valid + ("uid" to "listener-1")
        )

        malformed.forEach { assertNull(DurationConflictCodec.fromMap(it)) }
    }

    @Test
    fun `conflict identity deduplicates Edition value and method`() {
        val metadata = DurationConflict(
            editionId = "edition-1",
            candidateSeconds = 8_000L,
            provenance = DurationProvenance(
                source = "4read",
                derivedAt = 1_700_000_000_000L,
                method = DurationProvenance.METHOD_SOURCE_METADATA
            )
        )
        val repeatedLater = metadata.copy(
            provenance = metadata.provenance.copy(source = "lihtar", derivedAt = 1_800_000_000_000L)
        )
        val technicalProbe = metadata.copy(
            provenance = metadata.provenance.copy(method = DurationProvenance.METHOD_TECHNICAL_PROBE)
        )

        assertEquals(DurationConflictId.of(metadata), DurationConflictId.of(repeatedLater))
        org.junit.Assert.assertNotEquals(DurationConflictId.of(metadata), DurationConflictId.of(technicalProbe))
        assertEquals(
            "edition-1|8000|source_metadata",
            DurationConflictId.of(metadata)
        )
    }
}
