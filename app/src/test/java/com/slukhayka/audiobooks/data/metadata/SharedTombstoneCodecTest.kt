package com.slukhayka.audiobooks.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ADR-0035 / #607 — the shared tombstone document codec, fixture-tested (the
 * CoverCodec precedent): a valid document round-trips; a missing, mistyped,
 * blank, unknown-target or negative document is a MISS, never a crash; the
 * document key is deterministic per TARGET, so re-placing the same tombstone
 * REPLACE-no-ops.
 */
class SharedTombstoneCodecTest {

    private val workTombstone = SharedTombstone(
        targetKind = TombstoneTargetKind.WORK,
        mergeKey = "острів дума|стівен кінг",
        reason = "неякісна начитка",
        placedAt = 1_000,
        curatorId = "curator-7"
    )

    private val sourceTombstone = SharedTombstone(
        targetKind = TombstoneTargetKind.SOURCE,
        sourceUrl = "https://www.youtube.com/playlist?list=PLspam1",
        placedAt = 2_000,
        curatorId = "curator-7"
    )

    @Test
    fun `work tombstone round-trips`() {
        assertEquals(workTombstone, SharedTombstoneCodec.fromMap(SharedTombstoneCodec.toMap(workTombstone)!!))
    }

    @Test
    fun `source tombstone round-trips without reason`() {
        assertEquals(sourceTombstone, SharedTombstoneCodec.fromMap(SharedTombstoneCodec.toMap(sourceTombstone)!!))
    }

    @Test
    fun `empty document is a miss`() {
        assertNull(SharedTombstoneCodec.fromMap(emptyMap()))
    }

    @Test
    fun `unknown target kind is a miss`() {
        assertNull(
            SharedTombstoneCodec.fromMap(
                SharedTombstoneCodec.toMap(workTombstone)!! + ("targetKind" to "universe")
            )
        )
    }

    @Test
    fun `work tombstone without mergeKey is a miss`() {
        assertNull(SharedTombstoneCodec.fromMap(SharedTombstoneCodec.toMap(workTombstone)!! - "mergeKey"))
        assertNull(
            SharedTombstoneCodec.fromMap(
                SharedTombstoneCodec.toMap(workTombstone)!! + ("mergeKey" to "   ")
            )
        )
    }

    @Test
    fun `source tombstone without a real url is a miss`() {
        assertNull(SharedTombstoneCodec.fromMap(SharedTombstoneCodec.toMap(sourceTombstone)!! - "sourceUrl"))
        assertNull(
            SharedTombstoneCodec.fromMap(
                SharedTombstoneCodec.toMap(sourceTombstone)!! + ("sourceUrl" to "youtu.be/spam1")
            )
        )
    }

    @Test
    fun `negative placedAt or blank curator is a miss`() {
        assertNull(
            SharedTombstoneCodec.fromMap(
                SharedTombstoneCodec.toMap(workTombstone)!! + ("placedAt" to -1L)
            )
        )
        assertNull(SharedTombstoneCodec.fromMap(SharedTombstoneCodec.toMap(workTombstone)!! - "curatorId"))
    }

    @Test
    fun `overlong curatorId in a wild document is a miss`() {
        assertNull(
            SharedTombstoneCodec.fromMap(
                SharedTombstoneCodec.toMap(workTombstone)!! +
                    ("curatorId" to "c".repeat(SharedTombstoneLimits.MAX_CURATOR_ID_LEN + 1))
            )
        )
    }

    @Test
    fun `document key is deterministic per target - re-placing replaces`() {
        val rePlace = workTombstone.copy(placedAt = 9_000)
        assertEquals(
            SharedTombstoneCodec.documentId(workTombstone),
            SharedTombstoneCodec.documentId(rePlace)
        )
        assertEquals("work-${Integer.toHexString("острів дума|стівен кінг".hashCode())}", SharedTombstoneCodec.documentId(workTombstone))
        assertEquals(
            "src-${Integer.toHexString("https://www.youtube.com/playlist?list=PLspam1".hashCode())}",
            SharedTombstoneCodec.documentId(sourceTombstone)
        )
        assertNull(SharedTombstoneCodec.documentId(workTombstone.copy(mergeKey = null)))
    }
}