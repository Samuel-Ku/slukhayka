package com.slukhayka.audiobooks.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

/** Moderation T1/T3 (#834/#836) — the pre-check and the honest listener verdict. */
class ModerationQueuePolicyTest {

    private val url = "https://youtu.be/abc"

    @Test
    fun `a link nobody knows is queued`() {
        assertEquals(
            ModerationQueueDecision.ENQUEUE,
            ModerationQueuePolicy.decide(url, rejected = false, published = false, queued = false)
        )
    }

    @Test
    fun `an already queued link is a friendly state, not an error`() {
        assertEquals(
            ModerationQueueDecision.ALREADY_QUEUED,
            ModerationQueuePolicy.decide(url, rejected = false, published = false, queued = true)
        )
    }

    @Test
    fun `a published link is not a new candidate`() {
        assertEquals(
            ModerationQueueDecision.ALREADY_PUBLISHED,
            ModerationQueuePolicy.decide(url, rejected = false, published = true, queued = false)
        )
    }

    @Test
    fun `a rejected link can never come back, even if it is also queued`() {
        assertEquals(
            ModerationQueueDecision.REJECTED,
            ModerationQueuePolicy.decide(url, rejected = true, published = false, queued = true)
        )
    }

    @Test
    fun `a link without a canonical form can never be a candidate`() {
        assertEquals(
            ModerationQueueDecision.INVALID,
            ModerationQueuePolicy.decide("   ", rejected = false, published = false, queued = false)
        )
    }

    @Test
    fun `the pre-check is keyed by the same hash the documents use`() {
        assertEquals(
            SubmissionCandidateCodec.documentId(url),
            ModerationQueuePolicy.documentId(url)
        )
        assertEquals("c587c1a3f3f50e8dd88a68bd5bdac0fa12797416a17e55a02f2e699db815b8df", ModerationQueuePolicy.documentId(url))
    }

    @Test
    fun `the listener verdict is honest for every decision`() {
        assertEquals("queued", ModerationQueuePolicy.listenerVerdict(ModerationQueueDecision.ENQUEUE))
        assertEquals("on-moderation", ModerationQueuePolicy.listenerVerdict(ModerationQueueDecision.ALREADY_QUEUED))
        assertEquals("in-shared-base", ModerationQueuePolicy.listenerVerdict(ModerationQueueDecision.ALREADY_PUBLISHED))
        assertEquals("rejected", ModerationQueuePolicy.listenerVerdict(ModerationQueueDecision.REJECTED))
        assertEquals("invalid", ModerationQueuePolicy.listenerVerdict(ModerationQueueDecision.INVALID))
    }
}
