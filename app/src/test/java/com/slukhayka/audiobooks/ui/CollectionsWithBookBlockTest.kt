package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.screens.collections.CollectionWithBookRow
import com.slukhayka.audiobooks.ui.screens.collections.CollectionsWithBookBlock
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec-51 (#692) — «Добірки з цією книгою»: honest stars, honest emptiness. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CollectionsWithBookBlockTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val rated = CollectionWithBookRow("doc-1", "Магія", "Слухач", average = 4.5, ratingCount = 2)
    private val unrated = CollectionWithBookRow("doc-2", "Зорі", "Інший", average = null, ratingCount = 0)

    private fun setContent(rows: List<CollectionWithBookRow>, onOpen: (String) -> Unit = {}) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CollectionsWithBookBlock(rows = rows, onOpen = onOpen)
            }
        }
    }

    @Test
    fun `the block names the section and shows a real average with its vote count`() {
        setContent(listOf(rated))

        composeTestRule.onNodeWithTag("collections_with_book_block").assertIsDisplayed()
        composeTestRule.onNodeWithText("Добірки з цією книгою").assertIsDisplayed()
        composeTestRule.onNodeWithText("Магія").assertIsDisplayed()
        composeTestRule.onNodeWithText("добірка слухача Слухач").assertIsDisplayed()
        composeTestRule.onNodeWithText("★ 4.5 · 2 оцінки").assertIsDisplayed()
    }

    @Test
    fun `a collection without votes shows no stars, only the honest empty line`() {
        setContent(listOf(unrated))

        composeTestRule.onNodeWithText("Ще без оцінок").assertIsDisplayed()
        composeTestRule.onNodeWithText("★ 0.0 · 0 оцінок").assertDoesNotExist()
    }

    @Test
    fun `no rows means no block at all`() {
        setContent(emptyList())
        composeTestRule.onNodeWithTag("collections_with_book_block").assertDoesNotExist()
    }

    @Test
    fun `tapping a collection opens it`() {
        var opened: String? = null
        setContent(listOf(rated, unrated)) { opened = it }

        composeTestRule.onNodeWithTag("collection_with_book_doc-2").performClick()

        assertEquals("doc-2", opened)
    }
}
