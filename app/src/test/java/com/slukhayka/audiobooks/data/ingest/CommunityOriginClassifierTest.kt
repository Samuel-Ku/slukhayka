package com.slukhayka.audiobooks.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #831 AC1 — the origin classifier, pinned.
 *
 * The classifier is a PURE policy, so every branch is checkable without a
 * device, a network or a database. The interesting part is not the two obvious
 * cases but the PRECEDENCE between them, which the ticket left open and which
 * is now a written decision rather than an accident of `if` order.
 */
class CommunityOriginClassifierTest {

    private val group = TelegramMembershipPolicy.GROUP_URL
    private val verified = 1_700_000_000_000L

    // ------------------------------------------------------------------
    // The two facts on their own.
    // ------------------------------------------------------------------

    @Test
    fun `a verified submission is from listeners`() {
        assertEquals(
            CommunityOrigin.FROM_LISTENERS,
            CommunityOriginClassifier.classify(verifiedAt = verified, sourceUrl = "https://youtu.be/x")
        )
    }

    @Test
    fun `a link to the registered group is from the shared library`() {
        assertEquals(
            CommunityOrigin.FROM_SHARED_LIBRARY,
            CommunityOriginClassifier.classify(verifiedAt = null, sourceUrl = group)
        )
    }

    // ------------------------------------------------------------------
    // The precedence decision.
    // ------------------------------------------------------------------

    @Test
    fun `when BOTH are true the shared library wins`() {
        // The ticket's own wording does not answer this. The decision: a link
        // that resolves to the registered group states where the audio LIVES,
        // while "verified" only states that it was checked. Showing «Від
        // слухачів» here would be true but less useful than the label the
        // listener needs.
        assertEquals(
            CommunityOrigin.FROM_SHARED_LIBRARY,
            CommunityOriginClassifier.classify(verifiedAt = verified, sourceUrl = group)
        )
    }

    // ------------------------------------------------------------------
    // No claim, rather than a guessed one.
    // ------------------------------------------------------------------

    @Test
    fun `an unverified entry with an unrelated link carries NO origin`() {
        // ADR-0035: a source claim is never invented. Null is the honest
        // answer, and the caller renders no badge rather than a wrong one.
        assertNull(CommunityOriginClassifier.classify(verifiedAt = null, sourceUrl = "https://youtu.be/x"))
    }

    @Test
    fun `an unverified entry with no link carries NO origin`() {
        assertNull(CommunityOriginClassifier.classify(verifiedAt = null, sourceUrl = null))
    }

    @Test
    fun `a zero or negative verdict is NOT a verification`() {
        // `verifiedAt` is a timestamp; 0 is the "never" sentinel the rest of
        // the codebase uses. Treating it as a real moment would label an
        // unverified entry as "from listeners".
        assertNull(CommunityOriginClassifier.classify(verifiedAt = 0L, sourceUrl = "https://youtu.be/x"))
        assertNull(CommunityOriginClassifier.classify(verifiedAt = -1L, sourceUrl = "https://youtu.be/x"))
    }

    // ------------------------------------------------------------------
    // The same group, written the way people write it.
    // ------------------------------------------------------------------

    @Test
    fun `every spelling of the group link is recognised`() {
        val spellings = listOf(
            group,
            "https://t.me/slukhayka/",
            "http://t.me/slukhayka",
            "  https://t.me/slukhayka  ",
            "HTTPS://T.ME/SLUKHAYKA"
        )

        spellings.forEach { spelling ->
            assertTrue(
                "«$spelling» must resolve to the registered group — a classifier " +
                    "that only knows one spelling silently mislabels the rest",
                CommunityOriginClassifier.isRegisteredGroupLink(spelling)
            )
        }
    }

    @Test
    fun `a different group is NOT the registered one`() {
        // The point of the AC: the badge is reserved for OUR group, not for
        // any Telegram link.
        assertFalse(CommunityOriginClassifier.isRegisteredGroupLink("https://t.me/someone-else"))
        assertFalse(CommunityOriginClassifier.isRegisteredGroupLink("https://t.me/slukhayka2"))
    }
}
