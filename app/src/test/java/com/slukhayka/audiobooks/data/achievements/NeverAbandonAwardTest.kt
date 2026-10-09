package com.slukhayka.audiobooks.data.achievements

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.PlaybackEventEntity
import com.slukhayka.audiobooks.data.db.PlaybackEventKind
import com.slukhayka.audiobooks.data.db.ReadthroughMapping
import com.slukhayka.audiobooks.data.entries.AbandonUndo
import com.slukhayka.audiobooks.data.entries.AbandonedBooks
import com.slukhayka.audiobooks.data.entries.ReadingFormat
import com.slukhayka.audiobooks.data.entries.ReadingState
import com.slukhayka.audiobooks.data.entries.ReadthroughPolicy
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1174 (друга смуга, US28) — «Не кидаю»: ten FINISHED books and not one
 * «покинуто» mark standing.
 *
 * The metric asks ONE question with one number: the completed count itself
 * while no mark exists, and 0 the moment one does. That reading is the owner's,
 * and it is what makes the award reachable AGAIN after a return — a listener
 * who comes back and clears the abandoned passes opens the SAME award, not a
 * second one.
 *
 * Every case writes REAL rows into an in-memory Room, because both halves of
 * the answer are SQL: the completed count (a `COMPLETED` event per book, the
 * very fact the book ladder reads) and the marks (`readthroughs` AUDIO passes in
 * state ABANDONED, the same rows the library badge follows). A hand-built
 * snapshot would prove neither.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NeverAbandonAwardTest {

    private lateinit var database: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    /**
     * The mark owner itself, so the snapshot reads the marks through the SAME
     * flow production wires — not a hand-written set that could disagree with
     * what the door writes.
     */
    private val abandoned by lazy { abandonedBooks() }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AudiobookDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.audiobookDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `nine completed books stay one short of the threshold`() = runBlocking {
        repeat(9) { complete("book-$it") }

        val snapshot = snapshot()

        assertEquals("рахуємо саме завершення, а не полицю", 9L, snapshot.completedBooks)
        assertEquals(0L, snapshot.abandonedBooks)
        assertFalse(
            "«Не кидаю» не має відкриватись на порозі − 1",
            AWARD in earned(snapshot)
        )
    }

    @Test
    fun `ten completed books with no mark open it`() = runBlocking {
        repeat(10) { complete("book-$it") }

        val snapshot = snapshot()

        assertEquals(10L, snapshot.completedBooks)
        assertEquals(
            "метрика — той самий лік завершених книг, поки позначок немає",
            10L,
            AchievementMetric.COMPLETED_BOOKS_WITHOUT_ABANDON.value(snapshot)
        )
        assertTrue("«Не кидаю» мусить відкритись на десяти книгах", AWARD in earned(snapshot))
    }

    /**
     * The one case the award exists for: the same ten finished books, and ONE
     * position the listener said they are not coming back to. The completed
     * count is not the question any more — the mark makes the metric zero.
     */
    @Test
    fun `one abandoned book makes the metric zero and keeps the award shut`() = runBlocking {
        repeat(10) { complete("book-$it") }
        markAbandoned("book-abandoned")

        val snapshot = snapshot()

        assertEquals("позначка одна", 1L, snapshot.abandonedBooks)
        assertEquals("завершених книг усе ще десять", 10L, snapshot.completedBooks)
        assertEquals(
            "одна покинута позиція обнуляє метрику",
            0L,
            AchievementMetric.COMPLETED_BOOKS_WITHOUT_ABANDON.value(snapshot)
        )
        assertFalse("одна покинута — нагороди нема", AWARD in earned(snapshot))
    }

    /**
     * The owner's reading, pinned: «якщо людина повернулася й дочистила всі
     * покинуті позиції, нагорода відкривається». The clear runs through the
     * REAL completion door, so this is the whole story — the same completed
     * count counts again once the last mark is gone.
     */
    @Test
    fun `clearing the last mark opens the same award`() = runBlocking {
        repeat(10) { complete("book-$it") }
        markAbandoned("book-abandoned")
        assertFalse("спершу нагорода закрита", AWARD in earned(snapshot()))

        abandoned.finish("book-abandoned")

        val snapshot = snapshot()
        assertEquals("позначок більше немає", 0L, snapshot.abandonedBooks)
        assertTrue(
            "дочистив покинуту позицію — і той самий лік книг відкриває нагороду",
            AWARD in earned(snapshot)
        )
    }

    /**
     * A second mark after the award was already earned does not take it back:
     * the store hands an id out once, and the metric going to zero can only
     * keep an award from OPENING.
     */
    @Test
    fun `an earned award is not taken back by a later mark`() = runBlocking {
        repeat(10) { complete("book-$it") }
        val store = RoomAchievementStore(database.achievementDao())
        val earned = store.award(AchievementEvaluator.evaluate(snapshot(), emptySet()), earnedAt = 1L)
        assertTrue("нагороду видано", earned.any { it.id == AWARD })

        markAbandoned("book-abandoned")

        val again = AchievementEvaluator.evaluate(snapshot(), store.earned().map { it.id }.toSet())
        assertFalse("здобуте не переоцінюється", again.any { it.id == AWARD })
        assertTrue("і лишається в сховищі", store.earned().any { it.id == AWARD })
    }

    /** The catalogue slot: the ticket's threshold, on the abandon's own metric. */
    @Test
    fun `the award sits at the ticket threshold on the abandon metric`() {
        val definition = AchievementCatalog.definitions.single { it.id == AWARD }

        assertEquals(AchievementMetric.COMPLETED_BOOKS_WITHOUT_ABANDON, definition.metric)
        assertEquals("поріг тікета — десять книг", 10L, definition.threshold)
        assertFalse("нагорода видима — вона не з прихованих", definition.hidden)
    }

    private fun earned(snapshot: AchievementProgress): List<String> =
        AchievementEvaluator.evaluate(snapshot, emptySet()).map { it.id }

    private suspend fun snapshot(): AchievementProgress = RoomAchievementProgressSource(
        dao = database.achievementDao(),
        store = RoomAchievementStore(database.achievementDao()),
        registeredSourceIds = emptySet(),
        abandonedBookIds = abandoned.observeAbandonedBookIds()
    ).observe().first()

    /** One book really finished: the event the book ladder and this award both read. */
    private suspend fun complete(bookId: String, at: Long = BASE + bookId.hashCode()) {
        dao.insertAudiobooks(listOf(TestDataFactory.dataBooks().first().copy(id = bookId, title = "Книга $bookId")))
        dao.insertPlaybackEvent(
            PlaybackEventEntity(bookId = bookId, kind = PlaybackEventKind.COMPLETED, timestamp = at)
        )
    }

    /** One live «покинуто» mark, written the shape the abandon door writes. */
    private suspend fun markAbandoned(bookId: String) {
        val pass = ReadthroughPolicy.start(
            id = AbandonedBooks.readthroughId(bookId),
            libraryEntryId = bookId,
            workId = "work-$bookId",
            format = ReadingFormat.AUDIO,
            startedAt = 1L,
            editionId = "edition-$bookId"
        )!!.copy(state = ReadingState.ABANDONED)
        with(ReadthroughMapping) { dao.upsertReadthrough(pass.toEntity()) }
    }

    private fun abandonedBooks() = AbandonedBooks(
        dao = dao,
        listeningState = ListeningStateStore(dao),
        // The note is not what this test is about, and nothing here cancels a
        // mark: the completion takes it away on its own.
        undo = object : AbandonUndo {
            override fun remember(bookId: String, before: AbandonUndo.BeforeMark) = Unit
            override fun recall(bookId: String): AbandonUndo.BeforeMark? = null
            override fun forget(bookId: String) = Unit
        },
        now = { BASE }
    )

    private companion object {
        const val AWARD = "never_abandon_10"
        const val BASE = 1_700_000_000_000L
    }
}
