package com.slukhayka.audiobooks.data.achievements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.source.SourceRegistry
import java.time.LocalDate
import java.time.ZoneId
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
 * The one id the injected reader knows. Deliberately NOT a registered source:
 * the fifteen real sources carry no date and may not be given an invented one
 * (#1175), so the dated branch is pinned through the reader instead of through
 * `sources.json`.
 */
private const val DATED_SOURCE = "newcomer"

/**
 * #1175 (US42) — «Нова хвиля» (spec story 42): a book from a source that had
 * just appeared.
 *
 * The window runs FROM the source's recorded appearance date and joins two
 * recorded facts — `SourceFacts.appearedOn` in the registry (ADR-0038) and
 * `sources.addedAt`, the instant this book's row arrived. Nothing here reads
 * "now", so a book that qualified yesterday qualifies tomorrow, and the whole
 * arithmetic is pinned at its edges: the appearance day, 29, 30 and 31 days.
 *
 * Everything runs against REAL rows in an in-memory Room, because the honesty
 * of this slice lives in the wiring: the arrival row, the injected registry
 * reader and the catalogue entry. A fake snapshot would prove none of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NewWaveAwardTest {

    private val kyiv = ZoneId.of("Europe/Kyiv")

    /** A fixed appearance date: no assertion here depends on the day it runs. */
    private val appeared = LocalDate.of(2026, 10, 1)

    /** The injected registry reader: one DATED id, and unknown for everything else. */
    private val dated: (String) -> LocalDate? = { id -> appeared.takeIf { id == DATED_SOURCE } }

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
     * One library row that arrived through a source [offsetDays] after that
     * source appeared, dated by the listener's own calendar.
     *
     * [addedAt] is only passed where the instant must NOT be derived from the
     * fixed appearance date: the undated case needs the row to sit at "now", so
     * that a `LocalDate.now()` fallback would land inside the window and open
     * the award — exactly the defect that case exists to catch.
     */
    private suspend fun arrived(
        database: AudiobookDatabase,
        bookId: String,
        offsetDays: Long,
        sourceType: String = DATED_SOURCE,
        rowId: String = "row-$bookId-$sourceType",
        addedAt: Long = appeared.plusDays(offsetDays).atStartOfDay(kyiv).toInstant().toEpochMilli()
    ) = database.audiobookDao().insertSources(
        listOf(
            SourceEntity(
                id = rowId, bookId = bookId, type = sourceType, url = "https://example.test/$rowId",
                addedAt = addedAt
            )
        )
    )

    /** The production reader by default: the Source Registry is the date. */
    private suspend fun snapshot(
        database: AudiobookDatabase,
        appearedOnOf: (String) -> LocalDate? = SourceRegistry::appearedOn
    ): AchievementProgress = RoomAchievementProgressSource(
        database.achievementDao(), RoomAchievementStore(database.achievementDao()), emptySet(),
        appearedOnOf = appearedOnOf, zoneId = kyiv, abandonedBookIds = flowOf(emptySet())
    ).observe().first()

    private suspend fun earned(
        database: AudiobookDatabase,
        appearedOnOf: (String) -> LocalDate? = SourceRegistry::appearedOn
    ): List<String> = AchievementEvaluator.evaluate(snapshot(database, appearedOnOf), emptySet()).map { it.id }

    /**
     * The catalogue entry is pinned as DATA, and so is the owner's decision
     * that the award stays VISIBLE in «Попереду»: the registered sources are
     * undated on purpose, so it opens with the first dated one rather than
     * hiding until then.
     */
    @Test fun `the catalogue pins the new wave to a visible first book`() {
        val definition = AchievementCatalog.definitions.single { it.id == "new_wave" }

        assertEquals("doors", definition.group)
        assertEquals(3, definition.level)
        assertEquals(AchievementMetric.NEW_WAVE_BOOKS, definition.metric)
        assertEquals(1L, definition.threshold)
        assertFalse("«Нова хвиля» видима — не прихована", definition.hidden)
        assertTrue(
            "нагорода лишається в «Попереду», доки немає датованого джерела",
            AchievementBoard.of(earnedIds = emptySet()).upcoming.any { it.id == "new_wave" }
        )
    }

    /**
     * Every edge of the window, in one table: a book arriving the day before the
     * source appeared is a contradictory pair rather than a very early book, the
     * appearance day itself counts, twenty-nine and thirty days are inside, and
     * thirty-one is already outside.
     *
     * The number THIRTY is pinned twice: as the constant itself and as literals
     * in the table below. Computing the expectation from `WINDOW_DAYS` alone
     * would let a changed constant move the answer with it, and the boundary
     * the ticket names (29 / 30 / 31) would stop being a boundary.
     */
    @Test fun `the window runs from the appearance day and closes after thirty days`() {
        assertEquals("AC #1175 називає саме тридцять днів", 30L, NewWave.WINDOW_DAYS)

        val cases = listOf(
            "a book from before the source appeared" to -1L,
            "the appearance day itself" to 0L,
            "twenty-nine days later" to 29L,
            "the last day inside the window" to 30L,
            "one day past the window" to 31L
        )
        for ((name, offset) in cases) {
            withDatabase { database ->
                arrived(database, "book", offset)
                val expected = if (offset in 0L..30L) 1L else 0L

                assertEquals(name, expected, snapshot(database, dated).newWaveBooks)
                assertEquals(name, expected == 1L, "new_wave" in earned(database, dated))
            }
        }
    }

    /**
     * An UNDATED source never gives the award — not as "today", not as "any
     * arrival counts". The production reader is used here, and every registered
     * source is undated by decision: the field stays empty until a source is
     * added after that decision, and the first real date must bring its own pin
     * to this test.
     *
     * The row arrives NOW on purpose. With an old arrival a `today` fallback
     * would compute a negative distance and stay silent, so the two honest
     * zeros would prove nothing; at "now" the fallback lands exactly on day
     * zero and the award would open.
     */
    @Test fun `an undated source never gives the award`() = withDatabase { database ->
        assertTrue(
            "наявні 15 джерел навмисно недатовані — перше справжнє датоване джерело додає пін сюди (#1175)",
            SourceRegistry.entries.none { it.appearedOn != null }
        )
        arrived(
            database, "book", offsetDays = 0, sourceType = "4read", rowId = "undated-row",
            addedAt = System.currentTimeMillis()
        )

        assertEquals("недатоване джерело не дає жодної книги", 0L, snapshot(database).newWaveBooks)
        assertFalse("«Нова хвиля» не має відкриватись від недатованого джерела", "new_wave" in earned(database))

        // A reader that does not know the id answers null, which is the same
        // unknown, and it still gives nothing.
        assertEquals(0L, snapshot(database) { null }.newWaveBooks)
    }

    /**
     * Two doors of ONE book are one book: the award says the listener got a book
     * from a new source, and getting the same book twice does not make the wave
     * twice as new.
     */
    @Test fun `two rows of one book are one book`() = withDatabase { database ->
        arrived(database, "book", offsetDays = 5, rowId = "row-a")
        arrived(database, "book", offsetDays = 6, rowId = "row-b")

        assertEquals("та сама книга з двох дверей — це одна книга", 1L, snapshot(database, dated).newWaveBooks)
    }
}
