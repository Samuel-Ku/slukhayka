package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.ingest.ListenerSubmissionFlow
import com.slukhayka.audiobooks.data.ingest.SubmissionState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Moderation T4 (#837) — the card badge is the STORED truth, never a promise. */
class SubmissionBadgePolicyTest {

    private fun row(state: SubmissionState.State, reason: String? = null) = SubmissionState(
        sourceId = "source-1",
        url = "https://youtu.be/abc",
        bookId = "book-1",
        metadataJson = "{}",
        channelId = "",
        state = state,
        reason = reason
    )

    @Test
    fun `no submission means no badge`() {
        assertEquals(SubmissionBadge.NONE, SubmissionBadgePolicy.badgeFor(null))
    }

    @Test
    fun `a queued candidate is on moderation`() {
        assertEquals(
            SubmissionBadge.PENDING_MODERATION,
            SubmissionBadgePolicy.badgeFor(row(SubmissionState.State.PENDING_MODERATION))
        )
    }

    @Test
    fun `an approved candidate is in the shared base`() {
        assertEquals(
            SubmissionBadge.IN_SHARED_BASE,
            SubmissionBadgePolicy.badgeFor(row(SubmissionState.State.PUBLISHED))
        )
    }

    @Test
    fun `only the curator's rejection wears the rejected badge`() {
        assertEquals(
            SubmissionBadge.REJECTED,
            SubmissionBadgePolicy.badgeFor(
                row(SubmissionState.State.REFUSED, ListenerSubmissionFlow.Reason.REJECTED.name)
            )
        )
        assertEquals(
            "a daily-limit refusal is NOT a rejection",
            SubmissionBadge.NONE,
            SubmissionBadgePolicy.badgeFor(
                row(SubmissionState.State.REFUSED, ListenerSubmissionFlow.Reason.DAILY_LIMIT_REACHED.name)
            )
        )
        assertEquals(
            "a metadata refusal is NOT a rejection",
            SubmissionBadge.NONE,
            SubmissionBadgePolicy.badgeFor(
                row(SubmissionState.State.REFUSED, ListenerSubmissionFlow.Reason.METADATA_FAILED.name)
            )
        )
    }

    @Test
    fun `work in progress shows no badge`() {
        assertEquals(
            SubmissionBadge.NONE,
            SubmissionBadgePolicy.badgeFor(row(SubmissionState.State.AWAITING_PLAY))
        )
        assertEquals(
            SubmissionBadge.NONE,
            SubmissionBadgePolicy.badgeFor(row(SubmissionState.State.DEFERRED_PUBLICATION))
        )
        assertEquals(
            SubmissionBadge.NONE,
            SubmissionBadgePolicy.badgeFor(row(SubmissionState.State.WATCHING))
        )
    }
}
