package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.player.SeekSession
import com.slukhayka.audiobooks.ui.screens.PlayerProgressUi
import com.slukhayka.audiobooks.ui.screens.PlayerScreenContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1571 port — the scrub contract of the 1.6.x player's TWO adjustable sliders.
 *
 * `release/1.6.2` keeps the 1.6.x player: the chapter slider
 * (`player_progress_slider`) and the whole-book slider (`book_progress_slider`)
 * both seek on every value change, so a single drag fires a player seek plus a
 * database write per move (and, for the book slider, may re-prepare a chapter
 * per move). After the port each drag previews its time locally and commits
 * exactly ONCE on release; a cancelled drag commits nothing; TalkBack's
 * `SetProgress` and a tap commit immediately.
 *
 * The same `DualProgress` renders both sliders, so the assertions come in pairs.
 * The chapter half also pins the exact duration: 660_999 ms is 660 whole seconds
 * plus 999 ms, and the preview must map against the same exact millisecond value
 * the release commits against, not against the truncated seconds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerScrubCommitTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val book = AudiobookEntity(
        id = "scrub-player",
        title = "Нейромант",
        author = "Вільям Гібсон",
        narrator = "Олександр Завальський",
        description = "",
        coverDrawableRes = 0,
        genre = "Кіберпанк",
        sourceUrl = "https://example.invalid/player",
        totalDurationSeconds = 1_980,
        totalChapters = 3
    )
    private val chapters = listOf(600L, 660L, 720L).mapIndexed { index, duration ->
        ChapterEntity(
            id = "scrub-chapter-$index",
            bookId = book.id,
            chapterIndex = index,
            title = "Розділ ${index + 1}",
            durationSeconds = duration
        )
    }
    private val state = PlayerState(
        currentBook = book,
        chapters = chapters,
        currentChapterIndex = 1,
        isPlaying = true,
        currentPositionMs = 320_000,
        durationMs = 660_000
    )
    private val progress = PlayerProgressUi(
        chapterFraction = 320f / 660f,
        bookFraction = 920f / 1_980f,
        bookPositionSeconds = 920,
        bookDurationSeconds = 1_980,
        chapterMarkers = listOf(600f / 1_980f, 1_260f / 1_980f),
        bookmarkMarkers = listOf(800f / 1_980f)
    )

    /*
     * The pre-drag position is 320 s of a 660 s chapter and 920 s into a 1980 s
     * book: the rows render "05:20  /  11:00" and "15:20  /  33:00". A drag that
     * follows the finger must move its own row's time and leave the other row
     * untouched.
     */
    private val chapterRow = "05:20  /  11:00"
    private val bookRow = "15:20  /  33:00"

    @Test
    fun chapterDragThatIsStillDownPreviewsTheFingerTimeAndSeeksNothing() {
        val chapterSeeks = mutableListOf<Float>()
        val bookSeeks = mutableListOf<Float>()
        setPlayerContent(onSeek = { chapterSeeks += it }, onBookSeek = { bookSeeks += it })

        composeTestRule.onNodeWithTag("player_progress_slider").performTouchInput {
            down(center)
            moveBy(Offset(width * 0.2f, 0f))
            moveBy(Offset(width * 0.2f, 0f))
        }
        composeTestRule.waitForIdle()

        assertTrue(
            "a chapter drag that is not released yet must not touch the player; got seeks=$chapterSeeks",
            chapterSeeks.isEmpty()
        )
        assertTrue("a chapter drag must not seek the book; got $bookSeeks", bookSeeks.isEmpty())
        composeTestRule.onNodeWithText(chapterRow, useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(bookRow, useUnmergedTree = true).assertExists()
    }

    @Test
    fun bookDragThatIsStillDownPreviewsTheFingerBookTimeAndSeeksNothing() {
        val chapterSeeks = mutableListOf<Float>()
        val bookSeeks = mutableListOf<Float>()
        setPlayerContent(onSeek = { chapterSeeks += it }, onBookSeek = { bookSeeks += it })

        composeTestRule.onNodeWithTag("book_progress_slider").performTouchInput {
            down(center)
            moveBy(Offset(width * 0.2f, 0f))
            moveBy(Offset(width * 0.2f, 0f))
        }
        composeTestRule.waitForIdle()

        assertTrue(
            "a book drag that is not released yet must not touch the player; got book seeks=$bookSeeks",
            bookSeeks.isEmpty()
        )
        assertTrue("a book drag must not seek the chapter; got $chapterSeeks", chapterSeeks.isEmpty())
        composeTestRule.onNodeWithText(bookRow, useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(chapterRow, useUnmergedTree = true).assertExists()
    }

    @Test
    fun chapterDragPreviewUsesTheExactDurationTheReleaseCommitsAgainst() {
        val exactDurationMs = 660_999L
        val chapterSeeks = mutableListOf<Float>()
        setPlayerContent(
            playerState = state.copy(durationMs = exactDurationMs),
            progressUi = progress,
            onSeek = { chapterSeeks += it }
        )

        composeTestRule.onNodeWithTag("player_progress_slider").performTouchInput {
            down(center)
            moveBy(Offset(width * 0.2f, 0f))
            moveBy(Offset(width * 0.2f, 0f))
        }
        composeTestRule.waitForIdle()

        val fingerFraction = composeTestRule.onNodeWithTag("player_progress_slider")
            .fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
            .current
        val expectedSeconds = SeekSession.previewSeconds(fingerFraction, exactDurationMs)
        composeTestRule.onNodeWithText(
            "${MainViewModel.formatTime(expectedSeconds)}  /  11:00",
            useUnmergedTree = true
        ).assertExists(
            "finger fraction $fingerFraction of $exactDurationMs ms must preview " +
                MainViewModel.formatTime(expectedSeconds)
        )
        assertTrue("a finger-down preview must not seek; got $chapterSeeks", chapterSeeks.isEmpty())
    }

    @Test
    fun chapterSliderDragCommitsExactlyOneSeekOnRelease() {
        val chapterSeeks = mutableListOf<Float>()
        val bookSeeks = mutableListOf<Float>()
        setPlayerContent(onSeek = { chapterSeeks += it }, onBookSeek = { bookSeeks += it })

        composeTestRule.onNodeWithTag("player_progress_slider").performTouchInput {
            down(center)
            moveBy(Offset(width * 0.15f, 0f))
            moveBy(Offset(width * 0.15f, 0f))
            moveBy(Offset(width * 0.15f, 0f))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(
            "a chapter scrub must commit to the player exactly once, on release; " +
                "got ${chapterSeeks.size} seeks: $chapterSeeks",
            1,
            chapterSeeks.size
        )
        assertTrue("a chapter scrub must not seek the book; got $bookSeeks", bookSeeks.isEmpty())
    }

    @Test
    fun bookSliderDragCommitsExactlyOneBookSeekOnRelease() {
        val chapterSeeks = mutableListOf<Float>()
        val bookSeeks = mutableListOf<Float>()
        setPlayerContent(onSeek = { chapterSeeks += it }, onBookSeek = { bookSeeks += it })

        composeTestRule.onNodeWithTag("book_progress_slider").performTouchInput {
            down(center)
            moveBy(Offset(width * 0.2f, 0f))
            moveBy(Offset(width * 0.2f, 0f))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(
            "a book scrub must commit to the player exactly once, on release; " +
                "got ${bookSeeks.size} book seeks: $bookSeeks",
            1,
            bookSeeks.size
        )
        assertTrue("a book scrub must not seek the chapter; got $chapterSeeks", chapterSeeks.isEmpty())
    }

    @Test
    fun cancelledChapterDragCommitsNothingAndRestoresThePlayerTime() {
        val chapterSeeks = mutableListOf<Float>()
        setPlayerContent(onSeek = { chapterSeeks += it })

        composeTestRule.onNodeWithTag("player_progress_slider").performTouchInput {
            down(center)
            moveBy(Offset(width * 0.2f, 0f))
            moveBy(Offset(width * 0.2f, 0f))
            cancel()
        }
        composeTestRule.waitForIdle()

        assertEquals(
            "a cancelled chapter drag must not move the player; got seeks=$chapterSeeks",
            0,
            chapterSeeks.size
        )
        composeTestRule.onNodeWithText(chapterRow, useUnmergedTree = true).assertExists()
    }

    @Test
    fun cancelledBookDragCommitsNothingAndRestoresTheBookTime() {
        val bookSeeks = mutableListOf<Float>()
        setPlayerContent(onBookSeek = { bookSeeks += it })

        composeTestRule.onNodeWithTag("book_progress_slider").performTouchInput {
            down(center)
            moveBy(Offset(width * 0.2f, 0f))
            moveBy(Offset(width * 0.2f, 0f))
            cancel()
        }
        composeTestRule.waitForIdle()

        assertEquals(
            "a cancelled book drag must not move the player; got book seeks=$bookSeeks",
            0,
            bookSeeks.size
        )
        composeTestRule.onNodeWithText(bookRow, useUnmergedTree = true).assertExists()
    }

    @Test
    fun assistiveTechnologySetProgressStillCommitsExactlyOncePerSlider() {
        val chapterSeeks = mutableListOf<Float>()
        val bookSeeks = mutableListOf<Float>()
        setPlayerContent(onSeek = { chapterSeeks += it }, onBookSeek = { bookSeeks += it })

        composeTestRule.onNodeWithTag("player_progress_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        composeTestRule.onNodeWithTag("book_progress_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
        composeTestRule.waitForIdle()

        assertEquals(
            "chapter SetProgress must land as one seek; got $chapterSeeks",
            1,
            chapterSeeks.size
        )
        assertEquals(0.25f, chapterSeeks.single(), 0.001f)
        assertEquals(
            "book SetProgress must land as one book seek; got $bookSeeks",
            1,
            bookSeeks.size
        )
        assertEquals(0.75f, bookSeeks.single(), 0.001f)
    }

    private fun setPlayerContent(
        playerState: PlayerState = state,
        progressUi: PlayerProgressUi = progress,
        onSeek: (Float) -> Unit = {},
        onBookSeek: (Float) -> Unit = {}
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PlayerContent(
                        playerState = playerState,
                        progressUi = progressUi,
                        onSeek = onSeek,
                        onBookSeek = onBookSeek
                    )
                }
            }
        }
    }

    @Composable
    private fun PlayerContent(
        playerState: PlayerState = state,
        progressUi: PlayerProgressUi = progress,
        onSeek: (Float) -> Unit = {},
        onBookSeek: (Float) -> Unit = {}
    ) {
        Box(Modifier.fillMaxSize()) {
            PlayerScreenContent(
                playerState = playerState,
                book = book,
                currentChapterTitle = chapters[1].title,
                progress = progressUi,
                artworkAccent = Color(0xFF355D67),
                onArtworkLoaded = {},
                onDismiss = {},
                onToggleFavorite = {},
                onToggleDebug = {},
                onSeek = onSeek,
                onBookSeek = onBookSeek,
                onPreviousChapter = {},
                onBack = {},
                onPlayPause = {},
                onForward = {},
                onNextChapter = {},
                onUndoSeek = {},
                onSpeed = {},
                onTimer = {},
                onBookmark = {},
                onChapters = {},
            )
        }
    }
}
