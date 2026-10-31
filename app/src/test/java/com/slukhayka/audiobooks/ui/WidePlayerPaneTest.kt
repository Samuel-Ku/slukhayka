package com.slukhayka.audiobooks.ui

import android.annotation.SuppressLint
import android.graphics.Insets
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
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
import kotlin.math.roundToInt

/**
 * #1205 — the two placements of the player, and the mini-player's column, on
 * the REAL composition root.
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

    /**
     * #1711/#1712 — the host Activity the rule composes into. Held so a test
     * can hand Compose the system-bar insets a phone would report; Robolectric
     * draws no real bars, so without this the edge-to-edge contract would only
     * be asserted at zero and pass vacuously.
     */
    private lateinit var hostActivity: ComponentActivity

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
     * The unmerged tree on purpose: the tags this class reads (`player_pane`,
     * `mini_player_bar`) sit on plain layout nodes, and an unmerged lookup finds
     * them whatever their ancestors merge.
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
     *
     * #1711 — [statusBarDp] and [navigationBarDp] hand Compose the insets a
     * phone reports; Robolectric has no real bars, so the edge-to-edge
     * assertions would otherwise test all-zero insets and prove nothing.
     */
    private fun composeRootWithAPausedBook(
        statusBarDp: Int = 0,
        navigationBarDp: Int = 0,
        imeDp: Int = 0
    ): MainViewModel {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)
        val backDispatcher = OnBackPressedDispatcher()

        val audio = app.filesDir.resolve("wide-player-pane.wav")
        audio.writeBytes(silentWav(seconds = 2))

        compose.setContent {
            hostActivity = requireNotNull(LocalContext.current as? ComponentActivity) {
                "the compose test host must be a ComponentActivity, was " +
                    LocalContext.current
            }
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
        if (statusBarDp > 0 || navigationBarDp > 0 || imeDp > 0) {
            applySystemBars(statusBarDp, navigationBarDp, imeDp)
        }
        compose.waitForIdle()
        return viewModel
    }

    /**
     * #1711 — dispatches the system-bar insets a 3-button device reports onto
     * the host window, so the real root composes with them exactly as it does
     * on a device.
     */
    @SuppressLint("NewApi") // The class runs on sdk = [36]; the builder is API 29+.
    private fun applySystemBars(statusBarDp: Int, navigationBarDp: Int, imeDp: Int = 0) {
        compose.runOnUiThread {
            val window = hostActivity.window
            // Production runs edge-to-edge (MainActivity.enableEdgeToEdge());
            // the bare test host must opt in before the insets are dispatched.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val density = hostActivity.resources.displayMetrics.density
            val statusBarPx = (statusBarDp * density).roundToInt()
            val navigationBarPx = (navigationBarDp * density).roundToInt()
            val imePx = (imeDp * density).roundToInt()
            val builder = WindowInsets.Builder()
                .setInsets(
                    WindowInsets.Type.systemBars(),
                    Insets.of(0, statusBarPx, 0, navigationBarPx)
                )
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, statusBarPx, 0, 0))
                .setInsets(
                    WindowInsets.Type.navigationBars(),
                    Insets.of(0, 0, 0, navigationBarPx)
                )
                .setVisible(WindowInsets.Type.statusBars(), true)
                .setVisible(WindowInsets.Type.navigationBars(), true)
            if (imePx > 0) {
                builder
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, imePx))
                    .setVisible(WindowInsets.Type.ime(), true)
            }
            window.decorView.dispatchApplyWindowInsets(builder.build())
        }
        compose.waitForIdle()
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
        // The pane IS the player here: the bar has no slot at all at this width
        // (a strip under the pane would be the full-width layout decision 2
        // moved away from, and would take its ≈76 dp off the pane's height).
        compose.onNodeWithTag("mini_player_bar", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(
            "the pane must have the window's whole height",
            window.bottom.value - window.top.value,
            player.bottom.value - player.top.value,
            1f
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

    /**
     * The figure the owner's decision 3 names on its own: a PHONE IN LANDSCAPE,
     * 905 × 411 dp (OnePlus 8 Pro, measured in #962). It is past the 840 dp line
     * by WIDTH and well under any height line the app might be tempted to add —
     * and there is none: the pane answers by width alone, exactly as the
     * decision says («умови на висоту немає»).
     *
     * The pane is 0.6 × (905 − 80) ≈ 494 dp wide and only 411 dp tall, so this
     * is the tightest window the pane ever gets. Since #1708 the player picks
     * its two-column layout from its OWN width (not the window's), so this
     * pane deliberately gets the ONE-column layout: the two-column split left
     * its control column ~262 dp against the 288 dp transport row, and the
     * targets overlapped by 7–11 dp. That column is still too tall for 411 dp
     * minus the top bar, so it scrolls; the assertion is that every control is
     * LAID OUT (a non-zero box) and reachable inside the window, never clipped
     * off the bottom edge.
     */
    @Test
    @Config(qualifiers = "uk-rUA-w905dp-h411dp-560dpi", sdk = [36])
    fun `at 905x411 the player pane lays out every control inside the window`() {
        val viewModel = composeRootWithAPausedBook()

        compose.runOnIdle { viewModel.setShowFullPlayer(true) }
        awaitTag("player_pane")

        compose.onNodeWithTag("player_pane", useUnmergedTree = true).assertIsDisplayed()
        val window = compose.onRoot().getUnclippedBoundsInRoot()

        listOf(
            "player_play_pause_button",
            "speed_chip",
            "sleep_timer_chip",
            "add_bookmark_chip",
            "chapters_chip"
        ).forEach { tag ->
            // #1708 — this pane is too narrow for two columns and too short
            // for the one-screen portrait column, so the player scrolls: the
            // control is reachable, which is what "laid out inside the pane"
            // means here (and the scroll is the fix, not a workaround).
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            node.performScrollTo()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(
                "$tag was never laid out in the 905 × 411 pane: bounds=" +
                    "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]",
                (bounds.right.value - bounds.left.value) > 0f &&
                    (bounds.bottom.value - bounds.top.value) > 0f
            )
            assertTrue(
                "$tag falls outside the landscape window: bounds=$bounds, window=$window",
                bounds.bottom.value <= window.bottom.value + 1f &&
                    bounds.right.value <= window.right.value + 1f
            )
        }
    }

    /**
     * #1708 — the criterion the 905 × 411 case above did not state: the five
     * transport targets must not COLLIDE in the real pane. The pane is
     * `0.6 × (905 − 80) − 1 dp hairline ≈ 494 dp`; the old window-wide
     * decision still ran the two columns here, left the control column at
     * ~262 dp and `Arrangement.SpaceBetween` drew the 52 dp targets over one
     * another. One column gives the row its 288 dp, so this asserts the
     * geometry rather than only that each target has a box.
     */
    @Test
    @Config(qualifiers = "uk-rUA-w905dp-h411dp-560dpi", sdk = [36])
    fun `the real 494 dp pane keeps the transport targets apart`() {
        val viewModel = composeRootWithAPausedBook()

        compose.runOnIdle { viewModel.setShowFullPlayer(true) }
        awaitTag("player_pane")

        // The player root IS the right-hand pane's content (PlayerPane puts it
        // in WideDetailPane's 0.6 detail slot), and `player_pane` tags the
        // whole content area — so measure the surface the controls live in.
        val pane = compose.onNodeWithTag("full_player_screen", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val order = listOf(
            "player_previous_button",
            "player_rewind_button",
            "player_play_pause_button",
            "player_forward_button",
            "player_next_button"
        )
        val targets = order.map { tag ->
            tag to compose.onNodeWithTag(tag, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        }
        targets.forEach { (tag, bounds) ->
            assertTrue(
                "$tag was never laid out in the 494 dp pane: $bounds",
                bounds.right.value - bounds.left.value > 0f &&
                    bounds.bottom.value - bounds.top.value > 0f
            )
            assertTrue(
                "$tag is outside the pane: $bounds, pane=$pane",
                bounds.left.value >= pane.left.value - 1f &&
                    bounds.right.value <= pane.right.value + 1f
            )
        }
        targets.zipWithNext { (tag, left), (nextTag, right) ->
            assertTrue(
                "the transport targets overlap in the " +
                    "${pane.right.value - pane.left.value} dp pane: " +
                    "$tag ends at ${left.right}, $nextTag starts at ${right.left}",
                right.left.value >= left.right.value - 0.5f
            )
        }
    }

    // ------------------------------------------- the mini-player's slot

    /**
     * #1205 decision 2 — with room beside the content, the bar drops the
     * full-width strip under the rail and sits IN the leading column instead:
     * below the rail, left of the content. The bottom bar is gone there anyway
     * (the rail replaced it), which is what makes this slot the only one.
     */
    @Test
    fun `at 840 dp the mini-player sits in the leading column under the rail`() {
        composeRootWithAPausedBook()

        awaitTag("mini_player_bar")

        compose.onNodeWithTag("bottom_navigation_bar", useUnmergedTree = true)
            .assertDoesNotExist()

        val rail = compose.onNodeWithTag("navigation_rail", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag("mini_player_bar", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val content = compose.onNodeWithTag("library_screen", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "the bar must sit UNDER the rail (rail bottom=${rail.bottom}, " +
                "bar top=${bar.top})",
            bar.top.value >= rail.bottom.value - 1f
        )
        assertTrue(
            "the bar must sit in the leading column, left of the content " +
                "(bar right=${bar.right}, content left=${content.left})",
            bar.right.value <= content.left.value + 1f
        )
        assertTrue("the bar was not laid out: $bar", (bar.bottom.value - bar.top.value) > 0f)
    }

    /**
     * The phone contract, on the same real root: below the line the bar is the
     * full-width strip in the Scaffold's `bottomBar` it has always been, over
     * the bottom navigation bar — nothing about the phone route moved.
     */
    @Test
    @Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
    fun `on a phone the mini-player keeps the bottom strip`() {
        composeRootWithAPausedBook()

        awaitTag("mini_player_bar")

        compose.onNodeWithTag("bottom_navigation_bar", useUnmergedTree = true)
            .assertExists()

        val window = compose.onRoot().getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag("mini_player_bar", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        val windowHeight = window.bottom.value - window.top.value
        assertTrue(
            "the phone bar must stay in the LOWER half of the window, not in a " +
                "leading column: bar top=${bar.top}, window bottom=${window.bottom}",
            bar.top.value > windowHeight / 2f
        )
    }

    // ----------------------------------------------- #1711 the lost inset

    /**
     * #1711 — on EXPANDED the rail replaces the bottom bar, and the Scaffold
     * must still hand the content the navigation-bar inset: the empty
     * `bottomBar` Column used to make bottomBarHeight 0, so the last list line
     * ended up under a 3-button navigation bar. The leading mini-player is
     * outside that padded box and must carry the same inset itself.
     */
    @Test
    fun `expanded content and the leading mini-player end above the navigation bar`() {
        composeRootWithAPausedBook(statusBarDp = 28, navigationBarDp = 48)

        awaitTag("mini_player_bar")

        val window = compose.onRoot().getUnclippedBoundsInRoot()
        val navigationBarTop = window.bottom.value - 48f
        val content = compose.onNodeWithTag("library_screen", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag("mini_player_bar", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertEquals(
            "the injected status-bar inset must reach the content — otherwise " +
                "this test proves nothing",
            28f,
            content.top.value,
            1f
        )
        assertEquals(
            "the last content line must end above the navigation bar " +
                "(content bottom=${content.bottom}, nav top=$navigationBarTop)",
            navigationBarTop,
            content.bottom.value,
            1f
        )
        assertTrue(
            "the leading mini-player must end above the navigation bar " +
                "(bar bottom=${bar.bottom}, nav top=$navigationBarTop)",
            bar.bottom.value <= navigationBarTop + 1f
        )
        assertTrue(
            "the mini-player must sit at the bottom of the column, not float " +
                "away from it: bar bottom=${bar.bottom}, nav top=$navigationBarTop",
            bar.bottom.value >= navigationBarTop - 12f
        )
    }

    /**
     * #1712 — the IME inset reaches the window content: the host's single
     * `imePadding` lifts the whole content above the keyboard instead of
     * letting the focused field sit under it.
     */
    @Test
    fun `expanded content rises above the keyboard`() {
        composeRootWithAPausedBook(statusBarDp = 28, navigationBarDp = 48, imeDp = 300)

        awaitTag("library_screen")

        val window = compose.onRoot().getUnclippedBoundsInRoot()
        val keyboardTop = window.bottom.value - 300f
        val content = compose.onNodeWithTag("library_screen", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "the content must end above the keyboard (content bottom=" +
                "${content.bottom}, keyboard top=$keyboardTop)",
            content.bottom.value <= keyboardTop + 1f
        )
    }

    /**
     * #1711 — the player pane lives INSIDE the host content, which already pays
     * the status-bar inset. Its own `PlayerTopBar` must therefore see that inset
     * as consumed: adding `statusBarsPadding` a second time pushed the header
     * one status bar lower than the pane it belongs to.
     */
    @Test
    fun `the expanded player pane header is not offset twice by the status bar`() {
        val viewModel = composeRootWithAPausedBook(statusBarDp = 28, navigationBarDp = 48)

        compose.runOnIdle { viewModel.setShowFullPlayer(true) }
        awaitTag("player_pane")
        awaitTag("player_top_bar")

        val pane = compose.onNodeWithTag("player_pane", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val topBar = compose.onNodeWithTag("player_top_bar", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertEquals(
            "the pane must start below the injected status bar",
            28f,
            pane.top.value,
            1f
        )
        assertEquals(
            "the player header must not add the status bar a second time " +
                "(pane top=${pane.top}, header top=${topBar.top})",
            pane.top.value,
            topBar.top.value,
            1f
        )
    }
}
