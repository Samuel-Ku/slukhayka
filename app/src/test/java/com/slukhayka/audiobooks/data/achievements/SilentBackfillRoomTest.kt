package com.slukhayka.audiobooks.data.achievements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AchievementEntity
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0063 §7 — the SILENT BACKFILL, against the real Room store.
 *
 * The engine-level test (`AchievementEngineTest`) runs a fake store, so it
 * exercises `AchievementStore`'s DEFAULT `awardSilently` (award, then silence)
 * and never the production SQL. A broken `markPendingSeen` or a
 * `RoomAchievementStore.awardSilently` that forgets `seenAt` would stay green
 * there. This test pins the production path: a legacy award is silenced, the
 * historical snapshot is inserted ALREADY SEEN, and a live award after the
 * backfill still claims exactly one notice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SilentBackfillRoomTest {

    private lateinit var database: AudiobookDatabase
    private lateinit var store: RoomAchievementStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomAchievementStore(database.achievementDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** The engine claims notices only for ids the catalogue still knows. */
    private val knownIds = AchievementCatalog.definitions.map { it.id }.toSet()

    @Test
    fun `silencePending marks a legacy unseen award as seen and leaves nothing to claim`() = runBlocking {
        val legacy = AchievementCatalog.definitions.first().id
        database.achievementDao().insertAwards(listOf(AchievementEntity(legacy, earnedAt = 1L)))

        store.silencePending(knownIds + "an-award-from-an-older-build", seenAt = 500L)

        val rows = database.achievementDao().earned()
        assertEquals(1, rows.size)
        assertEquals(500L, rows.single().seenAt)
        assertNull("a silenced legacy award must not become a toast", store.claimNotice(knownIds, 600L))
    }

    @Test
    fun `awardSilently inserts the historical snapshot already seen`() = runBlocking {
        val definition = AchievementCatalog.definitions.first()

        store.awardSilently(listOf(definition), earnedAt = 100L, seenAt = 100L)

        val row = database.achievementDao().earned().single()
        assertEquals(definition.id, row.id)
        assertEquals(100L, row.earnedAt)
        assertEquals("history is not news: seenAt is written in the same insert", 100L, row.seenAt)
        assertNull(store.claimNotice(knownIds, 200L))
    }

    @Test
    fun `a live award after the backfill still claims exactly one notice`() = runBlocking {
        val historical = AchievementCatalog.definitions.first()
        val live = AchievementCatalog.definitions.last()
        store.awardSilently(listOf(historical), earnedAt = 100L, seenAt = 100L)

        store.award(listOf(live), earnedAt = 300L)

        val notice = store.claimNotice(knownIds, 400L)
        assertEquals("the live award is the one notice", live.id, notice?.id)
        assertNull("claiming is one-shot", store.claimNotice(knownIds, 500L))
        assertEquals(
            "the historical row keeps its silence",
            100L,
            database.achievementDao().earned().first { it.id == historical.id }.seenAt
        )
    }
}
