package com.slukhayka.audiobooks.data.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #894 — the five rules of §2 of the social-layer spec. */
class BlockPolicyTest {

    @Test
    fun `a block cancels the friendship in both directions`() {
        val friends = setOf("olena", "bohdan")

        assertEquals(setOf("bohdan"), BlockPolicy.friendsAfterBlock(friends, "olena"))
    }

    @Test
    fun `unblocking does not restore the friendship - a new request is needed`() {
        val afterBlock = BlockPolicy.friendsAfterBlock(setOf("olena"), "olena")
        val afterUnblock = BlockPolicy.friendsAfterUnblock(afterBlock, "olena")

        assertTrue("friendship stays cancelled", afterUnblock.isEmpty())
    }

    @Test
    fun `a request across a block is refused silently - from either side`() {
        val theyBlockedMe = BlockState(blockedBy = setOf("olena"))
        val iBlockedThem = BlockState(blocked = setOf("olena"))

        assertFalse(BlockPolicy.acceptsFriendRequest(theyBlockedMe, "olena"))
        assertFalse(BlockPolicy.acceptsFriendRequest(iBlockedThem, "olena"))
        assertTrue("an unrelated request is accepted", BlockPolicy.acceptsFriendRequest(BlockState(), "olena"))
    }

    @Test
    fun `a block hides the friends feed but not a public review`() {
        val state = BlockState(blocked = setOf("olena"))

        assertFalse(
            "a friends-only post stops being visible",
            BlockPolicy.visibleTo(Audience.FRIENDS, author = "olena", viewer = "me", state = state)
        )
        assertTrue(
            "a public review stays readable - the block is not a moderation tool",
            BlockPolicy.visibleTo(Audience.PUBLIC, author = "olena", viewer = "me", state = state)
        )
    }

    @Test
    fun `a private record is never visible to anyone else`() {
        assertTrue(BlockPolicy.visibleTo(Audience.PRIVATE, "me", "me", BlockState()))
        assertFalse(BlockPolicy.visibleTo(Audience.PRIVATE, "olena", "me", BlockState()))
    }
}
