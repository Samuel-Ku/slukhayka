package com.slukhayka.audiobooks.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #829 — the refusal is honest: a reason AND a way to fix it, never emptiness. */
class TelegramMembershipPolicyTest {

    @Test
    fun `no session on this device sends the listener to join`() {
        val refusal = TelegramMembershipPolicy.refusalFor(sessionReady = false, isMember = false)!!

        assertEquals(TelegramMembershipPolicy.Reason.NO_SESSION, refusal.reason)
        assertEquals("https://t.me/slukhayka", refusal.joinUrl)
    }

    @Test
    fun `a logged-in listener who is not in the group gets the join link`() {
        val refusal = TelegramMembershipPolicy.refusalFor(sessionReady = true, isMember = false)!!

        assertEquals(TelegramMembershipPolicy.Reason.NOT_A_MEMBER, refusal.reason)
        assertEquals(TelegramMembershipPolicy.GROUP_URL, refusal.joinUrl)
    }

    @Test
    fun `membership lets the fetcher work - no refusal invented`() {
        assertNull(TelegramMembershipPolicy.refusalFor(sessionReady = true, isMember = true))
    }

    @Test
    fun `the two refusals are told apart - they need different words`() {
        val noSession = TelegramMembershipPolicy.refusalFor(false, false)!!
        val notMember = TelegramMembershipPolicy.refusalFor(true, false)!!

        assertEquals(
            "a missing session is not the same problem as missing membership",
            false,
            noSession.reason == notMember.reason
        )
    }
}
