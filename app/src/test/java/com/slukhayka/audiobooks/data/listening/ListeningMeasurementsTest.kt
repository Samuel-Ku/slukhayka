package com.slukhayka.audiobooks.data.listening

import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1173 (T9) — the measurements layer as the recorder drives it: one immutable
 * observation at a time into the single writer.
 *
 * The clock is injected and the zone is fixed, so the 15-minute session edge
 * and the 00:00–04:00 night window are table tests rather than tests that
 * depend on when they run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListeningMeasurementsTest {
    private val kyiv: TimeZone = TimeZone.getTimeZone("Europe/Kyiv")

    /** A local wall-clock instant in the store's zone. */
    private fun at(local: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .apply { timeZone = kyiv }
            .parse(local)!!.time

    /**
     * The wall clock the store writes with. [interval] moves it to the end of
     * an interval of `millis` that started `gap` after the previous one ended,
     * so the pause between two observations is exactly `gap`.
     */
    private class Clock(var at: Long) {
        fun interval(millis: Long, gap: Long = 0L): Long {
            at += gap + millis
            return at
        }
    }

    private fun TestScope.store(dao: FakeAudiobookDao, clock: Clock) =
        ListeningStateStore(dao, UnconfinedTestDispatcher(testScheduler), now = { clock.at }, zone = kyiv)

    private suspend fun ListeningStateStore.play(millis: Long, bookId: String? = "book") =
        recordActualListeningTime(ListeningObservation.Played(millis, bookId))

    @Test fun `a 14 59 pause keeps one session and 15 00 exactly opens the next`() = runTest {
        for ((pause, sessions) in listOf(14 * 60_000L + 59_000L to 1, 15 * 60_000L to 2)) {
            val dao = FakeAudiobookDao()
            val clock = Clock(at("2026-10-08 12:00:00"))
            val store = store(dao, clock)
            store.play(60_000L)
            clock.interval(millis = 60_000L, gap = pause)
            store.play(60_000L)
            assertEquals("пауза $pause мс", sessions, dao.savedPlaybackSessions.size)
            assertEquals(120_000L, dao.savedListeningStats.single().verifiedListenedMillis)
        }
    }

    @Test fun `another book closes the session even a second later`() = runTest {
        val dao = FakeAudiobookDao()
        val clock = Clock(at("2026-10-08 12:00:00"))
        val store = store(dao, clock)
        store.play(60_000L, bookId = "book-a")
        clock.interval(millis = 60_000L, gap = 1_000L)
        store.play(60_000L, bookId = "book-b")
        val sessions = dao.savedPlaybackSessions
        assertEquals(2, sessions.size)
        assertEquals(60_000L, sessions[0].verifiedMillis)
        assertEquals(60_000L, sessions[1].verifiedMillis)
    }

    @Test fun `a stop closes the session even a second later`() = runTest {
        val dao = FakeAudiobookDao()
        val clock = Clock(at("2026-10-08 12:00:00"))
        val store = store(dao, clock)
        store.play(60_000L)
        clock.interval(millis = 0L, gap = 1_000L)
        store.recordActualListeningTime(ListeningObservation.Stopped)
        clock.interval(millis = 60_000L, gap = 1_000L)
        store.play(60_000L)
        assertEquals(2, dao.savedPlaybackSessions.size)
    }

    @Test fun `a dead process leaves the session closed at its last observed tick`() = runTest {
        val dao = FakeAudiobookDao()
        val clock = Clock(at("2026-10-08 12:00:00"))
        val first = store(dao, clock)
        first.play(60_000L)
        clock.interval(millis = 30_000L, gap = 0L)
        first.play(30_000L)
        val lastObserved = clock.at
        // The process dies with the session open; a new one starts a second later.
        clock.interval(millis = 10_000L, gap = 1_000L)
        val restarted = store(dao, clock)
        restarted.play(10_000L)

        val sessions = dao.savedPlaybackSessions
        assertEquals(2, sessions.size)
        assertEquals("сеанс закрито на останньому спостереженому тіку", lastObserved, sessions[0].endedAt)
        assertEquals(90_000L, sessions[0].verifiedMillis)
        assertEquals("наступний тік відкриває новий сеанс", clock.at - 10_000L, sessions[1].startedAt)
        assertEquals(100_000L, dao.savedListeningStats.single().verifiedListenedMillis)
    }

    @Test fun `offline cast and plain time are booked from the evidence of each interval`() = runTest {
        val dao = FakeAudiobookDao()
        val clock = Clock(at("2026-10-08 12:00:00"))
        val store = store(dao, clock)
        store.recordActualListeningTime(ListeningObservation.Played(1_000L, "book", offline = true))
        clock.interval(millis = 2_000L, gap = 5_000L)
        store.recordActualListeningTime(ListeningObservation.Played(2_000L, "book", cast = true))
        clock.interval(millis = 3_000L, gap = 5_000L)
        store.play(3_000L)

        val row = store.getAllListeningStats().first().single()
        assertEquals(6_000L, row.verifiedListenedMillis)
        assertEquals("лише інтервал із локальним доказом", 1_000L, row.offlineListenedMillis)
        assertEquals("лише інтервал із підтвердженим приймачем", 2_000L, row.castListenedMillis)
        assertEquals(0L, row.nightListenedMillis)
        val session = dao.savedPlaybackSessions.single()
        assertEquals(6_000L, session.verifiedMillis)
        assertEquals(1_000L, session.offlineMillis)
        assertEquals(2_000L, session.castMillis)
    }

    @Test fun `the night window is 00 00 to 03 59 local of the day the tick was written`() = runTest {
        val cases = listOf(
            "2026-10-07 23:59:00" to (0L to "2026-10-07"),
            "2026-10-08 00:00:00" to (1_000L to "2026-10-08"),
            "2026-10-08 03:59:59" to (1_000L to "2026-10-08"),
            "2026-10-08 04:00:00" to (0L to "2026-10-08")
        )
        for ((local, expected) in cases) {
            val dao = FakeAudiobookDao()
            val store = store(dao, Clock(at(local)))
            store.play(1_000L)
            val row = store.getAllListeningStats().first().single()
            assertEquals(local, expected.second, row.dateIso)
            assertEquals(local, expected.first, row.nightListenedMillis)
            assertEquals(1_000L, row.verifiedListenedMillis)
        }
    }

    @Test fun `a tick is counted whole where it was written and never split across the window`() = runTest {
        // The same interval shape, one second apart on the clock: 03:59:59 is
        // night in full, 04:00:00 is morning in full. A proportional split of
        // the interval would give both halves a number nobody observed.
        val before = FakeAudiobookDao()
        store(before, Clock(at("2026-10-08 03:59:59"))).play(10_000L)
        assertEquals(10_000L, before.savedListeningStats.single().nightListenedMillis)

        val after = FakeAudiobookDao()
        store(after, Clock(at("2026-10-08 04:00:00"))).play(10_000L)
        assertEquals(0L, after.savedListeningStats.single().nightListenedMillis)
        assertEquals(10_000L, after.savedListeningStats.single().verifiedListenedMillis)
    }
}
