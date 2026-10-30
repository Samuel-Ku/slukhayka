package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.screens.LibrarySavedSectionHeader
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1673 — the ink-theme witness for the «Збережене» section headings.
 *
 * The harness is deliberately the REAL shape of the defect: `PaperInkTheme`
 * provides the ink scheme but does NOT provide `LocalContentColor` (exactly the
 * screen's situation — a plain `Box` on the ink ground, no Material `Surface`
 * to set a content colour). Before the fix both headings inherited the default
 * black and vanished; the snapshot is the pixel proof, the source test pins the
 * decision. Recorded by the `record-snapshots` workflow.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class LibrarySavedHeaderInkSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun saved_section_headings_are_readable_on_the_ink_ground() {
        composeTestRule.setContent {
            PaperInkTheme(darkTheme = true) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    Column {
                        LibrarySavedSectionHeader(
                            text = stringResource(R.string.lib_section_people),
                            testTag = "library_saved_people_header",
                        )
                        LibrarySavedSectionHeader(
                            text = stringResource(R.string.lib_section_bookmarks),
                            testTag = "library_saved_bookmarks_header",
                        )
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("library_saved_people_header").assertExists()
        composeTestRule.onNodeWithTag("library_saved_bookmarks_header").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_saved_section_headers_ink.png"
        )
    }
}
