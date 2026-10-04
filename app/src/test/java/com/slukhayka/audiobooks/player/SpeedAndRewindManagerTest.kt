package com.slukhayka.audiobooks.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Manager behavior for wayfinder #26 (per-book speed memory + global default)
 * and wayfinder #25 (smart rewind on resume + position-history undo). The wall
 * clock is injected so the rewind tiers are deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpeedAndRewindManagerTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var dao: FakeAudiobookDao
    private lateinit var listeningState: ListeningStateStore

    private val book: AudiobookEntity = TestDataFactory.dataBooks()[STREAMING_BOOK_INDEX]
    private val chapters: List<ChapterEntity> = TestDataFactory.chaptersFor(book)
    // A playable fixture is required: missing audio correctly stops before engine creation.
    private val playable = chapters.zip(TestDataFactory.tracksFor(book, "speed-fixture")) { chapter, track ->
        com.slukhayka.audiobooks.data.catalog.SourceCatalog.PlayableChapter(chapter, track)
    }

    /** Injectable wall clock; tests advance it to simulate real time passing. */
    private var clockMs: Long = 1_000_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        dao = FakeAudiobookDao(
            books = TestDataFactory.dataBooks(),
            chapters = TestDataFactory.dataChapters()
        )
        listeningState = ListeningStateStore(dao, dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun managerTest(
        settings: PlaybackSettings? = null,
        listening: ListeningStateStore = listeningState,
        reloadPlayable: (suspend (String) -> List<SourceCatalog.PlayableChapter>)? = null,
        reloadBook: (suspend (String) -> AudiobookEntity?)? = null,
        body: suspend TestScope.(AudioPlayerManager, RecordingPlayerFactory) -> Unit
    ) = runTest(dispatcher) {
        val factory = RecordingPlayerFactory()
        val manager = AudioPlayerManager(
            context,
            listening,
            // ADR-0007: the fetcher yields chapter→track pairs (chapter rows
            // carry no stream URLs); these tests only assert positions/speeds.
            reloadPlayable ?: { id -> dao.getChaptersListForBook(id).map { ch -> SourceCatalog.PlayableChapter(ch, null) } },
            bookFetcher = reloadBook,
            injectedPlayerFactory = factory,
            now = { clockMs },
            settings = settings,
            widgetSyncEnabled = false,
            ioDispatcher = dispatcher
        )
        try {
            body(manager, factory)
        } finally {
            manager.release()
        }
    }

    // ---------------------------------------------------------------------
    // Wayfinder #26: per-book speed memory and the global default
    // ---------------------------------------------------------------------

    @Test
    fun `editing chapter order retains the active audio position speed timer and seek undo`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 42L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)
        manager.setPlaybackSpeed(1.5f)
        manager.setSleepTimer(10)
        manager.seekTo(600_000L)
        val before = manager.playerState.value
        val reversed = playable.reversed().mapIndexed { index, item -> item.copy(chapter = item.chapter.copy(chapterIndex = index), track = item.track?.copy(trackIndex = index)) }
        manager.commitChapterOrder(book.id, reversed.map { it.chapter.id })
        factory.current.simulateReady(1_800_000L)
        val after = manager.playerState.value
        assertEquals(chapters[0].id, after.chapters[after.currentChapterIndex].id)
        assertEquals(before.currentPositionMs, after.currentPositionMs)
        assertEquals(before.isPlaying, after.isPlaying)
        assertEquals(before.sleepTimerMinutes, after.sleepTimerMinutes)
        assertEquals(before.playbackSpeed, after.playbackSpeed, SPEED_TOLERANCE)
        manager.undoLastSeek()
        assertEquals(42_000L, manager.playerState.value.currentPositionMs)
        assertEquals(chapters[0].id, manager.playerState.value.chapters[manager.playerState.value.currentChapterIndex].id)
    }

    @Test
    fun `a load resolved before saving uses the committed order and the same chapter`() = managerTest { manager, factory ->
        val reversed = playable.reversed().mapIndexed { index, item ->
            item.copy(chapter = item.chapter.copy(chapterIndex = index), track = item.track?.copy(trackIndex = index))
        }
        // Save completes before the outstanding source read hands its stale queue to Play.
        manager.commitChapterOrder(book.id, reversed.map { it.chapter.id })
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 42L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)
        val state = manager.playerState.value
        assertEquals(reversed.map { it.chapter.id }, state.chapters.map { it.id })
        assertEquals(chapters[0].id, state.chapters[state.currentChapterIndex].id)
        assertEquals(42_000L, state.currentPositionMs)
        assertEquals(playable[0].track!!.url, state.currentStreamUrl)
    }

    @Test
    fun `a fresh resume anchor and an older queue still start the same audio`() = managerTest { manager, factory ->
        manager.commitChapterOrder(book.id, chapters.reversed().map { it.id })
        // Progress was read after commit (display 0), while the queue was read before it.
        val resumeId = chapters.last().id
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0,
            initialChapterId = resumeId, initialPositionSeconds = 42L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)
        val state = manager.playerState.value
        assertEquals(resumeId, state.chapters[state.currentChapterIndex].id)
        assertEquals(playable.last().track!!.url, state.currentStreamUrl)
        assertEquals(42_000L, state.currentPositionMs)
    }

    @Test
    fun `a pending Play for another book reloads the repaired topology instead of old audio`() {
        val extra = playable.last().copy(
            chapter = chapters.last().copy(id = "repaired-extra", chapterIndex = chapters.size),
            track = playable.last().track!!.copy(id = "extra-track", trackIndex = chapters.size)
        )
        val repaired = (playable + extra).mapIndexed { index, pair ->
            pair.copy(track = pair.track!!.copy(url = "https://cdn.test/repaired/$index.mp3"))
        }
        val freshBook = book.copy(totalChapters = repaired.size)
        managerTest(reloadPlayable = { repaired }, reloadBook = { freshBook }) { manager, factory ->
            val other = book.copy(id = book.id + "-other")
            manager.loadAndPlayBook(other, chapters, playable = playable, autoPlay = false)
            // B's queue is captured before an intervening repair, while A remains loaded.
            val version = manager.chapterStructureVersion(book.id)
            manager.clearCommittedChapterOrder(book.id)
            manager.loadAndPlayBook(book, chapters, playable = playable, initialPositionSeconds = 42,
                autoPlay = true, expectedChapterStructureVersion = version)
            testScheduler.runCurrent()
            factory.current.simulateReady(1_800_000L)
            val state = manager.playerState.value
            assertEquals(freshBook, state.currentBook)
            assertEquals(repaired.map { it.chapter.id }, state.chapters.map { it.id })
            assertEquals("https://cdn.test/repaired/0.mp3", state.currentStreamUrl)
            assertEquals(0L, state.currentPositionMs)
            assertFalse("the later repair keeps the invalidated Play paused", state.isPlaying)
        }
    }

    @Test
    fun `a newer Play wins over an invalidated queue reload`() = managerTest(reloadPlayable = { playable }) { manager, _ ->
        val version = manager.chapterStructureVersion(book.id)
        manager.clearCommittedChapterOrder(book.id)
        manager.loadAndPlayBook(book, chapters, playable = playable, expectedChapterStructureVersion = version)
        val newer = book.copy(id = book.id + "-newer")
        manager.loadAndPlayBook(newer, chapters, playable = playable, autoPlay = false)
        testScheduler.runCurrent()
        assertEquals(newer, manager.playerState.value.currentBook)
    }

    @Test
    fun `clearing playback discards an invalidated queue reload`() = managerTest(reloadPlayable = { playable }) { manager, _ ->
        val version = manager.chapterStructureVersion(book.id)
        manager.clearCommittedChapterOrder(book.id)
        manager.loadAndPlayBook(book, chapters, playable = playable, expectedChapterStructureVersion = version)
        manager.stopAndClear()
        testScheduler.runCurrent()
        assertNull(manager.playerState.value.currentBook)
    }

    @Test
    fun `confirmed structure repair discards manual order even when chapter ids are reused`() = managerTest { manager, _ ->
        manager.commitChapterOrder(book.id, chapters.reversed().map { it.id })
        manager.clearCommittedChapterOrder(book.id)
        val extra = playable.last().copy(chapter = chapters.last().copy(id = "repaired-extra", chapterIndex = chapters.size), track = playable.last().track?.copy(id = "extra-track", trackIndex = chapters.size, url = "https://cdn.test/extra.mp3"))
        val repaired = playable + extra
        manager.loadAndPlayBook(book, repaired.map { it.chapter }, playable = repaired, autoPlay = false)
        assertEquals(repaired.map { it.chapter.id }, manager.playerState.value.chapters.map { it.id })
        assertEquals(0, manager.playerState.value.currentChapterIndex)
    }

    @Test
    fun `persisted seek undo arriving after reorder retains its stable chapter`() {
        var target: AudioPlayerManager? = null
        var crossed = false
        val delayedDao = object : com.slukhayka.audiobooks.data.db.AudiobookDao by dao {
            override suspend fun getChapterOrderRows(bookId: String): List<com.slukhayka.audiobooks.data.db.ChapterOrderRow> {
                val before = dao.getChapterOrderRows(bookId)
                if (!crossed && bookId == book.id) {
                    crossed = true
                    before.reversed().forEachIndexed { index, row -> dao.updateChapterIndex(row.chapter.id, index) }
                    dao.upsertCorrection(com.slukhayka.audiobooks.data.db.CorrectionEntity(
                        mergeKey = "chapter-order:${book.id}", kind = "FIELD", value = com.slukhayka.audiobooks.data.imports.ChapterOrder.encode(before.map { it.chapter.id })))
                    target!!.commitChapterOrder(book.id, before.reversed().map { it.chapter.id })
                }
                return before
            }
        }
        managerTest(listening = ListeningStateStore(delayedDao, dispatcher)) { manager, factory ->
            target = manager
            dao.insertPlaybackEvent(com.slukhayka.audiobooks.data.db.PlaybackEventEntity(bookId = book.id,
                kind = "SEEK", chapterIndex = 0, positionSeconds = 600L, fromPositionSeconds = 42L, timestamp = clockMs))
            manager.loadAndPlayBook(book, chapters, playable = playable, initialPositionSeconds = 600L, autoPlay = false)
            testScheduler.runCurrent()
            assertTrue(crossed)
            assertTrue(manager.playerState.value.canUndoSeek)
            manager.undoLastSeek()
            factory.current.simulateReady(1_800_000L)
            val state = manager.playerState.value
            assertEquals(chapters[0].id, state.chapters[state.currentChapterIndex].id)
            assertEquals(playable[0].track!!.url, state.currentStreamUrl)
            assertEquals(42_000L, state.currentPositionMs)
        }
    }

    @Test
    fun `load applies the book's preferred speed`() = managerTest { manager, _ ->
        val fastBook = book.copy().also { it.preferredSpeed = 1.5f }
        manager.loadAndPlayBook(fastBook, chapters, playable = playable, initialChapterIndex = 0, autoPlay = false)
        assertEquals(1.5f, manager.playerState.value.playbackSpeed, SPEED_TOLERANCE)
    }

    @Test
    fun `load uses 1x when neither book nor default has a speed`() = managerTest { manager, _ ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = false)
        assertEquals(1.0f, manager.playerState.value.playbackSpeed, SPEED_TOLERANCE)
    }

    @Test
    fun `load uses the global default when the book has no saved speed`() = managerTest(
        settings = PlaybackSettings(context)
    ) { manager, _ ->
        manager.setDefaultSpeed(1.5f)
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = false)
        assertEquals(1.5f, manager.playerState.value.playbackSpeed, SPEED_TOLERANCE)
    }

    @Test
    fun `changing speed remembers it for the book`() = managerTest { manager, _ ->
        seedListeningStateRow()
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = false)

        manager.setPlaybackSpeed(1.5f)
        testScheduler.advanceTimeBy(AudioPlayerManager.PREFERRED_SPEED_SAVE_DEBOUNCE_MS + 100L)
        testScheduler.runCurrent()

        assertEquals(
            1.5f,
            dao.getPlaybackProgressSync(book.id)?.preferredSpeed ?: 0f,
            SPEED_TOLERANCE
        )
    }

    @Test
    fun `switching books does not drop the previous book's remembered speed`() = managerTest { manager, _ ->
        val other = TestDataFactory.dataBooks()[0]
        seedListeningStateRow(book)
        seedListeningStateRow(other)
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = false)
        manager.setPlaybackSpeed(1.5f)

        val otherChapters = TestDataFactory.chaptersFor(other)
        val otherPlayable = otherChapters.zip(TestDataFactory.tracksFor(other, "speed-fixture")) { chapter, track ->
            com.slukhayka.audiobooks.data.catalog.SourceCatalog.PlayableChapter(chapter, track)
        }
        manager.loadAndPlayBook(other, otherChapters, playable = otherPlayable, initialChapterIndex = 0, autoPlay = false)
        manager.setPlaybackSpeed(2.0f)

        testScheduler.advanceTimeBy(AudioPlayerManager.PREFERRED_SPEED_SAVE_DEBOUNCE_MS + 100L)
        testScheduler.runCurrent()

        assertEquals(1.5f, dao.getPlaybackProgressSync(book.id)?.preferredSpeed ?: 0f, SPEED_TOLERANCE)
        assertEquals(2.0f, dao.getPlaybackProgressSync(other.id)?.preferredSpeed ?: 0f, SPEED_TOLERANCE)
    }

    private suspend fun seedListeningStateRow(target: AudiobookEntity = book) {
        val editionId = EditionId.forBook(target.mergeKey, target.id, target.narrator)
        dao.replaceEdition(EditionEntity(id = editionId, workId = target.id))
        dao.savePlaybackProgress(PlaybackProgressEntity(editionId = editionId, bookId = target.id))
    }

    // ---------------------------------------------------------------------
    // Wayfinder #25: smart rewind on resume
    // ---------------------------------------------------------------------

    @Test
    fun `resume after a long pause rewinds by the medium tier`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 600L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)

        manager.pause()
        clockMs += 30 * 60 * 1000L // 30-minute break
        manager.play()

        assertEquals(600_000L - SmartRewind.REWIND_MEDIUM_SECONDS * 1000L, manager.playerState.value.currentPositionMs)
    }

    @Test
    fun `explicit reload position does not inherit the previous pause rewind`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialPositionSeconds = 600L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)
        manager.pause()
        clockMs += 30 * 60 * 1000L

        manager.loadAndPlayBook(book, chapters, playable = playable, initialPositionSeconds = 42L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)
        manager.play()

        assertEquals(42_000L, manager.playerState.value.currentPositionMs)
    }

    @Test
    fun `quick play pause toggle does not rewind`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 600L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)

        manager.pause()
        clockMs += 1_000L // one-second toggle
        manager.play()

        assertEquals(600_000L, manager.playerState.value.currentPositionMs)
    }

    @Test
    fun `an overnight pause rewinds the most`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 600L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)

        manager.pause()
        clockMs += 26 * 60 * 60 * 1000L // next day
        manager.play()

        assertEquals(600_000L - SmartRewind.REWIND_LONG_SECONDS * 1000L, manager.playerState.value.currentPositionMs)
    }

    @Test
    fun `resume at the very start does not rewind below zero`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 2L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)

        manager.pause()
        clockMs += 30 * 60 * 1000L
        manager.play()

        assertTrue("position must not go negative", manager.playerState.value.currentPositionMs >= 0L)
    }

    // ---------------------------------------------------------------------
    // Wayfinder #25: position-history undo after a big seek
    // ---------------------------------------------------------------------

    @Test
    fun `a big seek becomes undoable and undo restores the position`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 60L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)

        manager.seekTo(600_000L) // a 9-minute jump

        assertTrue(manager.playerState.value.canUndoSeek)
        assertEquals(60_000L, manager.playerState.value.undoFromPositionMs)

        manager.undoLastSeek()

        assertEquals(60_000L, manager.playerState.value.currentPositionMs)
        assertFalse(manager.playerState.value.canUndoSeek)
    }

    @Test
    fun `a small seek is not undoable`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 60L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)

        manager.seekTo(90_000L) // 30-second nudge

        assertFalse(manager.playerState.value.canUndoSeek)
    }

    @Test
    fun `loading a new book clears the undo state`() = managerTest { manager, factory ->
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 60L, autoPlay = false)
        factory.current.simulateReady(1_800_000L)
        manager.seekTo(600_000L)
        assertTrue(manager.playerState.value.canUndoSeek)

        manager.loadAndPlayBook(book.copy().also { it.preferredSpeed = 1.25f }, chapters, playable = playable, initialChapterIndex = 0, autoPlay = false)

        assertFalse(manager.playerState.value.canUndoSeek)
    }

    private companion object {
        const val STREAMING_BOOK_INDEX = 1
        const val SPEED_TOLERANCE = 0.001f
    }
}
