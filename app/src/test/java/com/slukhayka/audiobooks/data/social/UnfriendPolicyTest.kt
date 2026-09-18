package com.slukhayka.audiobooks.data.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #895 — the rules of §3 of the social-layer spec. */
class UnfriendPolicyTest {

    @Test
    fun `either side can end the friendship alone`() {
        val friends = setOf("olena", "bohdan")

        assertEquals(setOf("bohdan"), UnfriendPolicy.friendsAfterUnfriend(friends, "olena"))
    }

    @Test
    fun `a friends-only post stops being visible to the former friend`() {
        assertFalse(
            UnfriendPolicy.visibleTo(Audience.FRIENDS, "olena", "me", areFriends = false)
        )
    }

    @Test
    fun `the author keeps the post and other friends still see it`() {
        assertTrue(
            "the author never loses their own post",
            UnfriendPolicy.visibleTo(Audience.FRIENDS, "me", "me", areFriends = false)
        )
        assertTrue(
            "another friend sees the very same post",
            UnfriendPolicy.visibleTo(Audience.FRIENDS, "olena", "bohdan", areFriends = true)
        )
    }

    @Test
    fun `nothing is revoked retroactively - the rule is applied at display time`() {
        assertTrue(
            "what was public stays public",
            UnfriendPolicy.visibleTo(Audience.PUBLIC, "olena", "me", areFriends = false)
        )
        assertFalse(
            "a private record was never theirs to see",
            UnfriendPolicy.visibleTo(Audience.PRIVATE, "olena", "me", areFriends = true)
        )
    }

    @Test
    fun `renewed friendship is a new explicit request, not an automatic restore`() {
        assertFalse(UnfriendPolicy.acceptsRenewedFriendship(requestedAgain = false))
        assertTrue(UnfriendPolicy.acceptsRenewedFriendship(requestedAgain = true))
    }
}
