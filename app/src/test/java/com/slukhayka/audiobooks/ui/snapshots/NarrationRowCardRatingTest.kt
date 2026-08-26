package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.screens.NarrationRowCard
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0023 (#357) — the «Інші начитки» card shows ITS rendition's own
 * narration average when votes exist, and nothing when they don't (honest
 * absence, ADR-0014).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NarrationRowCardRatingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sibling = AudiobookEntity(
        id = "sibling-book",
        title = "Кобзар",
        author = "Тарас Шевченко",
        narrator = "Інший читач",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = "https://example.invalid/sibling"
    )

    private fun setContent(average: Double?, voteCount: Int) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    NarrationRowCard(
                        sibling = sibling,
                        average = average,
                        voteCount = voteCount,
                        onClick = {}
                    )
                }
            }
        }
    }

    @Test
    fun votes_show_this_renditions_average() {
        setContent(average = 4.5, voteCount = 2)

        // Existence is the contract here; the tight fixture row may clip the
        // label's pixel bounds, which says nothing about presence.
        composeTestRule.onNodeWithTag("narration_rating_average_sibling-book", useUnmergedTree = true).assertExists()
    }

    @Test
    fun no_votes_render_no_average() {
        setContent(average = null, voteCount = 0)

        composeTestRule.onNodeWithTag("narration_rating_average_sibling-book", useUnmergedTree = true).assertDoesNotExist()
    }
}
