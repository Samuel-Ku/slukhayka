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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #701 — «П'ять поспіль» (spec story 35).
 *
 * The reading pinned here is the one the ticket settled on: **five consecutive
 * completions in TIME that belong to Works of ONE series**. Deliberately NOT
 * «п'ять томів за порядком номерів» — the volume order would need the series
 * SIZE, and the database has none (`series_members` is written only for the
 * Work someone opened; `series` carries no size column). A number the data
 * cannot prove is not a number the award may use (ADR-0014).
 *
 * Everything runs against REAL rows in an in-memory Room, because the honesty
 * of this slice lives in the query: the two joins, the blank-title guard and
 * the time order. A fake would prove none of that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FiveInARowAwardTest {

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
     * Real library rows: an audiobook plus its Library Entry, which is what the
     * query joins `playback_events.bookId` through.
     */
    private suspend fun libraryBooks(database: AudiobookDatabase, count: Int): List<String> {
        val dao = database.audiobookDao()
        val first = TestDataFactory.dataBooks().first()
        val rows = (0 until count).map { first.copy(id = "run-book-$it", title = "Том $it") }
        dao.insertAudiobooks(rows)
        for (row in rows) dao.upsertLibraryEntry(row.id, row.id, false, 1L, 0f)
        return rows.map { it.id }
    }

    /** The series claim lives on the Work, so a completable book needs one. */
    private suspend fun series(database: AudiobookDatabase, bookId: String, title: String?) {
        database.audiobookDao().upsertWork(
            WorkEntity(
                id = bookId, mergeKey = "key-$bookId", title = bookId,
                author = "Автор", seriesTitle = title
            )
        )
    }

    private suspend fun completed(database: AudiobookDatabase, bookId: String, at: Long) {
        database.audiobookDao().insertPlaybackEvent(
            PlaybackEventEntity(bookId = bookId, kind = PlaybackEventKind.COMPLETED, timestamp = at)
        )
    }

    private suspend fun snapshot(database: AudiobookDatabase): AchievementProgress =
        RoomAchievementProgressSource(
            database.achievementDao(), RoomAchievementStore(database.achievementDao()), emptySet()
        ).observe().first()

    private suspend fun earned(database: AudiobookDatabase): List<String> =
        AchievementEvaluator.evaluate(snapshot(database), emptySet()).map { it.id }

    /**
     * The catalogue entry is pinned as DATA: the id, the group that did not
     * exist before this slice, the first rung of it, the metric and the spec's
     * five. A later change has to change this test first, which makes it a
     * decision rather than drift.
     */
    @Test
    fun `the catalogue pins five in a row to the first rung of the series group`() {
        val definition = AchievementCatalog.definitions.single { it.id == "five_in_a_row" }

        assertEquals("series", definition.group)
        assertEquals(1, definition.level)
        assertEquals(AchievementMetric.LONGEST_SERIES_RUN, definition.metric)
        assertEquals(5L, definition.threshold)
        assertFalse("«П'ять поспіль» видима — не прихована", definition.hidden)
    }

    /**
     * The boundary the spec fixes: «5 книг однієї серії підряд». Four
     * consecutive completions must not open it, and the snapshot must say four —
     * an off-by-one would hand the award a book early.
     */
    @Test
    fun `four consecutive completions of one series do not open the award`() = withDatabase { database ->
        val books = libraryBooks(database, 4)
        books.forEachIndexed { index, book ->
            series(database, book, "Відьмак")
            completed(database, book, base + index)
        }

        assertEquals("пробіг мусить бути чотири", 4L, snapshot(database).longestSeriesRun)
        assertFalse(
            "чотирьох завершень замало для «П'ять поспіль»",
            "five_in_a_row" in earned(database)
        )
    }

    @Test
    fun `five consecutive completions of one series open the award`() = withDatabase { database ->
        val books = libraryBooks(database, 5)
        books.forEachIndexed { index, book ->
            series(database, book, "Відьмак")
            completed(database, book, base + index)
        }

        assertEquals("пробіг мусить бути п'ять", 5L, snapshot(database).longestSeriesRun)
        assertTrue(
            "п'ять завершень однієї серії мусять відкрити «П'ять поспіль»",
            "five_in_a_row" in earned(database)
        )
    }

    /**
     * A run is CONSECUTIVE. Three volumes of one series, a foreign book, then
     * two more of the first series: the longest unbroken sequence is three, and
     * three is not five — a total-count bug would report five here.
     */
    @Test
    fun `a foreign series inside the sequence breaks the run`() = withDatabase { database ->
        val books = libraryBooks(database, 6)
        listOf("Відьмак", "Відьмак", "Відьмак", "Інша серія", "Відьмак", "Відьмак")
            .forEachIndexed { index, title ->
                series(database, books[index], title)
                completed(database, books[index], base + index)
            }

        assertEquals("найдовший неперервний пробіг — три", 3L, snapshot(database).longestSeriesRun)
        assertFalse(
            "розірвана послідовність не має відкривати нагороду",
            "five_in_a_row" in earned(database)
        )
    }

    /**
     * A nameless series is not a series. Five completions whose Works carry
     * NULL, an empty or a whitespace-only title leave the run at zero —
     * otherwise the award would open on a blank field (ADR-0014). All five
     * events are consecutive, so a bug that invented one nameless series would
     * report five.
     */
    @Test
    fun `a blank series title counts for nothing`() = withDatabase { database ->
        val books = libraryBooks(database, 5)
        listOf(null, "", "   ", null, "")
            .forEachIndexed { index, title ->
                series(database, books[index], title)
                completed(database, books[index], base + index)
            }

        assertEquals("порожня назва не робить серію", 0L, snapshot(database).longestSeriesRun)
        assertFalse(
            "нагорода не має відкриватись на порожній назві",
            "five_in_a_row" in earned(database)
        )
    }

    /**
     * A relisten is not a new tome. Four distinct volumes where one was heard
     * twice give FIVE completion events and a run of FOUR: replaying a book
     * must not buy a series.
     */
    @Test
    fun `a book finished twice does not add a second tome to the run`() = withDatabase { database ->
        val books = libraryBooks(database, 4)
        books.forEach { series(database, it, "Відьмак") }
        // b0, b1, b0 again (a relisten), b2, b3 — five events, four volumes.
        listOf(0, 1, 0, 2, 3).forEachIndexed { index, bookIndex ->
            completed(database, books[bookIndex], base + index)
        }

        assertEquals("переслух не додає тому — пробіг чотири", 4L, snapshot(database).longestSeriesRun)
        assertFalse(
            "п'ять подій із переслухом не мають відкривати нагороду",
            "five_in_a_row" in earned(database)
        )
    }

    /**
     * The series comes from the Work, and the Work comes from the Library
     * Entry. A completion of a book with no Work row (a blank-key local book)
     * names no series and counts for nothing itself; being last, it breaks
     * nothing after it either.
     */
    @Test
    fun `a completion without a Work row contributes no series`() = withDatabase { database ->
        val books = libraryBooks(database, 5)
        books.take(4).forEachIndexed { index, book ->
            series(database, book, "Відьмак")
            completed(database, book, base + index)
        }
        // The fifth book has a Library Entry and a real completion, but no Work.
        completed(database, books[4], base + 4)

        assertEquals("без рядка works серії не існує", 4L, snapshot(database).longestSeriesRun)
        assertFalse(
            "нагорода не має відкриватись на чотирьох творах",
            "five_in_a_row" in earned(database)
        )
    }

    /**
     * S1 (#1171) — a completion with NO series is a BREAK, not a skipped row.
     *
     * «Відьмак 1-3 → стороння книга → Відьмак 4-5» is six completions, but not
     * five volumes of one series «підряд»: the listener left the series for
     * another book in between. The query therefore keeps every completion
     * (LEFT joins), and `SeriesRun` resets on an unnamed one instead of letting
     * the three and the two join up into a run of five.
     */
    @Test
    fun `a completion with no series breaks the run`() = withDatabase { database ->
        val books = libraryBooks(database, 6)
        // Three volumes of «Відьмак», a foreign book with NO Work at all, then
        // two more volumes of «Відьмак» — in that order in time.
        listOf(0, 1, 2, 4, 5).forEach { index -> series(database, books[index], "Відьмак") }
        (0 until 6).forEach { index -> completed(database, books[index], base + index) }

        assertEquals(
            "завершення без серії розриває пробіг — лишається три",
            3L, snapshot(database).longestSeriesRun
        )
        assertFalse(
            "нагорода не має відкриватись через розрив безсерійною книгою",
            "five_in_a_row" in earned(database)
        )
    }

    /**
     * A trailing space is a typo in a claim, not another series: «Відьмак»,
     * «Відьмак » and « Відьмак» are one run. The CASE is deliberately not
     * folded — that would be a different decision.
     */
    @Test
    fun `a trailing space in the title does not break the run`() = withDatabase { database ->
        val books = libraryBooks(database, 5)
        listOf("Відьмак", "Відьмак ", "Відьмак", " Відьмак", "Відьмак")
            .forEachIndexed { index, title ->
                series(database, books[index], title)
                completed(database, books[index], base + index)
            }

        assertEquals("пробіл у назві — та сама серія", 5L, snapshot(database).longestSeriesRun)
        assertTrue(
            "«П'ять поспіль» мусить відкритись на одній серії з пробілом у назві",
            "five_in_a_row" in earned(database)
        )
    }

    /**
     * «Послідовних за часом» is the rule, so the query hands the rows back in
     * time order even when the events were written in another order, and every
     * row carries the series it will be grouped by.
     */
    @Test
    fun `the query returns completions in time order with their series`() = withDatabase { database ->
        val books = libraryBooks(database, 3)
        books.forEach { series(database, it, "Відьмак") }
        // Written newest first on purpose.
        listOf(2, 0, 1).forEach { index -> completed(database, books[index], base + index) }

        val rows = database.achievementDao().observeSeriesCompletions().first()

        assertEquals(
            "рядки мусять іти за часом, а не за порядком вставки",
            listOf(base, base + 1, base + 2),
            rows.map { it.timestamp }
        )
        assertTrue("кожен рядок несе назву серії", rows.all { it.seriesTitle == "Відьмак" })
    }
}
