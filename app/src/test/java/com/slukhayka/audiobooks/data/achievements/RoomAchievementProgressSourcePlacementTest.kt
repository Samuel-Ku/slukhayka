package com.slukhayka.audiobooks.data.achievements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.PlaybackEventEntity
import com.slukhayka.audiobooks.data.db.PlaybackEventKind
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1612 — the PLACEMENT witness for #1589 lever 2.
 *
 * `OwnLibraryFactsTest` proves the helper dedups equal snapshots; it cannot see
 * where the derivation is called from. This test runs the real composition and
 * counts the derivations through the `deriveLibraryFacts` seam:
 *
 * - the composition emits again on every upstream tick (here: TIMER_STOP
 *   playback events change the snapshot while leaving the OWN library list
 *   structurally identical — no COMPLETED event, so `completedAt` values and
 *   the facts do not move);
 * - the derivation must stay at ONE call.
 *
 * A revert that moves the four matchers back into the per-tick `combine`
 * (bypassing the seam) leaves the counter at zero — this test goes red, which
 * is the placement mutation's witness.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class)
class RoomAchievementProgressSourcePlacementTest {

    @Test
    fun `own-library derivation runs once per distinct snapshot through the real composition`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            try {
                val achievementDao = database.achievementDao()
                val audiobookDao = database.audiobookDao()
                val book = TestDataFactory.dataBooks().first()
                audiobookDao.insertAudiobooks(listOf(book.copy(totalChapters = 1)))
                audiobookDao.insertChapters(
                    TestDataFactory.dataChapters().filter { it.bookId == book.id }.take(1)
                )
                audiobookDao.upsertLibraryEntry(book.id, book.id, false, 1L, 0f)
                audiobookDao.updateLibraryEntryOrigin(book.id, "EXPLICIT_SAVE")

                var derivations = 0
                val source = RoomAchievementProgressSource(
                    achievementDao,
                    RoomAchievementStore(achievementDao),
                    setOf("sluhay"),
                    abandonedBookIds = flowOf(emptySet()),
                    deriveLibraryFacts = { own ->
                        derivations++
                        deriveOwnLibraryFacts(own, emptyList(), emptyList())
                    }
                )

                // #1614 review note: appended on Dispatchers.Default, read on the
                // test thread — a synchronized list keeps the witness race-free instead
                // of relying on the JMM to publish ArrayList writes.
                val snapshots = java.util.Collections.synchronizedList(
                    mutableListOf<AchievementProgress>()
                )
                val job = launch(Dispatchers.Default) {
                    source.observe().collect { snapshots += it }
                }
                try {
                    withTimeout(20_000) { while (snapshots.isEmpty()) delay(20) }
                    repeat(3) { index ->
                        // TIMER_STOP is a real event kind that moves the snapshot's
                        // counters while leaving the OWN-library list untouched.
                        audiobookDao.insertPlaybackEvent(
                            PlaybackEventEntity(
                                bookId = book.id,
                                kind = PlaybackEventKind.TIMER_STOP,
                                timestamp = 1_000L + index
                            )
                        )
                        delay(300)
                    }
                    withTimeout(20_000) { while (snapshots.size < 2) delay(20) }
                    delay(500)
                } finally {
                    job.cancel()
                }

                assertTrue(
                    "the composition must emit again on upstream ticks, got ${snapshots.size}",
                    snapshots.size >= 2
                )
                assertEquals(
                    "the OWN-library derivation must run once per distinct snapshot, not per tick",
                    1,
                    derivations
                )
            } finally {
                database.close()
            }
        }
}
