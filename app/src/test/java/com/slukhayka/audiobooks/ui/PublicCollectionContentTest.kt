package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.data.collections.PublishedCollection
import com.slukhayka.audiobooks.ui.screens.collections.PublicCollectionContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec-51 (#692) — reading someone else's collection is read-only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PublicCollectionContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val collection = PublishedCollection(
        authorId = "a".repeat(64),
        collectionId = "c1",
        pseudonym = "Слухач",
        title = "Магія",
        description = "про зорі",
        bookIds = listOf("a", "b", "c"),
        publishedAt = 1L
    )

    private fun setContent(available: Boolean, onSave: () -> Unit = {}) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                PublicCollectionContent(
                    collection = collection,
                    originalAvailableLocally = available,
                    onSaveForYou = onSave
                )
            }
        }
    }

    @Test
    fun `the reader sees the title, the curator pseudonym and the real count`() {
        setContent(available = true)

        composeTestRule.onNodeWithTag("public_collection_title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Магія").assertIsDisplayed()
        composeTestRule.onNodeWithText("добірка слухача Слухач").assertIsDisplayed()
        composeTestRule.onNodeWithText("Книг у добірці: 3").assertIsDisplayed()
    }

    @Test
    fun `the raw author id is never shown to the reader`() {
        setContent(available = true)
        // The hash may exist in the model, but it must not be on screen.
        composeTestRule.onNodeWithText("a".repeat(64)).assertDoesNotExist()
    }

    @Test
    fun `saving is offered but gated when the original is not local`() {
        setContent(available = false)
        composeTestRule.onNodeWithTag("save_collection_for_you")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun `saving works when the original is local`() {
        var saved = 0
        setContent(available = true, onSave = { saved++ })
        composeTestRule.onNodeWithTag("save_collection_for_you")
            .assertIsEnabled()
            .performClick()
        assertEquals(1, saved)
    }
}
