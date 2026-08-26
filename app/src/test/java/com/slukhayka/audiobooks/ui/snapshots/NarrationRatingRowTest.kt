package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.screens.NarrationRatingRow
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0023 (#348) — the narration-rating row states: honest absence without
 * votes and without a rater, visible average with votes, interactive own
 * stars wired through to the callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NarrationRatingRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(
        average: Double?,
        voteCount: Int,
        ownRating: Int?,
        canRate: Boolean,
        onRate: (Int) -> Unit = {},
        onDeleteOwn: (() -> Unit)? = null
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    NarrationRatingRow(
                        average = average,
                        voteCount = voteCount,
                        ownRating = ownRating,
                        canRate = canRate,
                        onRate = onRate,
                        onDeleteOwn = onDeleteOwn
                    )
                }
            }
        }
    }

    @Test
    fun no_votes_and_no_rater_renders_nothing() {
        setContent(average = null, voteCount = 0, ownRating = null, canRate = false)

        // The row returns early — neither its tag nor any star exists.
        composeTestRule.onNodeWithTag("rating_star_1").assertDoesNotExist()
    }

    @Test
    fun votes_show_the_average_and_count() {
        setContent(average = 3.5, voteCount = 2, ownRating = null, canRate = false)

        composeTestRule.onNodeWithTag("narration_rating_average").assertIsDisplayed()
    }

    @Test
    fun tapping_a_star_reports_that_rating() {
        var reported = -1
        setContent(average = null, voteCount = 0, ownRating = null, canRate = true, onRate = { reported = it })

        composeTestRule.onNodeWithTag("rating_star_4").performClick()

        assertEquals(4, reported)
    }

    // #358: an own rating offers its removal; no own rating — no delete.
    @Test
    fun own_rating_shows_the_delete_action_and_fires_it() {
        var deleted = 0
        setContent(
            average = 4.0,
            voteCount = 3,
            ownRating = 4,
            canRate = true,
            onRate = {},
            onDeleteOwn = { deleted++ }
        )

        composeTestRule.onNodeWithTag("narration_rating_delete").performClick()

        assertEquals(1, deleted)
    }

    @Test
    fun without_own_rating_no_delete_action_exists() {
        setContent(average = 4.0, voteCount = 3, ownRating = null, canRate = true, onRate = {})

        composeTestRule.onNodeWithTag("narration_rating_delete").assertDoesNotExist()
    }
}
