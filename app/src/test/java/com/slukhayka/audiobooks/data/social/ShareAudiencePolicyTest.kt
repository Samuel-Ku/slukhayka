package com.slukhayka.audiobooks.data.social

import org.junit.Assert.assertEquals
import org.junit.Test

/** #893 — the invariants of `docs/specs/2026-09-16-social-layer.md` §1 and §6. */
class ShareAudiencePolicyTest {

    private fun draft(kind: ShareKind = ShareKind.NOTE, text: String = "Дочитав розділ") =
        ShareDraft(kind = kind, text = text, sourceId = "book-1")

    @Test
    fun `an unconfirmed draft publishes nothing - closing the form is not a share`() {
        val decision = ShareAudiencePolicy.decide(draft(), Audience.FRIENDS, confirmed = false)

        assertEquals(ShareDecision.Refused(ShareDecision.Reason.NOT_CONFIRMED), decision)
    }

    @Test
    fun `a confirmed draft with a chosen audience becomes a post`() {
        val decision = ShareAudiencePolicy.decide(draft(), Audience.FRIENDS, confirmed = true)

        assertEquals(ShareDecision.Publish(Audience.FRIENDS), decision)
    }

    @Test
    fun `without an audience the share is refused, not guessed`() {
        val decision = ShareAudiencePolicy.decide(draft(), audience = null, confirmed = true)

        assertEquals(ShareDecision.Refused(ShareDecision.Reason.NO_AUDIENCE), decision)
    }

    @Test
    fun `a private note can never be made public by the social layer`() {
        val decision = ShareAudiencePolicy.decide(draft(ShareKind.NOTE), Audience.PUBLIC, true)

        assertEquals(ShareDecision.Refused(ShareDecision.Reason.AUDIENCE_NOT_ALLOWED), decision)
        assertEquals(
            "a note may be kept or shared with friends only",
            setOf(Audience.PRIVATE, Audience.FRIENDS),
            ShareAudiencePolicy.allowedAudiences(ShareKind.NOTE)
        )
    }

    @Test
    fun `a review may be public because that contract already exists`() {
        val decision = ShareAudiencePolicy.decide(draft(ShareKind.REVIEW), Audience.PUBLIC, true)

        assertEquals(ShareDecision.Publish(Audience.PUBLIC), decision)
    }

    @Test
    fun `nothing is published from an empty draft or a closed form`() {
        assertEquals(
            ShareDecision.Refused(ShareDecision.Reason.NO_DRAFT),
            ShareAudiencePolicy.decide(null, Audience.FRIENDS, confirmed = true)
        )
        assertEquals(
            ShareDecision.Refused(ShareDecision.Reason.EMPTY_TEXT),
            ShareAudiencePolicy.decide(draft(text = "   "), Audience.FRIENDS, confirmed = true)
        )
    }
}
