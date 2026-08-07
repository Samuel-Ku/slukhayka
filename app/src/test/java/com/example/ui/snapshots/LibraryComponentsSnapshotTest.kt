package com.example.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.testing.TestDataFactory
import com.example.ui.screens.EmptyStateMessage
import com.example.ui.screens.ListeningStatsCard
import com.example.ui.screens.OfflineBookItem
import com.example.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose snapshot tests for the `LibraryScreen` composables.
 *
 * Ticket #5 (`compose-snapshot-infra`) — picks Roborazzi (over Paparazzi)
 * because the test code is already JUnit 4 + Robolectric, so Roborazzi
 * hooks into the existing harness without an emulator or extra Gradle plugin.
 *
 * The full `LibraryScreen` is not snapshotted here because it requires a
 * concrete `MainViewModel` instance, and constructing one in a JVM unit
 * test would drag the real Room database + audio engine + 4read catalogue
 * fetcher into the rendering path. We exercise the three top-level
 * composables that make up the Library tabs instead:
 *
 * - [ListeningStatsCard] -- the Stats tab body, driven by `@Composable`
 *   inputs only.
 * - [OfflineBookItem]    -- one row in the Offline tab, driven by an
 *   `AudiobookEntity` and callbacks.
 * - [EmptyStateMessage]  -- the empty-state messaging for every tab.
 *
 * Every fixture comes from `TestDataFactory`, which is owned by sibling
 * ticket #6 and stays untouched here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class LibraryComponentsSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun stats_card_empty() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibrarySurface {
                    ListeningStatsCard(listeningStats = emptyList(), totalBooks = 0)
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_stats_card_empty.png"
        )
    }

    @Test
    fun stats_card_populated() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibrarySurface {
                    ListeningStatsCard(
                        listeningStats = TestDataFactory.seedListeningStats(),
                        totalBooks = TestDataFactory.BOOK_COUNT
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_stats_card_populated.png"
        )
    }

    @Test
    fun empty_state_no_offline_books() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibrarySurface {
                    EmptyStateMessage(
                        message = "Завантажені аудіокниги відсутні. " +
                            "Додайте їх для прослуховування без інтернету."
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_empty_state_no_offline_books.png"
        )
    }

    @Test
    fun offline_book_item_single_fixture_book() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibrarySurface {
                    OfflineBookItem(
                        book = TestDataFactory.dataBooks()[0],
                        onClick = {},
                        onPlayClick = {},
                        onDeleteClick = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_offline_book_item.png"
        )
    }
}

/**
 * Match the `LibraryScreen` chrome: scheme background, full-size column
 * with the same outer padding the screen would apply. Keeps the snapshot
 * faithful without dragging in `MainViewModel`.
 */
@Composable
private fun LibrarySurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(0.dp)) { content() }
    }
}
