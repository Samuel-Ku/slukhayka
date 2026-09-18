package com.slukhayka.audiobooks.data.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #897 — the data rules of §5 and the invariant §6.3. */
class SocialModelTest {

    @Test
    fun `a friendship is one fact for two pseudonyms, whichever side is named first`() {
        val one = Friendship("olena", "bohdan")
        val other = Friendship("bohdan", "olena")

        assertTrue(one.sameAs(other))
        assertEquals("olena", other.other("bohdan"))
        assertTrue(one.involves("bohdan"))
    }

    @Test
    fun `a listener cannot be their own friend`() {
        val failed = runCatching { Friendship("olena", "olena") }.isFailure

        assertTrue("the pair must be two different pseudonyms", failed)
    }

    @Test
    fun `an author or narrator can never be a friend`() {
        assertTrue(SocialModelRules.canBeFriend(SocialActor.Listener(id = "u1", pseudonym = "olena")))
        assertFalse(SocialModelRules.canBeFriend(SocialActor.Bibliographic(id = "author-1")))
    }

    @Test
    fun `a post is its own object - it may only point at what it shares`() {
        val post = Post(
            id = "p1",
            authorPseudonym = "olena",
            audience = Audience.FRIENDS,
            text = "Дочитала",
            sourceId = "review-7"
        )

        assertEquals("review-7", post.sourceId)
        assertEquals(Audience.FRIENDS, post.audience)
    }

    @Test
    fun `a private record gains no social fields`() {
        assertEquals(
            "progress, goal and finish stay private",
            setOf("progress", "goal", "finishedAt"),
            SocialModelRules.privateRecordFields()
        )
    }
}
