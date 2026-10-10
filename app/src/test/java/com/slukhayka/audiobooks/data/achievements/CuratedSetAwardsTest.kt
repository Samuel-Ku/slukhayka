package com.slukhayka.audiobooks.data.achievements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.collections.CollectionAssets
import com.slukhayka.audiobooks.data.collections.CollectionEntry
import com.slukhayka.audiobooks.data.collections.CollectionList
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.PlaybackEventEntity
import com.slukhayka.audiobooks.data.db.PlaybackEventKind
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.universe.UniverseAssets
import com.slukhayka.audiobooks.data.universe.UniverseList
import com.slukhayka.audiobooks.data.universe.UniverseSeries
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
 * #701 (US37, US38) — «Один всесвіт» і «Колекціонер».
 *
 * Both awards need the curated lists, and those are ASSETS: the production
 * wiring reads them in `App.kt` (`CollectionAssets.load` / `UniverseAssets.load`)
 * and hands them to the snapshot source as data. The tests here inject their own
 * two-entry list and one-series universe, so nothing is invented in the shipped
 * assets — and the last test pins exactly that promise.
 *
 * Everything else runs against REAL rows in an in-memory Room: what counts is
 * the `library_entries.origin` (own vs catalogue mirror), the Work's series
 * claim and a real end-of-book event. A fake snapshot would prove none of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CuratedSetAwardsTest {

    private val base = 1_700_000_000_000L

    /** A curated world that exists only here — never in the shipped universes. */
    private val world = UniverseList(
        id = "test-world",
        name = "Тестовий всесвіт",
        series = listOf(
            // The URL is the PRIMARY key of the universe matcher (ADR-0038):
            // the series-page claim wins over any spelling of the title.
            UniverseSeries("Відьмак", urls = listOf("https://4read.org/xfsearch/cikl/witcher/")),
            UniverseSeries("Сезон гроз")
        )
    )

    /** A curated list that exists only here — never in the shipped collections. */
    private val list = CollectionList(
        id = "test-list",
        name = "Тестовий список",
        entries = listOf(
            CollectionEntry("Автор Один", "Книга Один"),
            CollectionEntry("Автор Два", "Книга Два")
        )
    )

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

    private suspend fun ownBook(
        database: AudiobookDatabase,
        bookId: String,
        title: String = "Книга $bookId",
        author: String = "Автор",
        seriesTitle: String? = null,
        seriesUrl: String? = null,
        completedAt: Long? = null,
        origin: String = "EXPLICIT_SAVE",
        workId: String = bookId
    ) {
        val dao = database.audiobookDao()
        val row = TestDataFactory.dataBooks().first().copy(id = bookId, title = title, author = author)
        dao.insertAudiobooks(listOf(row))
        dao.upsertLibraryEntry(bookId, workId, false, 1L, 0f)
        dao.updateLibraryEntryOrigin(bookId, origin)
        dao.upsertWork(
            WorkEntity(
                id = workId, mergeKey = "key-$workId", title = title, author = author,
                seriesTitle = seriesTitle, seriesUrl = seriesUrl
            )
        )
        if (completedAt != null) {
            dao.insertPlaybackEvent(
                PlaybackEventEntity(bookId = bookId, kind = PlaybackEventKind.COMPLETED, timestamp = completedAt)
            )
        }
    }

    private suspend fun snapshot(
        database: AudiobookDatabase,
        collections: List<CollectionList> = emptyList(),
        universes: List<UniverseList> = emptyList()
    ): AchievementProgress = RoomAchievementProgressSource(
        database.achievementDao(), RoomAchievementStore(database.achievementDao()), emptySet(),
        abandonedBookIds = flowOf(emptySet()),
        curatedCollections = collections,
        curatedUniverses = universes
    ).observe().first()

    private suspend fun earned(
        database: AudiobookDatabase,
        collections: List<CollectionList> = emptyList(),
        universes: List<UniverseList> = emptyList()
    ): List<String> = AchievementEvaluator.evaluate(snapshot(database, collections, universes), emptySet())
        .map { it.id }

    /** The two catalogue entries as DATA: group, rung, metric and threshold. */
    @Test
    fun `the catalogue pins the universe at three books and the collection at one`() {
        val universe = AchievementCatalog.definitions.single { it.id == "one_universe" }
        assertEquals("series", universe.group)
        assertEquals(5, universe.level)
        assertEquals(AchievementMetric.UNIVERSE_BOOKS, universe.metric)
        assertEquals(3L, universe.threshold)
        assertFalse("«Один всесвіт» видима", universe.hidden)

        val collector = AchievementCatalog.definitions.single { it.id == "collector" }
        assertEquals("series", collector.group)
        assertEquals(6, collector.level)
        assertEquals(AchievementMetric.PASSED_COLLECTIONS, collector.metric)
        assertEquals(1L, collector.threshold)
    }

    /**
     * The boundary of «Один всесвіт»: two books of one world are not three, and
     * the third opens it. The same two books must NOT open anything on their
     * own, so the boundary is pinned from both sides.
     */
    @Test
    fun `one universe opens at exactly three books`() = withDatabase { database ->
        ownBook(database, "tome-1", seriesTitle = "Відьмак")
        ownBook(database, "tome-2", seriesTitle = "Відьмак")

        assertEquals(2L, snapshot(database, universes = listOf(world)).universeBooks)
        assertFalse("двох книг всесвіту замало", "one_universe" in earned(database, universes = listOf(world)))

        ownBook(database, "tome-3", seriesTitle = "Відьмак")
        assertEquals(3L, snapshot(database, universes = listOf(world)).universeBooks)
        assertTrue("третя книга відкриває «Один всесвіт»", "one_universe" in earned(database, universes = listOf(world)))
    }

    /**
     * A book outside every curated series contributes NOTHING — a title that
     * merely looks similar is not a claim (ADR-0014) — and the award stays shut
     * even when three such books are owned.
     */
    @Test
    fun `books of an uncurated series never count`() = withDatabase { database ->
        repeat(3) { index ->
            ownBook(database, "other-$index", seriesTitle = "Сторонній цикл $index")
        }
        ownBook(database, "curated-1", seriesTitle = "Відьмак")

        assertEquals("лише одна книга належить всесвіту", 1L, snapshot(database, universes = listOf(world)).universeBooks)
        assertFalse("«Один всесвіт» не має відкриватись", "one_universe" in earned(database, universes = listOf(world)))
    }

    /**
     * The unit is the WORK, not the library row: two renditions of one book are
     * one book of the universe, and counting rows would let a second narrator
     * buy the award.
     */
    @Test
    fun `two renditions of one work are one book of the universe`() = withDatabase { database ->
        ownBook(database, "rendition-a", seriesTitle = "Відьмак", workId = "witcher-1")
        ownBook(database, "rendition-b", seriesTitle = "Відьмак", workId = "witcher-1")
        ownBook(database, "rendition-c", seriesTitle = "Відьмак", workId = "witcher-1")

        assertEquals("три начитки одного твору — одна книга", 1L, snapshot(database, universes = listOf(world)).universeBooks)
    }

    /**
     * The universe matcher is URL-FIRST (ADR-0038 keeps the series page URL a
     * real claim), so a book whose series page is the curated one belongs to the
     * world even when the title is spelled differently.
     */
    @Test
    fun `a series URL resolves a differently spelled title`() = withDatabase { database ->
        ownBook(database, "url-tome", seriesTitle = "Саґа про відьмака", seriesUrl = "https://4read.org/xfsearch/cikl/witcher/")

        assertEquals(1L, snapshot(database, universes = listOf(world)).universeBooks)
    }

    /**
     * Completion is deliberately NOT required for «Один всесвіт»: the owner's
     * reading (#701) is three BOOKS of one world, not three finished ones. The
     * same three books must not open «У циклі» next door either, so the two
     * readings stay apart.
     */
    @Test
    fun `the universe counts books the listener owns, finished or not`() = withDatabase { database ->
        repeat(3) { index -> ownBook(database, "tome-$index", seriesTitle = "Відьмак") }

        val earned = earned(database, universes = listOf(world))
        assertTrue("три книги всесвіту — нагорода", "one_universe" in earned)
        assertFalse("жодну з них не завершено — «У циклі» закрита", "in_cycle" in earned)
    }

    /**
     * No curated list means no answer: the empty default of the snapshot source
     * must leave the award shut instead of reading "nobody told me" as "one
     * universe of zero books" (ADR-0014). This is the wiring production would
     * have if the assets failed to load.
     */
    @Test
    fun `without curated lists the universe award stays shut`() = withDatabase { database ->
        repeat(3) { index -> ownBook(database, "tome-$index", seriesTitle = "Відьмак") }

        assertEquals(0L, snapshot(database).universeBooks)
        assertFalse("без курованих всесвітів нагороди немає", "one_universe" in earned(database))
    }

    /**
     * «Колекціонер» — every entry of the curated list covered by an own FINISHED
     * book. The first two cases pin the two halves of the rule: a missing entry
     * and an unfinished one both keep the whole collection open.
     */
    @Test
    fun `a collection opens only when every entry is finished`() = withDatabase { database ->
        ownBook(database, "entry-1", title = "Книга Один", author = "Автор Один", completedAt = base)
        assertEquals(
            "одна з двох позицій — колекція не пройдена",
            0L, snapshot(database, collections = listOf(list)).passedCollections
        )

        ownBook(database, "entry-2", title = "Книга Два", author = "Автор Два")
        assertEquals(
            "друга позиція є, але не завершена",
            0L, snapshot(database, collections = listOf(list)).passedCollections
        )
        assertFalse("«Колекціонер» не має відкриватись", "collector" in earned(database, collections = listOf(list)))

        ownBook(database, "entry-2", title = "Книга Два", author = "Автор Два", completedAt = base + 1)
        assertEquals(1L, snapshot(database, collections = listOf(list)).passedCollections)
        assertTrue("обидві позиції завершено — колекція пройдена", "collector" in earned(database, collections = listOf(list)))
    }

    /**
     * The reading that would be dishonest, pinned as a negative: "the entries I
     * happen to own" is NOT the collection. One finished book of a two-entry
     * list must leave the award shut, because the curated asset says what the
     * whole list is.
     */
    @Test
    fun `finishing the one owned entry is not passing the collection`() = withDatabase { database ->
        ownBook(database, "only-owned", title = "Книга Один", author = "Автор Один", completedAt = base)

        assertEquals(0L, snapshot(database, collections = listOf(list)).passedCollections)
        assertFalse("одна власна позиція не проходить колекцію", "collector" in earned(database, collections = listOf(list)))
    }

    /**
     * Own records only (ADR-0060): a catalogue mirror that happens to match an
     * entry is not a listener's choice, so it covers nothing — even finished.
     */
    @Test
    fun `a catalogue mirror covers no collection entry`() = withDatabase { database ->
        ownBook(database, "mirror-1", title = "Книга Один", author = "Автор Один",
            completedAt = base, origin = "AUTO_SEED")
        ownBook(database, "mirror-2", title = "Книга Два", author = "Автор Два",
            completedAt = base + 1, origin = "CATALOG_SYNC")

        assertEquals(0L, snapshot(database, collections = listOf(list)).passedCollections)
        assertFalse("дзеркала каталогу не проходять колекцію", "collector" in earned(database, collections = listOf(list)))
    }

    /**
     * The entries are matched by the collections module's OWN rule (author
     * agreement, then the title) — including its diacritic fold and the
     * author-only fallback. A second matcher here would be the drift this test
     * exists to catch.
     */
    @Test
    fun `the collection matcher folds diacritics and honours an author-only entry`() = withDatabase { database ->
        val latin = CollectionList(
            id = "latin",
            name = "Латинський список",
            entries = listOf(CollectionEntry("García Márquez", "Cien años de soledad"))
        )
        val authorOnly = CollectionList(
            id = "author-only",
            name = "Авторський список",
            entries = listOf(CollectionEntry("Автор Один"))
        )
        ownBook(database, "latin-book", title = "Cien anos de soledad", author = "Garcia Marquez", completedAt = base)
        ownBook(database, "author-book", title = "Будь-що", author = "Автор Один", completedAt = base)

        assertEquals(
            "діакритика й авторська позиція мають матчитись як у CollectionMatcher",
            2L, snapshot(database, collections = listOf(latin, authorOnly)).passedCollections
        )
    }

    /**
     * The fixtures above live in the TEST, not in the shipped assets: the
     * curated lists are production data, and inventing an entry there to make a
     * test pass would change what every listener sees.
     */
    @Test
    fun `the curated fixtures are not part of the shipped assets`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertTrue(
            "тестовий список не має бути серед колекцій застосунку",
            CollectionAssets.load(context).none { it.id == list.id }
        )
        assertTrue(
            "тестовий всесвіт не має бути серед всесвітів застосунку",
            UniverseAssets.load(context).none { it.id == world.id }
        )
    }
}
