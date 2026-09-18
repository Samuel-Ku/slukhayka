package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.AdaptiveNavigationLayout
import com.slukhayka.audiobooks.AppBottomBarSlot
import com.slukhayka.audiobooks.ui.SelectedTab
import com.slukhayka.audiobooks.ui.adaptive.WindowLayout
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.slukhayka.audiobooks.ui.components.WideDetailPane
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #900 — the frame evidence for the two finished slices: the ≥600 dp breakpoint
 * with the rail that replaces the bottom bar, and the Бібліотека list with the
 * opened book's page beside it.
 *
 * These are JVM Roborazzi renders, NOT device screenshots: `adb devices` had
 * no device attached when this was written, and ADR-0017's on-device check is
 * still owed for both slices. They pin the two navigation surfaces side by
 * side, and the two-pane split, so a review can see what the width changes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class LargeScreenSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Where the frames land.
     *
     * Default: the repository's golden set, exactly where every other
     * `ui/snapshots` test writes. The override exists so a review can drop the
     * ticket's evidence beside its other working frames
     * (`LARGE_SCREEN_SNAPSHOT_DIR=/home/stealth/cmp`) without changing the test.
     */
    private val frameDir: String =
        System.getenv("LARGE_SCREEN_SNAPSHOT_DIR") ?: "src/test/snapshots"

    @Test
    fun large_phone_bottom_bar() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) { NavigationFrame(WindowLayout.COMPACT) }
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "$frameDir/large-phone-bottom-bar.png"
        )
    }

    @Test
    @Config(qualifiers = "uk-rUA-w840dp-h1000dp-420dpi", sdk = [36])
    fun large_wide_window_navigation_rail() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) { NavigationFrame(WindowLayout.EXPANDED) }
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "$frameDir/large-navigation-rail.png"
        )
    }

    @Test
    @Config(qualifiers = "uk-rUA-w840dp-h1000dp-420dpi", sdk = [36])
    fun large_library_two_pane() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WideDetailPane(
                        list = { PanePlaceholder("Список книг", "заглушка") },
                        detail = { PanePlaceholder("Сторінка книги", "заглушка") }
                    )
                }
            }
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "$frameDir/large-library-two-pane.png"
        )
    }

    /**
     * The two-pane split as a FRAME, with both panes deliberately labelled as
     * placeholders: the real [com.slukhayka.audiobooks.ui.screens.LibraryScreen],
     * `GenreScreen`, `SeriesScreen` and `BookDetailScreen` need a ViewModel, and
     * a MainViewModel-composing test belongs to the Room/Robolectric partition,
     * not to the snapshot one. So these pin the SPLIT (list 0.4 left, detail
     * right, hairline between) and nothing about the panes' content.
     */
    @Test
    @Config(qualifiers = "uk-rUA-w840dp-h1000dp-420dpi", sdk = [36])
    fun large_explore_two_pane() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WideDetailPane(
                        list = { PanePlaceholder("Добірка / жанр", "заглушка") },
                        detail = { PanePlaceholder("Картка твору", "заглушка") }
                    )
                }
            }
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "$frameDir/large-explore-two-pane.png"
        )
    }

    @Composable
    private fun PanePlaceholder(title: String, subtitle: String) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    /**
     * The two surfaces as the app wires them: [AppBottomBarSlot] in the
     * Scaffold's bottomBar, [AdaptiveNavigationLayout] in its content. The
     * placeholder stands in for whichever screen the tab is showing — this
     * slice changes the chrome around the screens, not the screens.
     */
    @Composable
    private fun NavigationFrame(layout: WindowLayout) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    AdaptiveNavigationLayout(
                        layout = layout,
                        selectedTab = SelectedTab.EXPLORE,
                        bookDetailOpen = false,
                        onSelect = {}
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Огляд",
                                    style = MaterialTheme.typography.headlineMedium
                                )
                                Text(
                                    text = if (layout == WindowLayout.EXPANDED) {
                                        "840 dp · рейл ліворуч"
                                    } else {
                                        "411 dp · нижня навігація"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                AppBottomBarSlot(
                    layout = layout,
                    selectedTab = SelectedTab.EXPLORE,
                    onSelect = {}
                )
            }
        }
    }
}
