package com.slukhayka.audiobooks.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1628 — the rendering half of the size line.
 *
 * The pure `bookDetailSizeLine` test pins the DECISION, but a fallback can
 * still be reintroduced at the render site while the pure test stays green
 * (the review's first mutation proved exactly that). This test pins the
 * absence: `Hidden` draws no text at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "uk-rUA")
class BookDetailSizeTextTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `hidden renders no unknown text and no size line`() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) { BookDetailSizeText(BookDetailSizeLine.Hidden) }
        }

        composeTestRule.onNodeWithText("невідомо").assertDoesNotExist()
        composeTestRule.onNodeWithText("МБ", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a real estimate renders its megabytes`() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                BookDetailSizeText(BookDetailSizeLine.Estimated(mb = 120L, approximate = false))
            }
        }

        composeTestRule.onNodeWithText("120 МБ").assertExists()
        composeTestRule.onNodeWithText("невідомо").assertDoesNotExist()
    }

    @Test
    fun `live progress renders its chapters line`() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                BookDetailSizeText(
                    BookDetailSizeLine.Downloading(
                        completedChapters = 2,
                        totalChapters = 10,
                        downloadedBytes = 3L * 1024L * 1024L,
                        totalBytes = 15L * 1024L * 1024L
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("МБ", substring = true).assertExists()
        composeTestRule.onNodeWithText("невідомо").assertDoesNotExist()
    }
}
