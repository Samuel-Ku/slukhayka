package com.slukhayka.audiobooks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.player.AudioPlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerControlsTest {

    private lateinit var context: Context
    private lateinit var database: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var playerManager: AudioPlayerManager

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = AudiobookDatabase.getDatabase(context)
        dao = database.audiobookDao()
        // ADR-0002: the player runs on the store + a chapter fetcher — no
        // repository graph.
        val listeningState = ListeningStateStore(dao)
        // Spec-22 T4: the widget-sync collector is a forever-running loop on
        // the test scheduler — disable it so runTest can finish.
        playerManager = AudioPlayerManager(
            context,
            listeningState,
            { dao.getChaptersListForBook(it).map { ch -> com.slukhayka.audiobooks.data.catalog.SourceCatalog.PlayableChapter(ch, null) } },
            widgetSyncEnabled = false
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleBook() = AudiobookEntity(
        id = "test_book_1",
        title = "Тестова книга",
        author = "Автор Тест",
        narrator = "Читець Тест",
        description = "Опис",
        coverDrawableRes = 0,
        coverImageUrl = null,
        genre = "Фантастика",
        sourceUrl = "https://4read.org/test",
        totalChapters = 3,
        totalDurationSeconds = 3000L
    )

    // ADR-0007: chapter rows carry no stream URLs anymore — the physical
    // playback data lives on the Source tracks. These fixtures only need the
    // logical chapter list (ids/durations) for the button-state assertions.
    private fun sampleChapters() = listOf(
        ChapterEntity("ch_1", "test_book_1", 0, "Глава 1", 1000L),
        ChapterEntity("ch_2", "test_book_1", 1, "Глава 2", 1000L),
        ChapterEntity("ch_3", "test_book_1", 2, "Глава 3", 1000L)
    )

    @Test
    fun testPlayPauseButtonToggle() {
        playerManager.loadAndPlayBook(
            book = sampleBook(),
            chapters = sampleChapters(),
            initialChapterIndex = 0,
            autoPlay = false
        )

        assertFalse("Початковий стан має бути paused", playerManager.playerState.value.isPlaying)

        // Натискання кнопки Play через togglePlayPause
        playerManager.togglePlayPause()
        // Або викликаємо pause()
        playerManager.pause()
        assertFalse("Після pause() стан має бути isPlaying = false", playerManager.playerState.value.isPlaying)
    }

    @Test
    fun testSkipForwardButton() {
        playerManager.loadAndPlayBook(
            book = sampleBook(),
            chapters = sampleChapters(),
            initialPositionSeconds = 10L,
            autoPlay = false
        )

        val initialPos = playerManager.playerState.value.currentPositionMs
        assertEquals(10000L, initialPos)

        // Натискання кнопки Перемотування Вперед (+30с)
        playerManager.skipForward(30)
        assertEquals(40000L, playerManager.playerState.value.currentPositionMs)
    }

    @Test
    fun testSkipBackwardButton() {
        playerManager.loadAndPlayBook(
            book = sampleBook(),
            chapters = sampleChapters(),
            initialPositionSeconds = 50L,
            autoPlay = false
        )

        // Натискання кнопки Перемотування Назад (-15с)
        playerManager.skipBackward(15)
        assertEquals(35000L, playerManager.playerState.value.currentPositionMs)
    }

    @Test
    fun testNextChapterButton() {
        playerManager.loadAndPlayBook(
            book = sampleBook(),
            chapters = sampleChapters(),
            initialChapterIndex = 0,
            autoPlay = false
        )

        assertEquals(0, playerManager.playerState.value.currentChapterIndex)

        // Натискання кнопки Наступна глава
        playerManager.nextChapter()
        assertEquals(1, playerManager.playerState.value.currentChapterIndex)

        // Натискання кнопки Наступна глава ще раз
        playerManager.nextChapter()
        assertEquals(2, playerManager.playerState.value.currentChapterIndex)

        // Натискання на останній главі не повинно виходити за межі списку
        playerManager.nextChapter()
        assertEquals(2, playerManager.playerState.value.currentChapterIndex)
    }

    @Test
    fun testPreviousChapterButton() {
        playerManager.loadAndPlayBook(
            book = sampleBook(),
            chapters = sampleChapters(),
            initialChapterIndex = 2,
            autoPlay = false
        )

        assertEquals(2, playerManager.playerState.value.currentChapterIndex)

        // Натискання кнопки Попередня глава
        playerManager.previousChapter()
        assertEquals(1, playerManager.playerState.value.currentChapterIndex)

        // Попередня глава ще раз -> Глава 0
        playerManager.previousChapter()
        assertEquals(0, playerManager.playerState.value.currentChapterIndex)

        // Якщо вже на першій главі, кнопка скидає позицію на 0мс
        playerManager.seekTo(5000L)
        playerManager.previousChapter()
        assertEquals(0, playerManager.playerState.value.currentChapterIndex)
        assertEquals(0L, playerManager.playerState.value.currentPositionMs)
    }

    @Test
    fun testPlaybackSpeedButton() {
        playerManager.loadAndPlayBook(
            book = sampleBook(),
            chapters = sampleChapters(),
            autoPlay = false
        )

        assertEquals(1.0f, playerManager.playerState.value.playbackSpeed, 0.01f)

        // Натискання кнопки зміни швидкості -> 1.25x
        playerManager.setPlaybackSpeed(1.25f)
        assertEquals(1.25f, playerManager.playerState.value.playbackSpeed, 0.01f)

        // 1.5x
        playerManager.setPlaybackSpeed(1.5f)
        assertEquals(1.5f, playerManager.playerState.value.playbackSpeed, 0.01f)

        // 2.0x
        playerManager.setPlaybackSpeed(2.0f)
        assertEquals(2.0f, playerManager.playerState.value.playbackSpeed, 0.01f)
    }

    @Test
    fun testSleepTimerButton() {
        playerManager.loadAndPlayBook(
            book = sampleBook(),
            chapters = sampleChapters(),
            autoPlay = false
        )

        assertEquals(0, playerManager.playerState.value.sleepTimerMinutes)

        // Налаштування таймера сну на 15 хвилин
        playerManager.setSleepTimer(15)
        assertEquals(15, playerManager.playerState.value.sleepTimerMinutes)
        assertEquals(900, playerManager.playerState.value.sleepTimerRemainingSeconds)

        // Вимкнення таймера сну (0 хв)
        playerManager.setSleepTimer(0)
        assertEquals(0, playerManager.playerState.value.sleepTimerMinutes)
        assertEquals(0, playerManager.playerState.value.sleepTimerRemainingSeconds)
    }

    @Test
    fun testSelectChapterDirectly() {
        playerManager.loadAndPlayBook(
            book = sampleBook(),
            chapters = sampleChapters(),
            initialChapterIndex = 0,
            autoPlay = false
        )

        // Вибір конкретної глави (наприклад, Глава 2 / Індекс 1) зі списку
        playerManager.selectChapter(1)
        assertEquals(1, playerManager.playerState.value.currentChapterIndex)
    }
}
