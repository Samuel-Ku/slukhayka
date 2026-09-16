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

    private fun setContent(
        available: Boolean,
        onSave: () -> Unit = {},
        isOwn: Boolean = false,
        myStars: Int? = null,
        onVote: (Int) -> Unit = {}
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                PublicCollectionContent(
                    collection = collection,
                    originalAvailableLocally = available,
                    onSaveForYou = onSave,
                    myStars = myStars,
                    isOwn = isOwn,
                    onVote = onVote
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
    @Test
    fun `a collection without votes shows the honest empty line and no stars`() {
        setContent(available = true)

        composeTestRule.onNodeWithTag("public_collection_no_ratings").assertIsDisplayed()
        composeTestRule.onNodeWithTag("public_collection_average").assertDoesNotExist()
    }

    @Test
    fun `the real average and its vote count are shown once people vote`() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                PublicCollectionContent(
                    collection = collection.copy(ratingSum = 9, ratingCount = 2),
                    originalAvailableLocally = true,
                    onSaveForYou = {}
                )
            }
        }

        composeTestRule.onNodeWithText("★ 4.5 · 2 оцінки").assertIsDisplayed()
        composeTestRule.onNodeWithTag("public_collection_no_ratings").assertDoesNotExist()
    }

    @Test
    fun `the author never sees the self-rating control`() {
        setContent(available = true, isOwn = true)

        composeTestRule.onNodeWithTag("public_collection_your_rating").assertDoesNotExist()
        composeTestRule.onNodeWithTag("rating_star_1").assertDoesNotExist()
    }

    @Test
    fun `a reader can rate the collection and the choice is reported`() {
        var voted = 0
        setContent(available = true, isOwn = false, onVote = { voted = it })

        composeTestRule.onNodeWithTag("rating_star_4").performClick()

        assertEquals(4, voted)
    }
}
