package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.recommend.RecommendationEngine
import com.slukhayka.audiobooks.ui.components.PosterCard
import com.slukhayka.audiobooks.ui.screens.RecommendedBookCard
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Snapshot pins for the spec-19 «Рекомендовано для вас» card: title, author
 * and the explanation chip («схоже на X»). Same Robolectric + roborazzi
 * pattern as the other Огляд row snapshots (CatalogRowsSnapshotTest) — pure
 * `@Composable` input, no `MainViewModel`.
 *
 * v1.4 C4 / #564 — the reason renders through PosterCard's dedicated
 * `reason` slot as the canonical plain MetadataChip; the shelf `caption`
 * slot stays a bare Text (pinned by `ListenScreenBlocksSnapshotTest`). The
 * class pins the `uk-rUA` locale because its text comes from resources:
 * without it the pins silently recorded the English chrome.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class RecommendedBookCardSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun recommended_book_card_with_reason_chip() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                RecommendedSurface {
                    RecommendedBookCard(
                        rec = RecommendationEngine.Recommendation(
                            candidate = RecommendationEngine.Candidate(
                                id = "4read-1234-tini-zabutyh-predkiv",
                                title = "Тіні забутих предків",
                                author = "Михайло Коцюбинський"
                            ),
                            score = 0.82,
                            reasonTitle = "Лісова пісня"
                        ),
                        onClick = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/recommended_book_card.png"
        )
    }

    /**
     * #486 — a «джерело радить» slot: the card wears the per-Source badge
     * (Джерело радить · sound-books) instead of the reason chip — the pick
     * comes from the source's top, not from the listener's profile.
     */
    @Test
    fun source_badge_on_exploration_slot() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                RecommendedSurface {
                    RecommendedBookCard(
                        rec = RecommendationEngine.Recommendation(
                            candidate = RecommendationEngine.Candidate(
                                id = "soundbooks-77-tini-zabutyh-predkiv",
                                title = "Тіні забутих предків",
                                author = "Михайло Коцюбинський"
                            ),
                            score = 0.31,
                            reasonTitle = "Лісова пісня",
                            isExploration = true,
                            sourceLabel = "sound-books"
                        ),
                        onClick = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/recommended_book_card_source_badge.png"
        )
    }

    /**
     * v1.4 C4 / #564 — the `reason` slot in isolation: the real Ukrainian
     * phrase rendered as the canonical plain MetadataChip, with none of the
     * recommendation card's ⋮/status chrome around it. This pin is what makes
     * «reason is a chip» visible on its own; the two card pins above show it
     * in context.
     */
    @Test
    fun reason_slot_is_a_metadata_chip() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                RecommendedSurface {
                    PosterCard(
                        title = "Тіні забутих предків",
                        author = "Михайло Коцюбинський",
                        onClick = {},
                        reason = stringResource(R.string.home_cycle_similar, "Лісова пісня")
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/poster_card_reason_chip.png"
        )
    }
}

/** Same chrome as the other row snapshots: scheme background, full size. */
@Composable
private fun RecommendedSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) { content() }
    }
}
