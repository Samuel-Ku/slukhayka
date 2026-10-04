package com.slukhayka.audiobooks.data.achievements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomAchievementStoreTest {
    @Test fun `awards and claimed notices survive closing and reopening the real local database`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "achievement-store-restart.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name).allowMainThreadQueries().build()
        suspend fun useDatabase(block: suspend (AudiobookDatabase) -> Unit) {
            val database = open()
            try { block(database) } finally { database.close() }
        }
        val definition = AchievementCatalog.definitions.first { it.id == "first_book" }
        try {
            useDatabase { database ->
                val first = RoomAchievementStore(database.achievementDao())
                first.recordFact(AchievementFact.REVIEW_ACCEPTED)
                assertEquals(listOf(EarnedAchievement("first_book", 100L)), first.award(listOf(definition), 100L))
            }
            useDatabase { database ->
                val restarted = RoomAchievementStore(database.achievementDao())
                assertEquals(setOf(AchievementFact.REVIEW_ACCEPTED),restarted.observeFacts().first())
                assertEquals(listOf(EarnedAchievement("first_book", 100L)), restarted.earned())
                assertEquals(emptyList<EarnedAchievement>(), restarted.award(listOf(definition), 200L))
                assertEquals(EarnedAchievement("first_book", 100L, 300L), restarted.claimNotice(setOf("first_book"), 300L))
            }
            useDatabase { database ->
                val restartedAgain = RoomAchievementStore(database.achievementDao())
                assertEquals(listOf(EarnedAchievement("first_book", 100L, 300L)), restartedAgain.earned())
                assertNull(restartedAgain.claimNotice(setOf("first_book"), 400L))
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun `concurrent stores have exactly one award and one notice winner`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context,AudiobookDatabase::class.java).allowMainThreadQueries().build()
        try {
            val stores = List(2) { RoomAchievementStore(database.achievementDao()) }
            val definition = AchievementCatalog.definitions.first { it.id=="first_book" }
            val inserted = stores.map { store -> async(Dispatchers.IO) { store.award(listOf(definition),100L) } }.awaitAll().flatten()
            assertEquals(1,inserted.size)
            val notices = stores.map { store -> async(Dispatchers.IO) { store.claimNotice(setOf("first_book"),200L) } }.awaitAll().filterNotNull()
            assertEquals(1,notices.size)
            assertEquals(EarnedAchievement("first_book",100L,200L),stores.first().earned().single())
        } finally { database.close() }
    }
    @Test fun `Room listening transactions preserve fractions across writer and database restart`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "achievement-listening-restart.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context,AudiobookDatabase::class.java,name).allowMainThreadQueries().build()
        var database = open()
        try {
            val stores = List(2) { ListeningStateStore(database.audiobookDao()) }
            listOf(650L,150L).mapIndexed { index,millis -> async(Dispatchers.IO) { stores[index].recordActualListeningTime(millis) } }.awaitAll()
            database.close()
            database = open()
            val restarted = ListeningStateStore(database.audiobookDao())
            restarted.recordActualListeningTime(400L)
            val row = restarted.getAllListeningStats().first().single()
            assertEquals(1200L,row.verifiedListenedMillis)
            assertEquals(1L,row.listenedSeconds)
        } finally { database.close(); context.deleteDatabase(name) }
    }

}
