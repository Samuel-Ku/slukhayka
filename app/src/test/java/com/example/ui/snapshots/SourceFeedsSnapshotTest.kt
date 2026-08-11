package com.example.ui.snapshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.data.repository.AudiobookRepository.SourceNewFeed
import com.example.data.source.SourceBook
import com.example.ui.screens.SourceFeedRow
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
 * Snapshot tests for the spec-10 T5 «Нове з кожного джерела» feed row:
 * the section header plus the horizontal book-card row. Pure-`@Composable`
 * inputs from fixture data — no `MainViewModel`, no network.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class SourceFeedsSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun book(title: String) = SourceBook(
        title = title,
        author = "Автор",
        url = "https://sound-books.net/${title.hashCode()}.html",
        sourceId = "soundbooks"
    )

    private val feed = SourceNewFeed(
        sourceId = "soundbooks",
        sourceName = "Sound-Books",
        books = listOf(book("Темна матерія"), book("Наслідок"), book("Інший світ"))
    )

    @Test
    fun feed_row_renders_header_and_cards() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                SourceFeedsSurface {
                    SourceFeedRow(feed = feed, onBookClick = {})
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/source_feed_row.png"
        )
    }
}

/** Same chrome as the other snapshot tests: scheme background, full size. */
@Composable
private fun SourceFeedsSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        content()
    }
}
