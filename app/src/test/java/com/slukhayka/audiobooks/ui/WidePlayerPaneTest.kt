package com.slukhayka.audiobooks.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.AudiobookApp
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * #1205 — the two placements of the player on the REAL composition root.
 *
 * The frames in `LargeScreenSnapshotTest` show what the boundary looks like;
 * this class pins the WIRING behind them, which a frame only mirrors: it renders
 * [AudiobookApp] itself, on a window of exactly 840 dp and of exactly 839 dp, so
 * a `rememberShowsPlayerPane(...)` that is read but not used — or a pane drawn
 * on the wrong side of the line — fails here.
 *
 * The window is the only thing that differs between the two player tests: the
 * same book, the same open player, 840 dp versus 839. That is the boundary the
 * owner's decision (issue #1205, 2026-10-10) draws, and there is deliberately no
 * height condition to trip it (decision 3).
 *
 * Audio is a real (silent) local PCM file, the recipe `AchievementNoticeLifecycleTest`
 * uses: preparation then stays off the network, and the player has a book to
 * show without a source fetch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA-w840dp-h1000dp-420dpi", sdk = [36])
class WidePlayerPaneTest {

    @get:Rule
    val compose = createComposeRule()

    private val book = AudiobookEntity(
        id = "wide-pane-book",
        title = "Нейромант",
        author = "Вільям Гібсон",
        narrator = "Олександр Завальський",
        description = "",
        coverDrawableRes = 0,
        genre = "Кіберпанк",
        sourceUrl = "https://example.invalid/book",
        isDownloaded = true,
        totalDurationSeconds = 30L,
        totalChapters = 1
    )
    private val chapter = ChapterEntity(
        id = "wide-pane-chapter",
        bookId = book.id,
        chapterIndex = 0,
        title = "Розділ 1. Зустріч у Чіба-сіті",
        durationSeconds = 30L
    )

    /**
     * The unmerged tree on purpose: the tags this class reads (`player_pane`)
     * sit on plain layout nodes, and an unmerged lookup finds them whatever
     * their ancestors merge.
     */
    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) {
            runCatching {
                compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
    }

    /**
     * The real root, on the LIBRARY tab, with a paused book loaded in the
     * player: the state both the mini-player's bar and the full player read.
     */
    private fun composeRootWithAPausedBook(): MainViewModel {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)
        val backDispatcher = OnBackPressedDispatcher()

        val audio = app.filesDir.resolve("wide-player-pane.wav")
        audio.writeBytes(silentWav(seconds = 2))

        compose.setContent {
            val lifecycleOwner = LocalLifecycleOwner.current
            CompositionLocalProvider(
                LocalOnBackPressedDispatcherOwner provides remember(backDispatcher) {
                    object : OnBackPressedDispatcherOwner {
                        override val onBackPressedDispatcher: OnBackPressedDispatcher =
                            backDispatcher
                        override val lifecycle: Lifecycle = lifecycleOwner.lifecycle
                    }
                }
            ) {
                AudiobookTheme(darkTheme = true) {
                    AudiobookApp(viewModel = viewModel)
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            viewModel.selectTab(SelectedTab.LIBRARY)
            viewModel.playerManager.loadAndPlayBook(
                book = book,
                chapters = listOf(chapter),
                playable = listOf(
                    SourceCatalog.PlayableChapter(
                        chapter = chapter,
                        track = SourceTrackEntity(
                            id = "wide-pane-track",
                            sourceId = "wide-pane-source",
                            trackIndex = 0,
                            url = audio.absolutePath,
                            localFilePath = audio.absolutePath
                        )
                    )
                ),
                autoPlay = false
            )
        }
        compose.waitUntil(20_000) {
            viewModel.playerState.value.currentBook?.id == book.id
        }
        compose.waitForIdle()
        return viewModel
    }

    private fun silentWav(seconds: Int): ByteArray {
        val dataSize = 8_000 * seconds * 2
        val wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1.toShort()).putShort(1.toShort()).putInt(8_000)
            .putInt(16_000).putShort(2.toShort()).putShort(16.toShort())
            .put("data".toByteArray()).putInt(dataSize)
        return wav.array()
    }

    // ------------------------------------------------- the player at 840 dp

    /**
     * The criterion, stated as geometry: on a window past the line the player
     * takes a pane on the RIGHT — it neither starts at the window's left edge
     * nor fills its width — and the content it was opened from is still drawn
     * beside it.
     */
    @Test
    fun `at 840 dp the open player takes the side pane and the content stays`() {
        val viewModel = composeRootWithAPausedBook()

        compose.runOnIdle { viewModel.setShowFullPlayer(true) }
        awaitTag("player_pane")

        compose.onNodeWithTag("player_pane", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("navigation_rail", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("library_screen", useUnmergedTree = true).assertIsDisplayed()

        val window = compose.onRoot().getUnclippedBoundsInRoot()
        val player = compose.onNodeWithTag("full_player_screen", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val content = compose.onNodeWithTag("library_screen", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "the player must not start at the window's left edge: $player",
            player.left.value > 0f
        )
        val windowWidth = window.right.value - window.left.value
        val playerWidth = player.right.value - player.left.value
        assertTrue(
            "the player must NOT replace the screen: its width is " +
                "$playerWidth dp of $windowWidth dp",
            playerWidth < windowWidth
        )
        assertTrue("the player pane was not laid out: $player", playerWidth > 0f)
        assertTrue(
            "the content must keep the leading share beside the player " +
                "(content right=${content.right}, player left=${player.left})",
            content.right.value <= player.left.value + 1f
        )
    }

    /**
     * The same book, the same open player, ONE dp below the line: the player is
     * the full-screen surface it has always been — no pane, and it covers the
     * window it is drawn in. 600–839 dp is exactly where the owner kept it.
     */
    @Test
    @Config(qualifiers = "uk-rUA-w839dp-h1000dp-420dpi", sdk = [36])
    fun `at 839 dp the open player still fills the window`() {
        val viewModel = composeRootWithAPausedBook()

        compose.runOnIdle { viewModel.setShowFullPlayer(true) }
        awaitTag("full_player_screen")

        compose.onNodeWithTag("player_pane", useUnmergedTree = true).assertDoesNotExist()

        val window = compose.onRoot().getUnclippedBoundsInRoot()
        val player = compose.onNodeWithTag("full_player_screen", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertEquals(
            "the full-screen player must start at the window's left edge",
            0f,
            player.left.value,
            1f
        )
        assertEquals(
            "the full-screen player must fill the window",
            window.right.value - window.left.value,
            player.right.value - player.left.value,
            1f
        )
    }
}
