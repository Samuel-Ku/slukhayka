package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.ui.screens.WorkFeedFilters
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Public Compose seam for the compact spec-42 T1 feed toolbar and sheet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class WorkFeedToolbarSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun resting_toolbar_has_one_sort_control_and_one_filter_control() {
        var selectedSort: Boolean? = null
        setToolbar(sortByTitle = false, genre = null, onSortChange = { selectedSort = it })

        composeTestRule.onAllNodesWithText("Спочатку нові").assertCountEquals(1)
        composeTestRule.onNodeWithText("За назвою").assertDoesNotExist()
        composeTestRule.onNodeWithText("Фільтри").assertExists()
        composeTestRule.onNodeWithText("Усі джерела").assertDoesNotExist()

        composeTestRule.onNodeWithText("Спочатку нові").performClick()
        composeTestRule.onNodeWithText("За назвою").assertExists().performClick()
        assertEquals(true, selectedSort)
    }

    @Test
    fun filter_sheet_applies_genre_immediately_and_done_only_closes() {
        var genre by mutableStateOf<String?>(null)
        setToolbar(
            sortByTitle = false,
            genre = genre,
            onGenreChange = { genre = it }
        )

        composeTestRule.onNodeWithText("Фільтри").performClick()
        composeTestRule.onNodeWithText("Жанри").assertExists()
        composeTestRule.onNodeWithText("Детективи").performClick()
        assertEquals("Детективи", genre)

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_filter_sheet.png"
        )

        composeTestRule.onNodeWithText("Готово").performClick()
        composeTestRule.onNodeWithText("Скинути все").assertDoesNotExist()
        assertEquals("Детективи", genre)

        composeTestRule.onNodeWithText("Фільтри").performClick()
        composeTestRule.onNodeWithText("Скинути все").performClick()
        assertEquals(null, genre)
    }

    @Test
    fun long_genre_list_scrolls_while_actions_stay_reachable() {
        var genre by mutableStateOf<String?>(null)
        setToolbar(
            sortByTitle = false,
            genre = genre,
            genres = (1..30).map { "Жанр $it" },
            onGenreChange = { genre = it }
        )

        composeTestRule.onNodeWithText("Фільтри").performClick()
        composeTestRule.onNodeWithText("Скинути все").assertIsDisplayed()
        composeTestRule.onNodeWithText("Готово").assertIsDisplayed()
        composeTestRule.onNodeWithText("Жанр 30").performScrollTo().performClick()
        assertEquals("Жанр 30", genre)
        composeTestRule.onNodeWithText("Скинути все").assertIsDisplayed()
        composeTestRule.onNodeWithText("Готово").assertIsDisplayed()
    }

    private fun setToolbar(
        sortByTitle: Boolean,
        genre: String?,
        genres: List<String> = listOf("Фантастика", "Детективи"),
        onGenreChange: (String?) -> Unit = {},
        onSortChange: (Boolean) -> Unit = {}
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WorkFeedFilters(
                        genreFilter = genre,
                        sortByTitle = sortByTitle,
                        genres = genres,
                        onGenreChange = onGenreChange,
                        onSortChange = onSortChange
                    )
                }
            }
        }
    }
}
