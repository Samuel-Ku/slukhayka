package com.slukhayka.audiobooks.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.slukhayka.audiobooks.data.ingest.ListenerSubmissionFlow
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1823 — the status is announced, and a refusal is not neutral. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubmissionStatusAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(state: SubmissionUiState) {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                SubmissionSheetContent(
                    state = state,
                    remainingToday = null,
                    onSubmit = {},
                    includePaneSemantics = false
                )
            }
        }
    }

    /**
     * #1823 — the real shape of the screen is a transition, not a single state:
     * Working first, then the terminal result. A live region only earns its
     * announcement when that result arrives, so each case starts from Working
     * and moves forward while the node stays in the tree.
     */
    private fun renderTransitionTo(result: SubmissionUiState) {
        var state by mutableStateOf<SubmissionUiState>(SubmissionUiState.Working)
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                SubmissionSheetContent(
                    state = state,
                    remainingToday = null,
                    onSubmit = {},
                    includePaneSemantics = false
                )
            }
        }
        compose.onNodeWithTag("submission_status")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.runOnIdle { state = result }
        compose.waitForIdle()
        compose.onNodeWithTag("submission_status")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite
                )
            )
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }

    @Test
    fun `a refused submission is a polite live region with error semantics`() {
        render(SubmissionUiState.Refused(ListenerSubmissionFlow.Reason.REJECTED))

        compose.onNodeWithTag("submission_status")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite
                )
            )
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test
    fun `working is not announced as a live region`() {
        render(SubmissionUiState.Working)

        compose.onNodeWithTag("submission_status")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `working to published announces the result politely and not as an error`() {
        renderTransitionTo(SubmissionUiState.Published)
    }

    @Test
    fun `working to pending moderation announces the result politely and not as an error`() {
        renderTransitionTo(SubmissionUiState.PendingModeration)
    }
}
