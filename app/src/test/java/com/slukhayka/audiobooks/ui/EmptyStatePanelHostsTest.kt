package com.slukhayka.audiobooks.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.screens.LibraryEmptyState
import com.slukhayka.audiobooks.ui.screens.LibraryRatingEmptyState
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * #1766 review — the two migrated empty states that had NO golden and no render
 * test: «Медіатека» and «Рейтинг».
 *
 * Both surfaces are reachable without a `MainViewModel`: [LibraryEmptyState] is
 * the public composable the library screen drops into its empty branch, and the
 * rating branch was extracted as [LibraryRatingEmptyState] for exactly this
 * reason. The tests pin the anatomy (title/body, ONE primary action + one quiet
 * link on the library; nothing to press on an empty ranked board) and the
 * anchoring half of the contract: the caller hands the panel a height and the
 * panel's column centres in it — here with the same `weight(1f)` the library
 * screen uses under its chrome.
 *
 * Not covered, named honestly: the full-screen RANKING integration (the
 * `LibraryRatingScreen` item that passes `fillParentMaxSize()`) still needs a
 * `MainViewModel`; this file covers the branch, not that one line of wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "uk-rUA-w411dp-h891dp")
class EmptyStatePanelHostsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun string(id: Int): String = context.getString(id)

    private fun centreInRoot(tag: String): Float {
        val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        return (bounds.top.value + bounds.bottom.value) / 2f
    }

    @Test
    fun `the library empty state renders one action, one quiet link and no glyph`() {
        var imported = false
        var browsed = false
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LibraryEmptyState(
                        onImportClick = { imported = true },
                        onBrowseClick = { browsed = true }
                    )
                }
            }
        }

        compose.onNodeWithText(string(R.string.library_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_empty_body)).assertIsDisplayed()
        compose.onNodeWithTag("library_empty_import").assertIsDisplayed().performClick()
        compose.onNodeWithTag("library_empty_browse").assertIsDisplayed().performClick()
        assertTrue("the import action must survive the migration", imported)
        assertTrue("the catalogue path must survive as the quiet link", browsed)
        // One primary button + one quiet link, no third CTA.
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
    }

    @Test
    fun `the library empty state centres in the height its host leaves`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("library_empty_host")
                    ) {
                        // Stand-in for the real header + chrome band: the contract
                        // under test is that the panel takes the REST of the
                        // height (`weight(1f)`, exactly what LibraryScreen
                        // passes), not a fixed pad under the chrome.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        )
                        LibraryEmptyState(
                            onImportClick = {},
                            onBrowseClick = {},
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    }
                }
            }
        }

        val host = compose.onNodeWithTag("library_empty_host").getUnclippedBoundsInRoot()
        val expected = (host.top.value + 200f + host.bottom.value) / 2f
        assertEquals(
            "the panel must be centred in the height the library host gives it",
            expected,
            centreInRoot("empty_state_panel"),
            2f
        )
    }

    @Test
    fun `the empty ranking board states the absence and offers nothing to press`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("rating_empty_host")
                    ) {
                        LibraryRatingEmptyState(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        compose.onNodeWithText(string(R.string.secondary_library_rating_empty))
            .assertIsDisplayed()
        // An empty board has no vote to show and no action to offer.
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)

        val host = compose.onNodeWithTag("rating_empty_host").getUnclippedBoundsInRoot()
        val hostCentre = (host.top.value + host.bottom.value) / 2f
        assertTrue(
            "the empty board must be centred in its list viewport",
            abs(centreInRoot("empty_state_panel") - hostCentre) < 2f
        )
    }
}
