package com.example.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.data.catalog.CatalogBook
import com.example.ui.screens.DurationSection
import com.example.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * spec-18 T3 (#114) — the Огляд «За тривалістю» section UI pin (prior art:
 * ListenScreenBlocksSnapshotTest): both rows render, the section is absent
 * when nothing is bucketed, and a card tap opens the book by its id. The
 * bucketing itself is pinned by the pure JVM DurationBucketsTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class DurationSectionSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun card(id: String) = CatalogBook(
        id = id,
        title = "Книга $id",
        author = "Автор",
        url = "https://4read.org/$id.html",
        coverImageUrl = null
    )

    @Test
    fun short_row_renders() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    Column(modifier = Modifier.padding(16.dp)) {
                        DurationSection(
                            shortBooks = listOf(card("s1"), card("s2"), card("s3")),
                            longBooks = emptyList(),
                            onBookClick = {}
                        )
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("duration_section").assertExists()
        composeTestRule.onNodeWithTag("duration_short_row").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/duration_short_row.png"
        )
    }

    @Test
    fun both_rows_render() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    Column(modifier = Modifier.padding(16.dp)) {
                        DurationSection(
                            shortBooks = listOf(card("s1"), card("s2"), card("s3")),
                            longBooks = listOf(card("l1"), card("l2")),
                            onBookClick = {}
                        )
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("duration_short_row").assertExists()
        composeTestRule.onNodeWithTag("duration_long_row").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/duration_both_rows.png"
        )
    }

    @Test
    fun hides_when_nothing_is_bucketed() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    DurationSection(shortBooks = emptyList(), longBooks = emptyList(), onBookClick = {})
                }
            }
        }
        composeTestRule.onNodeWithTag("duration_section").assertDoesNotExist()
    }

    @Test
    fun tapping_a_card_opens_that_book() {
        var clickedId: String? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    Column(modifier = Modifier.padding(16.dp)) {
                        DurationSection(
                            shortBooks = listOf(card("s1"), card("s2")),
                            longBooks = emptyList(),
                            onBookClick = { clickedId = it }
                        )
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("catalog_book_s2").performClick()
        assertEquals("s2", clickedId)
    }
}