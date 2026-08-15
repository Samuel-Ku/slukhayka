package com.example.ui.snapshots

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
import com.example.data.db.WorkFeedRow
import com.example.ui.screens.WorkFeedCard
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
 * Snapshot tests for the spec-23 T4/T5 feed card: the merged Work card shows
 * a compact «N джерел» badge when more than one source carries the Work
 * (T5), and stays quiet for a single source. Pure `@Composable` inputs — no
 * `MainViewModel`.
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
        narrator = "",
        seriesTitle = null,
        seriesIndex = null,
        coverImageUrl = null,
        addedAt = 0L,
        editionCount = editionCount,
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
    fun feed_card_two_sources_shows_badge() {
        setContent {
            WorkFeedCard(
                row = row("Пасажир", "Жан-Крістоф Гранже", editionCount = 2),
                onClick = {}
            )
        }
        // Self-verifying on top of the image: the «2 джерела» pill renders.
        composeTestRule.onNodeWithText("2 джерела").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_two_sources_badge.png"
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
}
