package com.slukhayka.audiobooks.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
 * #805 — [PlaybackResume] is the shared "nothing is loaded, take the freshest
 * progress row" step behind the media session's `onPlaybackResumption` and the
 * home-screen widget's play action.
 *
 * Robolectric is required for the same reason as in [AudioPlayerManagerTest]:
 * the manager touches `Uri`, `Log` and `Build.VERSION`. The player is the
 * injected [RecordingPlayerFactory], so nothing reaches a real media pipeline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackResumeTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var dao: FakeAudiobookDao
    private lateinit var libraryEntries: LibraryEntries
    private lateinit var listeningState: ListeningStateStore

    private val books: List<AudiobookEntity> = TestDataFactory.dataBooks()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        dao = FakeAudiobookDao(
            books = TestDataFactory.dataBooks(),
            chapters = TestDataFactory.dataChapters()
        )
        listeningState = ListeningStateStore(dao, dispatcher)
        libraryEntries = LibraryEntries(dao, emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `resumes the freshest progress row when nothing is loaded`() = resumeTest { manager, factory ->
        val older = books[0]
        val fresher = books[1]
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                editionId = older.id,
                bookId = older.id,
                currentChapterIndex = 0,
                currentPositionSeconds = 11L,
                lastListenedAt = 1_000L
            )
        )
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                editionId = fresher.id,
                bookId = fresher.id,
                currentChapterIndex = 1,
                currentPositionSeconds = 42L,
                lastListenedAt = 2_000L
            )
        )

        val resumed = resume(manager)

        assertTrue("the freshest row is resumable", resumed)
        val state = manager.playerState.value
        assertEquals(fresher.id, state.currentBook?.id)
        assertEquals(1, state.currentChapterIndex)
        assertEquals(42_000L, state.currentPositionMs)
        // #805 regression: the chapter must reach the engine WITH its track.
        // Loading the book without the playable list left every chapter
        // trackless («No playable locator») and the restore carried no audio.
        runCurrent()
        assertEquals(1, factory.current.mediaItems.size)
    }

    @Test
    fun `never replaces a loaded player`() = resumeTest { manager, _ ->
        val loaded = books[0]
        val other = books[1]
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                editionId = other.id,
                bookId = other.id,
                lastListenedAt = 5_000L
            )
        )
        manager.loadAndPlayBook(
            book = loaded,
            chapters = dao.getChaptersListForBook(loaded.id),
            autoPlay = false
        )

        val resumed = resume(manager)

        assertFalse("a loaded queue is the listener's choice", resumed)
        assertEquals(loaded.id, manager.playerState.value.currentBook?.id)
    }

    @Test
    fun `does nothing without progress rows`() = resumeTest { manager, _ ->
        val resumed = resume(manager)

        assertFalse(resumed)
        assertNull(manager.playerState.value.currentBook)
    }

    @Test
    fun `does nothing when the progress row points at a missing book`() = resumeTest { manager, _ ->
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                editionId = "gone",
                bookId = "gone",
                lastListenedAt = 9_000L
            )
        )

        val resumed = resume(manager)

        assertFalse(resumed)
        assertNull(manager.playerState.value.currentBook)
    }

    @Test
    fun `does nothing when the book has no chapters`() = resumeTest { manager, _ ->
        val book = books[0]
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                editionId = book.id,
                bookId = book.id,
                lastListenedAt = 9_000L
            )
        )

        val resumed = PlaybackResume.resumeMostRecent(
            playerManager = manager,
            libraryEntries = libraryEntries,
            playableFor = { emptyList() },
            autoPlay = false,
            ioDispatcher = dispatcher,
            playerDispatcher = dispatcher
        )

        assertFalse("an empty playable list never reaches the engine", resumed)
        assertNull(manager.playerState.value.currentBook)
    }

    private suspend fun TestScope.resume(manager: AudioPlayerManager): Boolean =
        PlaybackResume.resumeMostRecent(
            playerManager = manager,
            libraryEntries = libraryEntries,
            playableFor = { bookId -> playableFor(bookId) },
            autoPlay = false,
            ioDispatcher = dispatcher,
            playerDispatcher = dispatcher
        )

    /**
     * Chapter→track pairs the way `SourceCatalog.getPlayableChapters` hands them
     * over: without a track the manager answers «No playable locator» and the
     * restore silently carries no audio (#805).
     */
    private suspend fun playableFor(bookId: String): List<SourceCatalog.PlayableChapter> {
        val book = books.firstOrNull { it.id == bookId } ?: return emptyList()
        val tracks = TestDataFactory.tracksFor(book, "4read")
        return dao.getChaptersListForBook(bookId).mapIndexed { index, chapter ->
            SourceCatalog.PlayableChapter(chapter = chapter, track = tracks.getOrNull(index))
        }
    }

    private fun resumeTest(
        body: suspend TestScope.(AudioPlayerManager, RecordingPlayerFactory) -> Unit
    ) = runTest(dispatcher) {
        val factory = RecordingPlayerFactory()
        val manager = AudioPlayerManager(
            context,
            listeningState,
            { dao.getChaptersListForBook(it).map { chapter -> SourceCatalog.PlayableChapter(chapter, null) } },
            injectedPlayerFactory = factory,
            ioDispatcher = dispatcher,
            // Spec-22 T4: widget sync is a forever-running sampled collector;
            // keep it off the test scheduler.
            widgetSyncEnabled = false
        )
        try {
            body(manager, factory)
        } finally {
            manager.release()
        }
    }
}
