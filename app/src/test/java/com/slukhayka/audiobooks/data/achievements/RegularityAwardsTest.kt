package com.slukhayka.audiobooks.data.achievements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.ListeningStatEntity
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1166 (T8) — the regularity awards, wired to real `listening_stats` rows.
 *
 * The arithmetic itself is pinned in `ListeningRhythmTest`; here the question is
 * the WIRING: that the snapshot reads the table the recorder writes, that a day
 * without listening has no row and therefore cannot be counted, and that rows
 * from before v51 (verified millis still zero) never open an award.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RegularityAwardsTest {

    private val minute = ListeningRhythm.DAY_MILLIS

    private fun withSource(block: suspend (AudiobookDatabase, RoomAchievementProgressSource) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val store = RoomAchievementStore(database.achievementDao())
            block(database, RoomAchievementProgressSource(database.achievementDao(), store, emptySet()))
        } finally {
            database.close()
        }
    }

    private suspend fun AudiobookDatabase.heard(iso: String, millis: Long = 10 * minute) =
        audiobookDao().addVerifiedListeningTime(iso, millis)

    private fun AchievementProgress.earned(): Set<String> =
        AchievementEvaluator.evaluate(this, emptySet()).map { it.id }.toSet()

    @Test fun `seven adjacent days open the week, and a three-day silence never bridges it`() = withSource { db, source ->
        val days = listOf("2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04")
        days.take(6).forEach { db.heard(it) }
        val six = source.observe().first()
        assertEquals(6L, six.longestDayStreak)
        assertFalse("six days is not a week", "week_in_earphones" in six.earned())

        db.heard(days.last())
        val seven = source.observe().first()
        assertEquals(7L, seven.longestDayStreak)
        assertTrue("the seventh adjacent day opens it", "week_in_earphones" in seven.earned())

        // 07 and 10 are two days of silence apart: the run stays seven, because
        // the missing dates have no rows to count.
        db.heard("2026-10-07"); db.heard("2026-10-10")
        assertEquals(7L, source.observe().first().longestDayStreak)
    }

    @Test fun `the marathon opens at exactly three hours in one day`() = withSource { db, source ->
        db.heard("2026-10-01", 3 * 3_600_000L - 1000L)
        assertFalse("2:59:59 is not a marathon", "marathon_3h" in source.observe().first().earned())
        db.heard("2026-10-01", 1000L)
        val exact = source.observe().first()
        assertEquals(3 * 3_600_000L, exact.bestDayMillis)
        assertTrue("exactly three hours is", "marathon_3h" in exact.earned())
    }

    @Test fun `the month needs twenty days of ONE calendar month`() = withSource { db, source ->
        (1..19).forEach { db.heard("2026-03-%02d".format(it)) }
        assertEquals(19L, source.observe().first().bestMonthDays)
        assertFalse("nineteen days is not a month", "month_in_earphones" in source.observe().first().earned())
        db.heard("2026-04-01")
        assertEquals("days from two months never sum", 19L, source.observe().first().bestMonthDays)
        db.heard("2026-03-20")
        val twenty = source.observe().first()
        assertEquals(20L, twenty.bestMonthDays)
        assertTrue("the twentieth day of one month opens it", "month_in_earphones" in twenty.earned())
    }

    @Test fun `Mondays count by weekday, and nine is not ten`() = withSource { db, source ->
        val firstMonday = LocalDate.of(2026, 1, 5)
        assertEquals(DayOfWeek.MONDAY, firstMonday.dayOfWeek)
        (0 until 9).forEach { db.heard(firstMonday.plusWeeks(it.toLong()).toString()) }
        db.heard(firstMonday.plusDays(1).toString())
        assertEquals(9L, source.observe().first().mondaysListened)
        assertFalse("Tuesday is not a Monday", "mondays_10" in source.observe().first().earned())
        db.heard(firstMonday.plusWeeks(9).toString())
        val ten = source.observe().first()
        assertEquals(10L, ten.mondaysListened)
        assertTrue(ten.earned().contains("mondays_10"))
    }

    @Test fun `a pre-v51 row is not a day of listening`() = withSource { db, source ->
        // The old counter saved every five seconds and is not proof; migration
        // 50->51 left these rows with zero verified millis on purpose.
        (1..7).forEach { day ->
            db.audiobookDao().saveListeningStat(
                ListeningStatEntity("2026-05-%02d".format(day), listenedSeconds = 3600L)
            )
        }
        val legacy = source.observe().first()
        assertEquals(0L, legacy.longestDayStreak)
        assertEquals(0L, legacy.bestMonthDays)
        assertEquals(0L, legacy.mondaysListened)
        assertEquals(0L, legacy.bestDayMillis)
        assertEquals(emptySet<String>(), legacy.earned())
    }
}
