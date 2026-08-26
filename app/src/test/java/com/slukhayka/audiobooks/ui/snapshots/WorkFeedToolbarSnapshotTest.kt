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
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.ui.screens.WorkFeedFilters
import com.slukhayka.audiobooks.data.db.GenreFacetOption
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
        setToolbar(sortByTitle = false, selectedGenres = { emptySet() }, onSortChange = { selectedSort = it })

        composeTestRule.onAllNodesWithText("Спочатку нові").assertCountEquals(1)
        composeTestRule.onNodeWithText("За назвою").assertDoesNotExist()
        composeTestRule.onNodeWithText("Фільтри").assertExists()
        composeTestRule.onNodeWithText("Усі джерела").assertDoesNotExist()

        composeTestRule.onNodeWithText("Спочатку нові").performClick()
        composeTestRule.onNodeWithText("За назвою").assertExists().performClick()
        assertEquals(true, selectedSort)
    }

    @Test
    fun filter_sheet_applies_multi_genre_toggle_immediately_and_reset_clears_all() {
        var genres by mutableStateOf<Set<String>>(emptySet())
        setToolbar(
            sortByTitle = false,
            selectedGenres = { genres },
            onGenresChange = { genres = it }
        )

        composeTestRule.onNodeWithText("Фільтри").performClick()
        composeTestRule.onNodeWithText("Жанри").assertExists()
        composeTestRule.onNodeWithText("Фентезі").assertIsNotSelected().performClick().assertIsSelected()
        composeTestRule.onNodeWithText("Детективи").assertIsNotSelected().performClick().assertIsSelected()
        assertEquals(setOf("fantasy", "detective"), genres)

        composeTestRule.onNodeWithText("Фентезі").performClick().assertIsNotSelected()
        assertEquals(setOf("detective"), genres)

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_filter_sheet.png"
        )

        composeTestRule.onNodeWithText("Готово").performClick()
        composeTestRule.onNodeWithText("Скинути все").assertDoesNotExist()
        assertEquals(setOf("detective"), genres)

        composeTestRule.onNodeWithText("Фільтри").performClick()
        composeTestRule.onNodeWithText("Скинути все").performClick()
        assertEquals(emptySet<String>(), genres)
    }

    @Test
    fun long_genre_list_scrolls_while_actions_stay_reachable() {
        var selectedGenres by mutableStateOf<Set<String>>(emptySet())
        setToolbar(
            sortByTitle = false,
            selectedGenres = { selectedGenres },
            genres = (1..30).map { GenreFacetOption("genre-$it", "Жанр $it", 1) },
            onGenresChange = { selectedGenres = it }
        )

        composeTestRule.onNodeWithText("Фільтри").performClick()
        composeTestRule.onNodeWithText("Скинути все").assertIsDisplayed()
        composeTestRule.onNodeWithText("Готово").assertIsDisplayed()
        composeTestRule.onNodeWithText("Жанр 30").performScrollTo().performClick()
        assertEquals(setOf("genre-30"), selectedGenres)
        composeTestRule.onNodeWithText("Скинути все").assertIsDisplayed()
        composeTestRule.onNodeWithText("Готово").assertIsDisplayed()
    }

    private fun setToolbar(
        sortByTitle: Boolean,
        selectedGenres: () -> Set<String>,
        genres: List<GenreFacetOption> = listOf(
            GenreFacetOption("fantasy", "Фентезі", 1),
            GenreFacetOption("detective", "Детективи", 1)
        ),
        onGenresChange: (Set<String>) -> Unit = {},
        onSortChange: (Boolean) -> Unit = {}
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WorkFeedFilters(
                        selectedGenreIds = selectedGenres(),
                        sortByTitle = sortByTitle,
                        genres = genres,
                        onGenresChange = onGenresChange,
                        onSortChange = onSortChange
                    )
                }
            }
        }
    }
}
