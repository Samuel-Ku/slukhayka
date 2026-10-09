package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.entries.AbandonOffer
import com.slukhayka.audiobooks.testing.EnglishChromeWalk
import com.slukhayka.audiobooks.ui.screens.bookdetail.BookAbandonMenuItem
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec-52 US28 / #1174 — the ⋮ item the book page shows for «покинути книгу».
 *
 * The DECISION is the pure `AbandonBookPolicy` (pinned without a screen in
 * `AbandonBookPolicyTest`); this renders the production item with each of its
 * three answers, so the two edges the AC names — a finished book and a book
 * without progress — are proved to reach no action at all, and the item that
 * does appear keeps the menu's 48 dp target, its label and its TalkBack state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BookDetailAbandonActionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Composable
    private fun Item(
        offer: AbandonOffer,
        onAbandon: () -> Unit = {},
        onCancel: () -> Unit = {}
    ) {
        AudiobookTheme(darkTheme = true) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column {
                    BookAbandonMenuItem(offer, onAbandon, onCancel)
                }
            }
        }
    }

    @Test
    fun `a book with progress offers the abandon and keeps the touch target`() {
        var abandoned = 0
        composeTestRule.setContent { Item(AbandonOffer.ABANDON, onAbandon = { abandoned++ }) }

        composeTestRule.onNodeWithTag("book_detail_abandon")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeTestRule.onNodeWithText("Покинути книгу").assertIsDisplayed()

        assertEquals("the ONE action the item carries", 1, abandoned)
    }

    @Test
    fun `a finished book or a book without progress is offered nothing at all`() {
        composeTestRule.setContent { Item(AbandonOffer.NONE) }

        composeTestRule.onAllNodesWithTag("book_detail_abandon").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("book_detail_abandon_cancel").assertCountEquals(0)
    }

    @Test
    fun `a marked book offers the way back and states the mark to TalkBack`() {
        var cancelled = 0
        composeTestRule.setContent { Item(AbandonOffer.CANCEL, onCancel = { cancelled++ }) }

        composeTestRule.onNodeWithTag("book_detail_abandon_cancel")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertTextContains("Не покидати книгу")
            // The label is the action; the state says WHERE the book stands.
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Книгу позначено покинутою"
                )
            )
            .performClick()

        assertEquals(1, cancelled)
        composeTestRule.onAllNodesWithTag("book_detail_abandon").assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "en-rUS", sdk = [36])
    fun theEnglishRunCarriesNoUkrainianChrome() {
        composeTestRule.setContent { Item(AbandonOffer.ABANDON) }

        composeTestRule.onNodeWithText("Abandon book").assertIsDisplayed()
        EnglishChromeWalk.assertNoCyrillic(composeTestRule, "book abandon menu item")
    }
}
