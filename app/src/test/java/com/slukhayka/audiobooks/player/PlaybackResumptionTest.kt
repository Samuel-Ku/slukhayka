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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #805 — the values [PlaybackResumption] hands to Media3 for a resumption
 * request: the item the manager just loaded and the listener's saved position.
 *
 * The position is the interesting half: `AudioPlayerManager` parks the engine at
 * 0 and applies the saved offset only when the stream reports READY, so reading
 * the position off the player here would resume from zero.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackResumptionTest {

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
    fun `returns the restored item and the saved position`() = resumptionTest { manager ->
        val book = books[0]
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                editionId = book.id,
                bookId = book.id,
                currentChapterIndex = 0,
                currentPositionSeconds = 42L,
                lastListenedAt = 1_000L
            )
        )

        val items = itemsFor(manager)

        assertEquals(1, items.mediaItems.size)
        assertEquals(0, items.startIndex)
        assertEquals(
            "the listener's saved offset, not the engine's parked zero",
            42_000L,
            items.startPositionMs
        )
        assertEquals(
            "the load went through the manager",
            book.id,
            manager.playerState.value.currentBook?.id
        )
    }

    @Test
    fun `returns nothing to resume when no progress row exists`() = resumptionTest { manager ->
        val items = itemsFor(manager)

        assertTrue("Media3 treats an empty list as «nothing to resume»", items.mediaItems.isEmpty())
        assertEquals(0L, items.startPositionMs)
    }

    private suspend fun TestScope.itemsFor(manager: AudioPlayerManager) =
        PlaybackResumption.itemsFor(
            playerManager = manager,
            libraryEntries = libraryEntries,
            playableFor = { bookId -> playableFor(bookId) },
            player = manager.player
        )

    /**
     * Chapter→track pairs the way `SourceCatalog.getPlayableChapters` hands them
     * over; without a track the player never receives a media item (#805).
     */
    private suspend fun playableFor(bookId: String): List<SourceCatalog.PlayableChapter> {
        val book = books.firstOrNull { it.id == bookId } ?: return emptyList()
        val tracks = TestDataFactory.tracksFor(book, "4read")
        return dao.getChaptersListForBook(bookId).mapIndexed { index, chapter ->
            SourceCatalog.PlayableChapter(chapter = chapter, track = tracks.getOrNull(index))
        }
    }

    private fun resumptionTest(body: suspend TestScope.(AudioPlayerManager) -> Unit) = runTest(dispatcher) {
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
