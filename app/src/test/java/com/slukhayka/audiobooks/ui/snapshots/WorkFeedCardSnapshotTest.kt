package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.ui.screens.WorkFeedCard
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Snapshot tests for the spec-42 T1 feed card: Source provenance no longer
 * occupies the endless feed card. Pure `@Composable` inputs — no ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class WorkFeedCardSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(
        title: String,
        author: String,
        editionCount: Int
    ) = WorkFeedRow(
        workId = "w-$editionCount",
        mergeKey = title.lowercase(),
        title = title,
        author = author,
        seriesTitle = null,
        seriesIndex = null,
        coverImageUrl = null,
        addedAt = 0L,
        // ADR-0007: the badge counts the Work's SOURCE rows (work_sources).
        sourceCount = editionCount,
        genre = null
    )

    private fun setContent(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        content()
                    }
                }
            }
        }
    }

    @Test
    fun feed_card_never_shows_source_count() {
        setContent {
            WorkFeedCard(
                row = row("Пасажир", "Жан-Крістоф Гранже", editionCount = 2),
                onClick = {}
            )
        }
        composeTestRule.onNodeWithText("2 джерела").assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_no_source_badge.png"
        )
    }

    @Test
    fun feed_card_single_source_no_badge() {
        setContent {
            WorkFeedCard(
                row = row("Пасажир", "Жан-Крістоф Гранже", editionCount = 1),
                onClick = {}
            )
        }
        // A single source renders no badge at all.
        composeTestRule.onNodeWithText("1 джерело").assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_single_source_no_badge.png"
        )
    }

    // Spec-24 T1: the feed card shows the full book duration (Ч:ММ:СС) under
    // the author, and only when the duration is really known — never «0:00».
    @Test
    fun feed_card_known_duration_renders_time() {
        setContent {
            WorkFeedCard(
                row = row("Пасажир", "Жан-Крістоф Гранже", editionCount = 1)
                    .copy(durationSeconds = 60_061L),
                onClick = {}
            )
        }
        // Self-verifying on top of the image: the «16:41:01» line renders.
        composeTestRule.onNodeWithText("16:41:01").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_duration.png"
        )
    }

    @Test
    fun feed_card_materially_different_Editions_render_an_honest_range() {
        setContent {
            WorkFeedCard(
                row = row("Книга з начитками", "Автор", editionCount = 2)
                    .copy(durationSeconds = 10_800L, durationMaxSeconds = 43_200L),
                onClick = {}
            )
        }
        composeTestRule.onNodeWithText("3:00:00–12:00:00").assertExists()
        composeTestRule.onNodeWithText("2 джерела").assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_duration_range.png"
        )
    }

    @Test
    fun feed_card_unknown_duration_renders_nothing() {
        setContent {
            WorkFeedCard(
                row = row("Пасажир", "Жан-Крістоф Гранже", editionCount = 1),
                onClick = {}
            )
        }
        // No duration known — no time line at all (never a fabricated «0:00»).
        composeTestRule.onNodeWithText("16:41:01").assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_no_duration.png"
        )
    }
}
