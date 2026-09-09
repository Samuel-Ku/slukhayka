package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.metadata.SubmissionAccessMode
import com.slukhayka.audiobooks.data.metadata.SubmissionChapter
import com.slukhayka.audiobooks.data.metadata.SubmissionPublication
import com.slukhayka.audiobooks.testing.FakeSharedBookMetaStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0035 / #607 — the anti-spam policy (the PacingPolicy precedent), pure
 * JVM: the playback verdict is the main barrier; the per-device daily budget
 * refuses honestly and exposes the honest remaining count; the same
 * normalized URL is refused; a new UTC day restores the budget.
 */
class SubmissionPolicyTest {

    private val store = FakeSharedBookMetaStore()
    private var now = 10_000L
    private val verification = SubmissionVerification { now }
    private val policy = SubmissionPolicy(store, verification) { now }

    private val sourceId = "youtube-ed1-abc"
    private val url = "https://www.youtube.com/playlist?list=PLking1"

    private fun publish(deviceId: String, at: Long = now): SubmissionPublication = SubmissionPublication(
        sourceUrl = url,
        accessMode = SubmissionAccessMode.YOUTUBE,
        title = "Острів Дума",
        author = "Стівен Кінг",
        chapters = listOf(SubmissionChapter("Розділ 1", "https://www.youtube.com/watch?v=6XIPkMFZf-0")),
        verifiedAt = at - 100,
        submittedAt = at,
        submitterId = deviceId
    )

    @Test
    fun `no verdict never passes`() = runBlocking {
        val decision = policy.decide(sourceId, url, "device-1")
        assertFalse(decision.allowed)
        assertEquals(SubmissionPolicy.Reason.NOT_VERIFIED, decision.reason)
    }

    @Test
    fun `verified submission under the limit is allowed with the honest remaining`() = runBlocking {
        verification.record(sourceId, actualPlaybackStarted = true)
        // One slot already spent today (by another, non-duplicate submission).
        store.incrementSubmissionCount("device-1", SubmissionPolicy.dayKeyOf(now))

        val decision = policy.decide(sourceId, url, "device-1")
        assertTrue(decision.allowed)
        assertEquals(null, decision.reason)
        assertEquals(SubmissionPolicy.DAILY_SUBMISSION_LIMIT - 1, decision.remainingToday.toLong())
    }

    @Test
    fun `over the daily limit refuses honestly with zero remaining`() = runBlocking {
        verification.record(sourceId, actualPlaybackStarted = true)
        repeat(SubmissionPolicy.DAILY_SUBMISSION_LIMIT.toInt()) {
            store.incrementSubmissionCount("device-1", SubmissionPolicy.dayKeyOf(now))
        }

        val decision = policy.decide(sourceId, url, "device-1")
        assertFalse(decision.allowed)
        assertEquals(SubmissionPolicy.Reason.DAILY_LIMIT_REACHED, decision.reason)
        assertEquals(0, decision.remainingToday)
        assertEquals(0, policy.remainingToday("device-1"))
    }

    @Test
    fun `a new utc day restores the budget`() = runBlocking {
        verification.record(sourceId, actualPlaybackStarted = true)
        repeat(SubmissionPolicy.DAILY_SUBMISSION_LIMIT.toInt()) {
            store.incrementSubmissionCount("device-1", SubmissionPolicy.dayKeyOf(now))
        }
        assertFalse(policy.decide(sourceId, url, "device-1").allowed)

        now += 86_400_000L // the next UTC day
        assertTrue(policy.decide(sourceId, url, "device-1").allowed)
        assertEquals(SubmissionPolicy.DAILY_SUBMISSION_LIMIT, policy.remainingToday("device-1").toLong())
    }

    @Test
    fun `the budget is per device`() = runBlocking {
        verification.record(sourceId, actualPlaybackStarted = true)
        repeat(SubmissionPolicy.DAILY_SUBMISSION_LIMIT.toInt()) {
            store.incrementSubmissionCount("device-1", SubmissionPolicy.dayKeyOf(now))
        }
        assertFalse(policy.decide(sourceId, url, "device-1").allowed)
        assertTrue("device-2 keeps its own budget", policy.decide(sourceId, url, "device-2").allowed)
    }

