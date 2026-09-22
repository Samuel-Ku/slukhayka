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
    fun `resumes the freshest progress row when nothing is loaded`() = resumeTest { manager ->
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
    }

    @Test
    fun `never replaces a loaded player`() = resumeTest { manager ->
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
    fun `does nothing without progress rows`() = resumeTest { manager ->
        val resumed = resume(manager)

        assertFalse(resumed)
        assertNull(manager.playerState.value.currentBook)
    }

    @Test
    fun `does nothing when the progress row points at a missing book`() = resumeTest { manager ->
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
    fun `does nothing when the book has no chapters`() = resumeTest { manager ->
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
            chaptersFor = { emptyList() },
            autoPlay = false,
            ioDispatcher = dispatcher,
            playerDispatcher = dispatcher
        )

        assertFalse("an empty chapter list never reaches the engine", resumed)
        assertNull(manager.playerState.value.currentBook)
    }

    private suspend fun TestScope.resume(manager: AudioPlayerManager): Boolean =
        PlaybackResume.resumeMostRecent(
            playerManager = manager,
            libraryEntries = libraryEntries,
            chaptersFor = { bookId -> dao.getChaptersListForBook(bookId) },
            autoPlay = false,
            ioDispatcher = dispatcher,
            playerDispatcher = dispatcher
        )

    private fun resumeTest(body: suspend TestScope.(AudioPlayerManager) -> Unit) = runTest(dispatcher) {
        val manager = AudioPlayerManager(
            context,
            listeningState,
            { dao.getChaptersListForBook(it).map { chapter -> SourceCatalog.PlayableChapter(chapter, null) } },
            injectedPlayerFactory = RecordingPlayerFactory(),
            ioDispatcher = dispatcher,
            // Spec-22 T4: widget sync is a forever-running sampled collector;
            // keep it off the test scheduler.
            widgetSyncEnabled = false
        )
        try {
            body(manager)
        } finally {
            manager.release()
        }
    }
}
