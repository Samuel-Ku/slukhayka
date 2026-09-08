package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.data.authors.AuthorSummary
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.personbookmarks.PersonBookmarks
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import com.slukhayka.audiobooks.ui.screens.AuthorSearchResults
import com.slukhayka.audiobooks.ui.screens.AuthorsIndexScreen
import com.slukhayka.audiobooks.ui.screens.CanonicalAuthorScreen
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class AuthorDiscoverySnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val authors = (1..6).map { index ->
        AuthorSummary("author-$index", "Автор $index", "автор $index", index)
    }

    @Test
    fun author_results_are_first_bounded_to_five_and_open_overflow() {
        var opened: String? = null
        var allOpened = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column {
                        AuthorSearchResults(authors, onAuthorClick = { opened = it.id }, onShowAll = { allOpened = true })
                        Text("У вашій медіатеці")
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Автор 1").performClick()
        assertEquals("author-1", opened)
        composeTestRule.onNodeWithText("Автор 6").assertDoesNotExist()
        composeTestRule.onNodeWithText("Усі знайдені автори").performClick()
        assertTrue(allOpened)
        val authorTop = composeTestRule.onNodeWithText("Автори").fetchSemanticsNode().boundsInRoot.top
        val libraryTop = composeTestRule.onNodeWithText("У вашій медіатеці").fetchSemanticsNode().boundsInRoot.top
        assertTrue(authorTop < libraryTop)
        composeTestRule.onRoot().captureRoboImage("src/test/snapshots/author_search_results.png")
    }

    @Test
    fun no_author_match_leaves_book_search_without_an_empty_author_section() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Column {
                    AuthorSearchResults(emptyList(), onAuthorClick = {}, onShowAll = {})
                    Text("У вашій медіатеці")
                }
            }
        }

        composeTestRule.onNodeWithTag("author_search_results").assertDoesNotExist()
        composeTestRule.onNodeWithText("У вашій медіатеці").assertExists()
    }

    @Test
    fun full_author_index_and_canonical_page_show_truthful_counts() {
        val works = listOf(
            WorkEntity("w2", "w2", "Бояриня", "Леся Українка", addedAt = 2),
            WorkEntity("w1", "w1", "Лісова пісня", "Леся Українка", addedAt = 1)
        )
        // v1.4 E6 (ADR-0033): the counts ride the canonical scaffold's
        // subtitle — compose the real screens, not the bare content.
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column {
                        Box(Modifier.height(360.dp)) {
                            AuthorsIndexScreen(authors = authors, onBackClick = {}, onAuthorClick = {})
                        }
                        Box(Modifier.height(360.dp)) {
                            CanonicalAuthorScreen(
                                author = authors.first().copy(displayName = "Леся Українка", workCount = 2),
                                works = works,
                                isLoading = false,
                                loadFailed = false,
                                onBackClick = {},
                                onWorkClick = {},
                                personBookmarks = PersonBookmarks(FakeAudiobookDao())
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("6 авторів").assertExists()
        // The author index's subtitle is unique; the canonical page's «2 книги»
        // also legitimately matches «Автор 2»'s per-row count — assert both
        // (the header subtitle + the honest per-row work count).
        composeTestRule.onAllNodesWithText("2 книги").assertCountEquals(2)
        composeTestRule.onNodeWithText("Бояриня").assertExists()
        composeTestRule.onNodeWithText("Лісова пісня").assertExists()
        composeTestRule.onRoot().captureRoboImage("src/test/snapshots/canonical_author_page.png")
    }

    @Test
    fun empty_canonical_page_does_not_invent_a_work_count() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CanonicalAuthorScreen(
                    author = authors.first().copy(workCount = 0),
                    works = emptyList(),
                    isLoading = false,
                    loadFailed = false,
                    onBackClick = {},
                    onWorkClick = {},
                    personBookmarks = PersonBookmarks(FakeAudiobookDao())
                )
            }
        }

        composeTestRule.onNodeWithText("Книг цього автора поки немає в каталозі.").assertExists()
        composeTestRule.onNodeWithText("0 книг").assertDoesNotExist()
    }

    @Test
    fun canonical_failure_state_does_not_invent_work_count() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CanonicalAuthorScreen(
                    author = authors.first().copy(workCount = 12),
                    works = emptyList(),
                    isLoading = false,
                    loadFailed = true,
                    onBackClick = {},
                    onWorkClick = {},
                    personBookmarks = PersonBookmarks(FakeAudiobookDao())
                )
            }
        }

        composeTestRule.onNodeWithText("Не вдалося відкрити книги автора. Спробуйте ще раз.").assertExists()
        composeTestRule.onNodeWithText("12 книг").assertDoesNotExist()
    }
}
