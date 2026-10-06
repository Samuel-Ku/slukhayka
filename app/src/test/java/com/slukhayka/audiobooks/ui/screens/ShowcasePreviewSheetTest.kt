package com.slukhayka.audiobooks.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.achievements.ShowcaseAwardSnapshot
import com.slukhayka.audiobooks.data.achievements.ShowcasePreview
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #705 (T7) — nothing about the showcase goes public without this screen's
 * consent.
 *
 * The awards are listed BY NAME, and that is the point of the sheet rather than
 * a detail of it: «до 3 нагород» is not something a person can agree to. What
 * they are agreeing to is WHICH awards become public, so the test asserts the
 * names themselves, not a count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA", sdk = [36])
class ShowcasePreviewSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val preview = ShowcasePreview(
        pseudonym = "Слухач",
        awards = listOf(
            ShowcaseAwardSnapshot("first_book", "Перша книга"),
            ShowcaseAwardSnapshot("night_watch", "Нічний вартовий")
        )
    )

    private fun setContent(onConfirm: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ShowcasePreviewSheet(
                    preview = preview,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss
                )
            }
        }
    }

    @Test
    fun `the sheet names every award that will be published`() {
        setContent()

        composeTestRule.onNodeWithTag("showcase_publish_sheet").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.showcase_publish_title))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.showcase_publish_lead))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                context.getString(R.string.showcase_publish_line_pseudonym, "Слухач")
            )
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.showcase_publish_line_award_count, 2))
            .assertIsDisplayed()

        // By name, each one, so the choice is the actual awards.
        composeTestRule.onNodeWithText("Перша книга").assertIsDisplayed()
        composeTestRule.onNodeWithText("Нічний вартовий").assertIsDisplayed()
    }

    @Test
    fun `dismissing publishes nothing`() {
        var confirmed = false
        var dismissed = false
        setContent(onConfirm = { confirmed = true }, onDismiss = { dismissed = true })

        composeTestRule.onNodeWithTag("showcase_publish_cancel").performClick()

        assertEquals("nothing leaves the device on dismiss", false, confirmed)
        assertEquals(true, dismissed)
    }

    @Test
    fun `only the explicit confirm publishes`() {
        var confirmed = false
        setContent(onConfirm = { confirmed = true })

        composeTestRule.onNodeWithTag("showcase_publish_confirm").performClick()

        assertEquals(true, confirmed)
    }

    @Test
    fun `the sheet shows exactly the awards it was given, and invents none`() {
        val single = preview.copy(
            awards = listOf(ShowcaseAwardSnapshot("first_book", "Перша книга"))
        )
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ShowcasePreviewSheet(preview = single, onConfirm = {}, onDismiss = {})
            }
        }

        composeTestRule.onNodeWithTag("showcase_publish_award_0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("showcase_publish_award_1").assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.showcase_publish_line_award_count, 1))
            .assertIsDisplayed()
    }
}
