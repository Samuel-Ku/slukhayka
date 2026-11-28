package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.ui.screens.DiscoveryCollectionBook
import com.slukhayka.audiobooks.ui.screens.DiscoveryCollectionScreen
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA", sdk = [36])
class DiscoveryCollectionScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun book(id: String, title: String, completed: Boolean = false) = DiscoveryCollectionBook(
        result = GlobalSearchResult(title = title, author = "Автор", mergeKey = id, sources = emptyList()),
        completed = completed,
    )

    @Test fun `within collection search narrows books and can search all with same query`() {
        var allQuery: String? = null
        rule.setContent {
            AudiobookTheme(darkTheme = false) {
                DiscoveryCollectionScreen(
                    title = "Класика",
                    books = listOf(book("a", "Місто"), book("b", "Тигролови")),
                    onBackClick = {}, onBookClick = {}, onSearchAll = { allQuery = it },
                )
            }
        }
        rule.onNodeWithText("Місто").assertIsDisplayed()
        rule.onNodeWithText("Тигролови").assertIsDisplayed()
        rule.onNodeWithTag("discovery_collection_search").performTextInput("тигр")
        rule.onNodeWithText("Місто").assertDoesNotExist()
        rule.onNodeWithText("Тигролови").assertIsDisplayed()
        rule.onNodeWithTag("discovery_collection_search_all").performClick()
        assertEquals("тигр", allQuery)
    }
}
