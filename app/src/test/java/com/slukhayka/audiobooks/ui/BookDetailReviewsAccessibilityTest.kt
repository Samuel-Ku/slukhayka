package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.slukhayka.audiobooks.data.reviews.ListenerReview
import com.slukhayka.audiobooks.ui.screens.ListenerReviewFormSheet
import com.slukhayka.audiobooks.ui.screens.ReviewCard
import com.slukhayka.audiobooks.ui.screens.ReviewDeleteConfirmation
import com.slukhayka.audiobooks.ui.screens.ReviewDeleteConfirmationOwner
import com.slukhayka.audiobooks.ui.screens.ReviewStarsRow
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BookDetailReviewsAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val review = ListenerReview(
        workId = "work-1",
        uid = "uid-1",
        authorName = "Читачка Леся",
        rating = 4,
        body = "Дуже уважна начитка.",
        editionTag = "Валерій Завалко",
        createdAt = 1_755_000_000_000L
    )

    private val radioButtonMatcher = SemanticsMatcher("RadioButton role") { node ->
        node.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton
    }

    @Test
    fun readOnlyRatingIsOneSpokenSummaryInsteadOfFiveIcons() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ReviewStarsRow(rating = 4)
            }
        }

        composeTestRule.onNodeWithContentDescription("4 із 5")
            .assertExists()
        composeTestRule.onAllNodes(radioButtonMatcher, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun everyInteractiveStarIsA48DpSelectableRadioButton() {
        composeTestRule.setContent {
            var rating by remember { mutableIntStateOf(0) }
            AudiobookTheme(darkTheme = true) {
                ReviewStarsRow(
                    rating = rating,
                    interactive = true,
                    onRatingChange = { rating = it }
                )
            }
        }

        composeTestRule.onAllNodes(radioButtonMatcher, useUnmergedTree = true)
            .assertCountEquals(5)
        (1..5).forEach { value ->
            composeTestRule.onNodeWithContentDescription("$value із 5")
                .assertHeightIsAtLeast(48.dp)
                .performClick()
                .assertIsSelected()
            (1..5).filterNot { it == value }.forEach { other ->
                composeTestRule.onNodeWithContentDescription("$other із 5")
                    .assertIsNotSelected()
            }
        }
    }

    @Test
    fun formRequiresRatingSavesOnceAndAnnouncesErrorPolitely() {
        var saveCount = 0
        var savedRating = 0
        val error = "Не вдалося зберегти відгук. Спробуйте ще раз."
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    ListenerReviewFormSheet(
                        bookTitle = "Трохи ненависті",
                        editing = null,
                        editionOptions = emptyList(),
                        defaultEditionTag = "",
                        isSaving = false,
                        errorMessage = error,
                        onSave = { rating, _, _ ->
                            saveCount += 1
                            savedRating = rating
                        },
                        onDismiss = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("review_form_save")
            .assertIsNotEnabled()
        composeTestRule.onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.PaneTitle,
                "Відгук про «Трохи ненависті»"
            ),
            useUnmergedTree = true
        ).assertCountEquals(1)
        composeTestRule.onNodeWithText("Ваш відгук")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeTestRule.onNodeWithContentDescription("3 із 5")
            .performClick()
        composeTestRule.onNodeWithTag("review_form_save")
            .assertIsEnabled()
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithText(error)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite
                )
            )

        assertEquals(1, saveCount)
        assertEquals(3, savedRating)
    }

    @Test
    fun ownReviewActionsNameTheWorkAndReview() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ReviewCard(
                    review = review,
                    workTitle = "Трохи ненависті",
                    isOwn = true,
                    isPending = false
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(
            "Змінити ваш відгук про «Трохи ненависті», оцінка 4 із 5"
        ).assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription(
            "Видалити ваш відгук про «Трохи ненависті», оцінка 4 із 5"
        ).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun otherReviewMenuNamesAuthorAndHideAction() {
        var hidden = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ReviewCard(
                    review = review,
                    workTitle = "Трохи ненависті",
                    isOwn = false,
                    isPending = false,
                    onHideAuthor = { hidden = true }
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Дії з відгуком автора Читачка Леся")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeTestRule.onNodeWithText("Приховувати відгуки автора Читачка Леся")
            .performClick()
        assertTrue(hidden)
    }

    @Test
    fun destructiveConfirmationNamesExactReview() {
        var confirmed = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ReviewDeleteConfirmation(
                    workTitle = "Трохи ненависті",
                    review = review,
                    onConfirm = { confirmed = true },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithText(
            "Буде видалено ваш відгук про «Трохи ненависті»: оцінка 4 із 5 і текст відгуку. Дію не можна скасувати."
        ).assertExists()
        composeTestRule.onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.PaneTitle,
                "Видалення відгуку"
            ),
            useUnmergedTree = true
        ).assertCountEquals(1)
        composeTestRule.onNodeWithText("Видалити відгук?")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeTestRule.onNodeWithTag("review_delete_title")
            .assertIsFocused()
        composeTestRule.onNodeWithText("Видалити").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun confirmationOwnerRestoresFocusToDeleteActionAfterDismiss() {
        composeTestRule.setContent {
            var target by remember { mutableStateOf<ListenerReview?>(null) }
            val deleteFocusRequester = remember { FocusRequester() }
            AudiobookTheme(darkTheme = true) {
                Column {
                    Button(
                        onClick = { target = review },
                        modifier = Modifier
                            .focusRequester(deleteFocusRequester)
                            .testTag("review_delete_origin")
                    ) {
                        Text("Видалити свій відгук")
                    }
                    ReviewDeleteConfirmationOwner(
                        workTitle = "Трохи ненависті",
                        review = target,
                        returnFocusRequester = deleteFocusRequester,
                        onConfirm = { target = null },
                        onDismiss = { target = null }
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("review_delete_origin")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performClick()
        composeTestRule.onNodeWithTag("review_delete_title")
            .assertIsFocused()
        composeTestRule.onNodeWithText("Скасувати")
            .performClick()
        composeTestRule.onNodeWithTag("review_delete_origin")
            .assertIsFocused()
            .performClick()
        composeTestRule.onNodeWithTag("review_delete_title")
            .assertIsFocused()
        composeTestRule.onNodeWithTag("review_delete_confirm")
            .performClick()
        composeTestRule.onNodeWithTag("review_delete_origin")
            .assertIsFocused()
    }

    @Test
    fun reviewControlsRemainReachableAtTwoHundredPercentFontScale() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                    Surface {
                        Column(modifier = Modifier.width(320.dp)) {
                            ReviewStarsRow(rating = 2, interactive = true)
                            ReviewCard(
                                review = review,
                                workTitle = "Трохи ненависті",
                                isOwn = true,
                                isPending = false
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onAllNodes(radioButtonMatcher, useUnmergedTree = true)
            .assertCountEquals(5)
        composeTestRule.onNodeWithContentDescription(
            "Змінити ваш відгук про «Трохи ненависті», оцінка 4 із 5"
        ).assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription(
            "Видалити ваш відгук про «Трохи ненависті», оцінка 4 із 5"
        ).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun reviewFormReflowsAndRemainsScrollableAtTwoHundredPercentFontScale() {
        var saved = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                    ListenerReviewFormSheet(
                        bookTitle = "Трохи ненависті",
                        editing = null,
                        editionOptions = listOf("Валерій Завалко"),
                        defaultEditionTag = "Валерій Завалко",
                        onSave = { _, _, _ -> saved = true },
                        onDismiss = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("5 із 5")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeTestRule.onNodeWithTag("review_form_save")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertTrue(saved)
    }

    @Test
    fun saveOutcomeDistinguishesPublishedQueuedAndFailure() {
        assertEquals(ReviewSaveResult.PUBLISHED, reviewSaveResultFor(accepted = true, online = true))
        assertEquals(ReviewSaveResult.QUEUED, reviewSaveResultFor(accepted = true, online = false))
        assertEquals(ReviewSaveResult.FAILED, reviewSaveResultFor(accepted = false, online = true))
        assertEquals(ReviewSaveResult.FAILED, reviewSaveResultFor(accepted = false, online = false))
    }

    @Test
    fun reviewRefreshUsesWorkIdentityAndFallsBackToEditionIdentity() {
        assertEquals("work-1", reviewWorkIdFor(editionId = "edition-1", workId = "work-1"))
        assertEquals("edition-1", reviewWorkIdFor(editionId = "edition-1", workId = null))
        assertEquals("edition-1", reviewWorkIdFor(editionId = "edition-1", workId = "  "))
    }
}
