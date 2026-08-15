package com.example.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AudiobookEntity
import com.example.data.db.ChapterEntity
import com.example.data.repository.AudiobookRepository
import com.example.testing.FakeAudiobookDao
import com.example.testing.TestDataFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartSleepTimerTest {

    private lateinit var context: Context
    private lateinit var dao: FakeAudiobookDao
    private lateinit var repository: AudiobookRepository
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val book: AudiobookEntity = TestDataFactory.dataBooks()[0]
    private val chapters: List<ChapterEntity> = TestDataFactory.chaptersFor(book)

    private lateinit var playerManager: AudioPlayerManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        dao = FakeAudiobookDao()
        repository = AudiobookRepository(dao)
        val fakeEngine = FakePlayerEngine()
        playerManager = AudioPlayerManager(
            context = context,
            repository = repository,
            injectedPlayerFactory = { fakeEngine }
        )
    }

    @Test
    fun testSleepTimerStandardMinutes() = testScope.runTest {
        playerManager.setSleepTimer(15)
        val state = playerManager.playerState.value
        assertEquals(15, state.sleepTimerMinutes)
        assertEquals(15 * 60, state.sleepTimerRemainingSeconds)
        assertFalse(state.isSleepTimerEndOfChapter)
    }

    @Test
    fun testSleepTimerCancel() = testScope.runTest {
        playerManager.setSleepTimer(30)
        assertTrue(playerManager.playerState.value.sleepTimerMinutes > 0)

        playerManager.setSleepTimer(0)
        val state = playerManager.playerState.value
        assertEquals(0, state.sleepTimerMinutes)
        assertEquals(0, state.sleepTimerRemainingSeconds)
        assertFalse(state.isSleepTimerEndOfChapter)
    }

    @Test
    fun testSleepTimerEndOfChapterMode() = testScope.runTest {
        playerManager.loadAndPlayBook(
            book = book,
            chapters = chapters,
            initialChapterIndex = 0,
            autoPlay = false
        )

        playerManager.setSleepTimer(-1)
        val state = playerManager.playerState.value
        assertEquals(-1, state.sleepTimerMinutes)
        assertTrue(state.isSleepTimerEndOfChapter)
        assertTrue(state.sleepTimerRemainingSeconds > 0)
    }
}
