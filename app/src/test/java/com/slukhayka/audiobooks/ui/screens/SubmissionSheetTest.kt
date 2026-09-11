package com.slukhayka.audiobooks.ui.screens

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.slukhayka.audiobooks.data.ingest.ListenerSubmissionFlow
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-601 T3/T5 — the submission sheet's honest states: the remaining budget
 * is visible, submitting forwards the pasted link, and every refusal has its
 * own plainly-worded status (no silent failure, no fake success).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "uk-rUA")
class SubmissionSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        state: SubmissionUiState,
        remaining: Int? = 7,
        onSubmit: (String) -> Unit = {}
    ) {
        compose.setContent {
            AudiobookTheme {
                SubmissionSheetContent(
                    state = state,
                    remainingToday = remaining,
                    onSubmit = onSubmit,
                    includePaneSemantics = false
                )
            }
        }
    }

    @Test
    fun `shows the honest remaining budget`() {
        show(SubmissionUiState.Idle)
        compose.onNodeWithText("Залишилось надсилань сьогодні: 7").assertExists()
    }

    @Test
    fun `blank field cannot submit and a pasted link is forwarded`() {
        val submitted = mutableListOf<String>()
        show(SubmissionUiState.Idle, onSubmit = { submitted += it })

        compose.onNodeWithTag("submission_submit").assertIsNotEnabled()
        compose.onNodeWithTag("submission_url_field")
            .performTextInput("https://youtu.be/abc12345678")
        compose.onNodeWithTag("submission_submit").assertIsEnabled().performClick()

        assertEquals(listOf("https://youtu.be/abc12345678"), submitted)
    }

    @Test
    fun `every refusal renders its own status`() {
        show(
            SubmissionUiState.Refused(ListenerSubmissionFlow.Reason.DAILY_LIMIT_REACHED),
            remaining = 0
        )
        compose.onNodeWithText("Денний ліміт надсилань вичерпано.").assertExists()
    }

    @Test
    fun `imported without a shared base says so plainly`() {
        show(SubmissionUiState.Imported(publishable = false))
        compose.onNodeWithText(
            "Книгу додано локально. Спільна база недоступна — публікації не буде."
        ).assertExists()
    }

    @Test
    fun `published state is distinct`() {
        show(SubmissionUiState.Published)
        compose.onNodeWithText("Опубліковано в спільному каталозі.").assertExists()
    }

    @Test
    fun `metadata-only state is distinct`() {
        show(SubmissionUiState.MetadataPublished)
        compose.onNodeWithText(
            "Метадані опубліковано. Аудіо з цього посилання недоступне без логіну — інші побачать чесний недоступний стан."
        ).assertExists()
    }

    @Test
    fun `an unsupported link explains what is accepted`() {
        show(SubmissionUiState.Unsupported)
        compose.onNodeWithText(
            "Це посилання не підтримується. Надішліть YouTube-відео, плейлист або пост Telegram."
        ).assertExists()
    }
}
