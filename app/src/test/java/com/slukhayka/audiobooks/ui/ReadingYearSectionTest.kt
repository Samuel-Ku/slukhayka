package com.slukhayka.audiobooks.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.slukhayka.audiobooks.data.entries.ReadingFormat
import com.slukhayka.audiobooks.data.entries.ReadingProgressProjection
import com.slukhayka.audiobooks.data.entries.ReadingProgressRecorder
import com.slukhayka.audiobooks.data.entries.ReadingState
import com.slukhayka.audiobooks.data.entries.ReadingUnit
import com.slukhayka.audiobooks.data.entries.ReadingUnits
import com.slukhayka.audiobooks.data.entries.Readthrough
import com.slukhayka.audiobooks.data.entries.YearlyReadingGoal
import com.slukhayka.audiobooks.ui.screens.LibraryReadingYearSection
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1286 — the «Мій рік» surface reads the module's ONE state: the year total and
 * the active pass come from the same [ReadingProgressProjection], and every
 * honest outcome — including the refusal and the retry — is visible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReadingYearSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val paperPass = Readthrough(
        id = "paper-1",
        libraryEntryId = "entry-1",
        workId = "work-1",
        format = ReadingFormat.PAPER,
        state = ReadingState.IN_PROGRESS,
        startedAt = 1_000L,
        units = ReadingUnits(ReadingUnit.PAGES, 42)
    )

    private fun ready(
        projection: ReadingProgressProjection = ReadingProgressProjection(
            active = listOf(paperPass),
            year = YearlyReadingGoal(
                year = 2026,
                finishedByFormat = mapOf(ReadingFormat.PAPER to 2)
            )
        ),
        refreshFailed: Boolean = false
    ) = ReadingProgressRecorder.State.Ready(projection, refreshFailed)

    private fun section(
        state: ReadingProgressRecorder.State,
        message: String? = null,
        onRecord: (Readthrough, Int) -> Unit = { _, _ -> },
        onFinish: (Readthrough) -> Unit = {},
        onRetry: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LibraryReadingYearSection(
                        state = state,
                        message = message,
                        onRecord = onRecord,
                        onFinish = onFinish,
                        onRetry = onRetry
                    )
                }
            }
        }
    }

    @Test
    fun `the year and the active pass come from the same snapshot`() {
        section(ready())

        composeTestRule.onNodeWithTag("reading_year_total").assertIsDisplayed()
        composeTestRule.onNodeWithText("Завершено проходів: 2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reading_value_paper-1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reading_record_paper-1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reading_finish_paper-1").assertIsDisplayed()
    }

    @Test
    fun `a refusal is shown as a message, never as a success`() {
        section(ready(), message = "Прохід уже завершено або закрито — запис не додано.")

        composeTestRule.onNodeWithTag("reading_progress_message").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Прохід уже завершено або закрито — запис не додано.")
            .assertIsDisplayed()
    }

    @Test
    fun `a failed first read offers a retry, not the empty year`() {
        section(ReadingProgressRecorder.State.Failed(ReadingProgressRecorder.REASON_STORAGE))

        composeTestRule.onNodeWithTag("reading_year_retry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ще немає завершених проходів").assertDoesNotExist()
    }

    @Test
    fun `a stale later read keeps the known list and offers the retry`() {
        section(ready(refreshFailed = true))

        composeTestRule.onNodeWithTag("reading_year_stale").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reading_year_retry").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reading_value_paper-1").assertIsDisplayed()
    }
}
