package com.slukhayka.audiobooks.data.achievements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.PlaybackEventEntity
import com.slukhayka.audiobooks.data.db.PlaybackEventKind
import com.slukhayka.audiobooks.data.db.PlaybackSessionEntity
import java.time.LocalDateTime
import java.time.ZoneId
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
 * #1183 (T9b) — the eight awards built on the measurement layer (#1173).
 *
 * Two questions, split the way this module already splits them:
 *
 *  - the THRESHOLDS: every award opens at exactly its own number and not one
 *    millisecond earlier. The table at the top writes the spec's numbers out
 *    rather than reading `definition.threshold - 1`, because a test that reads
 *    the catalogue would follow a wrong threshold instead of failing on it;
 *  - the WIRING: each metric reads the recorded column or row it claims to
 *    read. Offline hours are not the verified total, cast hours are not offline
 *    ones, «Синхронно» is ONE session rather than a sum of two, «Літак» is an
 *    offline SESSION rather than offline hours, «Світанок» counts mornings
 *    rather than sessions, and «До кінця розділу» reads the arm counter rather
 *    than `TIMER_STOP`.
 *
 * The rows are real: an in-memory Room database, the real DAO queries and the
 * real composition adapter, with the zone INJECTED — "a session that started
 * between 06:00 and 08:00" is a local hour, not an instant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MeasurementAwardsTest {

    private val hour = 3_600_000L
    private val minute = 60_000L
    private val kyiv = ZoneId.of("Europe/Kyiv")

    /** Every award of this slice, so a test can say «none of the eight». */
    private val eight = setOf(
        "autonomous_10h", "download_gourmet_100h", "big_screen_10h", "airplane_2h",
        "chapter_end_10", "sync_4h", "night_shift_2h", "dawn_5"
    )

    private fun withSource(block: suspend (AudiobookDatabase, RoomAchievementProgressSource) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val store = RoomAchievementStore(database.achievementDao())
            block(database, RoomAchievementProgressSource(database.achievementDao(), store, emptySet(), zoneId = kyiv))
        } finally {
            database.close()
        }
    }

    /** A day row, written the way the recorder writes it (`addVerifiedListeningTime`). */
    private suspend fun AudiobookDatabase.heard(
        iso: String,
        millis: Long,
        offline: Long = 0L,
        cast: Long = 0L,
        night: Long = 0L
    ) = audiobookDao().addVerifiedListeningTime(iso, millis, offline, cast, night, null, null)

    /** One recorded session, as the measurement layer leaves it. */
    private suspend fun AudiobookDatabase.session(
        verified: Long,
        offline: Long = 0L,
        startedAt: Long = at(2026, 10, 1, 12)
    ) = audiobookDao().insertPlaybackSession(
        PlaybackSessionEntity(
            startedAt = startedAt,
            endedAt = startedAt + verified,
            verifiedMillis = verified,
            offlineMillis = offline
        )
    )

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(kyiv).toInstant().toEpochMilli()

    private fun AchievementProgress.earned(): Set<String> =
        AchievementEvaluator.evaluate(this, emptySet()).map { it.id }.toSet()

    /**
     * The threshold table: every one of the eight opens at exactly its number,
     * never at «поріг − 1», and a repeat evaluation adds nothing.
     */
    @Test fun `every award of the slice opens at exactly its own threshold`() {
        val cases = listOf(
            Triple("autonomous_10h", 10 * hour) { value: Long -> AchievementProgress(offlineMillis = value) },
            Triple("download_gourmet_100h", 100 * hour) { value: Long -> AchievementProgress(offlineMillis = value) },
            Triple("big_screen_10h", 10 * hour) { value: Long -> AchievementProgress(castMillis = value) },
            Triple("airplane_2h", 2 * hour) { value: Long -> AchievementProgress(longestOfflineSessionMillis = value) },
            Triple("chapter_end_10", 10L) { value: Long -> AchievementProgress(endOfChapterArms = value) },
            Triple("sync_4h", 4 * hour) { value: Long -> AchievementProgress(longestSessionMillis = value) },
            Triple("night_shift_2h", 2 * hour) { value: Long -> AchievementProgress(nightMillis = value) },
            Triple("dawn_5", 5L) { value: Long -> AchievementProgress(morningDays = value) }
        )

        for ((id, threshold, snapshot) in cases) {
            assertFalse(
                "$id не має відкриватись на ${threshold - 1}",
                id in snapshot(threshold - 1).earned()
            )
            assertTrue(
                "$id мусить відкритись на $threshold",
                id in snapshot(threshold).earned()
            )
            assertTrue(
                "повторна видача не має нічого додавати",
                AchievementEvaluator.evaluate(snapshot(threshold), setOf(id)).none { it.id == id }
            )
        }
    }

    /**
     * The offline and cast awards read the STRONG-evidence columns, never the
     * verified total: ten ordinary hours are neither ten offline hours nor ten
     * on a receiver, and the boundaries sit on the exact millisecond.
     */
    @Test fun `offline and cast hours read their own recorded columns`() = withSource { db, source ->
        db.heard("2026-10-01", millis = 10 * hour)
        val plain = source.observe().first()
        assertEquals(0L, plain.offlineMillis)
        assertEquals(0L, plain.castMillis)
        assertFalse("звичайні години не є офлайном", "autonomous_10h" in plain.earned())
        assertFalse("і не є кастом", "big_screen_10h" in plain.earned())

        db.heard("2026-10-02", millis = 10 * hour - 1, offline = 10 * hour - 1)
        val justBefore = source.observe().first()
        assertEquals(10 * hour - 1, justBefore.offlineMillis)
        assertFalse("10 год − 1 мс — ще не «Автономний»", "autonomous_10h" in justBefore.earned())

        db.heard("2026-10-02", millis = 1, offline = 1)
        val exact = source.observe().first()
        assertEquals("офлайн-години сумуються за днями", 10 * hour, exact.offlineMillis)
        assertEquals("каст у цьому стані нульовий", 0L, exact.castMillis)
        assertTrue("десять рівно — «Автономний»", "autonomous_10h" in exact.earned())
        assertFalse("сто годин — окремий поріг", "download_gourmet_100h" in exact.earned())
        // The columns are NOT interchangeable: ten offline hours and no receiver
        // must not open the cast award, which is exactly what reading the wrong
        // column would do (ADR-0060 — only a confirmed receiver proves cast).
        assertFalse("каст не відкривається офлайновими годинами", "big_screen_10h" in exact.earned())

        // A receiver day plus a DIFFERENT number of offline hours, so the two
        // sums cannot coincide and a swapped column cannot pass by luck.
        db.heard("2026-10-03", millis = 10 * hour, cast = 10 * hour)
        db.heard("2026-10-04", millis = 5 * hour, offline = 5 * hour)
        val onReceiver = source.observe().first()
        assertEquals(10 * hour, onReceiver.castMillis)
        assertEquals("офлайн і каст — різні колонки з різними сумами", 15 * hour, onReceiver.offlineMillis)
        assertTrue("десять каст-годин — «Великий екран»", "big_screen_10h" in onReceiver.earned())
    }

    /**
     * «Нічна зміна» is the SUM of the recorded night column across days, and
     * daytime listening is not night: the window itself belongs to the writer
     * (`NightWindow`, `ListeningMeasurementsTest`).
     */
    @Test fun `the night shift sums the recorded night column across days`() = withSource { db, source ->
        db.heard("2026-10-01", millis = 5 * hour)
        assertFalse("денні години не є ніччю", "night_shift_2h" in source.observe().first().earned())

        db.heard("2026-10-02", millis = 2 * hour - 1, night = 2 * hour - 1)
        val justBefore = source.observe().first()
        assertEquals(2 * hour - 1, justBefore.nightMillis)
        assertFalse("2 год − 1 мс — ще не «Нічна зміна»", "night_shift_2h" in justBefore.earned())

        db.heard("2026-10-03", millis = 1, night = 1)
        val exact = source.observe().first()
        assertEquals("частини різних днів складаються", 2 * hour, exact.nightMillis)
        assertTrue("дві нічні години сумою — «Нічна зміна»", "night_shift_2h" in exact.earned())
    }

    /** «Синхронно» is ONE session of four hours: two shorter ones never sum. */
    @Test fun `in sync needs one session of four hours, never two shorter ones`() = withSource { db, source ->
        db.session(verified = 3 * hour + 30 * minute, startedAt = at(2026, 10, 1, 12))
        db.session(verified = 3 * hour + 30 * minute, startedAt = at(2026, 10, 2, 12))
        val two = source.observe().first()
        assertEquals("найдовший сеанс — один, не сума", 3 * hour + 30 * minute, two.longestSessionMillis)
        assertFalse("два сеанси по 3,5 год не дають 4", "sync_4h" in two.earned())

        db.session(verified = 4 * hour - 1, startedAt = at(2026, 10, 3, 12))
        assertFalse("4 год − 1 мс — ще не «Синхронно»", "sync_4h" in source.observe().first().earned())

        db.session(verified = 4 * hour, startedAt = at(2026, 10, 4, 12))
        val one = source.observe().first()
        assertEquals(4 * hour, one.longestSessionMillis)
        assertTrue("один сеанс на 4 години — «Синхронно»", "sync_4h" in one.earned())
    }

    /** «Літак» is an offline SESSION: offline hours without one are not a trip. */
    @Test fun `airplane counts an offline session, not offline hours`() = withSource { db, source ->
        db.heard("2026-10-01", millis = 10 * hour, offline = 10 * hour)
        val hours = source.observe().first()
        assertTrue("офлайн-години відкривають «Автономний»", "autonomous_10h" in hours.earned())
        assertEquals(0L, hours.longestOfflineSessionMillis)
        assertFalse("години без сеансу не є подорожжю", "airplane_2h" in hours.earned())

        db.session(verified = 3 * hour, offline = 2 * hour - 1, startedAt = at(2026, 10, 2, 12))
        assertFalse(
            "офлайн-частина 2 год − 1 мс — не «Літак»",
            "airplane_2h" in source.observe().first().earned()
        )

        db.session(verified = 2 * hour, offline = 2 * hour, startedAt = at(2026, 10, 3, 12))
        val trip = source.observe().first()
        assertEquals(2 * hour, trip.longestOfflineSessionMillis)
        assertTrue("офлайн-сеанс на 2 години — «Літак»", "airplane_2h" in trip.earned())
    }

    /**
     * «До кінця розділу» reads the arm counter. Ten finished sleep timers are a
     * different fact and open nothing, and the tenth arm is the one that counts
     * (#700: at a chapter boundary the timer re-arms and `TIMER_STOP` may never
     * be written at all).
     */
    @Test fun `chapter end counts the arm counter, not TIMER_STOP`() = withSource { db, source ->
        repeat(10) {
            db.audiobookDao().insertPlaybackEvent(
                PlaybackEventEntity(bookId = "book", kind = PlaybackEventKind.TIMER_STOP)
            )
        }
        assertFalse("TIMER_STOP не є вмиканням режиму", "chapter_end_10" in source.observe().first().earned())

        repeat(9) { db.achievementDao().incrementCounter(AchievementCounter.END_OF_CHAPTER_ARM) }
        val nine = source.observe().first()
        assertEquals(9L, nine.endOfChapterArms)
        assertFalse("дев'ять увімкнень — ще не десять", "chapter_end_10" in nine.earned())

        db.achievementDao().incrementCounter(AchievementCounter.END_OF_CHAPTER_ARM)
        val ten = source.observe().first()
        assertEquals("повторне вмикання додає рівно один", 10L, ten.endOfChapterArms)
        assertTrue("десяте увімкнення відкриває нагороду", "chapter_end_10" in ten.earned())
    }

    /** «Світанок» counts MORNINGS: five breaks inside one dawn are still one. */
    @Test fun `dawn counts different mornings, not sessions`() = withSource { db, source ->
        repeat(5) { index ->
            db.session(verified = 10 * minute, startedAt = at(2026, 10, 8, 6, 30 + index * 5))
        }
        val oneMorning = source.observe().first()
        assertEquals("пʼять перерв за один ранок — це один ранок", 1L, oneMorning.morningDays)
        assertFalse("пʼять сеансів за один ранок нагороди не дають", "dawn_5" in oneMorning.earned())

        (1..4).forEach { day -> db.session(verified = 10 * minute, startedAt = at(2026, 10, day, 6, 30)) }
        val five = source.observe().first()
        assertEquals(5L, five.morningDays)
        assertTrue("пʼять різних ранків — «Світанок»", "dawn_5" in five.earned())
    }

    /**
     * The dawn window is 06:00–07:59 local of the session's own START; a session
     * that merely runs through the window was not started in it.
     */
    @Test fun `the dawn window is 06 00 to 07 59 local of the start`() = withSource { db, source ->
        val cases = listOf(
            Triple(at(2026, 10, 1, 5, 59), 0L, "05:59"),
            Triple(at(2026, 10, 2, 6, 0), 1L, "06:00"),
            Triple(at(2026, 10, 3, 7, 59), 1L, "07:59"),
            Triple(at(2026, 10, 4, 8, 0), 0L, "08:00")
        )

        var expected = 0L
        for ((startedAt, adds, label) in cases) {
            db.session(verified = minute, startedAt = startedAt)
            expected += adds
            assertEquals("старт о $label", expected, source.observe().first().morningDays)
        }
    }

    /** Nothing recorded, and nothing from before v54, opens any of the eight. */
    @Test fun `nothing recorded opens none of the eight`() = withSource { db, source ->
        assertEquals(emptySet<String>(), source.observe().first().earned())

        // A pre-v54 row: real verified time, but the three measured columns are
        // still zero, so the offline, cast and night awards stay shut.
        db.heard("2026-05-01", millis = 50 * hour)
        val legacy = source.observe().first()
        assertEquals(50 * hour, legacy.verifiedListeningMillis)
        assertTrue(
            "старі дані не відкривають жодної з восьми",
            legacy.earned().none { it in eight }
        )
    }
}
