package com.slukhayka.audiobooks.data.social

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.BlockEntity
import com.slukhayka.audiobooks.data.db.FriendshipStateEntity
import com.slukhayka.audiobooks.data.db.SocialDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #916 — the local social storage on a REAL Room database: the four friendship
 * states, both block directions, and the visibility consequences the pure
 * [FriendsFeedPolicy] derives from that stored state.
 *
 * The last group is the #898 blank-pseudonym bug: a blank identity is not a
 * person, so it must never become a friend, a block, or a visible author.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SocialStoreTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: SocialDao
    private lateinit var store: SocialStore

    /** A fixed clock: the stored instants are pinned, never slept on. */
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.socialDao()
        store = SocialStore(dao, clock = { now })
    }

    @After
    fun tearDown() = db.close()

    // --- the four states --------------------------------------------------

    @Test
    fun `every friendship state is stored and read back in its own bucket`() = runBlocking {
        assertTrue(store.setState("olena", FriendshipState.ACCEPTED))
        assertTrue(store.setState("bohdan", FriendshipState.INCOMING))
        assertTrue(store.setState("ihor", FriendshipState.OUTGOING))
        assertTrue(store.setState("yaryna", FriendshipState.DECLINED))

        val snapshot = store.read()

        assertEquals(setOf("olena"), snapshot.friendsNow)
        assertEquals(setOf("bohdan"), snapshot.incomingRequests)
        assertEquals(setOf("ihor"), snapshot.outgoingRequests)
        // A declined request is a settled "no": it is neither a friend nor a
        // pending request, and asking again is a NEW explicit action (§3).
        assertFalse("yaryna" in snapshot.friendsNow)
        assertFalse("yaryna" in snapshot.incomingRequests)
        assertFalse("yaryna" in snapshot.outgoingRequests)
    }

    @Test
    fun `re-writing a peer replaces the previous state instead of duplicating it`() = runBlocking {
        store.setState("olena", FriendshipState.OUTGOING)
        store.setState("olena", FriendshipState.ACCEPTED)

        val snapshot = store.read()
        assertEquals(setOf("olena"), snapshot.friendsNow)
        assertTrue("the old request does not linger", snapshot.outgoingRequests.isEmpty())
    }

    @Test
    fun `an unknown stored state name is dropped, never guessed`() = runBlocking {
        dao.upsertFriendshipState(FriendshipStateEntity("ghost", "MAYBE", now))

        val snapshot = store.read()
        assertTrue(snapshot.friendsNow.isEmpty())
        assertTrue(snapshot.incomingRequests.isEmpty())
        assertTrue(snapshot.outgoingRequests.isEmpty())
    }

    // --- blocks: both directions ------------------------------------------

    @Test
    fun `a block stored in either direction hides the pair`() = runBlocking {
        val viewer = "listener"
        store.setState("olena", FriendshipState.ACCEPTED)
        assertTrue(store.block("olena"))
        assertTrue(store.recordBlockedBy("petro"))

        val snapshot = store.read()

        // Stored per direction, as §2 requires: the action is one-sided...
        assertEquals(setOf("olena"), snapshot.blocks.blocked)
        assertEquals(setOf("petro"), snapshot.blocks.blockedBy)
        // ...and the consequence is two-sided: either row hides the pair.
        assertTrue(snapshot.blocks.hides("olena"))
        assertTrue(snapshot.blocks.hides("petro"))
        // §2 — the block cancelled the friendship; it is not a friend any more.
        assertFalse("olena" in snapshot.friendsNow)

        assertFalse(
            "I do not see the post of someone I blocked",
            FriendsFeedPolicy.visibleTo(Audience.FRIENDS, "olena", viewer, areFriendsNow = true, blocks = snapshot.blocks)
        )
        assertFalse(
            "I do not see the post of someone who blocked me",
            FriendsFeedPolicy.visibleTo(Audience.FRIENDS, "petro", viewer, areFriendsNow = true, blocks = snapshot.blocks)
        )
        // The other device reads the same pair from the opposite side: its
        // state has the blocked party as «blockedBy» (or vice versa), and it
        // hides my posts just the same. Built here by hand because the peer's
        // device is not this store.
        assertFalse(
            "on the blocked peer's device my post is hidden too",
            FriendsFeedPolicy.visibleTo(
                Audience.FRIENDS,
                author = viewer,
                viewer = "olena",
                areFriendsNow = true,
                blocks = BlockState(blockedBy = setOf(viewer))
            )
        )
        // §2 — a friend request across a block is refused silently.
        assertFalse(BlockPolicy.acceptsFriendRequest(snapshot.blocks, "olena"))
        assertFalse(BlockPolicy.acceptsFriendRequest(snapshot.blocks, "petro"))
    }

    @Test
    fun `unblocking removes only my direction and never restores the friendship`() = runBlocking {
        store.setState("olena", FriendshipState.ACCEPTED)
        store.block("olena")
        store.recordBlockedBy("petro")

        assertTrue(store.unblock("olena"))

        val snapshot = store.read()
        assertFalse("my block is gone", snapshot.blocks.hides("olena"))
        assertFalse("§2 — the friendship is not restored", "olena" in snapshot.friendsNow)
        // Unblocking me is not unblocking them: their block stays.
        assertFalse("there was nothing of mine blocking petro", store.unblock("petro"))
        assertTrue("petro's block still hides", snapshot.blocks.hides("petro"))
    }

    @Test
    fun `unfriending removes the local fact without touching a block`() = runBlocking {
        store.setState("olena", FriendshipState.ACCEPTED)
        store.setState("ihor", FriendshipState.ACCEPTED)

        assertTrue(store.unfriend("olena"))

        val snapshot = store.read()
        assertEquals(setOf("ihor"), snapshot.friendsNow)
    }

    // --- real-data visibility --------------------------------------------

    @Test
    fun `visibility is decided on the stored friends, not on a literal`() = runBlocking {
        val viewer = "listener"
        store.setState("olena", FriendshipState.ACCEPTED)

        val snapshot = store.read()

        assertTrue(
            "an accepted friend's FRIENDS post is visible",
            FriendsFeedPolicy.visibleTo(
                Audience.FRIENDS, "olena", viewer,
                areFriendsNow = "olena" in snapshot.friendsNow, blocks = snapshot.blocks
            )
        )
        assertFalse(
            "someone who never asked is not a friend",
            FriendsFeedPolicy.visibleTo(
                Audience.FRIENDS, "stranger", viewer,
                areFriendsNow = "stranger" in snapshot.friendsNow, blocks = snapshot.blocks
            )
        )
    }

    // --- the #898 blank-pseudonym guard ----------------------------------

    @Test
    fun `a blank pseudonym is never a friend, a block, or an author`() = runBlocking {
        // A corrupt or unresolved row that reached the table — the #898 shape.
        dao.upsertFriendshipState(FriendshipStateEntity("", FriendshipState.ACCEPTED.name, now))
        dao.insertBlock(BlockEntity("", BlockDirection.BY_ME.name, now))

        val snapshot = store.read()

        assertTrue("a blank identity is not a friend", snapshot.friendsNow.isEmpty())
        assertTrue("a blank identity does not hide anyone", snapshot.blocks.blocked.isEmpty())
        assertTrue(snapshot.blocks.blockedBy.isEmpty())

        // The store refuses to CREATE such a fact in the first place.
        assertFalse(store.setState("", FriendshipState.ACCEPTED))
        assertFalse(store.block(""))
        assertFalse(store.recordBlockedBy("   "))

        // §6.4/the #898 leak — a post with no author is shown to nobody, even
        // when the viewer is also unresolved, and a PRIVATE post never leaks.
        assertFalse(
            FriendsFeedPolicy.visibleTo(
                Audience.FRIENDS, author = "", viewer = "",
                areFriendsNow = "" in snapshot.friendsNow, blocks = snapshot.blocks
            )
        )
        assertFalse(
            FriendsFeedPolicy.visibleTo(
                Audience.PRIVATE, author = "", viewer = "",
                areFriendsNow = false, blocks = snapshot.blocks
            )
        )
    }

    // --- the observed flow ------------------------------------------------

    @Test
    fun `the observed snapshot reflects a write without a manual refresh`() = runBlocking {
        val observed = CompletableDeferred<SocialSnapshot>()
        val job = launch(Dispatchers.IO) {
            store.snapshot().collect { snapshot ->
                if (snapshot.friendsNow == setOf("olena")) observed.complete(snapshot)
            }
        }

        store.setState("olena", FriendshipState.ACCEPTED)

        val snapshot = withTimeout(5_000) { observed.await() }
        job.cancel()
        assertEquals(setOf("olena"), snapshot.friendsNow)
    }
}
