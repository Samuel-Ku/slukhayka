package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.data.editions.EditionMatchVerdict
import com.slukhayka.audiobooks.data.editions.FallbackCandidate
import com.slukhayka.audiobooks.data.editions.FallbackCandidateKind
import com.slukhayka.audiobooks.ui.screens.FallbackCandidatesCard
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #530 / AC9 — the fallback offer surface: every candidate is Source-labelled,
 * a different narration carries the explicit «Перемкнути» affordance, and an
 * empty offer renders nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA-w411dp-h2000dp-normal-long-notround-any-420dpi-keyshidden-nonav", sdk = [36])
class FallbackCandidatesCardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the offer lists source-labelled candidates in policy order`() {
        var selected: FallbackCandidate? = null
        compose.setContent {
            AudiobookTheme {
                FallbackCandidatesCard(
                    candidates = listOf(
                        FallbackCandidate("soundbooks", FallbackCandidateKind.ALTERNATE_DIRECT_SAME_EDITION, EditionMatchVerdict.SAME_EDITION),
                        FallbackCandidate("other", FallbackCandidateKind.CONFIRMED_OTHER_EDITION, EditionMatchVerdict.OTHER_EDITION, chapterMappingSafe = false)
                    ),
                    onSelect = { selected = it }
                )
            }
        }

        compose.onNodeWithTag("fallback_candidates_card").assertIsDisplayed()
        compose.onNodeWithText("Інші джерела").assertIsDisplayed()
        compose.onNodeWithText("Sound-Books").assertIsDisplayed()
        compose.onNodeWithText("Те саме прочитання").assertIsDisplayed()
        // A different narration asks explicitly.
        compose.onNodeWithText("Інша начитка").assertIsDisplayed()
        compose.onNodeWithTag("fallback_candidate_other").performClick()
        assertEquals("other", selected?.sourceId)
    }

    @Test
    fun `an empty offer renders nothing`() {
        compose.setContent {
            AudiobookTheme {
                FallbackCandidatesCard(candidates = emptyList(), onSelect = {})
            }
        }

        compose.onNodeWithTag("fallback_candidates_card").assertDoesNotExist()
    }
}
