@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AudiobookEntity
import com.example.data.db.ChapterEntity
import com.example.data.db.PlaybackProgressEntity
import com.example.data.repository.AudiobookRepository
import com.example.testing.FakeAudiobookDao
import com.example.testing.TestDataFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudiobookLibrarySessionCallbackTest {

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private lateinit var context: Context
    private lateinit var dao: FakeAudiobookDao
    private lateinit var repository: AudiobookRepository
    private lateinit var playerEngine: FakePlayerEngine
    private lateinit var playerManager: AudioPlayerManager
    private lateinit var callback: AudiobookLibrarySessionCallback

    private val books = TestDataFactory.dataBooks()
    private val chapters = TestDataFactory.dataChapters()

    @Suppress("UNCHECKED_CAST")
    private val dummySession: MediaLibrarySession
        get() = null as Any as MediaLibrarySession

    @Suppress("UNCHECKED_CAST")
    private val dummyBrowser: MediaSession.ControllerInfo
        get() = null as Any as MediaSession.ControllerInfo

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        dao = FakeAudiobookDao(
            books = books,
            chapters = chapters
        )
        repository = AudiobookRepository(dao, context, autoSyncOnInit = false)
        playerEngine = FakePlayerEngine()
        playerManager = AudioPlayerManager(
            context = context,
            repository = repository,
            injectedPlayerFactory = { playerEngine }
        )
        callback = AudiobookLibrarySessionCallback(
            context = context,
            repository = repository,
            playerManager = playerManager,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            scope = testScope
        )
    }

    @After
    fun tearDown() {
        playerManager.release()
        Dispatchers.resetMain()
    }

    @Test
    fun getLibraryRoot_returnsBrowsableRootItem() = runTest(dispatcher) {
        val future = callback.onGetLibraryRoot(dummySession, dummyBrowser, null)
        advanceUntilIdle()
        val result = future.get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        val rootItem = result.value
        assertNotNull(rootItem)
        assertEquals(AudiobookLibrarySessionCallback.ROOT_ID_ROOT, rootItem?.mediaId)
        assertTrue(rootItem?.mediaMetadata?.isBrowsable == true)
        assertFalse(rootItem?.mediaMetadata?.isPlayable == true)
    }

    @Test
    fun getChildren_forRoot_returnsRecentsFavoritesAndCatalogNodes() = runTest(dispatcher) {
        val future = callback.onGetChildren(
            session = dummySession,
            browser = dummyBrowser,
            parentId = AudiobookLibrarySessionCallback.ROOT_ID_ROOT,
            page = 0,
            pageSize = 10,
            params = null
        )
        advanceUntilIdle()
        val result = future.get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        val items = result.value
        assertNotNull(items)
        assertEquals(3, items?.size)
        assertEquals(AudiobookLibrarySessionCallback.ROOT_ID_RECENTS, items?.get(0)?.mediaId)
        assertEquals(AudiobookLibrarySessionCallback.ROOT_ID_FAVORITES, items?.get(1)?.mediaId)
        assertEquals(AudiobookLibrarySessionCallback.ROOT_ID_CATALOG, items?.get(2)?.mediaId)
    }

    @Test
    fun getChildren_forRecents_returnsPlayableRecentBooks() = runTest(dispatcher) {
        // Save progress for book 0
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                bookId = books[0].id,
                currentChapterIndex = 1,
                currentPositionSeconds = 120L,
                lastListenedAt = 5000L
            )
        )

        val future = callback.onGetChildren(
            session = dummySession,
            browser = dummyBrowser,
            parentId = AudiobookLibrarySessionCallback.ROOT_ID_RECENTS,
            page = 0,
            pageSize = 10,
            params = null
        )
        advanceUntilIdle()
        val result = future.get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        val items = result.value
        assertNotNull(items)
        assertEquals(1, items?.size)
        assertEquals(books[0].id, items?.get(0)?.mediaId)
        assertTrue(items?.get(0)?.mediaMetadata?.isPlayable == true)
        assertFalse(items?.get(0)?.mediaMetadata?.isBrowsable == true)
        assertEquals(books[0].title, items?.get(0)?.mediaMetadata?.title?.toString())
    }

    @Test
    fun getChildren_forFavorites_returnsPlayableFavoriteBooks() = runTest(dispatcher) {
        dao.setFavorite(books[0].id, true)
        dao.setFavorite(books[1].id, false)

        val future = callback.onGetChildren(
            session = dummySession,
            browser = dummyBrowser,
            parentId = AudiobookLibrarySessionCallback.ROOT_ID_FAVORITES,
            page = 0,
            pageSize = 10,
            params = null
        )
        advanceUntilIdle()
        val result = future.get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        val items = result.value
        assertNotNull(items)
        assertEquals(1, items?.size)
        assertEquals(books[0].id, items?.get(0)?.mediaId)
        assertTrue(items?.get(0)?.mediaMetadata?.isPlayable == true)
    }

    @Test
    fun getItem_forValidBook_returnsPlayableItem() = runTest(dispatcher) {
        val future = callback.onGetItem(
            session = dummySession,
            browser = dummyBrowser,
            mediaId = books[0].id
        )
        advanceUntilIdle()
        val result = future.get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        val item = result.value
        assertNotNull(item)
        assertEquals(books[0].id, item?.mediaId)
        assertEquals(books[0].title, item?.mediaMetadata?.title?.toString())
        assertTrue(item?.mediaMetadata?.isPlayable == true)
    }

    @Test
    fun getItem_forInvalidId_returnsErrorBadValue() = runTest(dispatcher) {
        val future = callback.onGetItem(
            session = dummySession,
            browser = dummyBrowser,
            mediaId = "non_existent_book_id"
        )
        advanceUntilIdle()
        val result = future.get()

        assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, result.resultCode)
    }

    @Test
    fun setMediaItems_loadsAndPlaysTargetBook() = runTest(dispatcher) {
        val targetBook = books[0]
        val mediaItem = AudiobookLibrarySessionCallback.mapBookToMediaItem(targetBook)

        val future = callback.onSetMediaItems(
            session = dummySession,
            controller = dummyBrowser,
            mediaItems = mutableListOf(mediaItem),
            startIndex = 0,
            startPositionMs = 0L
        )
        advanceUntilIdle()
        val result = future.get()

        assertNotNull(result)
        assertEquals(targetBook.id, playerManager.playerState.value.currentBook?.id)
    }
}