    @Test
    fun `an already published url is refused without consuming a slot`() = runBlocking {
        verification.record(sourceId, actualPlaybackStarted = true)
        store.publishSubmission(publish("device-1"))

        val decision = policy.decide(sourceId, url, "device-2")
        assertFalse(decision.allowed)
        assertEquals(SubmissionPolicy.Reason.ALREADY_PUBLISHED, decision.reason)
        assertEquals(SubmissionPolicy.DAILY_SUBMISSION_LIMIT, decision.remainingToday.toLong())
        assertEquals("nothing was consumed", 0L, store.getSubmissionCount("device-2", SubmissionPolicy.dayKeyOf(now)))
    }

    @Test
    fun `metadata-only decision needs no verdict - the parse is the quality bar`() = runBlocking {
        // No playback verdict exists for a TG preview (no audio) — the
        // decision must NOT be gated on it (ADR-0035 п. 13).
        val decision = policy.decideMetadataOnly(url, "device-1")
        assertTrue(decision.allowed)
        assertEquals(null, decision.reason)
        assertEquals(SubmissionPolicy.DAILY_SUBMISSION_LIMIT, decision.remainingToday.toLong())
    }

    @Test
    fun `metadata-only over the daily limit refuses honestly`() = runBlocking {
        repeat(SubmissionPolicy.DAILY_SUBMISSION_LIMIT.toInt()) {
            store.incrementSubmissionCount("device-1", SubmissionPolicy.dayKeyOf(now))
        }
        val decision = policy.decideMetadataOnly(url, "device-1")
        assertFalse(decision.allowed)
        assertEquals(SubmissionPolicy.Reason.DAILY_LIMIT_REACHED, decision.reason)
        assertEquals(0, decision.remainingToday)
    }

    @Test
    fun `metadata-only duplicate url refuses without consuming`() = runBlocking {
        // The dedup is URL-based and mode-agnostic: a YOUTUBE publication of
        // the same link blocks the metadata-only publish too (one shared base).
        store.publishSubmission(publish("device-1"))
        val decision = policy.decideMetadataOnly(url, "device-2")
        assertFalse(decision.allowed)
        assertEquals(SubmissionPolicy.Reason.ALREADY_PUBLISHED, decision.reason)
        assertEquals(0L, store.getSubmissionCount("device-2", SubmissionPolicy.dayKeyOf(now)))
    }

    @Test
    fun `metadata-only shares ONE budget with playable submissions`() = runBlocking {
        repeat(SubmissionPolicy.DAILY_SUBMISSION_LIMIT.toInt() - 1) {
            store.incrementSubmissionCount("device-1", SubmissionPolicy.dayKeyOf(now))
        }
        assertTrue("one slot left", policy.decideMetadataOnly(url, "device-1").allowed)
        policy.consume("device-1")
        assertFalse(policy.decideMetadataOnly(url, "device-1").allowed)
    }

    @Test
    fun `curator needs the verdict - the main barrier never drops`() = runBlocking {
        val decision = policy.decideCurator(sourceId, url)
        assertFalse(decision.allowed)
        assertEquals(SubmissionPolicy.Reason.NOT_VERIFIED, decision.reason)
    }

    @Test
    fun `curator passes with a verdict and refuses duplicates`() = runBlocking {
        verification.record(sourceId, actualPlaybackStarted = true)
        assertTrue(policy.decideCurator(sourceId, url).allowed)
        store.publishSubmission(publish("device-1"))
        val decision = policy.decideCurator(sourceId, url)
        assertFalse(decision.allowed)
        assertEquals(SubmissionPolicy.Reason.ALREADY_PUBLISHED, decision.reason)
    }

    @Test
    fun `curator ignores the daily limit entirely`() = runBlocking {
        // ADR-0035 п. 12: the seeder differs ONLY in throughput and the
        // absent daily limit — a listener would be refused at 10, the
        // curator seeds on.
        verification.record(sourceId, actualPlaybackStarted = true)
        repeat(SubmissionPolicy.DAILY_SUBMISSION_LIMIT.toInt() * 2) {
            store.incrementSubmissionCount("device-1", SubmissionPolicy.dayKeyOf(now))
        }
        assertTrue(policy.decideCurator(sourceId, url).allowed)
        assertEquals(null, policy.decideCurator(sourceId, url).reason)
    }

    @Test
    fun `consume spends exactly one slot`() = runBlocking {
        policy.consume("device-1")
        policy.consume("device-1")
        assertEquals(2L, store.getSubmissionCount("device-1", SubmissionPolicy.dayKeyOf(now)))
        assertEquals(SubmissionPolicy.DAILY_SUBMISSION_LIMIT - 2, policy.remainingToday("device-1").toLong())
    }
}