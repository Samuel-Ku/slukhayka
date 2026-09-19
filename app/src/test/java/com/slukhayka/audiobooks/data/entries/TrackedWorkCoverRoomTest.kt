package com.slukhayka.audiobooks.data.entries

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.metadata.CoverOverrideStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0053 / #855 (T2) — the cover of a tracked Work against the REAL SQL.
 *
 * The milestone asks for two things and this proves both on the data layer:
 * the tracked Work can carry a cover FROM DAY ONE through the existing cover
 * write path (no URL = an honest absence, never a placeholder), and the
 * listener's Override outranks an external claim — the shared base, or a
 * bibliography candidate the app used to create the Work — including on a
 * second claim, while identity (the mergeKey) never moves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TrackedWorkCoverRoomTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    private val trackedWorks = { TrackedWorks(dao) }
    private val overrides = { CoverOverrideStore(dao) }

    private val mergeKey = MergeKey.keyFor(TITLE, AUTHOR)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun add(coverUrl: String? = null, title: String = TITLE, author: String = AUTHOR) =
        trackedWorks().ensureTrackedWork(title, author, coverUrl, now = NOW)

    private suspend fun card() = dao.getAudiobookById(mergeKey)

    @Test
    fun `a tracked work is born with the cover it was given`() = runBlocking {
        val result = add(coverUrl = COVER)

        assertTrue(result is TrackedWorks.Result.Added)
        assertEquals("the cover is there from the first read", COVER, card()!!.coverImageUrl)
        // The cover is a URL, never a bundled stand-in image.
        assertEquals(0, card()!!.coverDrawableRes)
    }

    @Test
    fun `without a URL the card shows an honest absence, not a placeholder`() = runBlocking {
        add(coverUrl = null)

        assertNull("no invented cover URL", card()!!.coverImageUrl)
        assertEquals("and no invented drawable either", 0, card()!!.coverDrawableRes)
    }

    @Test
    fun `a blank URL is an absence, not a whitespace cover`() = runBlocking {
        add(coverUrl = "   ")

        assertNull(card()!!.coverImageUrl)
        assertEquals(0, card()!!.coverDrawableRes)
    }

    @Test
    fun `an external claim fills a blank cover of an already tracked work`() = runBlocking {
        add(coverUrl = null)

        val again = add(coverUrl = EXTERNAL)

        assertTrue("the Work is not forked", again is TrackedWorks.Result.AlreadyTracked)
        assertEquals("the claim landed through the cover write path", EXTERNAL, card()!!.coverImageUrl)
        assertEquals("still one card", 1, dao.getAllAudiobooksOnce().size)
    }

    @Test
    fun `an external claim never replaces a locally known cover`() = runBlocking {
        add(coverUrl = COVER)

        add(coverUrl = EXTERNAL)

        assertEquals(COVER, card()!!.coverImageUrl)
    }

    @Test
    fun `the Override beats the external claim - twice in a row`() = runBlocking {
        add(coverUrl = null)
        overrides().pin(mergeKey, mergeKey, COVER, now = NOW)

        add(coverUrl = EXTERNAL)
        assertEquals("the first claim loses", COVER, card()!!.coverImageUrl)
        add(coverUrl = EXTERNAL)
        assertEquals("and so does the second", COVER, card()!!.coverImageUrl)
    }

    @Test
    fun `a pinned absence stays absent against the external claim`() = runBlocking {
        add(coverUrl = COVER)
        // The listener removed a wrong cover: the decision is «no cover».
        overrides().pin(mergeKey, mergeKey, null, now = NOW)

        add(coverUrl = EXTERNAL)

        assertEquals("", card()!!.coverImageUrl)
    }

    @Test
    fun `a cover fix never forks the Work or moves its identity`() = runBlocking {
        add(coverUrl = COVER)
        val before = dao.findByMergeKey(mergeKey)!!

        overrides().pin(mergeKey, mergeKey, EXTERNAL, now = NOW)

        val after = dao.findByMergeKey(mergeKey)!!
        assertEquals("the same card", before.id, after.id)
        assertEquals("the same Work identity", mergeKey, after.mergeKey)
        assertEquals(EXTERNAL, after.coverImageUrl)
        assertEquals("one card, still", 1, dao.getAllAudiobooksOnce().size)
        assertEquals("one link, still", 1, dao.countLibraryEntries())
        assertEquals("one Work, still", 1, dao.countWorks())
    }

    private companion object {
        const val TITLE = "Кобзар"
        const val AUTHOR = "Тарас Шевченко"
        const val COVER = "https://mine.example/kobzar.jpg"
        const val EXTERNAL = "https://bibliography.example/kobzar.jpg"
        const val NOW = 1_700_000_000_000L
    }
}
