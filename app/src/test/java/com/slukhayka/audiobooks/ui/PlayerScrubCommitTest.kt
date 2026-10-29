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
 * #1569 — the scrub contract of the player's ONE adjustable control.
 *
 * A drag previews time locally and commits to the player exactly ONCE, on
 * release. Before the fix every `onValueChange` tick called [onSeek], so one
 * gesture produced a player seek plus a database write per move.
 *
 * The ticket body also asks for a book-slider assertion. That half was written
 * from the shipped 1.6.x layout, where the player had two sliders; `origin/main`
 * has one adjustable control since the #1490 «Папір і голос» refresh — "у плеєрі
 * ОДИН регульований контрол — seek розділу … лінія книги стала read-only"
 * (`docs/specs/2026-10-26-ui-refresh-2027.md`). The book ruler's half of the
 * contract is therefore pinned as a regression: a gesture on the read-only
 * `book_progress_line` never reaches the player.
 *
 * The preview assertion leaves the finger DOWN for the duration of the test:
 * `performTouchInput` batches one gesture per call and a fresh call cannot
 * resume it (a second `performTouchInput { up() }` throws "no gesture is in
 * progress"), so the time is read after the move events have been delivered but
 * before the release.
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
     * The pre-drag player position is 320 s of a 660 s chapter: the chapter row
     * renders "05:20", the book row renders "15:20". A drag that follows the
     * finger must move the chapter time away from 05:20 while the book time is
     * untouched.
     */
    private val playerChapterTime = "05:20"
    private val bookTime = "15:20"

    @Test
    fun dragThatIsStillDownPreviewsTheFingerTimeAndSeeksNothing() {
        val seeks = mutableListOf<Float>()
        setPlayerContent(onSeek = { seeks += it })

        composeTestRule.onNodeWithTag("player_progress_slider").performTouchInput {
            down(center)
            moveBy(Offset(width * 0.2f, 0f))
            moveBy(Offset(width * 0.2f, 0f))
        }
        composeTestRule.waitForIdle()

        assertTrue(
            "a drag that is not released yet must not touch the player; got seeks=$seeks",
            seeks.isEmpty()
        )
        composeTestRule.onNodeWithText(playerChapterTime, useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(bookTime, useUnmergedTree = true).assertExists()
    }

    @Test
    fun fingerDownPreviewUsesTheExactDurationTheReleaseCommitsAgainst() {
        // 660_999 ms is 660 whole seconds plus 999 ms: the duration label still
        // reads 11:00, but a seek at a fraction of it lands on a millisecond the
        // old preview — which mapped against the whole seconds (660_000 ms) —
        // could place a second earlier. The preview must use the same exact
        // duration the release commits against.
        val exactDurationMs = 660_999L
        val seeks = mutableListOf<Float>()
        setPlayerContent(
            playerState = state.copy(durationMs = exactDurationMs),
            progressUi = progress,
            onSeek = { seeks += it }
        )

        composeTestRule.onNodeWithTag("player_progress_slider").performTouchInput {
            down(center)
            moveBy(Offset(width * 0.2f, 0f))
            moveBy(Offset(width * 0.2f, 0f))
        }
        composeTestRule.waitForIdle()

        // Read the fraction the finger is at from the slider itself, then demand
        // that the rendered time is what the commit would land on.
        val fingerFraction = composeTestRule.onNodeWithTag("player_progress_slider")
            .fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
            .current
        val expectedSeconds = SeekSession.previewSeconds(fingerFraction, exactDurationMs)
        composeTestRule.onNodeWithText(
            MainViewModel.formatTime(expectedSeconds),
            useUnmergedTree = true
        ).assertExists(
            "finger fraction $fingerFraction of $exactDurationMs ms must preview " +
                MainViewModel.formatTime(expectedSeconds)
        )
        assertTrue(
            "a finger-down preview must not seek; got seeks=$seeks",
            seeks.isEmpty()
        )
    }

    @Test
    fun chapterSliderDragCommitsExactlyOneSeekOnRelease() {
        val seeks = mutableListOf<Float>()
        setPlayerContent(onSeek = { seeks += it })

        composeTestRule.onNodeWithTag("player_progress_slider").performTouchInput {
            down(center)
            moveBy(Offset(width * 0.15f, 0f))
            moveBy(Offset(width * 0.15f, 0f))
            moveBy(Offset(width * 0.15f, 0f))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(
            "a scrub gesture must commit to the player exactly once, on release; " +
                "got ${seeks.size} seeks: $seeks",
            1,
            seeks.size
        )
    }

    @Test
    fun cancelledDragCommitsNothingAndRestoresThePlayerTime() {
        val seeks = mutableListOf<Float>()
        setPlayerContent(onSeek = { seeks += it })

        composeTestRule.onNodeWithTag("player_progress_slider").performTouchInput {
            down(center)
            moveBy(Offset(width * 0.2f, 0f))
            moveBy(Offset(width * 0.2f, 0f))
            cancel()
        }
        composeTestRule.waitForIdle()

        assertEquals(
            "a cancelled drag must not move the player; got seeks=$seeks",
            0,
            seeks.size
        )
        composeTestRule.onNodeWithText(playerChapterTime, useUnmergedTree = true).assertExists()
    }

    @Test
    fun assistiveTechnologySetProgressStillCommitsExactlyOnce() {
        val seeks = mutableListOf<Float>()
        setPlayerContent(onSeek = { seeks += it })

        composeTestRule.onNodeWithTag("player_progress_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        composeTestRule.waitForIdle()

        assertEquals(
            "SetProgress must land as one seek; got seeks=$seeks",
            1,
            seeks.size
        )
        assertEquals(0.25f, seeks.single(), 0.001f)
    }

    @Test
    fun readOnlyBookRulerNeverSeeksFromADrag() {
        val chapterSeeks = mutableListOf<Float>()
        val bookSeeks = mutableListOf<Float>()
        setPlayerContent(onSeek = { chapterSeeks += it }, onBookSeek = { bookSeeks += it })

        composeTestRule.onNodeWithTag("book_progress_line").performTouchInput {
            down(center)
            moveBy(Offset(width * 0.3f, 0f))
            moveBy(Offset(width * 0.2f, 0f))
            up()
        }
        composeTestRule.waitForIdle()

        assertTrue(
            "the book ruler is read-only; a gesture on it must not seek the chapter: $chapterSeeks",
            chapterSeeks.isEmpty()
        )
        assertTrue(
            "the book ruler is read-only; a gesture on it must not seek the book: $bookSeeks",
            bookSeeks.isEmpty()
        )
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
