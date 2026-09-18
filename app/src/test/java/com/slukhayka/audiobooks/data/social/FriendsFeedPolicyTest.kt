package com.slukhayka.audiobooks.data.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #898 — the feed rule of `docs/specs/2026-09-16-social-layer.md` §1 ("Друзі"
 * means accepted friends), §2 (a block governs the feed) and §3 (the audience is
 * applied at the moment of display).
 */
class FriendsFeedPolicyTest {

    private val me = "me"
    private val friend = "friend"
    private val stranger = "stranger"

    private fun visible(
        audience: Audience,
        author: String,
        areFriendsNow: Boolean,
        blocks: BlockState = BlockState()
    ) = FriendsFeedPolicy.visibleTo(audience, author, me, areFriendsNow, blocks)

    // ---- own posts -------------------------------------------------------

    @Test
    fun `the author always sees their own post, whatever the audience is`() {
        Audience.entries.forEach { audience ->
            assertTrue(
                "own $audience post must stay visible to its author",
                visible(audience = audience, author = me, areFriendsNow = false)
            )
        }
    }

    // ---- PRIVATE ---------------------------------------------------------

    @Test
    fun `a private post is never shown to anyone else`() {
        assertFalse(
            "a friend must not see someone else's private post",
            visible(Audience.PRIVATE, friend, areFriendsNow = true)
        )
        assertFalse(
            "a stranger must not see a private post",
            visible(Audience.PRIVATE, stranger, areFriendsNow = false)
        )
        assertFalse(
            "a block must not become a way to read a private post",
            visible(Audience.PRIVATE, stranger, areFriendsNow = false, blocks = BlockState(blocked = setOf(stranger)))
        )
    }

    // ---- FRIENDS ---------------------------------------------------------

    @Test
    fun `a friends post is visible while the friendship is live and nobody is blocked`() {
        assertTrue(
            visible(Audience.FRIENDS, friend, areFriendsNow = true)
        )
    }

    @Test
    fun `a friends post is not visible to a non-friend`() {
        assertFalse(
            "an unblocked stranger is still not a friend",
            visible(Audience.FRIENDS, stranger, areFriendsNow = false)
        )
    }

    @Test
    fun `a friends post stops being visible the moment the friendship ends`() {
        // §3 — the same post, the same pair, only the friendship fact changed.
        assertTrue(visible(Audience.FRIENDS, friend, areFriendsNow = true))
        assertFalse(visible(Audience.FRIENDS, friend, areFriendsNow = false))
    }

    @Test
    fun `a friends post is hidden when either side blocked the other`() {
        // §2 — one-sided as an action, two-sided as a consequence.
        assertFalse(
            "the viewer blocked the author",
            visible(
                Audience.FRIENDS,
                friend,
                areFriendsNow = true,
                blocks = BlockState(blocked = setOf(friend))
            )
        )
        assertFalse(
            "the author blocked the viewer",
            visible(
                Audience.FRIENDS,
                friend,
                areFriendsNow = true,
                blocks = BlockState(blockedBy = setOf(friend))
            )
        )
    }

    @Test
    fun `unblocking does not bring a friends post back by itself`() {
        // §2 — a block cancels the friendship and unblocking does not restore
        // it; until a new request is accepted the pair are not friends.
        val unblocked = BlockState(blocked = setOf(friend)).unblock(friend)

        assertFalse(visible(Audience.FRIENDS, friend, areFriendsNow = false, blocks = unblocked))
    }

    // ---- PUBLIC ----------------------------------------------------------

    @Test
    fun `a public post is visible to friends, strangers and blocked people alike`() {
        assertTrue(visible(Audience.PUBLIC, friend, areFriendsNow = true))
        assertTrue(visible(Audience.PUBLIC, stranger, areFriendsNow = false))
        assertTrue(
            "§2 — a block does not take away someone's own public contract",
            visible(
                Audience.PUBLIC,
                stranger,
                areFriendsNow = false,
                blocks = BlockState(blocked = setOf(stranger), blockedBy = setOf(stranger))
            )
        )
    }

    @Test
    fun `a blank viewer never owns a post just because the author is blank too`() {
        // The listener identity resolves asynchronously (spec-40), so the feed
        // renders with viewer == "" for the first frames of a cold start. Two
        // blanks must not be read as "this is mine".
        assertFalse(
            "an unattributable private post must not leak to an unknown viewer",
            visible(Audience.PRIVATE, author = "", areFriendsNow = false, blocks = BlockState())
        )
        assertFalse(
            "nor may an unattributable friends post",
            visible(Audience.FRIENDS, author = "", areFriendsNow = false)
        )
        assertFalse(
            "nor a public one — it has no author to attribute it to",
            visible(Audience.PUBLIC, author = "", areFriendsNow = false)
        )
    }

    @Test
    fun `an unknown viewer still sees public posts from known authors`() {
        // Degrading to the honest state, not to nothing: a public post needs no
        // identity to be readable.
        assertTrue(visible(Audience.PUBLIC, author = stranger, areFriendsNow = false))
        assertFalse(visible(Audience.FRIENDS, author = stranger, areFriendsNow = false))
        assertFalse(visible(Audience.PRIVATE, author = stranger, areFriendsNow = false))
    }

    // ---- the whole feed --------------------------------------------------

    private data class Row(
        val id: String,
        val author: String,
        val audience: Audience
    )

    private val feed = listOf(
        Row("mine", me, Audience.PRIVATE),
        Row("friend-friends", friend, Audience.FRIENDS),
        Row("stranger-friends", stranger, Audience.FRIENDS),
        Row("friend-public", friend, Audience.PUBLIC),
        Row("stranger-private", stranger, Audience.PRIVATE)
    )

    private fun feedIds(
        viewer: String = me,
        friendsNow: Set<String> = setOf(friend),
        blocks: BlockState = BlockState()
    ) = FriendsFeedPolicy.visibleFeed(
        posts = feed,
        viewer = viewer,
        friendsNow = friendsNow,
        blocks = blocks,
        authorOf = { it.author },
        audienceOf = { it.audience }
    ).map { it.id }

    @Test
    fun `the feed keeps the author's own post, live friends posts and public posts`() {
        assertEquals(
            listOf("mine", "friend-friends", "friend-public"),
            feedIds()
        )
    }

    @Test
    fun `the feed drops friends posts from a former friend but keeps their public ones`() {
        assertEquals(
            listOf("mine", "friend-public"),
            feedIds(friendsNow = emptySet())
        )
    }

    @Test
    fun `a blocked author disappears from friends posts and keeps public ones`() {
        assertEquals(
            listOf("mine", "friend-public"),
            feedIds(blocks = BlockState(blocked = setOf(friend)))
        )
    }

    @Test
    fun `a feed with nothing visible is empty, never padded`() {
        val nothingVisible = listOf(
            Row("friend-friends", friend, Audience.FRIENDS),
            Row("stranger-private", stranger, Audience.PRIVATE)
        )

        val result = FriendsFeedPolicy.visibleFeed(
            posts = nothingVisible,
            viewer = me,
            friendsNow = emptySet(),
            authorOf = { it.author },
            audienceOf = { it.audience }
        )

        assertEquals(emptyList<Row>(), result)
        assertTrue("the filter must not invent posts", result.isEmpty())
    }
}
