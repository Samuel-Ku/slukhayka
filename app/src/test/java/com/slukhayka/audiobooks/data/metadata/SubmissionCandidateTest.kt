package com.slukhayka.audiobooks.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Moderation T1 (#834) — the candidate shape, its key and the hashed identity. */
class SubmissionCandidateTest {

    private val canonical = "https://youtu.be/abc"

    private fun candidate() = SubmissionCandidateFactory.create(
        url = "https://www.youtube.com/watch?v=abc",
        canonical = canonical,
        title = "Кобзар",
        uid = "listener-uid-1",
        playedAt = 1_700_000_000_000L,
        createdAt = 1_700_000_001_000L,
        author = "Тарас Шевченко",
        narrator = "Іван",
        coverUrl = "https://c/1.jpg",
        durationSeconds = 3_600L,
        chaptersCount = 12,
        sourceId = "youtube",
        metadataJson = "{\"id\":\"abc\"}"
    )!!

    @Test
    fun `the queue key is the canonical URL's hash, pinned`() {
        assertEquals(
            "c587c1a3f3f50e8dd88a68bd5bdac0fa12797416a17e55a02f2e699db815b8df",
            SubmissionCandidateCodec.documentId(canonical)
        )
        assertEquals("c587c1a3f3f50e8dd88a68bd5bdac0fa12797416a17e55a02f2e699db815b8df", candidate().documentId)
        assertEquals("", SubmissionCandidateCodec.documentId("  "))
    }

    @Test
    fun `the document never carries the raw uid`() {
        val encoded = SubmissionCandidateCodec.encode(candidate())

        assertEquals(64, (encoded["submitterHash"] as String).length)
        assertTrue(encoded.values.none { it == "listener-uid-1" })
    }

    @Test
    fun `a fresh candidate is pending and has no decision fields`() {
        val encoded = SubmissionCandidateCodec.encode(candidate())

        assertEquals("pending", encoded["state"])
        assertTrue("no decidedAt before the bot decides", "decidedAt" !in encoded)
        assertTrue("no decidedBy before the bot decides", "decidedBy" !in encoded)
    }

    @Test
    fun `the candidate round-trips through the codec`() {
        val original = candidate()
        val decoded = SubmissionCandidateCodec.decode(SubmissionCandidateCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `the bot's decision round-trips too`() {
        val decided = candidate().copy(
            state = SubmissionCandidate.State.APPROVED,
            decidedAt = 1_700_000_100_000L,
            decidedBy = "curator-bot"
        )
        val decoded = SubmissionCandidateCodec.decode(SubmissionCandidateCodec.encode(decided))

        assertEquals(SubmissionCandidate.State.APPROVED, decoded?.state)
        assertEquals(1_700_000_100_000L, decoded?.decidedAt)
        assertEquals("curator-bot", decoded?.decidedBy)
    }

    @Test
    fun `a hostile or incomplete document is a miss, never a half-valid candidate`() {
        val valid = SubmissionCandidateCodec.encode(candidate())

        assertNull(SubmissionCandidateCodec.decode(null))
        assertNull("missing identity", SubmissionCandidateCodec.decode(valid - "canonicalUrl"))
        assertNull("blank title", SubmissionCandidateCodec.decode(valid + ("title" to "   ")))
        assertNull("no submitter hash", SubmissionCandidateCodec.decode(valid - "submitterHash"))
        assertNull("unknown state", SubmissionCandidateCodec.decode(valid + ("state" to "maybe")))
    }

    @Test
    fun `nothing unverified or anonymous is ever queued`() {
        val base = mapOf(
            "url" to canonical,
            "canonical" to canonical,
            "title" to "Кобзар",
            "uid" to "u1",
            "playedAt" to 1L,
            "createdAt" to 1L
        )

        assertNull(
            "no real-playback verdict means no candidate",
            SubmissionCandidateFactory.create(
                url = base.getValue("url") as String,
                canonical = canonical,
                title = "Кобзар",
                uid = "u1",
                playedAt = 0L,
                createdAt = 1L
            )
        )
        assertNull(
            "no identity means no candidate",
            SubmissionCandidateFactory.create(
                url = canonical,
                canonical = canonical,
                title = "Кобзар",
                uid = null,
                playedAt = 1L,
                createdAt = 1L
            )
        )
    }
}
