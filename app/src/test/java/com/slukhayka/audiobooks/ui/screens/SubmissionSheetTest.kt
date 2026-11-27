package com.slukhayka.audiobooks.ui.screens

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubmissionSheetTest {

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
}
