package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.screens.collections.PublishedCollectionRow
import com.slukhayka.audiobooks.ui.screens.collections.PublishedCollectionsBlock
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec-51 (#691) — the published block exists only when there is something to show. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PublishedCollectionsBlockTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setBlock(rows: List<PublishedCollectionRow>, onOpen: (String) -> Unit = {}) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                PublishedCollectionsBlock(rows = rows, onOpen = onOpen)
            }
        }
    }

    @Test
    fun `nothing is rendered when nothing is published`() {
        setBlock(emptyList())
        composeTestRule.onNodeWithTag("published_collections_block").assertDoesNotExist()
    }

    @Test
    fun `published rows show the title, count and pseudonym, and open by document id`() {
        var opened: String? = null
        setBlock(
            rows = listOf(
                PublishedCollectionRow("doc-1", "Магія", 3, "Слухач"),
                PublishedCollectionRow("doc-2", "Космос", 1, "Слухач")
            ),
            onOpen = { opened = it }
        )

        composeTestRule.onNodeWithTag("published_collections_block").assertIsDisplayed()
        composeTestRule.onNodeWithText("Магія").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 книг · Слухач").assertIsDisplayed()

        composeTestRule.onNodeWithTag("published_collection_row_doc-2").performClick()
        assertEquals("doc-2", opened)
    }
}
