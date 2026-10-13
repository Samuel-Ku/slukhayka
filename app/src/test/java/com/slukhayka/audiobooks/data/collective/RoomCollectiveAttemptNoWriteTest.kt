package com.slukhayka.audiobooks.data.collective

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.FeedSnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An attempt leaves missing and malformed active-block storage unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomCollectiveAttemptNoWriteTest {

    @Test(timeout = 60_000L)
    fun `recording an attempt neither creates a missing block nor rewrites a malformed row`() = runBlocking {
        var db: AudiobookDatabase? = null
        var bodyFailure: Throwable? = null
        try {
            withTimeout(45_000L) {
                val context = ApplicationProvider.getApplicationContext<Application>()
                assertEquals(Application::class.java, context.javaClass)
                val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java).build()
                db = database
                val dao = database.audiobookDao()
                val store = RoomCollectiveFeedBlockStore(dao)

                assertNull(store.active("sluhayua|RECOMMENDATIONS"))
                assertNull(dao.getFeedSnapshot("sluhayua", "collective-recommendations"))
                store.recordAttempt("sluhayua|RECOMMENDATIONS",
                    CollectiveAttempt(30_000L, CollectiveAttemptStatus.TIMEOUT))
                assertNull("an attempt must not create a missing active block",
                    store.active("sluhayua|RECOMMENDATIONS"))
                assertNull("an undecodable created row would be invisible through active",
                    dao.getFeedSnapshot("sluhayua", "collective-recommendations"))

                val malformed = FeedSnapshotEntity(
                    sourceId = "soundbooks",
                    feedKey = "collective-new_arrivals",
                    pageCursor = "",
                    fetchedAt = 17_000L,
                    cardsJson = "{not a block}"
                )
                dao.upsertFeedSnapshot(malformed)
                assertEquals(malformed, dao.getFeedSnapshot("soundbooks", "collective-new_arrivals"))
                assertNull(store.active("soundbooks|NEW_ARRIVALS"))
                store.recordAttempt("soundbooks|NEW_ARRIVALS",
                    CollectiveAttempt(42_000L, CollectiveAttemptStatus.PARSE_FAILURE))
                assertNull("a malformed stored block must remain a cache miss",
                    store.active("soundbooks|NEW_ARRIVALS"))
                assertEquals("recording an attempt must preserve the complete malformed row",
                    malformed, dao.getFeedSnapshot("soundbooks", "collective-new_arrivals"))
            }
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    db?.close()
                } catch (failure: Throwable) {
                    val primary = bodyFailure
                    if (primary == null) throw failure else primary.addSuppressed(failure)
                }
            }
        }
    }
}
