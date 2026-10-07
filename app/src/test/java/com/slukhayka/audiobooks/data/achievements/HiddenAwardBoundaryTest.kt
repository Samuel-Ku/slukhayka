package com.slukhayka.audiobooks.data.achievements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.PlaybackEventEntity
import com.slukhayka.audiobooks.data.db.PlaybackEventKind
import com.slukhayka.audiobooks.testing.TestDataFactory
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #703 (T5) — AC3: every hidden award has a TABLE of its boundary, false
 * neighbours included.
 *
 * The scenario tests next door prove each award can open at all. What they do
 * not pin is where it stops: a completion at 01:59 is not night, 7 January is
 * Christmas and 2 January is not, and a book finished EXACTLY 365 days after it
 * was added is not «Старовинна» — the query asks for more than that. Those are
 * the numbers this file freezes, one row per case, so a threshold typed wrong
 * fails here instead of shipping.
 *
 * Two rules hold for everything below:
 *
 *  - the rows are REAL: an in-memory Room database, real `playback_events` and
 *    `library_entries`, the real DAO queries, and the real composition adapter;
 *  - the zone is INJECTED, because "between 02:00 and 04:00" is a local hour,
 *    not an instant. Without a pinned zone the same row would mean different
 *    things in CI and on a phone.
 *
 * Every row of every table runs against a FRESH database. Sharing one would
 * make the rows interfere — an award won by row three is still earned at row
 * four — and the assertion would then be about the order of the table rather
 * than about the boundary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HiddenAwardBoundaryTest {

    private val kyiv = ZoneId.of("Europe/Kyiv")

    private var database: AudiobookDatabase? = null
    private var bookCount = 0

    /**
     * A clean database and adapter for one table row.
     *
     * The previous one is closed rather than left open, so a table of eight
     * cases does not hold eight in-memory databases at once.
     */
    private fun freshDatabase(): RoomAchievementProgressSource {
        database?.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val opened = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        database = opened
        return RoomAchievementProgressSource(
            opened.achievementDao(),
            RoomAchievementStore(opened.achievementDao()),
            emptySet(),
            zoneId = kyiv
        )
    }

    @After fun closeDatabase() {
        database?.close()
        database = null
    }

    /**
     * A fresh fixture book per case, so the cases of one table cannot count each
     * other. The id stays derived from the factory's first book, which keeps
     * every non-id column valid.
     */
    private suspend fun newBookId(dao: AudiobookDatabase): String {
        val id = "boundary-${bookCount++}"
        dao.audiobookDao().insertAudiobooks(
            listOf(TestDataFactory.dataBooks().first().copy(id = id))
        )
        return id
    }

    private suspend fun record(dao: AudiobookDatabase, bookId: String, kind: String, at: Long) {
        dao.audiobookDao().insertPlaybackEvent(
            PlaybackEventEntity(bookId = bookId, kind = kind, timestamp = at)
        )
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(kyiv).toInstant().toEpochMilli()

    private suspend fun earnedIds(source: RoomAchievementProgressSource) =
        AchievementEvaluator.evaluate(source.observe().first(), emptySet()).map { it.id }

    // --- «Нічний вартовий»: завершення з локальною годиною 02:00–03:59 --------

    /**
     * `hourOf(it) in 2..3`, read from the completion's real timestamp in the
     * listener's zone. The rows sit on the minutes either side of both bounds —
     * 01:59 before 02:00 and 04:00 after 03:59 — so an off-by-one in either
     * direction fails here.
     */
    @Test fun `night watch opens exactly inside the 02 to 04 window`() = runBlocking {
        val cases = listOf(
            Triple(1, 59, false),
            Triple(2, 0, true),
            Triple(3, 59, true),
            Triple(4, 0, false),
            // The false boundary the old scenario test already had: midday.
            Triple(12, 0, false)
        )
        for ((hour, minute, expected) in cases) {
            val source = freshDatabase()
            val dao = database!!
            val book = newBookId(dao)
            record(dao, book, PlaybackEventKind.COMPLETED, at(2026, 3, 10, hour, minute))

            val snapshot = source.observe().first()
            assertEquals(
                "нічних завершень о $hour:$minute",
                if (expected) 1L else 0L, snapshot.nightCompletions
            )
            assertEquals(
                "«Нічний вартовий» о $hour:$minute",
                expected, "night_watch" in earnedIds(source)
            )
        }
    }

    /**
     * The window's upper edge is 03:59, not 04:00 — and a completion at 01:59
     * is early morning, not night. Both false neighbours together must leave the
     * award closed even when two completions exist.
     */
    @Test fun `night watch needs 02 00 and rejects 01 59 and 04 00`() = runBlocking {
        val source = freshDatabase()
        val dao = database!!
        record(dao, newBookId(dao), PlaybackEventKind.COMPLETED, at(2026, 3, 10, 1, 59))
        record(dao, newBookId(dao), PlaybackEventKind.COMPLETED, at(2026, 3, 10, 4, 0))

        assertEquals("дві хибні межі не рахуються", 0L, source.observe().first().nightCompletions)
        assertFalse("«Нічний вартовий» не має відкриватись на 01:59 чи 04:00",
            "night_watch" in earnedIds(source))
    }

    // --- «Сова й жайворонок»: min(сесії до 06:00, сесії від 22:00) >= 1 -------

    /**
     * The award needs BOTH sides. The early side takes every hour below 06:00,
     * so 05:59 still counts and only 06:00 falls out; the late side starts at
     * 22:00 exactly, so 21:59 does not.
     *
     * Each loop moves ONE session across its own bound while the other sits
     * comfortably inside the opposite side — 23:00 for the early loop, 05:00
     * for the late one. A widened early window therefore answers 1 at 06:00,
     * and a widened late window answers 1 at 21:59, both where the table says
     * 0. The rows at 21:59 and 22:00 in the first loop are the other half of
     * the same statement: a session in the evening is not an early one.
     */
    @Test fun `owl and lark needs one session on each side of 06 and 22`() = runBlocking {
        val earlyCases = listOf(
            Triple(5, 59, 1L),
            Triple(6, 0, 0L),
            Triple(21, 59, 0L),
            Triple(22, 0, 0L)
        )
        for ((earlyHour, earlyMinute, expected) in earlyCases) {
            val source = freshDatabase()
            val dao = database!!
            val book = newBookId(dao)
            record(dao, book, PlaybackEventKind.RESUME, at(2026, 3, 11, earlyHour, earlyMinute))
            record(dao, book, PlaybackEventKind.RESUME, at(2026, 3, 11, 23, 0))
            assertEquals(
                "рання сесія о $earlyHour:$earlyMinute проти пізньої о 23:00",
                expected, source.observe().first().owlLarkBalance
            )
        }

        val lateCases = listOf(
            Triple(21, 59, 0L),
            Triple(22, 0, 1L),
            Triple(23, 30, 1L)
        )
        for ((lateHour, lateMinute, expected) in lateCases) {
            val source = freshDatabase()
            val dao = database!!
            val book = newBookId(dao)
            record(dao, book, PlaybackEventKind.RESUME, at(2026, 3, 11, 5, 0))
            record(dao, book, PlaybackEventKind.RESUME, at(2026, 3, 11, lateHour, lateMinute))
            assertEquals(
                "пізня сесія о $lateHour:$lateMinute проти ранньої о 05:00",
                expected, source.observe().first().owlLarkBalance
            )
        }
    }

    /**
     * The false case AC2 is about: sessions that are all early (or all late)
     * are not a balance. Nothing here is a tap or a clock reading — both rows
     * are recorded `RESUME` events, the only thing the metric reads.
     *
     * Both rows stay strictly inside their own side: two sessions at 05:30 and
     * 05:59 are two mornings and no evening, two at 22:00 and 23:30 are two
     * evenings and no morning.
     */
    @Test fun `owl and lark stays zero when every session is on one side`() = runBlocking {
        val early = freshDatabase()
        var dao = database!!
        val earlyBook = newBookId(dao)
        record(dao, earlyBook, PlaybackEventKind.RESUME, at(2026, 3, 11, 5, 30))
        record(dao, earlyBook, PlaybackEventKind.RESUME, at(2026, 3, 11, 5, 59))
        assertEquals("лише ранні сесії — не баланс", 0L, early.observe().first().owlLarkBalance)
        assertFalse("«Сова й жайворонок» не має відкриватись на самих жайворонках",
            "owl_and_lark" in earnedIds(early))

        val late = freshDatabase()
        dao = database!!
        val lateBook = newBookId(dao)
        record(dao, lateBook, PlaybackEventKind.RESUME, at(2026, 3, 11, 22, 0))
        record(dao, lateBook, PlaybackEventKind.RESUME, at(2026, 3, 11, 23, 30))
        assertEquals("лише пізні сесії — не баланс", 0L, late.observe().first().owlLarkBalance)
        assertFalse("«Сова й жайворонок» не має відкриватись на самих совах",
            "owl_and_lark" in earnedIds(late))
    }

    // --- «Свято»: 1 січня, 7 січня, 25 грудня --------------------------------

    /**
     * Three real holidays and four false neighbours: 2 January, both sides of
     * 25 December, and 31 December. All of them are completions of the same
     * shape, so only the calendar decides.
     */
    @Test fun `holiday opens on the three dates and on no neighbour`() = runBlocking {
        val cases = listOf(
            Triple("2026-01-01", true, "Новий рік"),
            Triple("2026-01-07", true, "Різдво 7 січня"),
            Triple("2026-12-25", true, "Різдво 25 грудня"),
            Triple("2026-01-02", false, "2 січня — вже не свято"),
            Triple("2026-12-24", false, "24 грудня — ще не свято"),
            Triple("2026-12-26", false, "26 грудня — вже не свято"),
            Triple("2026-12-31", false, "31 грудня — не свято")
        )
        for ((date, expected, label) in cases) {
            val source = freshDatabase()
            val dao = database!!
            val (year, month, day) = date.split("-").map(String::toInt)
            val book = newBookId(dao)
            record(dao, book, PlaybackEventKind.COMPLETED, at(year, month, day, 12))

            val snapshot = source.observe().first()
            assertEquals(
                "$label: святкових завершень",
                if (expected) 1L else 0L, snapshot.holidayCompletions
            )
            assertEquals("$label: «Свято»", expected, "holiday" in earnedIds(source))
        }
    }

    // --- «Старовинна»: понад 365 днів між додаванням і завершенням -----------

    /**
     * `(e.timestamp - le.createdAt) > 31536000000` — STRICTLY more than 365
     * days. A book added exactly a year before it was finished does not count,
     * and one millisecond past that does.
     *
     * The wording around this edge is not unambiguous («понад рік» in the
     * ticket, "at least a YEAR" in the DAO comment), so this test pins the
     * behaviour that is actually there rather than choosing for the owner.
     */
    @Test fun `vintage needs more than 365 days, not exactly 365`() = runBlocking {
        val cases = listOf(
            365L * DAY to false,
            365L * DAY + 1L to true
        )
        for ((delta, expected) in cases) {
            val source = freshDatabase()
            val dao = database!!
            val book = newBookId(dao)
            dao.audiobookDao().upsertLibraryEntry(book, book, false, BASE, 0f)
            record(dao, book, PlaybackEventKind.COMPLETED, BASE + delta)

            assertEquals(
                "через $delta мс після додавання: «Старовинна»",
                expected, "vintage" in earnedIds(source)
            )
        }
    }

    // --- «Перерва»: понад 180 днів між двома сесіями -------------------------

    /**
     * `(b.timestamp - a.timestamp) > 15552000000` — STRICTLY more than 180
     * days between two recorded sessions of the same book.
     */
    @Test fun `comeback needs more than 180 days between two sessions`() = runBlocking {
        val cases = listOf(
            180L * DAY to false,
            180L * DAY + 1L to true
        )
        for ((gap, expected) in cases) {
            val source = freshDatabase()
            val dao = database!!
            val book = newBookId(dao)
            record(dao, book, PlaybackEventKind.RESUME, BASE)
            record(dao, book, PlaybackEventKind.RESUME, BASE + gap)

            assertEquals(
                "розрив $gap мс: «Перерва»",
                expected, "comeback" in earnedIds(source)
            )
        }
    }

    // --- «Ніколи не пізно»: понад 730 днів між відкриттям і фінішем ----------

    /**
     * `(e.timestamp - f.timestamp) > 63072000000` — STRICTLY more than 730
     * days between the completion and ANY earlier recorded event of the same
     * book. The earlier row here is a `RESUME` (the book was first opened), the
     * completion is the finish.
     */
    @Test fun `never too late needs more than 730 days from opening to finish`() = runBlocking {
        val cases = listOf(
            730L * DAY to false,
            730L * DAY + 1L to true
        )
        for ((gap, expected) in cases) {
            val source = freshDatabase()
            val dao = database!!
            val book = newBookId(dao)
            record(dao, book, PlaybackEventKind.RESUME, BASE)
            record(dao, book, PlaybackEventKind.COMPLETED, BASE + gap)

            assertEquals(
                "відкрито за $gap мс до фінішу: «Ніколи не пізно»",
                expected, "never_too_late" in earnedIds(source)
            )
        }
    }

    // --- Гард чесності: лише записані факти, не «час у застосунку» чи тапи ---

    /**
     * The second half of AC2, as a pin rather than a promise.
     *
     * The six merged hidden awards must sit on exactly these metrics, and every
     * one of them is computed from real `playback_events` timestamps by
     * `RoomAchievementProgressSource`. Pinning the exact set is the point: a
     * seventh hidden award added later on some metric nothing writes (taps,
     * time in the app, a counter nobody increments) fails here, so that would
     * be a decision with data behind it instead of drift.
     */
    @Test fun `hidden awards sit only on metrics real events write`() {
        val hidden = AchievementCatalog.definitions.filter { it.hidden }
        val actual = hidden.map { it.id to it.metric }.toMap()

        val expected = mapOf(
            "night_watch" to AchievementMetric.NIGHT_COMPLETIONS,
            "owl_and_lark" to AchievementMetric.OWL_LARK,
            "holiday" to AchievementMetric.HOLIDAY_COMPLETIONS,
            "vintage" to AchievementMetric.VINTAGE_COMPLETIONS,
            "comeback" to AchievementMetric.RETURNS_AFTER_BREAK,
            "never_too_late" to AchievementMetric.LATE_COMPLETIONS
        )

        assertEquals("прихованих нагород мусить бути рівно шість", expected.keys, actual.keys)
        assertEquals("метрики прихованих нагород не мають дрейфувати", expected, actual)
        for ((id, metric) in actual) {
            assertTrue(
                "$id: метрика $metric не має жодного записувача — це був би вигаданий факт",
                metric in recordedEventMetrics
            )
        }
    }

    /**
     * The same rule from the other end: with a real book in the library and a
     * real completion recorded, nothing hidden opens. An award that fired on
     * "the app has been open for a while" would appear here without any event
     * behind it.
     */
    @Test fun `a real book and a real completion open no hidden award on their own`() = runBlocking {
        val source = freshDatabase()
        val dao = database!!
        val book = newBookId(dao)
        record(dao, book, PlaybackEventKind.COMPLETED, at(2026, 6, 15, 12))
        dao.audiobookDao().upsertLibraryEntry(book, book, false, at(2026, 6, 1, 12), 0f)

        val snapshot = source.observe().first()
        val earned = earnedIds(source)
        val hiddenIds = AchievementCatalog.definitions.filter { it.hidden }.map { it.id }
        assertTrue(
            "жодна прихована не має відкриватись від самого лише завершення: " +
                hiddenIds.filter { it in earned },
            hiddenIds.none { it in earned }
        )
        assertEquals("звичайне завершення не робить день святковим", 0L, snapshot.holidayCompletions)
        assertEquals("звичайне завершення не є нічним", 0L, snapshot.nightCompletions)
        assertEquals("звичайне завершення не робить книгу старовинною", 0L, snapshot.vintageCompletions)
    }

    private companion object {
        const val DAY = 86_400_000L

        /** A fixed instant; nothing in these tests reads "now". */
        const val BASE = 1_700_000_000_000L

        /**
         * The six metrics the six merged hidden awards read, each derived from
         * a real event timestamp (`COMPLETED` / `RESUME`) written by the
         * player's Listening State.
         */
        val recordedEventMetrics = setOf(
            AchievementMetric.NIGHT_COMPLETIONS,
            AchievementMetric.OWL_LARK,
            AchievementMetric.HOLIDAY_COMPLETIONS,
            AchievementMetric.VINTAGE_COMPLETIONS,
            AchievementMetric.RETURNS_AFTER_BREAK,
            AchievementMetric.LATE_COMPLETIONS
        )
    }
}
