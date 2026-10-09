package com.slukhayka.audiobooks.data.achievements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.SourceEntity
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
 * #701 (US40, US44, US45, US46) — «Усі двері», «Резолвер», «Відновлювач» і
 * «Той самий голос».
 *
 * Three different readings share one file because they share one question: what
 * may an award claim about a MECHANISM? «Усі двері» compares the doors really
 * used against the registry's own list (dynamic, scam excluded), the two
 * mechanism awards read recorded facts, and «Той самий голос» reads the
 * narration the database already holds — no write at all.
 *
 * Everything runs against REAL rows in an in-memory Room, because the honesty
 * of these slices lives in the wiring: the `sources.type` rows, the shared
 * `editionId`, the injected registry and the fact table. A fake snapshot would
 * prove none of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DoorsAndMechanismAwardsTest {

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

    /** One library row that came through [sourceType] — the door really used. */
    private suspend fun door(
        database: AudiobookDatabase,
        sourceType: String,
        rowId: String = "row-$sourceType",
        editionId: String? = null,
        bookId: String = "book"
    ) {
        val dao = database.audiobookDao()
        if (dao.getAudiobookById(bookId) == null) {
            val row = TestDataFactory.dataBooks().first().copy(id = bookId)
            dao.insertAudiobooks(listOf(row))
            dao.upsertLibraryEntry(bookId, bookId, false, 1L, 0f)
        }
        dao.insertSources(
            listOf(
                SourceEntity(
                    id = rowId, bookId = bookId, editionId = editionId, type = sourceType,
                    url = "https://example.test/$rowId"
                )
            )
        )
    }

    private suspend fun snapshot(
        database: AudiobookDatabase,
        registered: Set<String> = emptySet(),
        isScam: (String) -> Boolean = { false }
    ): AchievementProgress = RoomAchievementProgressSource(
        database.achievementDao(), RoomAchievementStore(database.achievementDao()), registered,
        abandonedBookIds = flowOf(emptySet()),
        isScamSource = isScam
    ).observe().first()

    private suspend fun earned(
        database: AudiobookDatabase,
        registered: Set<String> = emptySet(),
        isScam: (String) -> Boolean = { false }
    ): List<String> = AchievementEvaluator.evaluate(snapshot(database, registered, isScam), emptySet())
        .map { it.id }

    /** The four catalogue entries as DATA: group, rung, metric and threshold. */
    @Test
    fun `the catalogue pins the doors rung and the three mechanism awards`() {
        val allDoors = AchievementCatalog.definitions.single { it.id == "all_doors" }
        assertEquals("doors", allDoors.group)
        assertEquals(4, allDoors.level)
        assertEquals(AchievementMetric.ALL_DOORS, allDoors.metric)
        assertEquals(1L, allDoors.threshold)
        assertFalse("«Усі двері» видимі", allDoors.hidden)

        assertEquals(
            listOf(
                Triple("deep_search", 1, AchievementMetric.SEARCH_IMPORTS),
                Triple("resolver", 2, AchievementMetric.CROSS_RESOLVES),
                Triple("recoverer", 3, AchievementMetric.SOURCE_RECOVERIES),
                Triple("same_voice", 4, AchievementMetric.SHARED_NARRATIONS)
            ),
            AchievementCatalog.definitions.filter { it.group == "mechanisms" }
                .map { Triple(it.id, it.level, it.metric) }
        )
        assertTrue(
            "усі три механізмні нагороди мають поріг 1",
            AchievementCatalog.definitions.filter { it.group == "mechanisms" }.all { it.threshold == 1L }
        )
    }

    /**
     * The boundary of «Усі двері»: one door short is not all of them, and the
     * last door is what opens the award.
     */
    @Test
    fun `all doors opens only when every registered door was used`() = withDatabase { database ->
        door(database, "sluhay")
        val one = setOf("sluhay", "soundbooks")

        assertEquals(0L, snapshot(database, one).allDoorsReached)
        assertFalse("однієї дверини замало", "all_doors" in earned(database, one))

        door(database, "soundbooks")
        assertEquals(1L, snapshot(database, one).allDoorsReached)
        assertTrue("усі двері пройдено", "all_doors" in earned(database, one))
    }

    /**
     * The acceptance criterion of #701: the award follows the REGISTRY, not a
     * hand-written list. Adding a source raises the bar the moment the registry
     * knows it — and the award already earned is never taken back (the evaluator
     * skips ids the store holds, so a later source cannot revoke it).
     */
    @Test
    fun `a source added to the registry makes the award harder`() = withDatabase { database ->
        door(database, "sluhay")

        assertTrue("одне джерело в реєстрі — одну дверину й пройдено", "all_doors" in earned(database, setOf("sluhay")))

        val harder = earned(database, setOf("sluhay", "lihtar"))
        assertFalse("друге джерело в реєстрі робить нагороду важчою", "all_doors" in harder)

        door(database, "lihtar")
        assertTrue("і друга дверина її відкриває", "all_doors" in earned(database, setOf("sluhay", "lihtar")))

        // Already earned is not taken back: the same registry, but the award is
        // in the earned set, so nothing is handed out again.
        assertTrue(
            "уже здобуте не відбирається",
            AchievementEvaluator.evaluate(
                snapshot(database, setOf("sluhay", "lihtar", "chitaka")), setOf("all_doors")
            ).none { it.id == "all_doors" }
        )
    }

    /**
     * A scam source is NOT a door: its audio is not the book (ADR-0038), so it
     * is never required. 4read is the real case, and the award must open without
     * it — requiring it would make «Усі двері» unreachable.
     */
    @Test
    fun `a scam door is never required`() = withDatabase { database ->
        door(database, "sluhay")
        val registry = setOf("sluhay", "4read")
        val scam: (String) -> Boolean = { it == "4read" }

        assertEquals("scam-джерело не вимагається", 1L, snapshot(database, registry, scam).allDoorsReached)
        assertTrue("нагорода відкрита без 4read", "all_doors" in earned(database, registry, scam))

        // And the same registry WITHOUT the scam reader is short by one door:
        // the mutation "count the scam source too" turns this into a red test.
        assertEquals(0L, snapshot(database, registry).allDoorsReached)
    }

    /**
     * A scam ROW in the library is not a required door either: it is neither
     * counted as one of the doors the award asks about nor able to open it by
     * itself.
     */
    @Test
    fun `a used scam row neither counts nor opens the award`() = withDatabase { database ->
        door(database, "4read", rowId = "scam-row")
        val scam: (String) -> Boolean = { it == "4read" }

        assertEquals(0L, snapshot(database, setOf("sluhay"), scam).allDoorsReached)
        assertFalse("сама лише scam-дверина нагороди не дає", "all_doors" in earned(database, setOf("sluhay"), scam))
    }

    /**
     * An EMPTY registry answers 0: "nobody told me which doors exist" must not
     * read as "all of them were used", which is the answer that OPENS the award
     * (ADR-0014).
     */
    @Test
    fun `an empty registry never opens all doors`() = withDatabase { database ->
        door(database, "sluhay")
        door(database, "lihtar")

        assertEquals(0L, snapshot(database).allDoorsReached)
        assertFalse("порожній реєстр не відкриває нагороду", "all_doors" in earned(database))
    }

    /**
     * «Той самий голос»: the same narration from two DIFFERENT sources. No fact
     * is written — the shared `editionId` is already a row.
     */
    @Test
    fun `the same edition in two sources opens the award without a write`() = withDatabase { database ->
        door(database, "sluhay", rowId = "row-a", editionId = "edition-shared")
        assertEquals("одна начинка в одному джерелі — ще не збіг", 0L, snapshot(database).sharedNarrations)
        assertFalse("нагорода закрита", "same_voice" in earned(database))

        door(database, "soundbooks", rowId = "row-b", editionId = "edition-shared")
        assertEquals(1L, snapshot(database).sharedNarrations)
        assertTrue("та сама начинка з двох джерел", "same_voice" in earned(database))
    }

    /**
     * The mutation that must stay red: TWO ROWS OF ONE SOURCE are one door. A
     * re-import of the same book must not read as "the same voice from two
     * sources" — `COUNT(DISTINCT type)` is what makes the difference, and
     * counting rows would hand the award out for a duplicate row.
     */
    @Test
    fun `two rows of one source are not two sources`() = withDatabase { database ->
        door(database, "sluhay", rowId = "row-a", editionId = "edition-shared")
        door(database, "sluhay", rowId = "row-b", editionId = "edition-shared")

        assertEquals("два рядки одного джерела — одне джерело", 0L, snapshot(database).sharedNarrations)
        assertFalse("«Той самий голос» не має відкриватись", "same_voice" in earned(database))
    }

    /**
     * An UNKNOWN narration proves nothing: a blank or missing `editionId` is not
     * a rendition two sources agree on, so it can never open the award
     * (ADR-0014).
     */
    @Test
    fun `an unknown narration never counts`() = withDatabase { database ->
        door(database, "sluhay", rowId = "row-a", editionId = null)
        door(database, "soundbooks", rowId = "row-b", editionId = "")
        door(database, "lihtar", rowId = "row-c", editionId = "edition-solo")

        assertEquals("порожня начинка не рахується, а самотня — не збіг", 0L, snapshot(database).sharedNarrations)
        assertFalse("«Той самий голос» закритий", "same_voice" in earned(database))
    }

    /**
     * The two mechanism awards read REAL rows of `achievement_facts`: this walks
     * the whole path — the enum key written by the store, the key read back by
     * the fold — so a renamed or misspelled fact cannot pass as a working award.
     */
    @Test
    fun `the mechanism facts travel from the store to the award`() = withDatabase { database ->
        val store = RoomAchievementStore(database.achievementDao())
        val source = RoomAchievementProgressSource(
            database.achievementDao(), store, emptySet(), abandonedBookIds = flowOf(emptySet())
        )

        assertTrue("жодного факту — жодної механізмної нагороди",
            AchievementEvaluator.evaluate(source.observe().first(), emptySet())
                .none { it.id in setOf("resolver", "recoverer") })

        store.recordFact(AchievementFact.CROSS_RESOLVED)
        val resolved = source.observe().first()
        assertEquals(1L, resolved.crossResolves)
        assertEquals("крос-резолв відкриває лише «Резолвера»", listOf("resolver"),
            AchievementEvaluator.evaluate(resolved, emptySet()).map { it.id })

        store.recordFact(AchievementFact.SOURCE_RECOVERED)
        val recovered = source.observe().first()
        assertEquals("один факт на обидва механізми відновлення", 1L, recovered.sourceRecoveries)
        assertTrue("«Відновлювач» відкривається тим самим фактом",
            AchievementEvaluator.evaluate(recovered, emptySet()).any { it.id == "recoverer" })
    }
}
