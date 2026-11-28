package com.slukhayka.audiobooks.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.ui.screens.DiscoveryCollectionBook
import com.slukhayka.audiobooks.ui.screens.DiscoveryCollectionScreen
import com.slukhayka.audiobooks.ui.screens.DiscoveryCollectionEdition
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA", sdk = [36])
class DiscoveryCollectionScreenTest {
    @get:Rule val rule = createComposeRule()

    @Before @After fun clearSavedFilterChoice() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("catalog_overview", Context.MODE_PRIVATE)
            .edit().remove("hide_finished").commit()
    }

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

    @Test fun `collection filters correlate editions and allow unknown facts only explicitly`() {
        val short = book("short", "Коротка").copy(
            genres = setOf("Фентезі", "Детективи"),
            editions = listOf(DiscoveryCollectionEdition("short-uk", "uk", 3_600L, "Читач")),
        )
        val mixed = book("mixed", "Дві мови").copy(
            genres = setOf("Фентезі"),
            editions = listOf(
                DiscoveryCollectionEdition("mixed-uk", "uk", 72_000L),
                DiscoveryCollectionEdition("mixed-en", "en", 3_600L),
            ),
        )
        val unknown = book("unknown", "Без тривалості").copy(
            genres = setOf("Детективи"),
            editions = listOf(DiscoveryCollectionEdition("unknown-uk", "uk", null)),
        )
        val completed = book("completed", "Прослухана", completed = true).copy(
            genres = setOf("Детективи"),
            editions = listOf(DiscoveryCollectionEdition("completed-uk", "uk", 3_600L)),
        )
        var hideCompleted: Boolean? = null
        var openedEdition: String? = null
        rule.setContent {
            AudiobookTheme(darkTheme = false) {
                DiscoveryCollectionScreen(
                    title = "Жанрова добірка", books = listOf(short, mixed, unknown, completed),
                    onBackClick = {}, onBookClick = {}, onSearchAll = {},
                    onHideCompletedChange = { hideCompleted = it },
                    onOpenEdition = { _, editionId -> openedEdition = editionId },
                )
            }
        }
        rule.onNodeWithTag("discovery_collection_filters").performClick()
        rule.onNodeWithTag("collection_filter_genre_fantasy").performClick()
        rule.onNodeWithTag("collection_filter_genre_detective").performClick()
        rule.onNodeWithTag("collection_filter_language_uk").performClick()
        rule.onNodeWithTag("collection_filter_duration_under_5h").performClick()
        rule.onNodeWithTag("collection_filter_hide_completed").performScrollTo().performClick()
        rule.onNodeWithTag("collection_filter_done").performClick()
        rule.onNodeWithText("Коротка").assertIsDisplayed()
        rule.onNodeWithText("Читач · 1:00:00").assertIsDisplayed()
        rule.onNodeWithText("Дві мови").assertDoesNotExist()
        rule.onNodeWithText("Без тривалості").assertDoesNotExist()
        rule.onNodeWithText("Прослухана").assertDoesNotExist()
        assertEquals(true, hideCompleted)
        rule.onNodeWithText("Коротка").performClick()
        assertEquals("short-uk", openedEdition)
        rule.onNodeWithTag("discovery_collection_include_unknown").performClick()
        rule.onNodeWithTag("discovery_collection_list").performScrollToNode(hasText("Без тривалості"))
        rule.onNodeWithText("Без тривалості").assertIsDisplayed()
        rule.onNodeWithText("Дві мови").assertDoesNotExist()
        rule.onNodeWithText("Прослухана").assertDoesNotExist()
    }

}
