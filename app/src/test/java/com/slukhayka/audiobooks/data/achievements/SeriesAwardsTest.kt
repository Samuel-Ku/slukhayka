package com.slukhayka.audiobooks.data.achievements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.PlaybackEventEntity
import com.slukhayka.audiobooks.data.db.PlaybackEventKind
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.testing.TestDataFactory
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
 * #701 (US33, US34, US36) — «У циклі», «Серієман» і «По порядку».
 *
 * The reading pinned here is the owner's (#701, 2026-10-07): a series is
 * FINISHED when every own book of it carries a recorded completion, because the
 * full membership of a series lives online and no local table holds it. The
 * order award is about ORDER, not completeness: the own numbered tomes must
 * have been finished in numeric order.
 *
 * Everything runs against REAL rows in an in-memory Room, because the honesty
 * of this slice lives in the query and the wiring: `library_entries.origin`
 * decides what is own, the `works` join carries the series claim, and the
 * completion instant comes from a real `playback_events` row. A fake snapshot
 * would prove none of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SeriesAwardsTest {

    private val base = 1_700_000_000_000L

    private fun <T> withDatabase(block: suspend (AudiobookDatabase) -> T): T = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    /**
     * One real library row with its Work: the Entry is what the query reads,
     * the Work carries the series claim, and the completion is a real
     * end-of-book event.
     *
     * [origin] is the ADR-0060 rule under test: only EXPLICIT_SAVE /
     * EXPLICIT_IMPORT are own books, and the default here is the explicit one so
     * every case says out loud when it means a catalogue mirror instead.
     */
    private suspend fun ownBook(
        database: AudiobookDatabase,
        bookId: String,
        seriesTitle: String? = null,
        seriesIndex: Int? = null,
        seriesUrl: String? = null,
        completedAt: Long? = null,
        origin: String = "EXPLICIT_SAVE"
    ) {
        val dao = database.audiobookDao()
        val row = TestDataFactory.dataBooks().first().copy(id = bookId, title = "Книга $bookId")
        dao.insertAudiobooks(listOf(row))
        dao.upsertLibraryEntry(bookId, bookId, false, 1L, 0f)
        dao.updateLibraryEntryOrigin(bookId, origin)
        dao.upsertWork(
            WorkEntity(
                id = bookId, mergeKey = "key-$bookId", title = row.title, author = "Автор",
                seriesTitle = seriesTitle, seriesUrl = seriesUrl, seriesIndex = seriesIndex
            )
        )
        if (completedAt != null) {
            dao.insertPlaybackEvent(
                PlaybackEventEntity(bookId = bookId, kind = PlaybackEventKind.COMPLETED, timestamp = completedAt)
            )
        }
    }

    private suspend fun snapshot(database: AudiobookDatabase): AchievementProgress =
        RoomAchievementProgressSource(
            database.achievementDao(), RoomAchievementStore(database.achievementDao()), emptySet(),
            abandonedBookIds = flowOf(emptySet())
        ).observe().first()

    private suspend fun earned(database: AudiobookDatabase): List<String> =
        AchievementEvaluator.evaluate(snapshot(database), emptySet()).map { it.id }

    /**
     * The three catalogue entries as DATA: the group, the rung, the metric and
     * the threshold the owner accepted. A later change has to change this test
     * first, which makes it a decision rather than drift.
     */
    @Test
    fun `the catalogue pins the series pair and the order award`() {
        val inCycle = AchievementCatalog.definitions.single { it.id == "in_cycle" }
        assertEquals("series", inCycle.group)
        assertEquals(2, inCycle.level)
        assertEquals(AchievementMetric.COMPLETED_SERIES, inCycle.metric)
        assertEquals(1L, inCycle.threshold)
        assertFalse("«У циклі» видима — не прихована", inCycle.hidden)

        val seriesMan = AchievementCatalog.definitions.single { it.id == "series_man" }
        assertEquals("series", seriesMan.group)
        assertEquals(3, seriesMan.level)
        assertEquals(AchievementMetric.COMPLETED_SERIES, seriesMan.metric)
        assertEquals("п'ять завершених циклів — поріг специфікації", 5L, seriesMan.threshold)

        val inOrder = AchievementCatalog.definitions.single { it.id == "in_order" }
        assertEquals("series", inOrder.group)
        assertEquals(4, inOrder.level)
        assertEquals(AchievementMetric.ORDERED_SERIES, inOrder.metric)
        assertEquals(1L, inOrder.threshold)
    }

    /**
     * The boundary of «У циклі»: one finished series opens it and four do not
     * open «Серіємана». The ladder is pinned at BOTH ends, so a changed
     * threshold cannot move the answer with it.
     */
    @Test
    fun `the cycle ladder opens at one finished series and at five`() = withDatabase { database ->
        for (index in 1..4) {
            ownBook(database, "book-$index", seriesTitle = "Цикл $index", completedAt = base + index)
        }
        val four = earned(database)
        assertTrue("одна завершена серія відкриває «У циклі»", "in_cycle" in four)
        assertFalse("чотири серії ще не «Серієман»", "series_man" in four)
        assertEquals(4L, snapshot(database).completedSeries)

        ownBook(database, "book-5", seriesTitle = "Цикл 5", completedAt = base + 5)
        val five = earned(database)
        assertEquals(5L, snapshot(database).completedSeries)
        assertTrue("п'ята завершена серія відкриває «Серіємана»", "series_man" in five)
    }

    /**
     * «Усі власні твори серії завершено» — read literally. One unfinished own
     * volume keeps the WHOLE series open, because the listener has not reached
     * the end of it; finishing that volume is what closes the series.
     */
    @Test
    fun `one unfinished own volume keeps its series open`() = withDatabase { database ->
        ownBook(database, "tome-1", seriesTitle = "Відьмак", completedAt = base)
        ownBook(database, "tome-2", seriesTitle = "Відьмак")

        assertEquals("серія з незавершеним власним томом не завершена", 0L, snapshot(database).completedSeries)
        assertFalse("«У циклі» не має відкриватись", "in_cycle" in earned(database))

        ownBook(database, "tome-2", seriesTitle = "Відьмак", completedAt = base + 1)
        assertEquals(1L, snapshot(database).completedSeries)
        assertTrue("після завершення тому серія закрита", "in_cycle" in earned(database))
    }

    /**
     * A catalogue mirror is NOT an own book (ADR-0047): a Work that arrived
     * with AUTO_SEED and was never opened must neither block a finished series
     * nor finish one by itself.
     */
    @Test
    fun `a catalogue mirror neither blocks nor completes a series`() {
        withDatabase { database ->
            ownBook(database, "own-tome", seriesTitle = "Відьмак", completedAt = base)
            ownBook(database, "mirror-tome", seriesTitle = "Відьмак", origin = "AUTO_SEED")

            assertEquals(
                "дзеркало каталогу не рахується власним томом",
                1L, snapshot(database).completedSeries
            )
            assertTrue("власний том завершено — серія закрита", "in_cycle" in earned(database))
        }
        // And the mirror alone never closes anything: without the own tome the
        // series has no own books at all, so there is nothing to finish.
        withDatabase { database ->
            ownBook(database, "mirror-only", seriesTitle = "Відьмак", origin = "AUTO_SEED")

            assertEquals(0L, snapshot(database).completedSeries)
            assertFalse("дзеркало не завершує серію", "in_cycle" in earned(database))
        }
    }

    /**
     * Every edge of «По порядку» in one table. The award is about ORDER: the
     * numbered tomes must have been finished in numeric order, and a tome
     * without a number proves nothing.
     *
     * The single-tome case is the definition, not a threshold: one tome is not
     * a sequence, so nothing about order can be proven from it — and opening
     * «По порядку» there would celebrate a single finished book.
     */
    @Test
    fun `the order award needs numbered tomes finished in numeric order`() {
        val cases = listOf(
            "один том — порядку ще немає" to listOf(1 to base),
            "перший том, потім другий" to listOf(1 to base, 2 to base + 1),
            "другий том, потім перший" to listOf(2 to base, 1 to base + 1),
            "три томи за порядком" to listOf(1 to base, 2 to base + 1, 3 to base + 2),
            "третій том між першим і другим" to listOf(1 to base, 3 to base + 1, 2 to base + 2)
        )
        val expected = mapOf(
            "один том — порядку ще немає" to false,
            "перший том, потім другий" to true,
            "другий том, потім перший" to false,
            "три томи за порядком" to true,
            "третій том між першим і другим" to false
        )
        for ((name, tomes) in cases) {
            withDatabase { database ->
                for ((index, at) in tomes) {
                    ownBook(
                        database, "tome-$index", seriesTitle = "Відьмак",
                        seriesIndex = index, completedAt = at
                    )
                }
                assertEquals(name, expected.getValue(name), "in_order" in earned(database))
            }
        }
    }

    /**
     * A tome whose `seriesIndex` the source never filled is NOT counted — an
     * unknown position cannot prove an order (ADR-0014) — and it does not break
     * the sequence of the numbered ones either: the owner's reading counts the
     * tomes that HAVE a number, and "томи без номерів не рахуються" is exactly
     * that.
     */
    @Test
    fun `a tome without a number is not counted and does not break the order`() = withDatabase { database ->
        ownBook(database, "tome-1", seriesTitle = "Відьмак", seriesIndex = 1, completedAt = base)
        ownBook(database, "tome-2", seriesTitle = "Відьмак", seriesIndex = 2, completedAt = base + 2)
        ownBook(database, "unnumbered", seriesTitle = "Відьмак", completedAt = base + 1)

        assertTrue(
            "том без номера не рахується і не ламає порядок нумерованих",
            "in_order" in earned(database)
        )
    }

    /**
     * Two tomes finished in the SAME millisecond are not an order: the sequence
     * cannot say which came first, so the honest answer is "not proven". The
     * comparison is strict on both ends for exactly this case.
     */
    @Test
    fun `two tomes finished at the same instant are not ordered`() = withDatabase { database ->
        ownBook(database, "tome-1", seriesTitle = "Відьмак", seriesIndex = 1, completedAt = base)
        ownBook(database, "tome-2", seriesTitle = "Відьмак", seriesIndex = 2, completedAt = base)

        assertFalse("однаковий момент не доводить порядку", "in_order" in earned(database))
    }

    /**
     * The series identity is the ONE normalized title (ADR-0012): «Відьмак» and
     * «Відьмак (цикл)» are one series, so a tome finished under either spelling
     * belongs to the same cycle — a second identity here would let the same
     * series be finished twice or never.
     */
    @Test
    fun `two spellings of one series are one series`() = withDatabase { database ->
        ownBook(database, "tome-1", seriesTitle = "Відьмак", completedAt = base)
        ownBook(database, "tome-2", seriesTitle = "Відьмак (цикл)", completedAt = base + 1)

        assertEquals("два написання — одна серія", 1L, snapshot(database).completedSeries)
    }
}
