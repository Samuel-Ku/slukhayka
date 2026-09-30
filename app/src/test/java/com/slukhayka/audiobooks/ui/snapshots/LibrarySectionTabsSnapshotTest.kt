package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.ui.screens.LibrarySectionTabs
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #885 (wave 3) — the library's section tabs, which had NO coverage at all.
 *
 * Found while restyling them: the change moved **zero** goldens, and the reason
 * was not "nothing was touched" but that only the *header* tabs have goldens —
 * `library_tab_*` appeared in `LibraryScreen.kt` and nowhere in `test/`.
 *
 * The prototype's tabs are EQUAL, 48 px tall, with the underline inset 22 %
 * from each side (`:1137-1140`) — i.e. 56 % of the tab. The app had
 * wrap-content tabs with no minimum height and a FIXED 24 dp underline, so the
 * mark bore no relation to the tab it marked. This pins the corrected shape.
 *
 * `LibrarySectionTabs` was made `internal` for this test — visibility only, and
 * consistent with `internal PosterWidth` / `internal SettingsDestination`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class LibrarySectionTabsSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun books_selected_pins_the_equal_tab_switcher() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                TabsSurface {
                    LibrarySectionTabs(
                        booksSelected = true,
                        savedSelected = false,
                        onBooks = {},
                        onShelves = {},
                        onSaved = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("library_tab_books").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_tab_shelves").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_tab_saved").assertIsDisplayed()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_section_tabs.png"
        )
    }

    @Test
    fun shelves_selected_pins_the_moved_underline() {
        // The underline has to FOLLOW the selection, so a second state is
        // pinned rather than assumed from the first.
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                TabsSurface {
                    LibrarySectionTabs(
                        booksSelected = false,
                        savedSelected = false,
                        onBooks = {},
                        onShelves = {},
                        onSaved = {}
                    )
                }
            }
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_section_tabs_shelves.png"
        )
    }

    /** A painted background — an unpainted surface invents defects (#1080). */
    @Composable
    private fun TabsSurface(content: @Composable () -> Unit) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content
        )
    }
}
