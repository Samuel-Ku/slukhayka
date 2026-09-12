package com.slukhayka.audiobooks.data.collective

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #523 — the persisted block over `feed_snapshots`: one deterministic row per
 * block, an empty snapshot never activates, a recorded attempt never touches
 * the active cards or version, and a broken document is a miss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomCollectiveFeedBlockStoreTest {

    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var store: RoomCollectiveFeedBlockStore

    private val key = collectiveBlockKey("soundbooks", CollectiveBlockKind.NEW_ARRIVALS)

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
        store = RoomCollectiveFeedBlockStore(dao)
    }

    @After
    fun tearDown() = db.close()

    private fun block(
        cards: List<CollectiveBlockCard> = listOf(
            CollectiveBlockCard("soundbooks", "https://sound-books.net/kobzar", "Кобзар", "Шевченко")
        ),
        version: Long = 1L,
        status: CollectiveAttemptStatus = CollectiveAttemptStatus.SUCCESS
    ) = CollectiveFeedBlock(
        blockKey = key,
        sourceId = "soundbooks",
        kind = CollectiveBlockKind.NEW_ARRIVALS,
        name = "Новинки Sound-Books",
        provenanceUrl = "https://sound-books.net/new",
        cards = cards,
        fetchedAt = 1_000L,
        staleAfter = 1_000L + CollectiveBlockPolicy.NEW_ARRIVALS_TTL_MS,
        version = version,
        lastAttempt = CollectiveAttempt(1_000L, status)
    )

    @Test
    fun `an activated block is read back whole`() = runBlocking {
        assertTrue(store.activate(block()))

        val active = store.active(key)
        assertEquals(block(), active)
        assertEquals(listOf("Кобзар"), active!!.cards.map { it.title })
    }

    @Test
    fun `an empty snapshot never activates`() = runBlocking {
        assertFalse(store.activate(block(cards = emptyList())))
        assertNull(store.active(key))
    }

    @Test
    fun `a recorded attempt keeps the cards and the version`() = runBlocking {
        store.activate(block(version = 4L))

        store.recordAttempt(key, CollectiveAttempt(2_000L, CollectiveAttemptStatus.TIMEOUT))

        val active = store.active(key)!!
        assertEquals(4L, active.version)
        assertEquals(1_000L, active.fetchedAt)
        assertEquals(listOf("Кобзар"), active.cards.map { it.title })
        assertEquals(CollectiveAttemptStatus.TIMEOUT, active.lastAttempt.status)
        assertEquals(2_000L, active.lastAttempt.at)
    }

    @Test
    fun `a broken stored document is a miss`() = runBlocking {
        dao.upsertFeedSnapshot(
            com.slukhayka.audiobooks.data.db.FeedSnapshotEntity(
                sourceId = "soundbooks",
                feedKey = collectiveFeedKey(CollectiveBlockKind.NEW_ARRIVALS),
                pageCursor = "",
                fetchedAt = 1_000L,
                cardsJson = "{not a block}"
            )
        )

        assertNull(store.active(key))
    }

    @Test
    fun `a different source never reads this block`() = runBlocking {
        store.activate(block())

        assertNull(store.active(collectiveBlockKey("sluhayua", CollectiveBlockKind.NEW_ARRIVALS)))
    }
}
