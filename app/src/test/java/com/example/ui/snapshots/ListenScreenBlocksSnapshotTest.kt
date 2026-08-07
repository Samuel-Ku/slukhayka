package com.example.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.testing.TestDataFactory
import com.example.ui.screens.ContinueSeriesRow
import com.example.ui.screens.ListenEmptyState
import com.example.ui.screens.ListenHeroCard
import com.example.ui.screens.RecentlyListenedRow
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
 * Snapshot tests for the Слухати tab blocks (spec-9 T3): the hero resume
 * card, a recently-listened row and the fresh-install empty state. Pure
 * `@Composable` inputs — no `MainViewModel`, no Room.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ListenScreenBlocksSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val book = TestDataFactory.dataBooks()[0]
    private val progress = TestDataFactory.seedPlaybackProgress(listOf(book), chapterIndex = 2, positionSeconds = 420L)[0]

    @Test
    fun hero_resume_card() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ListenSurface {
                    ListenHeroCard(
                        book = book,
                        progress = progress,
                        onResumeClick = {},
                        onBookClick = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/listen_hero_card.png"
        )
    }

    @Test
    fun hero_resume_card_light() {
        // Themes ticket (#37): the light scheme must render the migrated
        // reference screen, not just the primitives.
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = false) {
                ListenSurface {
                    ListenHeroCard(
                        book = book,
                        progress = progress,
                        onResumeClick = {},
                        onBookClick = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/listen_hero_card_light.png"
        )
    }

    @Test
    fun recently_listened_row() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ListenSurface {
                    RecentlyListenedRow(
                        book = book,
                        progress = progress,
                        onClick = {},
                        onPlayClick = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/listen_recent_row.png"
        )
    }

    @Test
    fun continue_series_row() {
        val nextVolume = book.copy(
            title = "Наступна книга циклу",
            seriesTitle = "Сага про Дріззта",
            seriesIndex = 3
        )
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ListenSurface {
                    ContinueSeriesRow(
                        seriesTitle = "Сага про Дріззта",
                        book = nextVolume,
                        onClick = {},
                        onPlayClick = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/listen_continue_series.png"
        )
    }

    @Test
    fun empty_state_with_ctas() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ListenSurface {
                    ListenEmptyState(
                        onBrowseClick = {},
                        onImportClick = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/listen_empty_state.png"
        )
    }
}

/** Same chrome as the other snapshot suites: scheme background, full size. */
@Composable
private fun ListenSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) { content() }
    }
}
