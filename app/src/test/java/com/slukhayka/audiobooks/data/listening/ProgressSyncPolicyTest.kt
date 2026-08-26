package com.slukhayka.audiobooks.data.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0023 (spec-43 T6) — the pure decision matrix of Progress Sync: what
 * pulls, what pushes, how documents decode. No Android, no coroutines.
 */
class ProgressSyncPolicyTest {

    private fun remote(updatedAt: Long, editionId: String = "ed-1") = RemoteListeningState(
        editionId = editionId,
        chapterIndex = 2,
        positionSeconds = 120L,
        isCompleted = false,
        preferredSpeed = 1.25f,
        updatedAtServerMs = updatedAt
    )

    // --- shouldPull ---------------------------------------------------------

    @Test
    fun `pull matrix - only strictly newer server states apply`() {
        val cases = listOf(
            "no remote document" to Triple(null, 1000L, false),
            "corrupt stamp never applies" to Triple(remote(0L), null as Long?, false),
            "first sight of the cloud" to Triple(remote(5000L), null as Long?, true),
            "newer than what we saw" to Triple(remote(9000L), 5000L, true),
            "equal means we already are the server state" to Triple(remote(5000L), 5000L, false),
            "older than what we saw" to Triple(remote(4000L), 5000L, false)
        )
        for ((name, value) in cases) {
            val (remote, synced, expected) = value
            assertEquals(name, expected, ProgressSyncPolicy.shouldPull(remote, synced))
        }
    }

    // --- shouldPush ---------------------------------------------------------

    @Test
    fun `push matrix - honest moments bypass the pacing window`() {
        val cases = listOf(
            "immediate push ignores everything" to Quad(1000L, null as Long?, true, true),
            "first tick pushes at once" to Quad(1000L, null, false, true),
            "tick inside the window waits" to Quad(30_000L, 0L, false, false),
            "exactly at the window's end goes" to Quad(60_000L, 0L, false, true),
            "tick past the window goes" to Quad(60_001L, 0L, false, true)
        )
        for ((name, value) in cases) {
            val (nowMs, lastAttempt, immediate, expected) = value
            assertEquals(
                name,
                expected,
                ProgressSyncPolicy.shouldPush(nowMs, lastAttempt, immediate)
            )
        }
    }

    @Test
    fun `document id keeps the reviews shape`() {
        assertEquals("uid-1_ed-9", ProgressSyncPolicy.documentId("uid-1", "ed-9"))
    }

    // --- codec --------------------------------------------------------------

    @Test
    fun `codec round-trips a payload without fabricating updatedAt`() {
        val original = remote(42L)
        val doc = ProgressSyncCodec.toDocument("listener", original)
        assertFalse(doc.containsKey(ProgressSyncCodec.FIELD_UPDATED_AT)) // the server stamps it
        assertEquals("listener", doc[ProgressSyncCodec.FIELD_UID])

        val decoded = ProgressSyncCodec.fromDocument(doc + mapOf(ProgressSyncCodec.FIELD_UPDATED_AT to 7777L))
        assertTrue(decoded != null)
        assertEquals(original.copy(updatedAtServerMs = 7777L), decoded)
    }

    @Test
    fun `codec drops corrupt documents instead of crashing`() {
        val base: Map<String, Any> = mapOf(
            ProgressSyncCodec.FIELD_EDITION_ID to "ed-1",
            ProgressSyncCodec.FIELD_CHAPTER_INDEX to 1L,
            ProgressSyncCodec.FIELD_POSITION_SECONDS to 10L,
            ProgressSyncCodec.FIELD_IS_COMPLETED to false,
            ProgressSyncCodec.FIELD_UPDATED_AT to 5L
        )
        val cases: List<Pair<Map<String, Any>, String>> = listOf(
            base - ProgressSyncCodec.FIELD_EDITION_ID to "missing edition",
            (base + mapOf(ProgressSyncCodec.FIELD_EDITION_ID to "")) to "blank edition",
            (base + mapOf(ProgressSyncCodec.FIELD_EDITION_ID to "x".repeat(301))) to "overlong edition",
            (base + mapOf(ProgressSyncCodec.FIELD_CHAPTER_INDEX to -1)) to "negative chapter",
            (base + mapOf(ProgressSyncCodec.FIELD_POSITION_SECONDS to -5L)) to "negative position",
            (base + mapOf(ProgressSyncCodec.FIELD_POSITION_SECONDS to 10_000_000_000L)) to "absurd position",
            (base + mapOf(ProgressSyncCodec.FIELD_PREFERRED_SPEED to 9.0f)) to "speed out of range",
            (base + mapOf(ProgressSyncCodec.FIELD_PREFERRED_SPEED to "fast")) to "speed of a wrong type",
            ((base - ProgressSyncCodec.FIELD_UPDATED_AT)) to "no server stamp",
            (base + mapOf(ProgressSyncCodec.FIELD_UPDATED_AT to 0L)) to "zero stamp"
        )
        for ((doc, name) in cases) {
            assertNull(name, ProgressSyncCodec.fromDocument(doc))
        }
        assertNull("null document decodes to null", ProgressSyncCodec.fromDocument(null))
    }
}

/** Tiny named-tuple helper so matrix rows read like sentences. */
private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
