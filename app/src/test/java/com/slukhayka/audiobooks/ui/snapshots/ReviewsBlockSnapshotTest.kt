package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.data.reviews.ListenerReview
import com.slukhayka.audiobooks.ui.screens.ListenerReviewFormSheet
import com.slukhayka.audiobooks.ui.screens.ReviewCard
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Snapshot tests for the spec-40 «Відгуки» block states: a published card
 * (stars ABOVE text, nickname, date, «Начитка: …» chip — #277/#278), a
 * minimal card whose empty tag renders nothing, and the honest pending
 * state of an offline-written review («надішлемо при мережі» — #280), plus
 * the write form. Pure `@Composable` inputs, no ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ReviewsBlockSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val review = ListenerReview(
        workId = "work-1",
        uid = "uid-1",
        authorName = "Читач_Олег",
        rating = 4,
        body = "Чудова начитка — слухав не відриваючись усю дорогу.",
        editionTag = "Валерій Завалко",
        createdAt = 1_755_000_000_000L
    )

    @Test
    fun review_card_full_with_edition_tag() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ReviewsSurface {
                    ReviewCard(review = review, isOwn = false, isPending = false)
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/review_card_full.png"
        )
    }

    @Test
    fun review_card_minimal_empty_tag_renders_nothing() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ReviewsSurface {
                    ReviewCard(
                        review = review.copy(uid = "uid-2", body = null, editionTag = null),
                        isOwn = false,
                        isPending = false
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/review_card_minimal.png"
        )
    }

    @Test
    fun review_card_pending_offline_write() {
        // #280 — the honest state of an offline-written review: visible,
        // but never pretending to be published.
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ReviewsSurface {
                    ReviewCard(review = review.copy(uid = "uid-3"), isOwn = false, isPending = true)
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/review_card_pending.png"
        )
    }

    @Test
    fun review_form_sheet_with_counter_and_dropdown() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ListenerReviewFormSheet(
                        bookTitle = "Собор Паризької Богоматері",
                        editing = null,
                        editionOptions = listOf("Валерій Завалко", "Дмитро Кузьменко"),
                        defaultEditionTag = "Валерій Завалко",
                        onSave = { _, _, _ -> },
                        onDismiss = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/review_form.png"
        )
    }
}

/** Same chrome as the other block-snapshot tests. */
@Composable
private fun ReviewsSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.padding(16.dp)) {
            Text("Відгуки", style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
