package com.slukhayka.audiobooks.data.achievements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.ListeningStatEntity
import com.slukhayka.audiobooks.ui.achievements.achievementName
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
            block(database, RoomAchievementProgressSource(database.achievementDao(), store, emptySet(), abandonedBookIds = flowOf(emptySet())))
        } finally {
            database.close()
        }
    }

    private suspend fun AudiobookDatabase.heard(iso: String, millis: Long = 10 * minute) =
        audiobookDao().addVerifiedListeningTime(iso, millis, 0L, 0L, 0L, null, null)

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

    /**
     * #1166 (T8, story 13) — «Слухацький рік»: 365 DIFFERENT days with
     * listening, counted cumulatively over the whole history.
     *
     * The boundary is the point: 364 days must not open it, and the two rows
     * that are NOT days of listening — a pre-v51 row with zero verified millis
     * and a stray half-minute — must not shorten the wait either. They are real
     * rows in `listening_stats`, so a row COUNT would already have opened the
     * year on 364 heard days.
     *
     * The fixture deliberately CROSSES 1 January: 200 days of 2026 plus 164 of
     * 2027 are 364 different days, and no calendar year owns more than 200 of
     * them. Inside one year a «most days in a year» reading or a rolling
     * 365-day window would report the same number as the cumulative one, so the
     * two boundaries would pin nothing.
     */
    @Test fun `the listening year opens at 365 different days, and no other row counts`() = withSource { db, source ->
        val acrossNewYear = (0 until 200).map { LocalDate.of(2026, 6, 1).plusDays(it.toLong()) } +
            (0 until 164).map { LocalDate.of(2027, 1, 1).plusDays(it.toLong()) }
        acrossNewYear.forEach { db.heard(it.toString()) }
        // The old five-second counter is not proof, and half a minute is not a
        // day: both rows exist, neither day happened.
        db.audiobookDao().saveListeningStat(ListeningStatEntity("2027-07-01", listenedSeconds = 3600L))
        db.heard("2027-07-02", 30_000L)

        val almost = source.observe().first()
        assertEquals("рік мусить порахувати 364 різні дні, а не 366 рядків", 364L, almost.listeningDays)
        assertFalse("364 дні ще не рік", "listening_year_365" in almost.earned())

        db.heard("2027-06-14")
        val exact = source.observe().first()
        assertEquals("365-й різний день, уже в наступному році", 365L, exact.listeningDays)
        assertTrue("365 різних днів мусять відкрити «Слухацький рік»", "listening_year_365" in exact.earned())
    }

    /**
     * The catalogue entry is pinned as DATA: the rung, the metric and the 365
     * days. «Слухацький рік» is CUMULATIVE (owner's decision, #1166), and the
     * name that decision explicitly ruled out is «Рік у навушниках» — it would
     * promise the calendar year this award does not measure.
     */
    @Test fun `the catalogue pins the listening year to 365 cumulative days, visibly`() {
        val definition = AchievementCatalog.definitions.single { it.id == "listening_year_365" }

        assertEquals("regularity", definition.group)
        assertEquals(5, definition.level)
        assertEquals(AchievementMetric.LISTENING_DAYS, definition.metric)
        assertEquals(365L, definition.threshold)
        assertFalse("«Слухацький рік» видимий — не прихований", definition.hidden)
        assertTrue(
            "і стоїть у «Попереду», доки не здобутий",
            AchievementBoard.of(earnedIds = emptySet()).upcoming.any { it.id == "listening_year_365" }
        )
    }

    @Test fun `the year award is named «Слухацький рік», not a calendar year in earphones`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("Слухацький рік", achievementName(context, "listening_year_365"))
    }
}
