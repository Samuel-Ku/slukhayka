package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.collections.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moderation T6 (#839) — the SHARED contract between the app and the curator's
 * bot, pinned by fixtures in `app/src/test/resources/fixtures/`. The bot lives
 * outside this repository, so a drift here is a production bug on both sides:
 * if the app's shape changes without the fixture (or the other way round), this
 * test fails.
 */
class SubmissionModerationContractTest {

    /** The document id the BOT must compute too: sha256(canonicalUrl). */
    private val expectedDocumentId =
        "9a83685cbc05c4584046fff1e0a8698a6e6edee376ea86ff55e3489bf4d86c6f"

    private fun fixture(name: String): Map<String, Any?> {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/$name")
            ?: error("fixture $name is missing")
        val text = stream.bufferedReader().use { it.readText() }
        @Suppress("UNCHECKED_CAST")
        return MiniJson.parse(text) as Map<String, Any?>
    }

    @Test
    fun `the candidate fixture decodes into the app's own shape`() {
        val candidate = SubmissionCandidateCodec.decode(fixture("submission-candidate.json"))

        assertNotNull("the contract fixture must decode", candidate)
        candidate!!
        assertEquals("https://www.youtube.com/watch?v=6XIPkMFZf-0", candidate.url)
        assertEquals("Острів Дума", candidate.title)
        assertEquals("Стівен Кінг", candidate.author)
        assertEquals(5400L, candidate.durationSeconds)
        assertEquals(2, candidate.chaptersCount)
        assertEquals(SubmissionCandidate.State.PENDING, candidate.state)
        assertEquals(expectedDocumentId, candidate.documentId)
        assertEquals("the bot computes the same key", expectedDocumentId, SubmissionCandidateCodec.documentId(candidate.canonicalUrl))
    }

    @Test
    fun `the fixture key set IS the codec's key set - a drifting field fails the contract`() {
        val fixtureKeys = fixture("submission-candidate.json").keys
        val encoded = SubmissionCandidateCodec.encode(
            requireNotNull(
                SubmissionCandidateFactory.create(
                    url = "https://www.youtube.com/watch?v=6XIPkMFZf-0",
                    canonical = "https://www.youtube.com/watch?v=6XIPkMFZf-0",
                    title = "Острів Дума",
                    uid = "device-1",
                    playedAt = 1_700_000_000_000L,
                    createdAt = 1_700_000_001_000L,
                    author = "Стівен Кінг",
                    narrator = "Олександр",
                    coverUrl = "https://i.ytimg.com/vi/6XIPkMFZf-0/hq.jpg",
                    durationSeconds = 5400L,
                    chaptersCount = 2,
                    sourceId = "youtube",
                    metadataJson = "{\"id\":\"6XIPkMFZf-0\"}"
                )
            )
        )

        assertEquals(
            "the app and the bot must agree on the candidate's fields",
            fixtureKeys,
            encoded.keys
        )
    }

    @Test
    fun `a bot approval carries the decision and keeps the queue key`() {
        val approved = SubmissionCandidateCodec.decode(fixture("submission-decision-approve.json"))

        assertNotNull(approved)
        assertEquals(SubmissionCandidate.State.APPROVED, approved!!.state)
        assertEquals(1_700_000_100_000L, approved.decidedAt)
        assertEquals("curator-bot", approved.decidedBy)
        assertEquals("the key never moves with the decision", expectedDocumentId, approved.documentId)
    }

    @Test
    fun `a bot rejection carries the decision too`() {
        val rejected = SubmissionCandidateCodec.decode(fixture("submission-decision-reject.json"))

        assertEquals(SubmissionCandidate.State.REJECTED, rejected?.state)
        assertEquals(1_700_000_200_000L, rejected?.decidedAt)
        assertEquals("curator-bot", rejected?.decidedBy)
    }

    @Test
    fun `the blocklist entry the bot writes decodes into the app's blocklist shape`() {
        val entry = RejectedSubmissionCodec.decode(fixture("submission-blocklist-entry.json"))

        assertNotNull(entry)
        assertEquals("https://www.youtube.com/watch?v=6XIPkMFZf-0", entry!!.canonicalUrl)
        assertEquals("не аудіокнига", entry.reason)
        assertEquals("curator-bot", entry.rejectedBy)
        assertEquals("the blocklist uses the SAME key as the queue", expectedDocumentId, entry.documentId)
        assertTrue(
            "a rejected link is refused before queueing",
            ModerationQueuePolicy.decide(
                canonicalUrl = entry.canonicalUrl,
                rejected = true,
                published = false,
                queued = false
            ) == ModerationQueueDecision.REJECTED
        )
    }

    @Test
    fun `a candidate without a decision is never decoded as decided`() {
        val pending = SubmissionCandidateCodec.decode(SubmissionCandidateCodec.encode(
            requireNotNull(
                SubmissionCandidateFactory.createMetadataOnly(
                    url = "https://t.me/slukhayka/573/1",
                    canonical = "https://t.me/slukhayka/573/1",
                    title = "Джералдова гра",
                    uid = "device-1",
                    createdAt = 1L
                )
            )
        ))

        assertEquals(0L, pending?.playedAt)
        assertEquals(SubmissionCandidate.State.PENDING, pending?.state)
        assertEquals(null, pending?.decidedAt)
        assertEquals(null, pending?.decidedBy)
    }
}
